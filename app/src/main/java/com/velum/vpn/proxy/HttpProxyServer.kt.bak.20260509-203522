package com.velum.vpn.proxy

import com.velum.vpn.core.LogBus
import com.velum.vpn.cert.MitmCa
import com.velum.vpn.core.RelayConfig
import com.velum.vpn.core.Stats
import com.velum.vpn.net.RelayClient
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.io.InputStream
import java.io.OutputStream
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Local HTTP/HTTPS proxy listener.
 * Handles plain HTTP via GAS relay and HTTPS via MITM decryption.
 *
 * Fix #10:
 *   - Added SO_TIMEOUT (60 s) on every TLS session so idle/abandoned
 *     clients do not park a coroutine forever waiting on read().
 *   - Wrapped handshake + read loop in try { … } finally { tls.close() }
 *     so a thrown exception (handshake failure, disconnect, OOM, …)
 *     can never leak the SSLSocket. Previously, on handshake failure
 *     we returned without closing tls, which leaked the underlying
 *     OS file descriptor and the wrapped `client` Socket.
 *   - Chunked transfer-encoding body support for both plain HTTP and
 *     decrypted MITM traffic.
 */
class HttpProxyServer(
    private val cfg: RelayConfig,
    private val relay: RelayClient,
    private val mitm: MitmCa,
    private val stats: Stats,
) {
    private val tag = "VELUM-Proxy"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: ServerSocket? = null

    @Volatile var isRunning = false
        private set

    fun start() {
        if (isRunning) return
        try {
            val s = ServerSocket()
            s.reuseAddress = true
            val bind = if (cfg.lanShare) "0.0.0.0" else cfg.listenHost
            s.bind(InetSocketAddress(bind, cfg.listenPort), 256)
            server = s
            isRunning = true
            scope.launch { acceptLoop(s) }
            LogBus.i(tag, "HTTP proxy listening on $bind:${cfg.listenPort}")
        } catch (e: Exception) {
            LogBus.e(tag, "Failed to start HTTP proxy", e)
        }
    }

    fun stop() {
        isRunning = false
        try { server?.close() } catch (_: Throwable) {}
        server = null
        scope.cancel()
    }

    private suspend fun acceptLoop(s: ServerSocket) {
        while (isRunning && !s.isClosed) {
            val client = try { s.accept() } catch (_: Throwable) { break }
            stats.connOpened()
            scope.launch {
                try {
                    handleClient(client)
                } catch (e: Exception) {
                    if (e !is kotlinx.coroutines.CancellationException) {
                        LogBus.d(tag, "client session error: ${e.message}")
                    }
                } finally {
                    runCatching { client.close() }
                    stats.connClosed()
                }
            }
        }
    }

    private suspend fun handleClient(client: Socket) {
        client.tcpNoDelay = true
        val input = client.getInputStream()
        val output = client.getOutputStream()

        val firstLine = readLine(input) ?: return
        val headerBlock = StringBuilder().append(firstLine).append("\r\n")
        val hdrs = mutableMapOf<String, String>()

        while (true) {
            val line = readLine(input) ?: break
            headerBlock.append(line).append("\r\n")
            if (line.isEmpty()) break
            val sep = line.indexOf(':')
            if (sep > 0) {
                hdrs[line.substring(0, sep).trim().lowercase()] = line.substring(sep + 1).trim()
            }
        }

        val parts = firstLine.split(" ", limit = 3)
        if (parts.size < 2) return
        val method = parts[0].uppercase()
        stats.incrRequests()

        if (method == "CONNECT") {
            handleConnect(parts[1], client, input, output)
        } else {
            handleHttp(method, parts[1], hdrs, headerBlock.toString(), input, output)
        }
    }

    private suspend fun handleConnect(target: String, client: Socket, input: InputStream, output: OutputStream) {
        val (host, port) = parseHostPort(target, 443)
        LogBus.i(tag, "CONNECT -> $host:$port")

        output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
        output.flush()

        // MITM decryption — wrap the client TCP socket in our per-host TLS context.
        val ctx = mitm.contextFor(host)
        val ssf: SSLSocketFactory = ctx.socketFactory
        val tls = (ssf.createSocket(client, host, port, true) as SSLSocket).apply {
            useClientMode = false
            // Fix #10: bound idle wait so abandoned clients do not park
            // a coroutine forever inside read().
            soTimeout = 60_000
        }

        // Fix #10: every code path below MUST close `tls`. Wrap the entire
        // handshake-and-read-loop in try { … } finally { tls.close() } so
        // even a thrown exception, OOM, or coroutine cancellation cannot
        // leak the SSLSocket / underlying file descriptor.
        try {
            try {
                tls.startHandshake()
            } catch (e: Throwable) {
                val msg = e.message ?: e.toString()
                LogBus.d(tag, "TLS handshake FAILED for $host: $msg")
                if (msg.contains("alert", true)) {
                    LogBus.e(tag, "Client REJECTED certificate for $host. " +
                        "Possible causes: CA not installed, or non-compliant leaf cert.")
                }
                return
            }

            val tlsIn = tls.inputStream
            val tlsOut = tls.outputStream

            while (true) {
                val firstReq = readLine(tlsIn) ?: break
                if (firstReq.isEmpty()) continue

                val rHdrs = mutableMapOf<String, String>()
                val sb = StringBuilder().append(firstReq).append("\r\n")
                while (true) {
                    val line = readLine(tlsIn) ?: break
                    sb.append(line).append("\r\n")
                    if (line.isEmpty()) break
                    val sep = line.indexOf(':')
                    if (sep > 0) rHdrs[line.substring(0, sep).trim().lowercase()] = line.substring(sep + 1).trim()
                }

                val rParts = firstReq.split(" ", limit = 3)
                if (rParts.size < 2) break
                val rMethod = rParts[0]
                val rPath = rParts[1]

                // Fix #10: chunked transfer-encoding request bodies.
                val te = rHdrs["transfer-encoding"]?.lowercase()
                val body = if (te != null && te.contains("chunked")) {
                    readChunkedBody(tlsIn)
                } else {
                    val cl = rHdrs["content-length"]?.toIntOrNull() ?: 0
                    if (cl > 0) readNBytesCompat(tlsIn, cl) else ByteArray(0)
                }
                stats.addUp(sb.length.toLong() + body.size)

                val url = if (port == 443) "https://$host$rPath" else "https://$host:$port$rPath"
                val resp = try {
                    relay.relaySmart(rMethod, url, rHdrs, body)
                } catch (e: Exception) {
                    com.velum.vpn.net.Response.error(502, "relay error: ${e.message}")
                }

                val bytes = resp.toHttpBytes()
                tlsOut.write(bytes)
                tlsOut.flush()
                stats.addDown(bytes.size.toLong())
            }
        } finally {
            // Fix #10: ALWAYS close the TLS socket. runCatching swallows any
            // close-time IOException so that one failure cannot hide an
            // earlier, more important one.
            runCatching { tls.close() }
        }
    }

    private suspend fun handleHttp(
        method: String,
        url: String,
        hdrs: Map<String, String>,
        headerBlock: String,
        input: InputStream,
        output: OutputStream,
    ) {
        // Fix #10: chunked transfer-encoding for plain HTTP.
        val te = hdrs["transfer-encoding"]?.lowercase()
        val body = if (te != null && te.contains("chunked")) {
            readChunkedBody(input)
        } else {
            val cl = hdrs["content-length"]?.toIntOrNull() ?: 0
            if (cl > 0) readNBytesCompat(input, cl) else ByteArray(0)
        }
        stats.addUp(headerBlock.length.toLong() + body.size)

        val resp = try {
            relay.relaySmart(method, url, hdrs, body)
        } catch (e: Exception) {
            com.velum.vpn.net.Response.error(502, "relay error: ${e.message}")
        }

        val bytes = resp.toHttpBytes()
        output.write(bytes)
        output.flush()
        stats.addDown(bytes.size.toLong())
    }

    private fun parseHostPort(spec: String, defaultPort: Int): Pair<String, Int> {
        val idx = spec.lastIndexOf(':')
        return if (idx > 0) spec.substring(0, idx) to (spec.substring(idx + 1).toIntOrNull() ?: defaultPort)
               else spec to defaultPort
    }

    private fun readNBytesCompat(input: InputStream, n: Int): ByteArray {
        if (n <= 0) return ByteArray(0)
        val buffer = ByteArray(n)
        var offset = 0
        while (offset < n) {
            val read = input.read(buffer, offset, n - offset)
            if (read == -1) {
                return buffer.copyOf(offset)
            }
            offset += read
        }
        return buffer
    }

    /**
     * Fix #10: Read HTTP chunked transfer-encoding body.
     * Parses chunk-size lines, reads chunk-data + CRLF per chunk,
     * and concatenates into a single ByteArray.
     */
    private fun readChunkedBody(input: InputStream): ByteArray {
        val chunks = mutableListOf<ByteArray>()
        while (true) {
            val sizeLine = readLine(input) ?: break
            val chunkSize = sizeLine.trim().substringBefore(';').toIntOrNull(16) ?: 0
            if (chunkSize == 0) {
                // Read trailing CRLF after last chunk
                readLine(input)
                break
            }
            val chunk = readNBytesCompat(input, chunkSize)
            chunks += chunk
            // Read CRLF after chunk data
            readLine(input)
        }
        val total = chunks.sumOf { it.size }
        val result = ByteArray(total)
        var off = 0
        for (c in chunks) {
            System.arraycopy(c, 0, result, off, c.size)
            off += c.size
        }
        return result
    }

    private fun readLine(input: InputStream): String? {
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString()
            if (b == '\n'.code) {
                val res = out.toString().trimEnd('\r')
                return res
            }
            out.write(b)
            if (out.size() > 16384) break // Limit line length
        }
        return out.toString()
    }
}
