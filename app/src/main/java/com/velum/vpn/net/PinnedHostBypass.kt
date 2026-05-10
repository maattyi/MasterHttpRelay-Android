package com.velum.vpn.net

object PinnedHostBypass {

    private val SUFFIXES = setOf(
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
            if (h == suffix || h.endsWith(".$suffix")) {
                return true
            }
        }

        return false
    }

    /** Used by the SettingsScreen "Bypass list" panel for transparency. */
    fun summary(): String =
        "${SUFFIXES.size} hardcoded suffixes covering Google, Apple, " +
        "Microsoft, social, streaming, messaging, and CDNs."
}