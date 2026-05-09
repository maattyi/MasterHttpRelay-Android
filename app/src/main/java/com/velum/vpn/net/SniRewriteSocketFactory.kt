package com.velum.vpn.net

import java.net.InetAddress
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class SniRewriteSocketFactory(
    private val delegate: SSLSocketFactory,
    private val sniHost: String,
) : SSLSocketFactory() {

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

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
            try {
                val m = socket.javaClass.getMethod("setHostname", String::class.java)
                m.invoke(socket, sniHost)
            } catch (_: Throwable) {
                // Not Conscrypt — JSSE fallback below handles it.
            }
            try {
                val params: SSLParameters = socket.sslParameters
                params.serverNames = listOf(SNIHostName(sniHost))
                socket.sslParameters = params
            } catch (_: Throwable) {
                // Some stacks reject SSLParameters mutation; setHostname above is enough.
            }
        }
        return socket
    }
}
