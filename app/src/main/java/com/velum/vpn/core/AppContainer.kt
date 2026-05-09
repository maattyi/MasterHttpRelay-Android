package com.velum.vpn.core

import android.content.Context
import com.velum.vpn.cert.MitmCa
import com.velum.vpn.net.RelayClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Lightweight DI container — built once at app startup.
 *
 * Holds the long-lived singletons that the UI layer, the foreground
 * services, and the VPN service all need to share: config, profile
 * manager, stats, multi-id router, and the relay engine factory.
 *
 * Fix #12: AppContainer now owns a singleton RelayClient (both
 * LocalProxyService and RelayVpnService were building their own,
 * which doubled OkHttp pools and cert handshakes during hand-off).
 * Also exposes vpnState/proxyState StateFlows so the UI can bind
 * to real service lifecycle events instead of local UI state.
 */

enum class ConnectionState {
    Idle, Connecting, Connected, Disconnecting
}

class AppContainer(val context: Context) {
    val config: ConfigStore by lazy { ConfigStore(context) }
    val profiles: ProfileManager by lazy { ProfileManager(context) }
    val stats: Stats by lazy { Stats() }
    val multiId: MultiIdDispatcher by lazy { MultiIdDispatcher() }
    val mitm: com.velum.vpn.cert.MitmCa by lazy {
        com.velum.vpn.cert.MitmCa(context)
    }

    // Fix #12: Singleton RelayClient shared across services.
    // Built lazily; once created, both VPN and proxy services reuse it.
    @Volatile var relayClient: RelayClient? = null
        private set

    fun getOrCreateRelayClient(
        cfg: RelayConfig,
        rcfg: RuntimeRelayConfig,
    ): RelayClient {
        relayClient?.let { return it }
        synchronized(this) {
            relayClient?.let { return it }
            val client = RelayClient(cfg, rcfg, multiId) { gas, cf, relayMs ->
                stats.gasLatencyMs = gas
                stats.cfLatencyMs = cf
                stats.relayLatencyMs = relayMs
            }
            relayClient = client
            return client
        }
    }

    fun destroyRelayClient() {
        synchronized(this) {
            relayClient?.shutdown()
            relayClient = null
        }
    }

    // Fix #12: Real connection state flows for UI binding
    private val _vpnState = MutableStateFlow(ConnectionState.Idle)
    val vpnState: StateFlow<ConnectionState> = _vpnState

    private val _proxyState = MutableStateFlow(ConnectionState.Idle)
    val proxyState: StateFlow<ConnectionState> = _proxyState

    fun updateVpnState(state: ConnectionState) { _vpnState.value = state }
    fun updateProxyState(state: ConnectionState) { _proxyState.value = state }
}
