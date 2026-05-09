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
import com.velum.vpn.ui.theme.PitchBlack

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
    var vpnRunning by remember { mutableStateOf(false) }
    var proxyRunning by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        when (current) {
            "dash" -> DashboardScreen(
                container = container,
                vpnRunning = vpnRunning,
                proxyRunning = proxyRunning,
                onStartVpn = { vpnRunning = true; onStartVpn() },
                onStopVpn  = { vpnRunning = false; onStopVpn() },
                onStartProxy = { proxyRunning = true; onStartProxy() },
                onStopProxy  = { proxyRunning = false; onStopProxy() },
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
