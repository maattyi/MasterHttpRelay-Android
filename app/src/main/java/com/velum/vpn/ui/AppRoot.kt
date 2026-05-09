package com.velum.vpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.velum.vpn.core.AppContainer
import com.velum.vpn.ui.components.FloatingPillNav
import com.velum.vpn.ui.components.PillNavItem
import com.velum.vpn.ui.screens.DashboardScreen
import com.velum.vpn.ui.screens.LogsScreen
import com.velum.vpn.ui.screens.ProfilesScreen
import com.velum.vpn.ui.screens.SettingsScreen

/**
 * Fix #14: AppRoot no longer maintains local vpnRunning/proxyRunning state.
 * The DashboardScreen now reads ConnectionState flows directly from AppContainer,
 * which are updated by the actual services. This means the UI reflects reality
 * even if VpnService.prepare is dismissed or the service crashes.
 */
@Composable
fun AppRoot(
    container: AppContainer,
    onStartVpn: () -> Unit,
    onStopVpn: () -> Unit,
    onStartProxy: () -> Unit,
    onStopProxy: () -> Unit,
) {
    val navItems = listOf(
        PillNavItem("dash", androidx.compose.ui.res.stringResource(com.velum.vpn.R.string.dashboard), Icons.Outlined.Home),
        PillNavItem("prof", androidx.compose.ui.res.stringResource(com.velum.vpn.R.string.profiles), Icons.Outlined.Layers),
        PillNavItem("set",  androidx.compose.ui.res.stringResource(com.velum.vpn.R.string.settings),  Icons.Outlined.Settings),
        PillNavItem("logs", androidx.compose.ui.res.stringResource(com.velum.vpn.R.string.logs),      Icons.Outlined.Description),
    )
    var current by remember { mutableStateOf("dash") }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        when (current) {
            "dash" -> DashboardScreen(
                container = container,
                onStartVpn = onStartVpn,
                onStopVpn = onStopVpn,
                onStartProxy = onStartProxy,
                onStopProxy = onStopProxy,
            )
            "prof" -> ProfilesScreen(container.profiles)
            "set"  -> SettingsScreen(container)
            "logs" -> LogsScreen()
        }

        // Floating pill nav anchored to bottom of the parent Box
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        ) {
            FloatingPillNav(
                items = navItems,
                selected = current,
                onSelect = { current = it },
            )
        }
    }
}
