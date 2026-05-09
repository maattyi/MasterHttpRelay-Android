package com.velum.vpn

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.StrictMode
import com.velum.vpn.core.AppContainer

class VelumApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Crash-hardening: never let a stray policy violation kill us at launch.
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder().permitAll().build()
            )
        }
        container = AppContainer(this)
        createChannels()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_VPN, "Velum VPN",
                NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Active relay tunnel" }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROXY, "Local proxy",
                NotificationManager.IMPORTANCE_LOW)
                .apply { description = "HTTP / SOCKS5 listener" }
        )
    }

    companion object {
        const val CHANNEL_VPN = "mrelay_vpn"
        const val CHANNEL_PROXY = "mrelay_proxy"
    }
}
