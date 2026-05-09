package com.velum.vpn.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import com.velum.vpn.proxy.LocalProxyService
import com.velum.vpn.vpn.RelayVpnService

/**
 * Optional auto-start. We can't elevate VpnService consent at boot, so
 * we only auto-start the local-proxy listener (no VPN dialog).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON") return

        // Auto-start the local proxy if user enabled it
        val prefs = context.getSharedPreferences("autostart", Context.MODE_PRIVATE)
        if (prefs.getBoolean("auto_start_proxy", false)) {
            val svc = Intent(context, LocalProxyService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc)
            } else {
                context.startService(svc)
            }
        }
    }
}
