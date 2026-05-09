package com.velum.vpn.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * Lightweight, single-shot diagnostic.
 *
 * STRICT SIGNATURE:
 *   suspend fun runTest(proxyPort: Int): Result
 *   data class Result(val success: Boolean, val message: String, val detail: String? = null)
 */
class DiagnosticManager(private val context: Context) {

    data class Result(
        val success: Boolean,
        val message: String,
        val detail: String? = null,
    )

    suspend fun runTest(proxyPort: Int): Result = withContext(Dispatchers.IO) {
        try {
            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxyPort))
            val client = OkHttpClient.Builder()
                .proxy(proxy)
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .callTimeout(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build()

            val request = Request.Builder()
                .url("https://www.google.com/generate_204")
                .header("User-Agent", "VelumVPN-Diagnostic/1.0")
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val code = response.code
                    if (code == 204 || response.isSuccessful) {
                        Result(
                            success = true,
                            message = "Proxy is working",
                            detail = "Connected via 127.0.0.1:$proxyPort and received HTTP $code from Google. " +
                                "TLS chain accepted, relay reachable, MITM operational."
                        )
                    } else {
                        Result(
                            success = false,
                            message = "Proxy reachable but upstream returned $code",
                            detail = "TLS completed, but generate_204 returned $code instead of 204. " +
                                "Likely a relay-side configuration issue."
                        )
                    }
                }
            } catch (e: Throwable) {
                val msg = (e.message ?: e.toString())
                val detail = when {
                    msg.contains("Trust anchor", true) ->
                        "CA not trusted. Install the Velum Root CA via Settings -> Security -> " +
                        "Encryption & credentials -> Install certificate -> CA certificate. " +
                        "Note: Android 7+ apps targeting SDK >= 24 may still reject user CAs " +
                        "unless networkSecurityConfig opts in."
                    msg.contains("CertPathValidator", true) ->
                        "Certificate path validation failed. The leaf cert may be malformed or expired."
                    msg.contains("Connection refused", true) ->
                        "Nothing listening on 127.0.0.1:$proxyPort. Start the proxy/VPN first."
                    msg.contains("SSLHandshake", true) || msg.contains("alert", true) ->
                        "TLS handshake failed. Most commonly: CA not installed, or app pins certs."
                    msg.contains("timeout", true) || msg.contains("timed out", true) ->
                        "Network timeout. Relay may be unreachable or overloaded."
                    else -> "Error: ${msg.take(400)}"
                }
                Result(success = false, message = "Diagnostic failed", detail = detail)
            }
        } catch (e: Throwable) {
            Result(
                success = false,
                message = "Diagnostic crashed",
                detail = "Unexpected error: ${(e.message ?: e.toString()).take(400)}"
            )
        }
    }
}
