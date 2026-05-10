package com.velum.vpn.proxy

import com.velum.vpn.core.LogBus
import com.velum.vpn.core.RelayConfig
import com.velum.vpn.core.Stats
import com.velum.vpn.net.RelayClient
import com.velum.vpn.net.Response
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer

/**
 * SOCKS5 server (Telegram-compatible). CONNECT-only, no UDP relay.
 *
 * SECURITY MODEL (post-refactor):
 *   * Plain HTTP (port 80) is relayed through GAS as before — plaintext
 *     only, no TLS handling.
 *   * HTTPS (port 443) is now a *raw TCP tunnel*. The previous build
 *     intercepted TLS using a forged leaf signed by a per-device root CA.
 *     That was MITM, required a user-installed CA, and broke any pinned
 *     or CT-enforced client. We removed it. The client now performs its
 *     real TLS handshake end-to-end with the upstream origin.
 *
 * No SSLSocket, no SSLContext, no MitmCa. Pure byte-pumping for 443.
 */
class Socks5Server(
    private val cfg: RelayConfig,
    private val relay: RelayClient,
    private val stats: Stats,
) {
    private val tag = "VELUM-SOCKS5"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: ServerSocket? = null
    @Volatile var isRunning = false; private set

    fun start() {
        if (isRunning) return
        val s = ServerSocket()
        s.reuseAddress = true
        val bind = if (cfg.lanShare) "0.0.0.0" else cfg.socksHost
        s.bind(InetSocketAddress(bind, cfg.socksPort), 256)
        server = s
        isRunning = true
        scope.launch { acceptLoop(s) }
        LogBus.i(tag, "SOCKS5 listening on $bind:${cfg.socksPort}")
    }

    fun stop() {
        isRunning = false
        runCatching { server?.close() }
        server = null
        scope.cancel()
    }

    private suspend fun acceptLoop(s: ServerSocket) {
        while (isRunning) {
            val client = try { s.accept() } catch (_: Throwable) { break }
            stats.connOpened()
            scope.launch {
                try { handle(client) }
                catch (e: Throwable) { LogBus.d(tag, "socks5 client: ${e.message}") }
                finally {
                    runCatching { client.close() }
                    stats.connClosed()
                }
            }
        }
    }

    private suspend fun handle(client: Socket) {
        client.tcpNoDelay = true
        val input = client.getInputStream()
        val output = client.getOutputStream()

        // SOCKS5 greeting
        val ver = input.read(); val nm = input.read()
        if (ver != 0x05 || nm < 0) return
        readNBytesCompat(input, nm) // discard methods
        output.write(byteArrayOf(0x05, 0x00)); output.flush()

        // Request
        val head = readNBytesCompat(input, 4)
        if (head.size < 4 || head[0].toInt() != 0x05) return
        val cmd = head[1].toInt(); val atyp = head[3].toInt()
        if (cmd != 0x01) { sendReply(output, 0x07); return }

        val host = when (atyp) {
            0x01 -> {
                val b = readNBytesCompat(input, 4)
                "${b[0].toInt() and 0xff}.${b[1].toInt() and 0xff}.${b[2].toInt() and 0xff}.${b[3].toInt() and 0xff}"
            }
            0x03 -> {
                val ln = input.read()
                String(readNBytesCompat(input, ln), Charsets.UTF_8)
            }
            0x04 -> {
                val b = readNBytesCompat(input, 16)
                java.net.InetAddress.getByAddress(b).hostAddress ?: "::"
            }
            else -> { sendReply(output, 0x08); return }
        }
        val portBytes = readNBytesCompat(input, 2)
        val port = ((portBytes[0].toInt() and 0xff) shl 8) or (portBytes[1].toInt() and 0xff)

        sendReply(output, 0x00)
        stats.incrRequests()
        LogBus.i(tag, "SOCKS5 -> $host:$port")

        if (port == 80) {
            plainHttp(host, port, input, output)
        } else {
            // HTTPS and any non-80 traffic: pure TCP tunnel, end-to-end TLS.
            tunnelRaw(host, port, input, output)
        }
    }

    private suspend fun plainHttp(host: String, port: Int, input: InputStream, output: OutputStream) {
        val first = readLine(input) ?: return
        val sb = StringBuilder(first).append("\r\n")
        val hdrs = mutableMapOf<String, String>()
        while (true) {
            val l = readLine(input) ?: break
            sb.append(l).append("\r\n")
            if (l.isEmpty()) break
            val sep = l.indexOf(':')
            if (sep > 0) hdrs[l.substring(0, sep).trim()] = l.substring(sep + 1).trim()
        }
        val cl = hdrs.entries.firstOrNull { it.key.equals("content-length", true) }?.value?.toIntOrNull() ?: 0
        val body = if (cl > 0) readNBytesCompat(input, cl) else ByteArray(0)
        val parts = first.split(" ", limit = 3)
        val method = parts.getOrNull(0) ?: "GET"
        val path = parts.getOrNull(1) ?: "/"
        val url = if (port == 80) "http://$host$path" else "http://$host:$port$path"
        val resp = try { relay.relaySmart(method, url, hdrs, body) }
                   catch (e: Throwable) { Response.error(502, e.message ?: "relay error") }
        val bytes = resp.toHttpBytes()
        output.write(bytes); output.flush()
    }

    /**
     * Pure TCP relay between client and upstream. End-to-end TLS preserved.
     */
    private fun tunnelRaw(host: String, port: Int, clientIn: InputStream, clientOut: OutputStream) {
        var upstream: Socket? = null
        try {
            upstream = Socket()
            upstream.tcpNoDelay = true
            upstream.connect(InetSocketAddress(host, port), 10_000)
            upstream.soTimeout = 0

            val upIn = upstream.getInputStream()
            val upOut = upstream.getOutputStream()

            val t1 = Thread({ pipe(clientIn, upOut, "C->U") }, "s5-c2u-$host").apply {
                isDaemon = true; start()
            }
            val t2 = Thread({ pipe(upIn, clientOut, "U->C") }, "s5-u2c-$host").apply {
                isDaemon = true; start()
            }
            t1.join(); t2.join()
        } catch (e: Throwable) {
            LogBus.d(tag, "SOCKS5 passthrough $host:$port failed: ${e.message}")
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
                try { dst.write(buf, 0, n); dst.flush() } catch (_: Throwable) { break }
                if (label.startsWith("C")) stats.addUp(n.toLong())
                else stats.addDown(n.toLong())
            }
        } finally {
            runCatching { dst.flush() }
        }
    }

    private fun sendReply(out: OutputStream, code: Int) {
        val buf = ByteBuffer.allocate(10)
        buf.put(0x05).put(code.toByte()).put(0x00).put(0x01)
            .put(0).put(0).put(0).put(0).put(0).put(0)
        out.write(buf.array()); out.flush()
    }

    private fun readNBytesCompat(input: InputStream, n: Int): ByteArray {
        if (n <= 0) return ByteArray(0)
        val buffer = ByteArray(n)
        var offset = 0
        while (offset < n) {
            val read = input.read(buffer, offset, n - offset)
            if (read == -1) return buffer.copyOf(offset)
            offset += read
        }
        return buffer
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArray(8192); var n = 0
        while (n < buf.size) {
            val b = input.read()
            if (b < 0) return if (n == 0) null else String(buf, 0, n)
            if (b == '\n'.code) {
                if (n > 0 && buf[n - 1] == '\r'.code.toByte()) n--
                return String(buf, 0, n)
            }
            buf[n++] = b.toByte()
        }
        return String(buf, 0, n)
    }
}
