package com.velum.vpn.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.velum.vpn.core.AppContainer
import com.velum.vpn.core.ConnectionState
import com.velum.vpn.ui.components.BezierBandwidthChart
import com.velum.vpn.ui.components.PulsingConnectButton
import com.velum.vpn.ui.components.StatCard
import com.velum.vpn.ui.components.StatCardKind
import com.velum.vpn.ui.theme.*
import kotlinx.coroutines.delay

/**
 * Fix #14: Dashboard now binds to real service connection states from
 * AppContainer instead of local UI state. If VpnService.prepare is
 * dismissed or the service crashes, the UI reflects the real state.
 * A "connecting" state is shown between idle and connected.
 */
@Composable
fun DashboardScreen(
    container: AppContainer,
    onStartVpn: () -> Unit,
    onStopVpn: () -> Unit,
    onStartProxy: () -> Unit,
    onStopProxy: () -> Unit,
) {
    val snap by container.stats.flow.collectAsState()
    val cfg by container.config.flow.collectAsState(initial = container.config.snapshot())
    val vpnState by container.vpnState.collectAsState()
    val proxyState by container.proxyState.collectAsState()
    LaunchedEffect(Unit) { while (true) { delay(1000); container.stats.snapshot() } }

    val vpnActive = vpnState == ConnectionState.Connected
    val proxyActive = proxyState == ConnectionState.Connected
    val vpnConnecting = vpnState == ConnectionState.Connecting
    val active = vpnActive || proxyActive
    val bgColor = MaterialTheme.colorScheme.background

    Column(
        Modifier.fillMaxSize().background(bgColor)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
            .padding(bottom = 120.dp),
    ) {
        Spacer(Modifier.height(22.dp))
        TopBar(active = active)

        Spacer(Modifier.height(20.dp))
        PulsingConnectButton(
            connected = vpnActive,
            connecting = vpnConnecting,
            onToggle = { if (vpnActive) onStopVpn() else onStartVpn() },
        )

        // Fix #8: Show system-proxy mode warning when VPN is active
        if (vpnActive) {
            Spacer(Modifier.height(4.dp))
            Text(
                "System Proxy Mode — only proxy-aware apps are covered",
                color = WarmAmber,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            )
        }

        Spacer(Modifier.height(8.dp))
        AnimatedVisibility(
            visible = !vpnActive,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            LocalProxyRow(
                running = proxyActive,
                connecting = proxyState == ConnectionState.Connecting,
                port = cfg.listenPort,
                onStart = onStartProxy,
                onStop = onStopProxy,
            )
        }

        Spacer(Modifier.height(24.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()) {
            StatCard(
                label = androidx.compose.ui.res.stringResource(com.velum.vpn.R.string.download),
                value = humanBytesShort(snap.bytesDown),
                unit = humanBytesUnit(snap.bytesDown),
                icon = Icons.Outlined.Download,
                kind = if (active) StatCardKind.NEON else StatCardKind.MATTE,
                modifier = Modifier.weight(1f).height(110.dp),
            )
            StatCard(
                label = androidx.compose.ui.res.stringResource(com.velum.vpn.R.string.upload),
                value = humanBytesShort(snap.bytesUp),
                unit = humanBytesUnit(snap.bytesUp),
                icon = Icons.Outlined.Upload,
                kind = StatCardKind.MATTE,
                modifier = Modifier.weight(1f).height(110.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()) {
            StatCard(
                label = androidx.compose.ui.res.stringResource(com.velum.vpn.R.string.relay_latency),
                value = "%.0f".format(snap.relayLatencyMs),
                unit = "ms",
                icon = Icons.Outlined.Speed,
                kind = StatCardKind.MATTE,
                modifier = Modifier.weight(1f).height(110.dp),
            )
            StatCard(
                label = androidx.compose.ui.res.stringResource(com.velum.vpn.R.string.active_connections),
                value = "${snap.connections}",
                unit = "conns",
                icon = Icons.Outlined.Cable,
                kind = StatCardKind.MATTE,
                modifier = Modifier.weight(1f).height(110.dp),
            )
        }

        Spacer(Modifier.height(24.dp))
        BandwidthCard(snap.seriesDown)

        Spacer(Modifier.height(16.dp))
        EndpointStrip(container, active)
    }
}

@Composable
private fun TopBar(active: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Text(
                androidx.compose.ui.res.stringResource(com.velum.vpn.R.string.app_name),
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black,
                letterSpacing = (-0.5).sp,
            )
            Text(
                androidx.compose.ui.res.stringResource(com.velum.vpn.R.string.app_subtitle),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.weight(1f))
        StatusPill(active = active)
    }
}

@Composable
private fun StatusPill(active: Boolean) {
    val targetBg = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val targetFg = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .background(targetBg, RoundedCornerShape(24.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(6.dp).background(
                if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                CircleShape,
            )
        )
        Text(
            if (active) androidx.compose.ui.res.stringResource(com.velum.vpn.R.string.status_connected)
            else androidx.compose.ui.res.stringResource(com.velum.vpn.R.string.status_offline),
            color = targetFg,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun LocalProxyRow(running: Boolean, connecting: Boolean, port: Int, onStart: () -> Unit, onStop: () -> Unit) {
    com.velum.vpn.ui.components.GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 24.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.Icon(
                Icons.Outlined.SettingsInputComponent,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Local Proxy", color = MaterialTheme.colorScheme.onSurface,
                     fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(when {
                        connecting -> "Connecting..."
                        running -> "Listening on 127.0.0.1:$port"
                        else -> "HTTP/SOCKS5 listener"
                     },
                     color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            androidx.compose.material3.TextButton(
                onClick = if (running) onStop else onStart,
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Text(
                    when {
                        connecting -> "..."
                        running -> "STOP"
                        else -> "START"
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                )
            }
        }
    }
}

@Composable
private fun BandwidthCard(series: List<Pair<Long, Double>>) {
    com.velum.vpn.ui.components.GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 24.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "TRAFFIC",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.sp,
                )
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(
                    "LIVE",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                )
            }
            Spacer(Modifier.height(16.dp))
            BezierBandwidthChart(
                series = series,
                modifier = Modifier.fillMaxWidth().height(150.dp),
            )
        }
    }
}

@Composable
private fun EndpointStrip(container: AppContainer, active: Boolean) {
    val mid = container.multiId.snapshot()
    com.velum.vpn.ui.components.GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 20.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "RELAY",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.sp,
                )
                Text(
                    if (mid.endpoints == 0) "Disconnected" else mid.activeEndpoint.ifEmpty { "Automatic" },
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${mid.endpointsHealthy}/${mid.endpoints.coerceAtLeast(1)} UP",
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                )
                Text(
                    "%.0f ms".format(mid.latencyMs),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

private fun humanBytesShort(n: Long): String = when {
    n < 1024 -> "$n"
    n < 1024L * 1024 -> "%.1f".format(n / 1024.0)
    n < 1024L * 1024 * 1024 -> "%.1f".format(n / 1024.0 / 1024.0)
    else -> "%.1f".format(n / 1024.0 / 1024.0 / 1024.0)
}

private fun humanBytesUnit(n: Long): String = when {
    n < 1024 -> "B"
    n < 1024L * 1024 -> "KB"
    n < 1024L * 1024 * 1024 -> "MB"
    else -> "GB"
}
