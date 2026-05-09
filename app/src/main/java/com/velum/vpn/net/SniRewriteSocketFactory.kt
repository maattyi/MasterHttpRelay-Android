package com.velum.vpn.net

import java.net.InetAddress
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Wraps an [SSLSocketFactory] and forces the TLS SNI extension to a fixed
 * value (e.g. "www.google.com") regardless of the destination hostname
 * passed to OkHttp. This is what makes the domain-fronting trick work:
 *   * The TCP connection lands on Google's edge IP
 *   * The TLS handshake's SNI shows the front domain
 *   * The Host header inside encrypted traffic still points at script.google.com
 *
 * Fix #5: Conscrypt on Android 14 overrides SNI set via SSLParameters
 * after the factory returns. We now:
 *   1. Pass [sniHost] as the `host` parameter when wrapping sockets so
 *      any host-based SNI logic sees the correct value from the start.
 *   2. Reflect-call `setHostname` on Conscrypt sockets (the internal
 *      method that Conscrypt honours over SSLParameters.serverNames).
 *   3. Also re-apply SSLParameters.serverNames as a fallback for
 *      non-Conscrypt JSSE implementations.
 */
class SniRewriteSocketFactory(
    private val delegate: SSLSocketFactory,
    private val sniHost: String,
) : SSLSocketFactory() {

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    // Fix #5: pass sniHost as the host parameter so Conscrypt's
    // Platform.get().configureTlsExtensions sees the right hostname
    override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
        rewrite(delegate.createSocket(s, sniHost, port, autoClose))

    override fun createSocket(host: String, port: Int): Socket =
        rewrite(delegate.createSocket(host, port))
    override fun createSocket(host: String, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        rewrite(delegate.createSocket(host, port, localHost, localPort))
    override fun createSocket(host: InetAddress, port: Int): Socket =
        rewrite(delegate.createSocket(host, port))
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        rewrite(delegate.createSocket(address, port, localAddress, localPort))

    private fun rewrite(socket: Socket): Socket {
        if (socket is SSLSocket) {
            // Fix #5 Part A: Conscrypt path — reflect-call setHostname
            // On Android 14's Conscrypt, OpenSSLSocketImpl.setHostname(String)
            // is called via reflection by Platform.configureTlsExtensions and
            // wins over SSLParameters.serverNames. We pre-empt it here.
            try {
                val m = socket.javaClass.getMethod("setHostname", String::class.java)
                m.invoke(socket, sniHost)
            } catch (_: Throwable) {
                // Not a Conscrypt socket — that's fine
            }

            // Fix #5 Part B: Standard JSSE path
            val params: SSLParameters = socket.sslParameters
            params.serverNames = listOf(SNIHostName(sniHost))
            socket.sslParameters = params
        }
        return socket
    }
}
