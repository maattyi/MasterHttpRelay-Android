package com.velum.vpn.proxy

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.velum.vpn.VelumApp
import com.velum.vpn.R
import com.velum.vpn.core.AppContainer
import com.velum.vpn.core.ConnectionState
import com.velum.vpn.net.RelayClient
import com.velum.vpn.ui.MainActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Lightweight foreground service running the local HTTP + SOCKS5 listener
 * (no VpnService). Used for app-level proxy mode and LAN sharing.
 *
 * Post-refactor: no MitmCa, no SSLContext, no per-host leaf signing.
 * The proxy now does TCP pass-through for HTTPS so the user's app can
 * complete its real TLS handshake end-to-end with the origin.
 */
class LocalProxyService : LifecycleService() {

    private lateinit var container: AppContainer
    private var http: HttpProxyServer? = null
    private var socks: Socks5Server? = null
    private var relay: RelayClient? = null

    override fun onCreate() {
        super.onCreate()
        container = (application as VelumApp).container
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        com.velum.vpn.core.LogBus.i("Proxy", "Local proxy service start command: ${intent?.action}")
        when (intent?.action) {
            ACTION_STOP -> {
                com.velum.vpn.core.LogBus.i("Proxy", "Stopping local proxy service")
                stopProxy(); stopSelf(); return START_NOT_STICKY
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, notification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIF_ID, notification())
        }
        lifecycleScope.launch { startProxy() }
        return START_STICKY
    }

    private suspend fun startProxy() {
        if (http?.isRunning == true) return
        container.updateProxyState(ConnectionState.Connecting)
        val cfg = container.config.flow.first()
        val rcfg = container.profiles.runtime() ?: run {
            container.updateProxyState(ConnectionState.Idle)
            return stopSelf()
        }
        val client = container.getOrCreateRelayClient(cfg, rcfg)
        relay = client
        http = HttpProxyServer(cfg, client, container.stats).also { it.start() }
        if (cfg.enableSocks5) {
            socks = Socks5Server(cfg, client, container.stats).also { it.start() }
        }
        container.updateProxyState(ConnectionState.Connected)
    }

    private fun stopProxy() {
        container.updateProxyState(ConnectionState.Disconnecting)
        http?.stop(); http = null
        socks?.stop(); socks = null
        relay = null
        container.updateProxyState(ConnectionState.Idle)
    }

    override fun onDestroy() {
        stopProxy()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, LocalProxyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, VelumApp.CHANNEL_PROXY)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.proxy_running))
            .setOngoing(true)
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stop)
            .build()
    }

    companion object {
        const val NOTIF_ID = 0xCAFE
        const val ACTION_STOP = "com.velum.vpn.STOP_PROXY"
    }
}
