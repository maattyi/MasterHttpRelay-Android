package com.velum.vpn.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.velum.vpn.core.DiagnosticManager
import com.velum.vpn.core.RelayConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer) {
    val current by container.config.flow.collectAsState(initial = RelayConfig())
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var working by remember(current) { mutableStateOf(current) }
    var lastSave by remember { mutableStateOf<MitmCa.SaveResult?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
            .padding(bottom = 130.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(22.dp))
        Text(
            "SETTINGS",
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
                listOf("dark", "light", "system").forEach { opt ->
                    FilterChip(
                        selected = working.themeMode == opt,
                        onClick = { working = working.copy(themeMode = opt) },
                        label = { Text(opt.replaceFirstChar { ch -> ch.uppercase() }) },
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Language", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                listOf("en" to "English", "fa" to "\u0641\u0627\u0631\u0633\u06cc").forEach { (code, name) ->
                    FilterChip(
                        selected = working.language == code,
                        onClick = { working = working.copy(language = code) },
                        label = { Text(name) },
                        modifier = Modifier.padding(horizontal = 4.dp),
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
                "Save the Velum Root CA to your Downloads folder, then install it from " +
                    "Settings -> Security -> Encryption & credentials -> Install a certificate -> CA certificate.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )

            // Result panel — green on save, red on failure.
            val saved = lastSave
            if (saved != null) {
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (saved.success) Color(0xFF1B5E20) else Color(0xFFB71C1C),
                        )
                        .padding(12.dp),
                ) {
                    Column {
                        Text(
                            saved.message,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                        )
                        val detail = saved.detail
                        if (!detail.isNullOrBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                detail,
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VelumButton(
                    label = "SAVE TO DOWNLOADS",
                    onClick = {
                        scope.launch {
                            // Save off the main thread; MediaStore I/O can stutter UI.
                            val result = withContext(Dispatchers.IO) {
                                container.mitm.saveCertificateToDownloads()
                            }
                            lastSave = result
                            Toast.makeText(
                                ctx,
                                if (result.success) result.message else "Save failed",
                                Toast.LENGTH_LONG,
                            ).show()

                            // On success, also fire the system Install Certificate UI.
                            if (result.success) {
                                runCatching {
                                    val intent = container.mitm.exportCertificate()
                                    ctx.startActivity(intent)
                                }
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                VelumButton(
                    label = "SHARE",
                    onClick = {
                        scope.launch {
                            runCatching {
                                val intent = container.mitm.shareCertificate()
                                ctx.startActivity(Intent.createChooser(intent, "Save CA Certificate"))
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(Modifier.height(12.dp))

            DiagnosticsCard(
                proxyPort = working.listenPort,
                onDumpLeaf = {
                    scope.launch {
                        runCatching {
                            val intent = container.mitm.dumpLastLeaf()
                            if (intent != null) {
                                ctx.startActivity(Intent.createChooser(intent, "Share Leaf Cert"))
                            }
                        }
                    }
                },
            )
        }

        SettingsSection("ABOUT") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        runCatching {
                            ctx.startActivity(
                                Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://t.me/Mattymatins")),
                            )
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Icon(
                    Icons.Outlined.Send,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        "Telegram Channel",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "@Mattymatins",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        VelumButton(
            label = "SAVE CHANGES",
            onClick = { scope.launch { runCatching { container.config.save(working) } } },
            modifier = Modifier.fillMaxWidth(),
            big = true,
        )
    }
}

@Composable
private fun DiagnosticsCard(
    proxyPort: Int,
    onDumpLeaf: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var result by remember { mutableStateOf<DiagnosticManager.Result?>(null) }
    var loading by remember { mutableStateOf(false) }

    Text(
        "SSL Diagnostics",
        color = MaterialTheme.colorScheme.onSurface,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
    )
    Text(
        "Probes the local proxy with a real HTTPS request. Make sure the proxy/VPN is running before pressing RUN.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp,
    )

    val r = result
    if (r != null) {
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (r.success) Color(0xFF1B5E20) else Color(0xFFB71C1C))
                .padding(12.dp),
        ) {
            Column {
                Text(
                    if (r.success) "PASS - ${r.message}" else "FAIL - ${r.message}",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
                val detail = r.detail
                if (!detail.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        detail,
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = {
                scope.launch {
                    loading = true
                    result = try {
                        DiagnosticManager(ctx).runTest(proxyPort)
                    } catch (e: Throwable) {
                        DiagnosticManager.Result(
                            success = false,
                            message = "Diagnostic crashed",
                            detail = e.message ?: e.toString(),
                        )
                    }
                    loading = false
                }
            },
            modifier = Modifier.weight(1f),
            enabled = !loading,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary,
            ),
        ) {
            if (loading) {
                CircularProgressIndicator(
                    Modifier.size(18.dp),
                    color = Color.White,
                    strokeWidth = 2.dp,
                )
            } else {
                Text("RUN SSL TEST", fontWeight = FontWeight.Bold)
            }
        }

        OutlinedButton(
            onClick = onDumpLeaf,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text("DUMP LEAF", fontWeight = FontWeight.Bold)
        }
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
        onValueChange = { newValue ->
            val filtered = newValue.filter { c -> c.isDigit() }
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
            Text(
                label,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
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
