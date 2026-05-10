package com.velum.vpn.net

/**
 * Domains that cannot be MITMed on a non-rooted device, regardless of
 * how correctly the user installed Velum's CA.
 *
 * Reasons (per the Velum forensic audit):
 *   - Chrome / Chromium-based apps enforce Certificate Transparency
 *     (since Chrome 99) and reject any leaf without valid SCTs.
 *   - Google Play Services and *.google.com endpoints use hardcoded
 *     SPKI pins that ignore the OS TrustManager entirely.
 *   - Banking and major social apps ship their own CertificatePinner.
 *
 * Attempting to intercept these will cause TLS_ALERT_CERTIFICATE_UNKNOWN
 * or app-side connection resets, which the user perceives as "the VPN
 * broke my apps." Industry tools (HTTP Toolkit, mitmproxy, PCAPdroid)
 * all maintain an equivalent passthrough list.
 *
 * Use [shouldBypass] to test a SNI hostname or destination host.
 */
object PinnedHostBypass {
        // — Cloudflare / Akamai client-side pins —
        "cloudflare.com",
        "cloudflareinsights.com",
        "akamaihd.net",
        "akamaiedge.net"
    )

    /**
     * Returns true if [host] should be passed through directly without
     * MITM. Comparison is lowercase and trailing-dot tolerant.
     */
    fun shouldBypass(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val h = host.lowercase().trimEnd('.')
        for (suffix in SUFFIXES) {
            if (h == suffix || h.endsWith(".$suffix")) return true
        }
        return false
    }

    /** Used by the SettingsScreen "Bypass list" panel for transparency. */
    fun summary(): String =
        "${SUFFIXES.size} hardcoded suffixes covering Google, Apple, " +
            "Microsoft, social, streaming, messaging, and CDNs."
}
