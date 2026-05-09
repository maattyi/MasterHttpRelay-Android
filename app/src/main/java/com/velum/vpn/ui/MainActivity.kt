package com.velum.vpn.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.velum.vpn.VelumApp
import com.velum.vpn.core.LogBus
import com.velum.vpn.proxy.LocalProxyService
import com.velum.vpn.ui.theme.VelumTheme
import com.velum.vpn.vpn.RelayVpnService
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val vpnConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService()
        }
    }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Handled by system */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container = (application as VelumApp).container

        // Pre-apply locale from snapshot if available
        applyLocale(container.config.snapshot().language)

        ensureNotificationPermission()

        setContent {
            val config by container.config.flow.collectAsState(initial = container.config.snapshot())
            
            // Re-apply locale when config changes
            LaunchedEffect(config.language) {
                val currentLang = Locale.getDefault().language
                if (currentLang != config.language) {
                    applyLocale(config.language)
                    recreate()
                }
            }

            VelumTheme(darkTheme = when(config.themeMode) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppRoot(
                        container = container,
                        onStartVpn = ::requestVpnPermission,
                        onStopVpn = ::stopVpn,
                        onStartProxy = ::startProxy,
                        onStopProxy = ::stopProxy,
                    )
                }
            }
        }
    }

    private fun applyLocale(lang: String) {
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val config = resources.configuration
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        @Suppress("DEPRECATION")
        resources.updateConfiguration(config, resources.displayMetrics)
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
            != PackageManager.PERMISSION_GRANTED) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) vpnConsentLauncher.launch(intent)
        else startVpnService()
    }

    private fun startVpnService() {
        ContextCompat.startForegroundService(
            this, Intent(this, RelayVpnService::class.java)
        )
    }

    private fun stopVpn() {
        startService(Intent(this, RelayVpnService::class.java).apply {
            action = RelayVpnService.ACTION_STOP
        })
    }

    private fun startProxy() {
        ContextCompat.startForegroundService(this, Intent(this, LocalProxyService::class.java))
    }

    private fun stopProxy() {
        startService(Intent(this, LocalProxyService::class.java).apply {
            action = LocalProxyService.ACTION_STOP
        })
    }
}
