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
import com.velum.vpn.core.AppContainer
import com.velum.vpn.core.ConnectionState
import com.velum.vpn.net.RelayClient
import com.velum.vpn.proxy.HttpProxyServer
import com.velum.vpn.proxy.Socks5Server
import com.velum.vpn.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/**
 * RelayVpnService — system-wide VpnService.
 *
 * Establishes a local TUN, runs the local HTTP + SOCKS5 listeners, and
 * (on API 29+) sets the system-proxy field of the VPN so apps that honour
 * a system HTTP proxy route through us. Apps that bypass the system proxy
 * (Chrome QUIC, native sockets) continue to work end-to-end via the
 * normal Android networking stack — they simply do not benefit from the
 * GAS relay.
 *
 * Post-refactor: no MitmCa dependency. The proxy is a clean tunnel:
 *   * plain HTTP -> GAS relay
 *   * HTTPS CONNECT -> raw TCP pass-through (end-to-end TLS preserved)
 *
 * No certificate issuance, no user CA install required.
 */
class RelayVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tun: ParcelFileDescriptor? = null
    private var http: HttpProxyServer? = null
    private var socks: Socks5Server? = null
    private var relay: RelayClient? = null
    private lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        container = (application as VelumApp).container
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
            startForeground(
                NOTIF_ID, notification(true),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
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

        val client = container.getOrCreateRelayClient(cfg, rcfg)
        relay = client
        http = HttpProxyServer(cfg, client, container.stats).also { it.start() }
        if (cfg.enableSocks5) {
            socks = Socks5Server(cfg, client, container.stats).also { it.start() }
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
        // System-proxy mode: TUN exists primarily to host setHttpProxy().
        // We do not addRoute(0/0) because there is no userspace TCP/IP
        // stack reading the FD — that would blackhole traffic.
        val builder = Builder()
            .setSession("VelumVPN (System Proxy)")
            .setMtu(1500)
            .addAddress("10.111.222.1", 24)
            .addRoute("10.111.222.1", 32) // dummy route to keep VPN alive
            .setBlocking(false)

        if (cfg.perAppMode) {
            if (cfg.allowedApps.isNotEmpty()) {
                cfg.allowedApps.forEach { runCatching { builder.addAllowedApplication(it) } }
            } else if (cfg.disallowedApps.isNotEmpty()) {
                cfg.disallowedApps.forEach { runCatching { builder.addDisallowedApplication(it) } }
            }
        }
        runCatching { builder.addDisallowedApplication(packageName) }

        cfg.splitTunnelDirectIps.forEach { ip ->
            if (Build.VERSION.SDK_INT >= 33) {
                runCatching {
                    builder.excludeRoute(android.net.IpPrefix(java.net.InetAddress.getByName(ip), 32))
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setHttpProxy(
                android.net.ProxyInfo.buildDirectProxy("127.0.0.1", cfg.listenPort),
            )
        }
        builder.addDnsServer("1.1.1.1")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cm = getSystemService(android.net.ConnectivityManager::class.java)
            val active = cm?.activeNetwork
            if (active != null) builder.setUnderlyingNetworks(arrayOf(active))
        }
        LogBus.w(TAG, "VPN started in SYSTEM-PROXY mode. Apps honouring the system proxy will be covered.")
        return builder.establish() ?: error("VpnService.establish() returned null")
    }

    private fun stopVpn() {
        container.updateVpnState(ConnectionState.Disconnecting)
        runCatching { tun?.close() }; tun = null
        http?.stop(); http = null
        socks?.stop(); socks = null
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
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, RelayVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
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
        private const val TAG = "VELUM-VPN"
        const val NOTIF_ID = 0xBABE
        const val ACTION_STOP = "com.velum.vpn.STOP_VPN"
    }
}
