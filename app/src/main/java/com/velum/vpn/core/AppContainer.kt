package com.velum.vpn.core

import android.content.Context

/**
 * Lightweight DI container — built once at app startup.
 *
 * Holds the long-lived singletons that the UI layer, the foreground
 * services, and the VPN service all need to share: config, profile
 * manager, stats, multi-id router, and the relay engine factory.
 */
class AppContainer(val context: Context) {
    val config: ConfigStore by lazy { ConfigStore(context) }
    val profiles: ProfileManager by lazy { ProfileManager(context) }
    val stats: Stats by lazy { Stats() }
    val multiId: MultiIdDispatcher by lazy { MultiIdDispatcher() }
    val mitm: com.velum.vpn.cert.MitmCa by lazy { com.velum.vpn.cert.MitmCa(context) }
}
