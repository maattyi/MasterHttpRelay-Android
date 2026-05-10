package com.velum.vpn.proxy

import com.velum.vpn.cert.MitmCa
import com.velum.vpn.core.LogBus
import com.velum.vpn.core.RelayConfig
import com.velum.vpn.core.Stats
import com.velum.vpn.net.PinnedHostBypass
import com.velum.vpn.net.RelayClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

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
        } catch (e: Throwable) {
            LogBus.e(tag, "Failed to start HTTP proxy", e)
        }
    }

    fun stop() {
        isRunning = false
        runCatching { server?.close() }
        server = null
        runCatching { scope.cancel() }
    }

    private suspend fun acceptLoop(s: ServerSocket) {
        while (isRunning && !s.isClosed) {
            val client = try { s.accept() } catch (_: Throwable) { break }
            stats.connOpened()
            scope.launch {
                try {
                    handleClient(client)
                } catch (e: Throwable) {
                    if (e !is CancellationException) {
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
        runCatching { client.tcpNoDelay = true }
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
                hdrs[line.substring(0, sep).trim().lowercase()] =
                    line.substring(sep + 1).trim()
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

    /**
     * CONNECT handler with split-tunnel decision.
     *
     *   1. Parse target host:port.
     *   2. If [PinnedHostBypass.shouldBypass] returns true, set up a raw
     *      bidirectional TCP relay between the client and the upstream
     *      server. The client's TLS handshake goes end-to-end with the
     *      real server — Velum never sees plaintext, but the user's app
     *      keeps working instead of breaking with a CT or pinning error.
     *   3. Otherwise, proceed with the existing MITM path: leaf-sign per
     *      host, decrypt, relay through GAS, re-encrypt back to client.
     */
    private suspend fun handleConnect(
        target: String,
        client: Socket,
        input: InputStream,
        output: OutputStream,
    ) {
        val (host, port) = parseHostPort(target, 443)
        LogBus.i(tag, "CONNECT -> $host:$port")

        if (PinnedHostBypass.shouldBypass(host)) {
            LogBus.i(tag, "PASSTHROUGH (pinned) -> $host:$port")
            tunnelRaw(host, port, client, input, output)
            return
        }

        try {
            output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            output.flush()
        } catch (e: Throwable) {
            LogBus.d(tag, "Failed to send CONNECT 200: ${e.message}")
            return
        }

        val ctx = try {
            mitm.contextFor(host)
        } catch (e: Throwable) {
            LogBus.e(tag, "MITM context for $host failed: ${e.message}", e)
            return
        }

        val ssf: SSLSocketFactory = ctx.socketFactory
        val tls: SSLSocket = try {
            (ssf.createSocket(client, host, port, true) as SSLSocket).apply {
                useClientMode = false
                soTimeout = 60_000
            }
        } catch (e: Throwable) {
            LogBus.e(tag, "TLS wrap failed for $host: ${e.message}", e)
            return
        }

        try {
            try {
                tls.startHandshake()
            } catch (e: Throwable) {
                val msg = e.message ?: e.toString()
                LogBus.d(tag, "TLS handshake FAILED for $host: $msg")
                if (msg.contains("certificate_unknown", true) || msg.contains("alert", true)) {
                    LogBus.e(
                        tag,
                        "Client REJECTED Velum cert for $host. Likely cause: the client " +
                            "app does not trust user CAs (default for SDK >= 24) or " +
                            "implements certificate pinning. Add this host to the " +
                            "bypass list if it should pass through.",
                    )
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
                    if (sep > 0) {
                        rHdrs[line.substring(0, sep).trim().lowercase()] =
                            line.substring(sep + 1).trim()
                    }
                }

                val rParts = firstReq.split(" ", limit = 3)
                if (rParts.size < 2) break
                val rMethod = rParts[0]
                val rPath = rParts[1]

                val te = rHdrs["transfer-encoding"]?.lowercase()
                val body = if (te != null && te.contains("chunked")) {
                    runCatching { readChunkedBody(tlsIn) }.getOrDefault(ByteArray(0))
                } else {
                    val cl = rHdrs["content-length"]?.toIntOrNull() ?: 0
                    if (cl > 0) runCatching { readNBytesCompat(tlsIn, cl) }
                        .getOrDefault(ByteArray(0))
                    else ByteArray(0)
                }
                stats.addUp(sb.length.toLong() + body.size)

                val url = if (port == 443) "https://$host$rPath"
                else "https://$host:$port$rPath"
                val resp = try {
                    relay.relaySmart(rMethod, url, rHdrs, body)
                } catch (e: Throwable) {
                    com.velum.vpn.net.Response.error(502, "relay error: ${e.message}")
                }

                try {
                    val bytes = resp.toHttpBytes()
                    tlsOut.write(bytes)
                    tlsOut.flush()
                    stats.addDown(bytes.size.toLong())
                } catch (e: Throwable) {
                    LogBus.d(tag, "TLS write failed for $host: ${e.message}")
                    break
                }
            }
        } finally {
            runCatching { tls.close() }
        }
    }

    /**
     * Pinned-host passthrough. Opens a TCP connection to upstream, returns
     * "200 Connection Established" to the client, then pipes bytes both
     * ways until either side closes. The TLS handshake is end-to-end —
     * Velum never has the plaintext and never has to forge a leaf.
     */
    private fun tunnelRaw(
        host: String,
        port: Int,
        client: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
    ) {
        var upstream: Socket? = null
        try {
            upstream = Socket()
            upstream.tcpNoDelay = true
            upstream.connect(InetSocketAddress(host, port), 5_000)
            upstream.soTimeout = 0

            try {
                clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                clientOut.flush()
            } catch (e: Throwable) {
                LogBus.d(tag, "passthrough: client closed before 200: ${e.message}")
                return
            }

            val upIn = upstream.getInputStream()
            val upOut = upstream.getOutputStream()

            val t1 = Thread({ pipe(clientIn, upOut, "C->U") }, "pt-c2u-$host").apply {
                isDaemon = true
                start()
            }
            val t2 = Thread({ pipe(upIn, clientOut, "U->C") }, "pt-u2c-$host").apply {
                isDaemon = true
                start()
            }
            t1.join()
            t2.join()
        } catch (e: Throwable) {
            LogBus.d(tag, "passthrough $host:$port failed: ${e.message}")
        } finally {
            runCatching { upstream?.close() }
        }
    }

    private fun pipe(src: InputStream, dst: OutputStream, label: String) {
        val buf = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = try { src.read(buf) } catch (_: Throwable) { -1 }
                if (n <= 0) break
                try {
                    dst.write(buf, 0, n)
                    dst.flush()
                } catch (_: Throwable) {
                    break
                }
                if (label.startsWith("C")) stats.addUp(n.toLong())
                else stats.addDown(n.toLong())
            }
        } finally {
            runCatching { dst.flush() }
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
        val te = hdrs["transfer-encoding"]?.lowercase()
        val body = if (te != null && te.contains("chunked")) {
            runCatching { readChunkedBody(input) }.getOrDefault(ByteArray(0))
        } else {
            val cl = hdrs["content-length"]?.toIntOrNull() ?: 0
            if (cl > 0) runCatching { readNBytesCompat(input, cl) }
                .getOrDefault(ByteArray(0))
            else ByteArray(0)
        }
        stats.addUp(headerBlock.length.toLong() + body.size)

        val resp = try {
            relay.relaySmart(method, url, hdrs, body)
        } catch (e: Throwable) {
            com.velum.vpn.net.Response.error(502, "relay error: ${e.message}")
        }

        try {
            val bytes = resp.toHttpBytes()
            output.write(bytes)
            output.flush()
            stats.addDown(bytes.size.toLong())
        } catch (e: Throwable) {
            LogBus.d(tag, "HTTP write failed: ${e.message}")
        }
    }

    private fun parseHostPort(spec: String, defaultPort: Int): Pair<String, Int> {
        val idx = spec.lastIndexOf(':')
        return if (idx > 0) {
            spec.substring(0, idx) to (spec.substring(idx + 1).toIntOrNull() ?: defaultPort)
        } else {
            spec to defaultPort
        }
    }

    private fun readNBytesCompat(input: InputStream, n: Int): ByteArray {
        if (n <= 0) return ByteArray(0)
        val buffer = ByteArray(n)
        var offset = 0
        while (offset < n) {
            val read = try { input.read(buffer, offset, n - offset) } catch (_: Throwable) { -1 }
            if (read == -1) return buffer.copyOf(offset)
            offset += read
        }
        return buffer
    }

    private fun readChunkedBody(input: InputStream): ByteArray {
        val chunks = mutableListOf<ByteArray>()
        while (true) {
            val sizeLine = readLine(input) ?: break
            val chunkSize = sizeLine.trim().substringBefore(';').toIntOrNull(16) ?: 0
            if (chunkSize == 0) {
                readLine(input)
                break
            }
            val chunk = readNBytesCompat(input, chunkSize)
            chunks += chunk
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
            val b = try { input.read() } catch (_: Throwable) { return null }
            if (b < 0) return if (out.size() == 0) null else out.toString()
            if (b == '\n'.code) return out.toString().trimEnd('\r')
            out.write(b)
            if (out.size() > 16384) break
        }
        return out.toString()
    }
}
