package com.velum.vpn.proxy

import com.velum.vpn.cert.MitmCa
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
import javax.net.ssl.SSLSocket

/**
 * Telegram-compatible SOCKS5 server.  CONNECT only, no UDP relay.
 *
 * Fix #9: HTTPS (port 443) now performs real MITM using the shared
 * MitmCa instance — identical to HttpProxyServer.handleConnect.
 * Previously this branch returned a hardcoded 502, which broke
 * Telegram's MTProto-over-HTTPS and any SOCKS5 client expecting
 * TLS interception.
 */
class Socks5Server(
    private val cfg: RelayConfig,
    private val relay: RelayClient,
    private val stats: Stats,
    private val mitm: MitmCa,  // Fix #9: MitmCa now injected
) {
    private val tag = "MR-SOCKS5"
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
        // Greeting
        val ver = input.read(); val nm = input.read()
        if (ver != 0x05 || nm < 0) return
        val methods = readNBytesCompat(input, nm)
        // No-auth only
        output.write(byteArrayOf(0x05, 0x00)); output.flush()

        // Request
        val head = readNBytesCompat(input, 4)
        if (head.size < 4 || head[0].toInt() != 0x05) return
        val cmd = head[1].toInt(); val atyp = head[3].toInt()
        if (cmd != 0x01) { sendReply(output, 0x07); return }

        val host = when (atyp) {
            0x01 -> { val b = readNBytesCompat(input, 4); "${b[0].toInt() and 0xff}.${b[1].toInt() and 0xff}.${b[2].toInt() and 0xff}.${b[3].toInt() and 0xff}" }
            0x03 -> { val ln = input.read(); String(readNBytesCompat(input, ln), Charsets.UTF_8) }
            0x04 -> { val b = readNBytesCompat(input, 16); java.net.InetAddress.getByAddress(b).hostAddress ?: "::" }
            else -> { sendReply(output, 0x08); return }
        }
        val portBytes = readNBytesCompat(input, 2)
        val port = ((portBytes[0].toInt() and 0xff) shl 8) or (portBytes[1].toInt() and 0xff)

        if (port != 80 && port != 443) { sendReply(output, 0x07); return }
        sendReply(output, 0x00)
        stats.incrRequests()
        LogBus.i(tag, "SOCKS5 -> $host:$port")

        if (port == 80) plainHttp(host, port, input, output) else httpsMitm(host, port, client, input, output)
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
     * Fix #9: Real MITM for SOCKS5+HTTPS, mirroring HttpProxyServer.handleConnect.
     * Wraps the SOCKS5 client socket with a TLS layer using MitmCa's per-host
     * certificate, then reads/relays HTTP requests over the decrypted stream.
     */
    private suspend fun httpsMitm(host: String, port: Int, client: Socket, input: InputStream, output: OutputStream) {
        val ctx = mitm.contextFor(host)
        val ssf = ctx.socketFactory
        val tls = try {
            (ssf.createSocket(client, host, port, true) as SSLSocket).apply {
                useClientMode = false
                soTimeout = 60_000  // Fix #10: prevent parked coroutines on idle clients
            }
        } catch (e: Throwable) {
            LogBus.d(tag, "SOCKS5 TLS wrap failed for $host: ${e.message}")
            return
        }

        try {
            tls.startHandshake()
        } catch (e: Throwable) {
            LogBus.d(tag, "SOCKS5 TLS handshake FAILED for $host: ${e.message}")
            return
        } finally {
            // Ensure TLS socket is always closed
            if (!tls.isClosed) runCatching { tls.close() }
        }

        // Mirror HttpProxyServer.handleConnect request loop
        val tlsIn = tls.inputStream
        val tlsOut = tls.outputStream

        try {
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

                // Fix #10: Support chunked transfer-encoding request bodies
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
                    Response.error(502, "relay error: ${e.message}")
                }

                val bytes = resp.toHttpBytes()
                tlsOut.write(bytes)
                tlsOut.flush()
                stats.addDown(bytes.size.toLong())
            }
        } catch (e: Throwable) {
            LogBus.d(tag, "SOCKS5 MITM session error for $host: ${e.message}")
        } finally {
            runCatching { tls.close() }
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
