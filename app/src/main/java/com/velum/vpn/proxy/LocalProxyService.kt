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
import com.velum.vpn.cert.MitmCa
import com.velum.vpn.core.AppContainer
import com.velum.vpn.net.RelayClient
import com.velum.vpn.ui.MainActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Lightweight foreground service: just runs the local HTTP + SOCKS5
 * listener (no VpnService). Used for app-level proxy mode and for
 * sharing the proxy to other devices on the LAN.
 */
class LocalProxyService : LifecycleService() {

    private lateinit var container: AppContainer
    private var http: HttpProxyServer? = null
    private var socks: Socks5Server? = null
    private var relay: RelayClient? = null
    private lateinit var mitm: MitmCa

    override fun onCreate() {
        super.onCreate()
        container = (application as VelumApp).container
        mitm = container.mitm
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
            startForeground(NOTIF_ID, notification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification())
        }
        lifecycleScope.launch { startProxy() }
        return START_STICKY
    }

    private suspend fun startProxy() {
        if (http?.isRunning == true) return
        val cfg = container.config.flow.first()
        val rcfg = container.profiles.runtime() ?: return stopSelf()
        val client = RelayClient(cfg, rcfg, container.multiId) { gas, cf, relayMs ->
            container.stats.gasLatencyMs = gas
            container.stats.cfLatencyMs = cf
            container.stats.relayLatencyMs = relayMs
        }
        relay = client
        http = HttpProxyServer(cfg, client, mitm, container.stats).also { it.start() }
        if (cfg.enableSocks5) {
            socks = Socks5Server(cfg, client, container.stats).also { it.start() }
        }
    }

    private fun stopProxy() {
        http?.stop(); http = null
        socks?.stop(); socks = null
        relay?.shutdown(); relay = null
    }

    override fun onDestroy() {
        stopProxy()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, LocalProxyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
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
