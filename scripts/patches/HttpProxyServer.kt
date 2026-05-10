package com.velum.vpn.proxy

import com.velum.vpn.core.LogBus
import com.velum.vpn.core.RelayConfig
import com.velum.vpn.core.Stats
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

/**
 * HttpProxyServer — local HTTP proxy that the system / VPN points at.
 *
 * SECURITY MODEL (post-refactor):
 *   * Plain HTTP requests (method != CONNECT) are forwarded through the
 *     RelayClient (GAS backend). The relay sees plaintext only because
 *     the user's app already chose to send plaintext. No interception
 *     of TLS happens here.
 *
 *   * HTTPS is handled with a CONNECT method (HTTP CONNECT tunnel). We
 *     do NOT terminate TLS, do NOT mint per-host leaf certificates, and
 *     do NOT need a user-installed CA. Instead we open a raw TCP tunnel
 *     to the upstream host and pipe bytes both directions until either
 *     side closes. The user's app performs its own end-to-end TLS
 *     handshake with the real origin — exactly as the OS designed it.
 *
 * This removes the entire MITM layer that previous Velum builds shipped.
 * Censorship-bypass behaviour is preserved by routing the *plaintext*
 * relay traffic through the GAS backend; for HTTPS, we provide a clean
 * TCP relay path so apps work without any certificate trust friction.
 */
class HttpProxyServer(
    private val cfg: RelayConfig,
    private val relay: RelayClient,
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
     * CONNECT handler — pure TCP pass-through.
     *
     * No TLS interception. No leaf signing. No per-host certificates.
     * The client's TLS handshake goes end-to-end to the real origin,
     * which is exactly what every modern proxy server (Squid, mitmproxy
     * in regular mode, Nginx stream module, etc.) does by default.
     */
    private fun handleConnect(
        target: String,
        client: Socket,
        input: InputStream,
        output: OutputStream,
    ) {
        val (host, port) = parseHostPort(target, 443)
        LogBus.i(tag, "CONNECT (passthrough) -> $host:$port")
        tunnelRaw(host, port, client, input, output)
    }

    /**
     * Pure TCP relay between client and upstream. End-to-end TLS is
     * preserved; Velum never sees plaintext for CONNECT-tunnelled
     * traffic. This is the same model Android's own VpnService examples
     * recommend for non-MITM proxies.
     */
    private fun tunnelRaw(
        host: String,
        port: Int,
        @Suppress("UNUSED_PARAMETER") client: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
    ) {
        var upstream: Socket? = null
        try {
            upstream = Socket()
            upstream.tcpNoDelay = true
            upstream.connect(InetSocketAddress(host, port), 10_000)
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
            try {
                clientOut.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
                clientOut.flush()
            } catch (_: Throwable) { /* client gone */ }
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

    /**
     * Plain HTTP path — forwarded through the GAS relay so we get the
     * censorship-bypass benefit. Plaintext only; nothing TLS-related
     * happens in this branch.
     */
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
