package com.velum.vpn.net

import okhttp3.CertificatePinner
import okhttp3.ConnectionPool
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.TlsVersion
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import java.security.KeyStore

/**
 * SecureHttpClient — production-grade OkHttp factory for VelumVPN.
 *
 * Replaces the old `RelayClient` ad-hoc TLS configuration which:
 *   - installed a trust-all X509TrustManager
 *   - disabled hostname verification
 *   - swapped the SSLSocketFactory with an SNI-rewriting one
 *   - depended on a user-installed root CA
 *
 * The new client follows modern Android security defaults:
 *
 *   1. Uses the device's *system* trust store via the platform-default
 *      TrustManagerFactory. We never install a custom CA, never look at
 *      user CAs, and never override hostname verification.
 *
 *   2. Restricts the TLS handshake to ConnectionSpec.MODERN_TLS, which on
 *      OkHttp 4.x means TLS 1.3 + TLS 1.2 with a forward-secure cipher
 *      suite list (Android 8 / API 26 is the floor in this app).
 *
 *   3. Optionally pins the Google Apps Script (script.google.com) and
 *      script.googleusercontent.com endpoints via OkHttp's CertificatePinner.
 *      Pinning is keyed on the SubjectPublicKeyInfo (SPKI) of Google's
 *      currently-deployed roots, NOT on a single leaf certificate, so it
 *      keeps working across normal Google certificate rotation.
 *
 *      You can rotate / extend pins by editing GAS_PINS below.
 *
 *   4. Speaks HTTP/2 + HTTP/1.1 only. No cleartext fallback (cleartext is
 *      still required by the local proxy listener for plain-HTTP origins,
 *      but the *transport to GAS* is HTTPS-only).
 *
 *   5. Honours Android's NetworkSecurityConfig: any change to
 *      res/xml/network_security_config.xml automatically applies because
 *      we do not bypass the platform validator.
 *
 * NOTHING in this file is "trust everything". If a handshake fails, the
 * call fails — exactly as it should.
 */
object SecureHttpClient {

    /**
     * Public-key SPKI pins for the Google Apps Script transport.
     *
     * IMPORTANT — these are SHA-256 of the SubjectPublicKeyInfo (the SAME
     * format Chrome/Android use for HPKP-style pinning). They cover all
     * Google-issued leaves chained under these intermediates / roots.
     *
     * Pin set chosen from Google's published roots
     * (https://pki.goog/repository/) and rotated on each major build.
     *
     * If you get an SSLPeerUnverifiedException with "Certificate pinning
     * failure" after a Google rotation, refresh from the URL above and
     * re-deploy the app. Until then, set [enforcePinning] to `false`
     * to fall back to system-trust-only (still TLS-validated).
     */
    private val GAS_PINS: List<String> = listOf(
        // GTS Root R1
        "sha256/hxqRlPTu1bMS/0DITB1SSu0vd4u/8l8TjPgfaAp63Gc=",
        // GTS Root R2
        "sha256/Vfd95BwDeSQo+NUYxVEEIlvkOlWY2SalKK1lPhzOx78=",
        // GTS Root R3
        "sha256/QXnt2YHvdHR3tJYmQIr0Paosp6t/nggsEGD4QJZ3Q0g=",
        // GTS Root R4
        "sha256/mEflZT5enoR1FuXLgYYGqnVEoZvmf9c2bVBpiOjYQ0c=",
        // GlobalSign Root CA — R2  (legacy fallback chain)
        "sha256/iie1VXtL7HzAMF+/PVPR9xzT80kQxdZeJ+zduCB3uj0=",
    )

    /** Toggle pinning. Default ON. Switch OFF only as an emergency hatch. */
    var enforcePinning: Boolean = true

    /**
     * Hostnames that the pinner enforces. Regular Android trust still
     * applies to every other host that may incidentally be contacted.
     */
    private val PINNED_HOSTS = listOf(
        "script.google.com",
        "script.googleusercontent.com",
        "*.googleusercontent.com",
    )

    /**
     * Build an OkHttpClient configured for talking to the Google Apps
     * Script relay endpoint. The caller may further customise (timeouts,
     * dispatcher) via [block].
     */
    fun buildGasClient(
        connectTimeoutMs: Long = 10_000L,
        readTimeoutMs: Long = 30_000L,
        writeTimeoutMs: Long = 30_000L,
        callTimeoutMs: Long = 35_000L,
        poolSize: Int = 8,
        block: (OkHttpClient.Builder.() -> Unit) = {},
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(writeTimeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectionPool(ConnectionPool(poolSize, 5, TimeUnit.MINUTES))
            // Modern TLS only — no SSLv3, no TLS 1.0/1.1, no NULL ciphers.
            .connectionSpecs(
                listOf(
                    ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                        .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
                        .build(),
                    // Cleartext is intentionally NOT present — GAS is HTTPS only.
                ),
            )

        // System default trust manager — explicitly wired so this client is
        // independent of any future global JSSE default change.
        val systemTrust = systemTrustManager()
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(systemTrust), java.security.SecureRandom())
        }
        builder.sslSocketFactory(sslContext.socketFactory, systemTrust)

        // Hostname verification stays at the platform default
        // (HttpsURLConnection.getDefaultHostnameVerifier() == OkHttp's default
        // OkHostnameVerifier). We DO NOT override it.
        builder.hostnameVerifier(HttpsURLConnection.getDefaultHostnameVerifier())

        if (enforcePinning) {
            val pinner = CertificatePinner.Builder().apply {
                for (host in PINNED_HOSTS) {
                    for (pin in GAS_PINS) add(host, pin)
                }
            }.build()
            builder.certificatePinner(pinner)
        }

        builder.block()
        return builder.build()
    }

    /**
     * Resolve the platform default X509TrustManager. We never construct a
     * custom one — the JVM/Android default already validates against the
     * system store with full path checking, name constraints, EKU, etc.
     */
    private fun systemTrustManager(): X509TrustManager {
        val tmf = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm(),
        )
        // Passing null KeyStore tells the factory to load the platform CA store.
        tmf.init(null as KeyStore?)
        val tms = tmf.trustManagers
        return tms.firstOrNull { it is X509TrustManager } as? X509TrustManager
            ?: error("No system X509TrustManager available")
    }
}
