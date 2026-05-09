package com.velum.vpn.core

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable

private val Context.dataStore by preferencesDataStore("master_relay_config")

@Serializable
data class RelayConfig(
    var listenHost: String = "127.0.0.1",
    var listenPort: Int = 8080,
    var socksHost: String = "127.0.0.1",
    var socksPort: Int = 1080,
    var enableSocks5: Boolean = true,
    var lanShare: Boolean = false,
    var youtubeViaRelay: Boolean = true,
    var maxRelayConcurrency: Int = 64,
    var warmCount: Int = 30,
    var poolMinIdle: Int = 15,
    var batchWindowMicroMs: Int = 5,
    var batchWindowMacroMs: Int = 50,
    var batchMax: Int = 50,
    var chunkSize: Int = 262_144,
    var maxParallel: Int = 16,
    var relayTimeoutMs: Int = 30_000,
    var relayRetryCount: Int = 2,
    var cacheMb: Int = 50,
    var h2MaxStreams: Int = 100,
    var multiIdFailThreshold: Int = 3,
    var multiIdCooldownSec: Int = 30,
    var themeMode: String = "dark", // "dark", "light", "system"
    var language: String = "en",    // "en", "fa"
    var perAppMode: Boolean = false,
    var allowedApps: Set<String> = emptySet(),
    var disallowedApps: Set<String> = emptySet(),
    var splitTunnelDirectIps: Set<String> = emptySet(),
    var autoReconnect: Boolean = true,
    var batterySaver: Boolean = false,
    var logLevel: String = "INFO",
)

class ConfigStore(private val context: Context) {

    // Cached snapshot — populated lazily off the main thread.
    @Volatile private var cached: RelayConfig = RelayConfig()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val K_HOST = stringPreferencesKey("listen_host")
    private val K_PORT = intPreferencesKey("listen_port")
    private val K_SOCKS_HOST = stringPreferencesKey("socks_host")
    private val K_SOCKS_PORT = intPreferencesKey("socks_port")
    private val K_SOCKS_ON = booleanPreferencesKey("socks_on")
    private val K_LAN = booleanPreferencesKey("lan")
    private val K_YT = booleanPreferencesKey("youtube")
    private val K_CONC = intPreferencesKey("conc")
    private val K_WARM = intPreferencesKey("warm")
    private val K_IDLE = intPreferencesKey("idle")
    private val K_BMICRO = intPreferencesKey("b_micro")
    private val K_BMACRO = intPreferencesKey("b_macro")
    private val K_BMAX = intPreferencesKey("b_max")
    private val K_CHUNK = intPreferencesKey("chunk")
    private val K_PAR = intPreferencesKey("par")
    private val K_TO = intPreferencesKey("to")
    private val K_RETRY = intPreferencesKey("retry")
    private val K_CACHE = intPreferencesKey("cache")
    private val K_H2 = intPreferencesKey("h2")
    private val K_MFAIL = intPreferencesKey("m_fail")
    private val K_MCOOL = intPreferencesKey("m_cool")
    private val K_THEME_MODE = stringPreferencesKey("theme_mode")
    private val K_LANG = stringPreferencesKey("language")
    private val K_PERAPP = booleanPreferencesKey("per_app")
    private val K_ALLOW = stringSetPreferencesKey("allow")
    private val K_DISALLOW = stringSetPreferencesKey("disallow")
    private val K_DIRECT_IPS = stringSetPreferencesKey("direct_ips")
    private val K_RECONNECT = booleanPreferencesKey("reconnect")
    private val K_BATTERY = booleanPreferencesKey("battery")
    private val K_LOG = stringPreferencesKey("log_level")

    val flow: Flow<RelayConfig> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { p ->
            RelayConfig(
                listenHost = p[K_HOST] ?: "127.0.0.1",
                listenPort = p[K_PORT] ?: 8080,
                socksHost = p[K_SOCKS_HOST] ?: "127.0.0.1",
                socksPort = p[K_SOCKS_PORT] ?: 1080,
                enableSocks5 = p[K_SOCKS_ON] ?: true,
                lanShare = p[K_LAN] ?: false,
                youtubeViaRelay = p[K_YT] ?: true,
                maxRelayConcurrency = p[K_CONC] ?: 64,
                warmCount = p[K_WARM] ?: 30,
                poolMinIdle = p[K_IDLE] ?: 15,
                batchWindowMicroMs = p[K_BMICRO] ?: 5,
                batchWindowMacroMs = p[K_BMACRO] ?: 50,
                batchMax = p[K_BMAX] ?: 50,
                chunkSize = p[K_CHUNK] ?: 262_144,
                maxParallel = p[K_PAR] ?: 16,
                relayTimeoutMs = p[K_TO] ?: 30_000,
                relayRetryCount = p[K_RETRY] ?: 2,
                cacheMb = p[K_CACHE] ?: 50,
                h2MaxStreams = p[K_H2] ?: 100,
                multiIdFailThreshold = p[K_MFAIL] ?: 3,
                multiIdCooldownSec = p[K_MCOOL] ?: 30,
                themeMode = p[K_THEME_MODE] ?: "dark",
                language = p[K_LANG] ?: "en",
                perAppMode = p[K_PERAPP] ?: false,
                allowedApps = p[K_ALLOW] ?: emptySet(),
                disallowedApps = p[K_DISALLOW] ?: emptySet(),
                splitTunnelDirectIps = p[K_DIRECT_IPS] ?: emptySet(),
                autoReconnect = p[K_RECONNECT] ?: true,
                batterySaver = p[K_BATTERY] ?: false,
                logLevel = p[K_LOG] ?: "INFO",
            ).also { cached = it }
        }
        .stateIn(ioScope, SharingStarted.Eagerly, RelayConfig())

    /** Returns the most recent cached value. NEVER blocks. */
    fun snapshot(): RelayConfig = cached

    suspend fun save(c: RelayConfig) {
        cached = c
        context.dataStore.edit { p ->
            p[K_HOST] = c.listenHost; p[K_PORT] = c.listenPort
            p[K_SOCKS_HOST] = c.socksHost; p[K_SOCKS_PORT] = c.socksPort
            p[K_SOCKS_ON] = c.enableSocks5
            p[K_LAN] = c.lanShare
            p[K_YT] = c.youtubeViaRelay
            p[K_CONC] = c.maxRelayConcurrency
            p[K_WARM] = c.warmCount; p[K_IDLE] = c.poolMinIdle
            p[K_BMICRO] = c.batchWindowMicroMs
            p[K_BMACRO] = c.batchWindowMacroMs
            p[K_BMAX] = c.batchMax
            p[K_CHUNK] = c.chunkSize
            p[K_PAR] = c.maxParallel
            p[K_TO] = c.relayTimeoutMs
            p[K_RETRY] = c.relayRetryCount
            p[K_CACHE] = c.cacheMb
            p[K_H2] = c.h2MaxStreams
            p[K_MFAIL] = c.multiIdFailThreshold
            p[K_MCOOL] = c.multiIdCooldownSec
            p[K_THEME_MODE] = c.themeMode
            p[K_LANG] = c.language
            p[K_PERAPP] = c.perAppMode
            p[K_ALLOW] = c.allowedApps
            p[K_DISALLOW] = c.disallowedApps
            p[K_DIRECT_IPS] = c.splitTunnelDirectIps
            p[K_RECONNECT] = c.autoReconnect
            p[K_BATTERY] = c.batterySaver
            p[K_LOG] = c.logLevel
        }
    }
}
