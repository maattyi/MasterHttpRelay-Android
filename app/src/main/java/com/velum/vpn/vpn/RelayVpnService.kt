package com.velum.vpn.vpn

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.velum.vpn.core.LogBus
import androidx.core.app.NotificationCompat
import com.velum.vpn.VelumApp
import com.velum.vpn.R
import com.velum.vpn.cert.MitmCa
import com.velum.vpn.core.AppContainer
import com.velum.vpn.core.ConnectionState
import com.velum.vpn.net.RelayClient
import com.velum.vpn.proxy.HttpProxyServer
import com.velum.vpn.proxy.Socks5Server
import com.velum.vpn.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/**
 * System-wide VpnService. Establishes a local TUN, then redirects all
 * outgoing TCP/UDP through a transparent HTTP proxy that itself relays
 * through GAS / Cloudflare. Per-app filtering and split tunneling are
 * applied via VpnService.Builder.add{Allowed|Disallowed}Application().
 *
 * Strategy:
 *   1. Establish TUN with Builder.
 *   2. Start the in-process HttpProxyServer + Socks5Server on
 *      127.0.0.1.
 *   3. Configure the system proxy through the TUN's HTTP_PROXY field
 *      (Android 10+ honours this). Fallback for older devices: leave
 *      the user with the local proxy listener and let them set the
 *      system Wi-Fi proxy manually (we surface the IP/port in the UI).
 */
class RelayVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tun: ParcelFileDescriptor? = null
    private var http: HttpProxyServer? = null
    private var socks: Socks5Server? = null
    private var relay: RelayClient? = null
    private lateinit var container: AppContainer
    private lateinit var mitm: MitmCa

    override fun onCreate() {
        super.onCreate()
        container = (application as VelumApp).container
        mitm = container.mitm
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LogBus.i(TAG, "VPN service start command: ${intent?.action}")
        when (intent?.action) {
            ACTION_STOP -> { 
                LogBus.i(TAG, "Stopping VPN service")
                stopVpn(); stopSelf(); return START_NOT_STICKY 
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification(true), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification(true))
        }
        scope.launch { startVpn() }
        return START_STICKY
    }

    private suspend fun startVpn() {
        if (tun != null) return
        container.updateVpnState(ConnectionState.Connecting)
        val cfg = container.config.flow.first()
        val rcfg = container.profiles.runtime()
        if (rcfg == null) {
            LogBus.e(TAG, "no active profile; aborting VPN start")
            container.updateVpnState(ConnectionState.Idle)
            stopSelf(); return
        }

        // Fix #12: Use shared RelayClient from AppContainer
        val client = container.getOrCreateRelayClient(cfg, rcfg)
        relay = client
        http = HttpProxyServer(cfg, client, mitm, container.stats).also { it.start() }
        if (cfg.enableSocks5) {
            socks = Socks5Server(cfg, client, container.stats, mitm).also { it.start() }
        }

        try {
            tun = buildTun(cfg)
            container.updateVpnState(ConnectionState.Connected)
            LogBus.i(TAG, "TUN established with HTTP proxy 127.0.0.1:${cfg.listenPort}")
        } catch (e: Throwable) {
            LogBus.e(TAG, "TUN setup failed: ${e.message}")
            container.updateVpnState(ConnectionState.Idle)
        }
    }

    private fun buildTun(cfg: com.velum.vpn.core.RelayConfig): ParcelFileDescriptor {
        // Fix #8: This is "system-proxy mode" — not a true VPN tunnel.
        // Without addRoute("0.0.0.0", 0) and a tun2socks raw stack reading the FD,
        // only apps that honour the system proxy (setHttpProxy) are covered.
        // Chrome's QUIC stack, native SDKs, Telegram, and any OkHttp client
        // that uses Proxy.NO_PROXY will bypass this entirely.
        // TODO(medium-term): Integrate hev-socks5-tunnel (JNI) or tun2socks
        //   for real userspace TCP/IP, then add addRoute("0.0.0.0", 0) and
        //   addDnsServer("1.1.1.1") + setUnderlyingNetworks for metered accounting.
        val builder = Builder()
            .setSession("VelumVPN (System Proxy)")
            .setMtu(1500)
            .addAddress("10.111.222.1", 24)
            // Do not addRoute("0.0.0.0", 0) because we lack a tun2socks raw TCP/IP stack.
            // Routing everything to TUN without reading the FD causes a traffic blackhole.
            .addRoute("10.111.222.1", 32) // Dummy route to keep VPN alive
            .setBlocking(false)

        // Per-app routing
        if (cfg.perAppMode) {
            if (cfg.allowedApps.isNotEmpty()) {
                cfg.allowedApps.forEach { runCatching { builder.addAllowedApplication(it) } }
            } else if (cfg.disallowedApps.isNotEmpty()) {
                cfg.disallowedApps.forEach { runCatching { builder.addDisallowedApplication(it) } }
            }
        }
        // Always exclude this app to avoid recursion
        runCatching { builder.addDisallowedApplication(packageName) }

        // Split tunnel: any IP listed here goes direct (not through TUN)
        cfg.splitTunnelDirectIps.forEach { ip ->
            // Builder.excludeRoute requires API 33; on older devices fall back
            // to per-app exclusion (no IP-level exclusion possible).
            if (Build.VERSION.SDK_INT >= 33) {
                runCatching {
                    builder.excludeRoute(android.net.IpPrefix(java.net.InetAddress.getByName(ip), 32))
                }
            }
        }

        // System proxy: Android 10+ supports VpnService.Builder.setHttpProxy
        // This is the ONLY traffic capture mechanism in current mode.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setHttpProxy(android.net.ProxyInfo.buildDirectProxy(
                "127.0.0.1", cfg.listenPort
            ))
        }
        // Fix #8: DNS & metered-network accounting for system-proxy mode
        builder.addDnsServer("1.1.1.1")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cm = getSystemService(android.net.ConnectivityManager::class.java)
            val active = cm.activeNetwork
            if (active != null) {
                builder.setUnderlyingNetworks(arrayOf(active))
            }
        }
        LogBus.w(TAG, "VPN started in SYSTEM-PROXY mode. Only apps honouring the system proxy will be covered.")
        return builder.establish() ?: error("VpnService.establish() returned null")
    }

    private fun stopVpn() {
        container.updateVpnState(ConnectionState.Disconnecting)
        runCatching { tun?.close() }; tun = null
        http?.stop(); http = null
        socks?.stop(); socks = null
        // Fix #12: Don't shutdown shared relay client here;
        // let AppContainer own the lifecycle.
        relay = null
        container.updateVpnState(ConnectionState.Idle)
    }

    override fun onDestroy() {
        stopVpn()
        scope.cancel()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    private fun notification(running: Boolean): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, RelayVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, VelumApp.CHANNEL_VPN)
            .setSmallIcon(android.R.drawable.stat_sys_vp_phone_call)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(if (running) R.string.vpn_running else R.string.vpn_idle))
            .setOngoing(true)
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stop)
            .build()
    }

    companion object {
        private const val TAG = "MR-VPN"
        const val NOTIF_ID = 0xBABE
        const val ACTION_STOP = "com.velum.vpn.STOP_VPN"
    }
}
