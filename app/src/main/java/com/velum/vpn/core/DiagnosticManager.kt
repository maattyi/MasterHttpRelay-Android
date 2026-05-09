package com.velum.vpn.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class DiagnosticManager(private val context: Context) {

    data class Result(
        val success: Boolean,
        val message: String,
        val detail: String? = null
    )

    suspend fun runTest(proxyPort: Int): Result = withContext(Dispatchers.IO) {
        try {
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(null as java.security.KeyStore?)
            val tm = tmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
            
            if (tm == null) {
                return@withContext Result(false, "No X509TrustManager found in system")
            }

            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxyPort))
            val client = OkHttpClient.Builder()
                .proxy(proxy)
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url("https://www.google.com/generate_204")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful || response.code == 204) {
                    Result(true, "SSL Trust Verified!", "The system trust store successfully validated the MITM certificate.")
                } else {
                    Result(false, "Proxy reached, but HTTP error: ${response.code}")
                }
            }
        } catch (e: Exception) {
            val msg = e.message ?: e.toString()
            LogBus.e("Diag", "Diagnostic failure: \$msg", e)
            
            val detail = when {
                msg.contains("Trust anchor for certification path not found", true) ->
                    "FAIL: CA not trusted. Ensure the certificate is installed in 'Settings -> Security -> Encryption & credentials -> Trusted credentials -> User'."
                msg.contains("CertPathValidatorException", true) ->
                    "FAIL: Certificate validation error. Check leaf compliance (serial, validity, SAN)."
                msg.contains("Connection refused", true) ->
                    "FAIL: Proxy not running. Start the proxy/VPN before running diagnostics."
                else -> "Error: \$msg"
            }
            Result(false, "SSL Handshake Failed", detail)
        }
    }
}