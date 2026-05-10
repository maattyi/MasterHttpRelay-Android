package com.velum.vpn.core

import android.content.Context
import com.velum.vpn.net.SecureHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * DiagnosticManager — lightweight 4-step health probe.
 *
 * Post-refactor: there is no MITM layer to test, so the diagnostic now
 * checks what actually matters for a non-root, non-MITM, censorship-bypass
 * VPN client:
 *
 *   Step 1 — local proxy is bound on the configured port
 *   Step 2 — Android system trust store is reachable
 *   Step 3 — HTTPS handshake to script.google.com (or a known control)
 *            succeeds via the hardened SecureHttpClient (TLS 1.2/1.3,
 *            system trust, certificate pinning)
 *   Step 4 — wire test through the local proxy to a known unpinned host
 *            (httpbin.org). This validates the CONNECT pass-through path.
 *
 * No CA install checks. No "user store" probes. No MITM expectation.
 */
class DiagnosticManager(@Suppress("UNUSED_PARAMETER") private val context: Context) {

    data class Result(
        val success: Boolean,
        val message: String,
        val detail: String? = null,
    )

    suspend fun runTest(proxyPort: Int): Result = withContext(Dispatchers.IO) {

        // Step 1: local proxy listening?
        try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", proxyPort), 1500) }
        } catch (_: Throwable) {
            return@withContext Result(
                success = false,
                message = "Step 1/4 — Local proxy not running",
                detail = "Nothing listening on 127.0.0.1:$proxyPort. Start the VPN " +
                    "or Local Proxy from the Dashboard, then retry.",
            )
        }

        // Step 2: system trust store reachable?
        val systemAnchors = try { systemAnchorCount() } catch (e: Throwable) { -1 }
        if (systemAnchors <= 0) {
            return@withContext Result(
                success = false,
                message = "Step 2/4 — System trust store unavailable",
                detail = "TrustManagerFactory could not load the platform CA store. " +
                    "This usually indicates a corrupted device profile.",
            )
        }

        // Step 3: hardened HTTPS handshake to GAS endpoint
        val direct = directHandshake()
        if (!direct.success) return@withContext direct

        // Step 4: pass-through wire test through local proxy
        val wire = wireTestThroughProxy(proxyPort)
        if (!wire.success) return@withContext wire

        Result(
            success = true,
            message = "All 4 checks passed — proxy operational",
            detail = "Local proxy reachable.\n" +
                "System trust anchors: $systemAnchors\n" +
                "Direct HTTPS to script.google.com: OK (TLS 1.2/1.3, pinned).\n" +
                "Pass-through HTTPS to httpbin.org: OK.\n\n" +
                "VelumVPN is running in production-safe mode: no MITM, no user CA, " +
                "end-to-end TLS preserved for HTTPS traffic.",
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun systemAnchorCount(): Int {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        val tm = tmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
        return tm?.acceptedIssuers?.size ?: 0
    }

    private fun directHandshake(): Result {
        return try {
            val client = SecureHttpClient.buildGasClient(
                connectTimeoutMs = 5_000L,
                readTimeoutMs = 8_000L,
                callTimeoutMs = 15_000L,
            )
            val req = Request.Builder()
                .url("https://script.google.com/")
                .header("User-Agent", "VelumVPN-Diagnostic/4.0")
                .build()
            client.newCall(req).execute().use { resp ->
                // 200/302/4xx are all fine here — we are only validating the TLS chain.
                Result(true, "direct ok (HTTP ${resp.code})", null)
            }
        } catch (e: Throwable) {
            val msg = e.message ?: e.toString()
            val (head, det) = when {
                msg.contains("Certificate pinning failure", true) -> Pair(
                    "Step 3/4 — Certificate pin mismatch",
                    "The Google root pin set is out of date. Update GAS_PINS in " +
                        "SecureHttpClient.kt or set enforcePinning = false as a " +
                        "temporary workaround.",
                )
                msg.contains("Trust anchor", true) ||
                    msg.contains("CertPathValidator", true) -> Pair(
                    "Step 3/4 — System TrustManager rejected the chain",
                    "Device system trust store does not validate Google's chain. " +
                        "This is extremely unusual on stock Android — check device " +
                        "date/time and any installed enterprise profiles.",
                )
                msg.contains("timeout", true) -> Pair(
                    "Step 3/4 — Network timeout to script.google.com",
                    "TLS handshake or TCP connect timed out. The network may be " +
                        "blocking Google or you have no connectivity.",
                )
                else -> Pair("Step 3/4 — Direct HTTPS handshake failed", "Error: ${msg.take(400)}")
            }
            Result(false, head, det)
        }
    }

    private fun wireTestThroughProxy(proxyPort: Int): Result {
        return try {
            val proxy = java.net.Proxy(
                java.net.Proxy.Type.HTTP,
                InetSocketAddress("127.0.0.1", proxyPort),
            )
            val client = okhttp3.OkHttpClient.Builder()
                .proxy(proxy)
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .callTimeout(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build()
            val req = Request.Builder()
                .url("https://httpbin.org/status/200")
                .header("User-Agent", "VelumVPN-Diagnostic/4.0")
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    Result(true, "wire ok", null)
                } else {
                    Result(
                        success = false,
                        message = "Step 4/4 — Wire test got HTTP ${resp.code}",
                        detail = "TLS chain accepted but httpbin returned ${resp.code}. " +
                            "Likely a transient relay issue; retry.",
                    )
                }
            }
        } catch (e: Throwable) {
            val msg = e.message ?: e.toString()
            Result(
                success = false,
                message = "Step 4/4 — Wire test through proxy failed",
                detail = "Error: ${msg.take(400)}\n\n" +
                    "If the local proxy is up but this fails, the upstream TCP " +
                    "tunnel could not reach the origin. Check connectivity.",
            )
        }
    }
}
