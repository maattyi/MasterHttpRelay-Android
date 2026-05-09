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
 *   * The TCP connection lands on Google's edge IP.
 *   * The TLS handshake's SNI shows the front domain.
 *   * The Host header inside encrypted traffic still points at script.google.com.
 *
 * Fix #5 (Conscrypt on Android 14):
 *   Conscrypt's Platform layer reads SNI from a *private* internal field
 *   (set via `OpenSSLSocketImpl.setHostname(String)`) and only falls back
 *   to `SSLParameters.serverNames` on plain JSSE. If we only set the JSSE
 *   property, Android 14 silently overwrites our SNI with the original
 *   destination hostname during the handshake, which:
 *     - leaks the real target to on-path observers, and
 *     - causes Google's edge to refuse the handshake (cert mismatch).
 *
 *   We now do all three things, in order:
 *     1. Pass [sniHost] as the `host` parameter to the underlying factory
 *        so any host-based SNI logic sees the correct value from the start.
 *     2. Reflect-call `setHostname(sniHost)` on the resulting SSLSocket —
 *        this is the path Conscrypt actually honours.
 *     3. Set `SSLParameters.serverNames` as the standards-compliant
 *        fallback for non-Conscrypt JSSE implementations.
 */
class SniRewriteSocketFactory(
    private val delegate: SSLSocketFactory,
    private val sniHost: String,
) : SSLSocketFactory() {

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    // Fix #5 step 1: pass sniHost as the host parameter so Conscrypt's
    // internal Platform.configureTlsExtensions sees the right hostname
    // *before* we even reach the rewrite() block.
    override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
        rewrite(delegate.createSocket(s, sniHost, port, autoClose))

    override fun createSocket(host: String, port: Int): Socket =
        rewrite(delegate.createSocket(sniHost, port))

    override fun createSocket(host: String, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        rewrite(delegate.createSocket(sniHost, port, localHost, localPort))

    override fun createSocket(host: InetAddress, port: Int): Socket =
        rewrite(delegate.createSocket(host, port))

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        rewrite(delegate.createSocket(address, port, localAddress, localPort))

    private fun rewrite(socket: Socket): Socket {
        if (socket is SSLSocket) {
            // Fix #5 step 2: Conscrypt path.
            // On Android 14 (and recent 13 patch levels), Conscrypt's
            // OpenSSLSocketImpl.setHostname(String) is the field actually
            // serialised into the ClientHello SNI extension. It wins over
            // SSLParameters.serverNames. We pre-empt Conscrypt by setting it
            // ourselves via reflection. We swallow exceptions because the
            // method is absent on stock JSSE (and that's perfectly fine —
            // step 3 handles that path).
            try {
                val m = socket.javaClass.getMethod("setHostname", String::class.java)
                m.invoke(socket, sniHost)
            } catch (_: Throwable) {
                // Not a Conscrypt socket — fall through to the JSSE path.
            }

            // Fix #5 step 3: standard JSSE path — required for non-Conscrypt
            // providers and harmless on Conscrypt (it has already taken the
            // value from setHostname above).
            val params: SSLParameters = socket.sslParameters
            params.serverNames = listOf(SNIHostName(sniHost))
            socket.sslParameters = params
        }
        return socket
    }
}
