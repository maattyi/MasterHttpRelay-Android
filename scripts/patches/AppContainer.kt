package com.velum.vpn.core

import android.content.Context
import com.velum.vpn.net.RelayClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Lightweight DI container — built once at app startup.
 *
 * Post-refactor: the MitmCa singleton has been removed. There is no
 * longer any per-device root CA, no per-host leaf cert factory, and
 * no PKCS12 keystore on disk. The proxy is a clean TCP tunnel for
 * HTTPS and a relay for plain HTTP, so no app-side TLS material is
 * needed.
 */

enum class ConnectionState {
    Idle, Connecting, Connected, Disconnecting,
}

class AppContainer(val context: Context) {
    val config: ConfigStore by lazy { ConfigStore(context) }
    val profiles: ProfileManager by lazy { ProfileManager(context) }
    val stats: Stats by lazy { Stats() }
    val multiId: MultiIdDispatcher by lazy { MultiIdDispatcher() }

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

    private val _vpnState = MutableStateFlow(ConnectionState.Idle)
    val vpnState: StateFlow<ConnectionState> = _vpnState

    private val _proxyState = MutableStateFlow(ConnectionState.Idle)
    val proxyState: StateFlow<ConnectionState> = _proxyState

    fun updateVpnState(state: ConnectionState) { _vpnState.value = state }
    fun updateProxyState(state: ConnectionState) { _proxyState.value = state }
}
