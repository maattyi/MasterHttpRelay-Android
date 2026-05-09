package com.velum.vpn.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cable
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.SettingsInputComponent
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.velum.vpn.core.AppContainer
import com.velum.vpn.core.ConnectionState
import com.velum.vpn.ui.components.BezierBandwidthChart
import com.velum.vpn.ui.components.GlassSurface
import com.velum.vpn.ui.components.PulsingConnectButton
import com.velum.vpn.ui.components.StatCard
import com.velum.vpn.ui.components.StatCardKind
import com.velum.vpn.ui.theme.WarmAmber
import kotlinx.coroutines.delay

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

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            container.stats.snapshot()
        }
    }

    val vpnActive = vpnState == ConnectionState.Connected
    val proxyActive = proxyState == ConnectionState.Connected
    val vpnConnecting = vpnState == ConnectionState.Connecting
    val anyActive = vpnActive || proxyActive

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
            .padding(bottom = 120.dp),
    ) {
        Spacer(Modifier.height(22.dp))
        TopBar(active = anyActive)

        Spacer(Modifier.height(20.dp))
        PulsingConnectButton(
            connected = vpnActive,
            connecting = vpnConnecting,
            onToggle = { if (vpnActive) onStopVpn() else onStartVpn() },
        )

        if (vpnActive) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "System Proxy Mode  -  only proxy-aware apps are routed",
                color = WarmAmber,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
            )
        }

        Spacer(Modifier.height(12.dp))
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

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            StatCard(
                label = "DOWNLOAD",
                value = humanBytesShort(snap.bytesDown),
                unit = humanBytesUnit(snap.bytesDown),
                icon = Icons.Outlined.Download,
                kind = if (anyActive) StatCardKind.NEON else StatCardKind.MATTE,
                modifier = Modifier.weight(1f).height(110.dp),
            )
            StatCard(
                label = "UPLOAD",
                value = humanBytesShort(snap.bytesUp),
                unit = humanBytesUnit(snap.bytesUp),
                icon = Icons.Outlined.Upload,
                kind = StatCardKind.MATTE,
                modifier = Modifier.weight(1f).height(110.dp),
            )
        }

        Spacer(Modifier.height(12.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            StatCard(
                label = "RELAY",
                value = "%.0f".format(snap.relayLatencyMs),
                unit = "ms",
                icon = Icons.Outlined.Speed,
                kind = StatCardKind.MATTE,
                modifier = Modifier.weight(1f).height(110.dp),
            )
            StatCard(
                label = "CONNECTIONS",
                value = "${snap.connections}",
                unit = "active",
                icon = Icons.Outlined.Cable,
                kind = StatCardKind.MATTE,
                modifier = Modifier.weight(1f).height(110.dp),
            )
        }

        Spacer(Modifier.height(24.dp))
        BandwidthCard(snap.seriesDown)

        Spacer(Modifier.height(16.dp))
        EndpointStrip(container, anyActive)
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
                "VELUM",
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black,
                letterSpacing = (-0.5).sp,
            )
            Text(
                "Proxy Tunnel  -  v1.0",
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
    val bg = if (active) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (active) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier
            .background(bg, RoundedCornerShape(24.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(6.dp).background(
                if (active) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.primary,
                CircleShape,
            ),
        )
        Text(
            if (active) "CONNECTED" else "OFFLINE",
            color = fg,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun LocalProxyRow(
    running: Boolean,
    connecting: Boolean,
    port: Int,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 24.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.SettingsInputComponent,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Local Proxy",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    when {
                        connecting -> "Starting..."
                        running -> "Listening on 127.0.0.1:$port"
                        else -> "HTTP / SOCKS5 listener"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
            TextButton(
                onClick = if (running) onStop else onStart,
                colors = ButtonDefaults.textButtonColors(
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
                    letterSpacing = 1.sp,
                )
            }
        }
    }
}

@Composable
private fun BandwidthCard(series: List<Pair<Long, Double>>) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 24.dp,
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
                    letterSpacing = 1.5.sp,
                )
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .size(8.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "LIVE",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.5.sp,
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
    GlassSurface(
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
                    letterSpacing = 1.5.sp,
                )
                Text(
                    if (mid.endpoints == 0) "Disconnected"
                    else mid.activeEndpoint.ifEmpty { "Automatic" },
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${mid.endpointsHealthy}/${mid.endpoints.coerceAtLeast(1)} UP",
                    color = if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.8.sp,
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
