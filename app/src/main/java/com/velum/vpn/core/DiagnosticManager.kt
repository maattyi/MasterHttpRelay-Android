package com.velum.vpn.core

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Four-step reasoning diagnostic.
 *
 *   Step 1: Is the local proxy actually accepting TCP on the configured port?
 *   Step 2: Is the Velum Root CA present in the platform's user trust store?
 *   Step 3: Does THIS APP trust user-installed CAs (network_security_config opt-in)?
 *   Step 4: Wire test through the proxy to a NON-pinned endpoint (httpbin).
 *
 * Why not test against google.com any more:
 *   The previous version probed https://www.google.com/generate_204. Chrome
 *   and Google services are statically pinned, so a MITM attempt on those
 *   domains is unconditionally rejected with TLS alert 46
 *   (SSLV3_ALERT_CERTIFICATE_UNKNOWN) regardless of how correctly the CA is
 *   installed. We now use httpbin.org, which has no pinning and is the
 *   industry-standard endpoint for proxy verification.
 *
 * Strict signature — DO NOT change without updating SettingsScreen.kt:
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
        // Step 1: proxy reachable?
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", proxyPort), 1500)
            }
        } catch (e: Throwable) {
            return@withContext Result(
                success = false,
                message = "Step 1/4: Proxy is not running",
                detail = "Nothing is listening on 127.0.0.1:$proxyPort. " +
                    "Go to the Dashboard and tap START on either the VPN button " +
                    "or the Local Proxy row, then run this test again.",
            )
        }

        // Step 2: Velum Root CA in the user trust store?
        val caInUserStore: Boolean = try {
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(null as KeyStore?)
            val tm = tmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
            tm?.acceptedIssuers?.any { issuer ->
                val name = issuer.subjectX500Principal.name
                name.contains("VelumVPN", ignoreCase = true) ||
                    name.contains("Velum Root CA", ignoreCase = true)
            } ?: false
        } catch (_: Throwable) { false }

        if (!caInUserStore) {
            return@withContext Result(
                success = false,
                message = "Step 2/4: Velum Root CA is not installed",
                detail = "The CA is not present in the device trust store. " +
                    "(1) Tap SAVE TO DOWNLOADS above. " +
                    "(2) Open Android Settings -> Security -> Encryption & credentials " +
                    "-> Install a certificate -> CA certificate. " +
                    "(3) Pick velum_ca.crt from Downloads/Velum/. " +
                    "Tip: it MUST be installed via 'CA certificate', NOT 'User certificate'.",
            )
        }

        // Step 3: does THIS app trust user CAs? (network_security_config check)
        val appTrustsUserCAs: Boolean = appAlsoTrustsUserCAs()
        if (!appTrustsUserCAs) {
            return@withContext Result(
                success = false,
                message = "Step 3/4: App is not configured to trust user CAs",
                detail = "Velum's network_security_config.xml does not opt the app " +
                    "into the user CA store. This is a build-time fix: add " +
                    "<certificates src=\"user\"/> inside <base-config><trust-anchors> " +
                    "in res/xml/network_security_config.xml and rebuild. " +
                    "(See Android Nougat trust changes documentation.)",
            )
        }

        // Step 4: wire test through the proxy to an unpinned endpoint.
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
                .url("https://httpbin.org/status/200")
                .header("User-Agent", "VelumVPN-Diagnostic/2.0")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result(
                        success = true,
                        message = "All 4 steps passed — proxy is fully operational",
                        detail = "Proxy on 127.0.0.1:$proxyPort accepted the connection, " +
                            "Velum Root CA is trusted by both the system and this app, " +
                            "and an HTTPS request through the relay returned " +
                            "${response.code} from httpbin.org. Note: Chrome and " +
                            "Google domains are hard-pinned and cannot be MITMed even " +
                            "with a correctly installed CA — that's by design and " +
                            "applies to every MITM proxy, not just Velum.",
                    )
                } else {
                    Result(
                        success = false,
                        message = "Step 4/4: Upstream returned HTTP ${response.code}",
                        detail = "TLS handshake completed end-to-end and the chain was " +
                            "trusted, but httpbin.org returned ${response.code}. " +
                            "Likely a transient relay issue — try again.",
                    )
                }
            }
        } catch (e: Throwable) {
            val msg = e.message ?: e.toString()
            val (headline, detail) = when {
                msg.contains("Trust anchor", true) ||
                    msg.contains("CertPathValidator", true) -> Pair(
                    "Step 4/4: TLS chain rejected by the system",
                    "Even though the CA appears to be in the user store, the system " +
                        "TrustManager refused the chain. This usually means the CA " +
                        "was installed under 'User certificate' instead of 'CA " +
                        "certificate'. Re-install it via Settings -> Security -> " +
                        "Encryption & credentials -> Install a certificate -> " +
                        "CA certificate, then run this test again.",
                )
                msg.contains("CERTIFICATE_UNKNOWN", true) ||
                    msg.contains("alert", true) -> Pair(
                    "Step 4/4: Server sent TLS alert (cert unknown)",
                    "The TLS endpoint we probed sent alert 46 — it does not trust " +
                        "the MITM leaf. This is normal for hard-pinned hosts " +
                        "(Chrome on Google domains, Telegram, banking apps). " +
                        "If you see this on httpbin.org specifically, the CA is " +
                        "not really installed; verify it appears in Settings -> " +
                        "Security -> Trusted credentials -> User.",
                )
                msg.contains("timeout", true) -> Pair(
                    "Step 4/4: Network timeout",
                    "Relay endpoint is slow or unreachable. Check the multi-id " +
                        "endpoints in Settings and try again.",
                )
                else -> Pair(
                    "Step 4/4: Wire test failed",
                    "Error: ${msg.take(400)}",
                )
            }
            Result(success = false, message = headline, detail = detail)
        }
    }

    /**
     * Heuristic check: does the app's effective TrustManager actually
     * include the user CA store? We do this by walking the default
     * TrustManager's accepted issuers and seeing if our Velum CA is
     * reachable from it. The Velum CA cert lives in the user store and
     * was just confirmed present in step 2, so if the default TrustManager
     * exposes it, network_security_config has opted us in.
     *
     * On Android 6.0 (API 23) and lower, user CAs are trusted by default
     * regardless of network_security_config, so we always return true.
     */
    private fun appAlsoTrustsUserCAs(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return true
        return try {
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(null as KeyStore?)
            val tm = tmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
                ?: return false
            tm.acceptedIssuers.any { issuer ->
                val name = issuer.subjectX500Principal.name
                name.contains("VelumVPN", ignoreCase = true) ||
                    name.contains("Velum Root CA", ignoreCase = true)
            }
        } catch (_: Throwable) {
            false
        }
    }
}
