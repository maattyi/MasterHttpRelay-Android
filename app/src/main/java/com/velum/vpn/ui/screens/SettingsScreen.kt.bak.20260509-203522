package com.velum.vpn.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.velum.vpn.cert.MitmCa
import com.velum.vpn.core.AppContainer
import com.velum.vpn.core.RelayConfig
import com.velum.vpn.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer) {
    val current by container.config.flow.collectAsState(initial = RelayConfig())
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var working by remember(current) { mutableStateOf(current) }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
            .padding(bottom = 130.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(22.dp))
        Text(
            androidx.compose.ui.res.stringResource(com.velum.vpn.R.string.settings).uppercase(),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 2.sp,
        )
        Text(
            "Configuration",
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.5).sp,
        )

        SettingsSection("APPEARANCE") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Theme", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                val options = listOf("dark", "light", "system")
                options.forEach { opt ->
                    FilterChip(
                        selected = working.themeMode == opt,
                        onClick = { working = working.copy(themeMode = opt) },
                        label = { Text(opt.replaceFirstChar { it.uppercase() }) },
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Language", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                listOf("en" to "English", "fa" to "فارسی").forEach { (code, name) ->
                    FilterChip(
                        selected = working.language == code,
                        onClick = { working = working.copy(language = code) },
                        label = { Text(name) },
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }
        }

        SettingsSection("LISTENER") {
            IntField("HTTP port", working.listenPort) { working = working.copy(listenPort = it) }
            IntField("SOCKS5 port", working.socksPort) { working = working.copy(socksPort = it) }
            SettingsSwitchRow(
                "SOCKS5 listener",
                "Enable SOCKS5 relay interface",
                working.enableSocks5,
            ) { working = working.copy(enableSocks5 = it) }
            SettingsSwitchRow(
                "Allow LAN connections",
                "Enable access from other devices on Wi-Fi",
                working.lanShare,
            ) { working = working.copy(lanShare = it) }
        }

        SettingsSection("ROUTING") {
            SettingsSwitchRow(
                "Per-app proxy mode",
                "Tunnel specific applications",
                working.perAppMode,
            ) { working = working.copy(perAppMode = it) }
            SettingsSwitchRow(
                "YouTube via relay",
                "Force YouTube traffic through relay",
                working.youtubeViaRelay,
            ) { working = working.copy(youtubeViaRelay = it) }
            SettingsSwitchRow(
                "Auto-reconnect",
                "Automatically restore connection",
                working.autoReconnect,
            ) { working = working.copy(autoReconnect = it) }
            SettingsSwitchRow(
                "Battery saver",
                "Optimized resource usage",
                working.batterySaver,
            ) {
                working = working.copy(
                    batterySaver = it,
                    maxRelayConcurrency = if (it) 24 else 64,
                    warmCount = if (it) 8 else 30,
                    poolMinIdle = if (it) 4 else 15,
                )
            }
        }

        SettingsSection("PERFORMANCE") {
            IntField("Max relay concurrency", working.maxRelayConcurrency) {
                working = working.copy(maxRelayConcurrency = it.coerceIn(4, 256))
            }
            IntField("Warm connections", working.warmCount) {
                working = working.copy(warmCount = it.coerceIn(0, 100))
            }
            IntField("Relay timeout (ms)", working.relayTimeoutMs) {
                working = working.copy(relayTimeoutMs = it.coerceIn(5_000, 120_000))
            }
        }

        SettingsSection("CERTIFICATE") {
            Text(
                "To relay HTTPS, you must install the Velum Root CA. After exporting, go to: Settings → Security → Encryption & credentials → Install a certificate → CA certificate.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VelumButton(
                    label = androidx.compose.ui.res.stringResource(com.velum.vpn.R.string.cert_install).uppercase(),
                    onClick = {
                        scope.launch {
                            val intent = container.mitm.exportCertificate()
                            ctx.startActivity(intent)
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
                VelumButton(
                    label = androidx.compose.ui.res.stringResource(com.velum.vpn.R.string.cert_share).uppercase(),
                    onClick = {
                        scope.launch {
                            val intent = container.mitm.shareCertificate()
                            ctx.startActivity(Intent.createChooser(intent, "Save CA Certificate"))
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
            }
            
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(Modifier.height(12.dp))
            
            Text(
                "SSL Diagnostics (4-Stage)",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Runs a full 4-stage diagnostic: storage, synthesis, trust, and wire test. Ensure the proxy is running first.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            
            var diagResult by remember { mutableStateOf<com.velum.vpn.core.DiagnosticManager.Result?>(null) }
            var diagLoading by remember { mutableStateOf(false) }
            
            if (diagResult != null) {
                val r = diagResult!!
                // Show per-stage results
                r.stages.forEach { stage ->
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(if (stage.success) Color(0xFF1B5E20) else Color(0xFFB71C1C))
                            .padding(12.dp)
                    ) {
                        Column {
                            Text("Stage ${stage.stage}: ${stage.name} — ${if (stage.success) "PASS" else "FAIL"}",
                                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(stage.message, color = Color.White.copy(alpha = 0.9f), fontSize = 12.sp)
                            stage.detail?.let { Text(it, color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp) }
                        }
                    }
                }
                // Show user CA caveat
                r.userCaCaveat?.let { caveat ->
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF37474F))
                            .padding(12.dp)
                    ) {
                        Text(caveat, color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp, lineHeight = 15.sp)
                    }
                }
            }
            
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            diagLoading = true
                            diagResult = com.velum.vpn.core.DiagnosticManager(ctx).runTest(working.listenPort, container.mitm)
                            diagLoading = false
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !diagLoading,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    if (diagLoading) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    else Text("RUN SSL TEST", fontWeight = FontWeight.Bold)
                }
                
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val intent = container.mitm.dumpLastLeaf()
                            if (intent != null) ctx.startActivity(Intent.createChooser(intent, "Share Leaf Cert"))
                        }
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("DUMP LEAF", fontWeight = FontWeight.Bold)
                }
            }
        }

        SettingsSection("ABOUT") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://t.me/Mattymatins")))
                }.padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(
                    Icons.Outlined.Send,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Telegram Channel", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("@Mattymatins", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        VelumButton(
            label = "SAVE CHANGES",
            onClick = { scope.launch { container.config.save(working) } },
            modifier = Modifier.fillMaxWidth(),
            big = true,
        )
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            title,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 11.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.sp,
        )
        content()
    }
}

@Composable
private fun IntField(label: String, value: Int, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            val filtered = it.filter { c -> c.isDigit() }
            text = filtered
            filtered.toIntOrNull()?.let(onChange)
        },
        label = {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
            focusedBorderColor = MaterialTheme.colorScheme.primary,
        ),
        shape = RoundedCornerShape(12.dp),
    )
}

@Composable
private fun SettingsSwitchRow(
    label: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp,
                 fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}

@Composable
private fun VelumButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    big: Boolean = false,
) {
    val shape = RoundedCornerShape(if (big) 16.dp else 12.dp)
    Button(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = if (big) 56.dp else 48.dp)
            .clip(shape),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        shape = shape,
    ) {
        Text(
            label,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.sp,
            fontSize = if (big) 15.sp else 14.sp,
        )
    }
}
