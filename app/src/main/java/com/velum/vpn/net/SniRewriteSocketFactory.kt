package com.velum.vpn.net

import java.net.InetAddress
import java.net.Socket
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
 */
class SniRewriteSocketFactory(
    private val delegate: SSLSocketFactory,
    private val sniHost: String,
) : SSLSocketFactory() {

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

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
            try {
                val m = socket.javaClass.getMethod("setHostname", String::class.java)
                m.invoke(socket, sniHost)
            } catch (_: Throwable) {}
            val p = socket.sslParameters
            p.serverNames = listOf(javax.net.ssl.SNIHostName(sniHost))
            socket.sslParameters = p
        }
        return socket
    }
}
