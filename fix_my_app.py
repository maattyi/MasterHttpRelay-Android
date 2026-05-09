#!/usr/bin/env python3
"""
fix_my_app.py — bulletproof rewriter + zipper for MasterHttpRelay / VelumVPN.

Usage:
    python3 fix_my_app.py [--project-dir /path/to/project]

What it does:
  1. Locates the broken Kotlin files anywhere under <project-dir>/app/ .
  2. Backs up each target file to <file>.bak.<timestamp>.
  3. Overwrites them with the corrected, compiling code.
  4. Verifies every target was written successfully.
  5. Zips the entire app/ directory into MasterHttpRelay_Bulletproof.zip
     in <project-dir>, ready to upload to GitHub.

Safe to re-run; existing .bak files are kept (timestamped).
"""

from __future__ import annotations

import argparse
import os
import sys
import time
import zipfile
from pathlib import Path

# ─────────────────────────────────────────────────────────────────────────────
# File contents — keys are the basename we look for; values are full source.
# ─────────────────────────────────────────────────────────────────────────────

FILES: dict[str, str] = {}

FILES["DiagnosticManager.kt"] = r'''package com.velum.vpn.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * Lightweight, single-shot diagnostic.
 *
 * STRICT SIGNATURE:
 *   suspend fun runTest(proxyPort: Int): Result
 *   data class Result(val success: Boolean, val message: String, val detail: String? = null)
 */
class DiagnosticManager(private val context: Context) {

    data class Result(
        val success: Boolean,
        val message: String,
        val detail: String? = null,
    )

    suspend fun runTest(proxyPort: Int): Result = withContext(Dispatchers.IO) {
        try {
            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxyPort))
            val client = OkHttpClient.Builder()
                .proxy(proxy)
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .callTimeout(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build()

            val request = Request.Builder()
                .url("https://www.google.com/generate_204")
                .header("User-Agent", "VelumVPN-Diagnostic/1.0")
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val code = response.code
                    if (code == 204 || response.isSuccessful) {
                        Result(
                            success = true,
                            message = "Proxy is working",
                            detail = "Connected via 127.0.0.1:$proxyPort and received HTTP $code from Google. " +
                                "TLS chain accepted, relay reachable, MITM operational."
                        )
                    } else {
                        Result(
                            success = false,
                            message = "Proxy reachable but upstream returned $code",
                            detail = "TLS completed, but generate_204 returned $code instead of 204. " +
                                "Likely a relay-side configuration issue."
                        )
                    }
                }
            } catch (e: Throwable) {
                val msg = (e.message ?: e.toString())
                val detail = when {
                    msg.contains("Trust anchor", true) ->
                        "CA not trusted. Install the Velum Root CA via Settings -> Security -> " +
                        "Encryption & credentials -> Install certificate -> CA certificate. " +
                        "Note: Android 7+ apps targeting SDK >= 24 may still reject user CAs " +
                        "unless networkSecurityConfig opts in."
                    msg.contains("CertPathValidator", true) ->
                        "Certificate path validation failed. The leaf cert may be malformed or expired."
                    msg.contains("Connection refused", true) ->
                        "Nothing listening on 127.0.0.1:$proxyPort. Start the proxy/VPN first."
                    msg.contains("SSLHandshake", true) || msg.contains("alert", true) ->
                        "TLS handshake failed. Most commonly: CA not installed, or app pins certs."
                    msg.contains("timeout", true) || msg.contains("timed out", true) ->
                        "Network timeout. Relay may be unreachable or overloaded."
                    else -> "Error: ${msg.take(400)}"
                }
                Result(success = false, message = "Diagnostic failed", detail = detail)
            }
        } catch (e: Throwable) {
            Result(
                success = false,
                message = "Diagnostic crashed",
                detail = "Unexpected error: ${(e.message ?: e.toString()).take(400)}"
            )
        }
    }
}
'''

FILES["SettingsScreen.kt"] = r'''package com.velum.vpn.ui.screens

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
import com.velum.vpn.core.AppContainer
import com.velum.vpn.core.DiagnosticManager
import com.velum.vpn.core.RelayConfig
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
                        label = { Text(opt.replaceFirstChar { ch -> ch.uppercase() }) },
                        modifier = Modifier.padding(horizontal = 4.dp)
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
                "To relay HTTPS, install the Velum Root CA. After exporting, go to: Settings -> " +
                    "Security -> Encryption & credentials -> Install a certificate -> CA certificate.",
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
                            runCatching {
                                val intent = container.mitm.exportCertificate()
                                ctx.startActivity(intent)
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
                VelumButton(
                    label = androidx.compose.ui.res.stringResource(com.velum.vpn.R.string.cert_share).uppercase(),
                    onClick = {
                        scope.launch {
                            runCatching {
                                val intent = container.mitm.shareCertificate()
                                ctx.startActivity(Intent.createChooser(intent, "Save CA Certificate"))
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
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
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                    runCatching {
                        ctx.startActivity(
                            Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://t.me/Mattymatins"))
                        )
                    }
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
                    Text(
                        "Telegram Channel",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "@Mattymatins",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
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
        fontWeight = FontWeight.Bold
    )
    Text(
        "Probes the local proxy with a real HTTPS request. Make sure the proxy/VPN is running before pressing RUN.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp
    )

    val r = result
    if (r != null) {
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (r.success) Color(0xFF1B5E20) else Color(0xFFB71C1C))
                .padding(12.dp)
        ) {
            Column {
                Text(
                    if (r.success) "PASS - ${r.message}" else "FAIL - ${r.message}",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                val detail = r.detail
                if (!detail.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        detail,
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 12.sp,
                        lineHeight = 16.sp
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
                            detail = e.message ?: e.toString()
                        )
                    }
                    loading = false
                }
            },
            modifier = Modifier.weight(1f),
            enabled = !loading,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary
            )
        ) {
            if (loading) {
                CircularProgressIndicator(
                    Modifier.size(18.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
            } else {
                Text("RUN SSL TEST", fontWeight = FontWeight.Bold)
            }
        }

        OutlinedButton(
            onClick = onDumpLeaf,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp)
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
                fontWeight = FontWeight.SemiBold
            )
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
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
'''

FILES["MitmCa.kt"] = r'''package com.velum.vpn.cert

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.velum.vpn.core.LogBus
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.*
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.*
import java.security.cert.X509Certificate
import java.util.*
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

class MitmCa(
    private val context: Context,
    private val fileProviderAuthority: String = "com.velum.vpn.provider",
    private val caCommonName: String = "VelumVPN Root CA",
    private val caDirOverride: File? = null,
) {

    companion object {
        private val DEFAULT_PASSWORD = CharArray(0)
    }

    private val bcProvider: BouncyCastleProvider = BouncyCastleProvider()

    private val caDir: File = try {
        (caDirOverride ?: File(context.filesDir, "ca")).apply { mkdirs() }
    } catch (e: Throwable) {
        LogBus.e("Cert", "Cannot create CA dir, falling back to cacheDir", e)
        File(context.cacheDir, "ca").apply { runCatching { mkdirs() } }
    }
    private val caCertFile = File(caDir, "ca.crt")
    private val caKeyFile = File(caDir, "ca.key")

    private val cache = object : LinkedHashMap<String, SSLContext>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SSLContext>?): Boolean {
            return size > 256
        }
    }

    private val leafKeyPair: KeyPair by lazy {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        kpg.generateKeyPair()
    }

    private val caPair: Pair<X509Certificate, PrivateKey> = loadOrCreate()
    private val caCert: X509Certificate get() = caPair.first
    private val caKey: PrivateKey get() = caPair.second

    @Volatile private var lastLeaf: X509Certificate? = null

    fun exportCertificate(): Intent {
        return try {
            LogBus.i("Cert", "Exporting root CA certificate for installation")
            val certFile = File(context.cacheDir, "velum_ca.crt").apply {
                writeBytes(caCert.encoded)
            }
            val uri = FileProvider.getUriForFile(context, fileProviderAuthority, certFile)
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/x-x509-ca-cert")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } catch (e: Throwable) {
            LogBus.e("Cert", "Failed to create cert export intent", e)
            try {
                android.security.KeyChain.createInstallIntent().apply {
                    putExtra(android.security.KeyChain.EXTRA_CERTIFICATE, caCert.encoded)
                    putExtra(android.security.KeyChain.EXTRA_NAME, caCommonName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } catch (e2: Throwable) {
                LogBus.e("Cert", "Fallback cert export also failed", e2)
                Intent()
            }
        }
    }

    fun shareCertificate(): Intent {
        return try {
            LogBus.i("Cert", "Sharing root CA certificate file")
            val certFile = File(context.cacheDir, "velum_ca.crt").apply {
                writeBytes(caCert.encoded)
            }
            val uri = FileProvider.getUriForFile(context, fileProviderAuthority, certFile)
            Intent(Intent.ACTION_SEND).apply {
                type = "application/x-x509-ca-cert"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } catch (e: Throwable) {
            LogBus.e("Cert", "Failed to share certificate", e)
            Intent()
        }
    }

    @Synchronized
    fun contextFor(host: String): SSLContext {
        return try {
            cache.getOrPut(host) { sign(host) }
        } catch (e: Throwable) {
            LogBus.e("Cert", "contextFor($host) failed, retrying with fallback host", e)
            cache.getOrPut("fallback") { sign("fallback.local") }
        }
    }

    fun dumpLastLeaf(): Intent? {
        val leaf = lastLeaf ?: return null
        return try {
            val file = File(context.cacheDir, "last_leaf.crt").apply {
                writeText(
                    "-----BEGIN CERTIFICATE-----\n" +
                        android.util.Base64.encodeToString(leaf.encoded, android.util.Base64.DEFAULT) +
                        "-----END CERTIFICATE-----\n"
                )
            }
            val uri = FileProvider.getUriForFile(context, fileProviderAuthority, file)
            Intent(Intent.ACTION_SEND).apply {
                type = "application/x-x509-ca-cert"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } catch (e: Throwable) {
            LogBus.e("Cert", "Failed to dump leaf", e)
            null
        }
    }

    private fun loadOrCreate(): Pair<X509Certificate, PrivateKey> {
        if (caCertFile.exists() && caKeyFile.exists()) {
            try {
                val ks = KeyStore.getInstance("PKCS12", bcProvider)
                ks.load(caCertFile.inputStream(), DEFAULT_PASSWORD)
                val key = ks.getKey("ca", DEFAULT_PASSWORD) as? PrivateKey
                val cert = ks.getCertificate("ca") as? X509Certificate
                if (key != null && cert != null) {
                    return cert to key
                }
            } catch (e: Throwable) {
                LogBus.w("Cert", "Failed to load existing CA, regenerating: ${e.message}")
                runCatching { caCertFile.delete() }
                runCatching { caKeyFile.delete() }
            }
        }

        val kpg = KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }
        val kp = kpg.generateKeyPair()
        val name = X500Name("CN=$caCommonName, O=VelumVPN")
        val notBefore = Date(System.currentTimeMillis() - 24 * 3600 * 1000L)
        val notAfter = Date(System.currentTimeMillis() + 3650L * 24 * 3600 * 1000L)
        val serial = BigInteger(159, SecureRandom()).abs()

        val builder = JcaX509v3CertificateBuilder(
            name, serial, notBefore, notAfter, name, kp.public,
        )
            .addExtension(Extension.basicConstraints, true, BasicConstraints(true))
            .addExtension(
                Extension.keyUsage, true,
                KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyCertSign or KeyUsage.cRLSign)
            )
            .addExtension(
                Extension.subjectKeyIdentifier, false,
                org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils()
                    .createSubjectKeyIdentifier(kp.public)
            )

        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider(bcProvider)
            .build(kp.private)
        val cert = JcaX509CertificateConverter()
            .setProvider(bcProvider)
            .getCertificate(builder.build(signer))

        val ks = KeyStore.getInstance("PKCS12", bcProvider).apply { load(null, null) }
        ks.setKeyEntry("ca", kp.private, DEFAULT_PASSWORD, arrayOf(cert))
        caCertFile.outputStream().use { ks.store(it, DEFAULT_PASSWORD) }
        runCatching { caKeyFile.writeText("STORED_IN_PKCS12") }
        return cert to kp.private
    }

    private fun sign(host: String): SSLContext {
        val kp = leafKeyPair
        val safeHost = host.ifBlank { "localhost" }.take(253)

        val subject = X500Name("CN=${safeHost.take(64)}")
        val notBefore = Date(System.currentTimeMillis() - 24 * 3600 * 1000L)
        val notAfter = Date(System.currentTimeMillis() + 375L * 24 * 3600 * 1000L)

        val isIp = safeHost.matches(Regex("^[0-9.]+$")) || safeHost.contains(':')
        val san = if (isIp) GeneralName(GeneralName.iPAddress, safeHost)
        else GeneralName(GeneralName.dNSName, safeHost)

        val utils = org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils()

        val caSkiBytes: ByteArray? = try {
            caCert.getExtensionValue(Extension.subjectKeyIdentifier.id)?.let {
                org.bouncycastle.asn1.ASN1OctetString.getInstance(it).octets
            }
        } catch (_: Throwable) { null }

        val serial = BigInteger(159, SecureRandom()).abs()
        val builder = JcaX509v3CertificateBuilder(
            X500Name.getInstance(caCert.subjectX500Principal.encoded),
            serial, notBefore, notAfter, subject, kp.public,
        )
            .addExtension(Extension.basicConstraints, true, BasicConstraints(false))
            .addExtension(Extension.subjectAlternativeName, true, GeneralNames(san))
            .addExtension(
                Extension.extendedKeyUsage, false,
                ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth)
            )
            .addExtension(
                Extension.keyUsage, true,
                KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment)
            )
            .addExtension(
                Extension.subjectKeyIdentifier, false,
                utils.createSubjectKeyIdentifier(kp.public)
            )

        if (caSkiBytes != null) {
            builder.addExtension(
                Extension.authorityKeyIdentifier, false,
                org.bouncycastle.asn1.x509.AuthorityKeyIdentifier(caSkiBytes)
            )
        }

        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider(bcProvider)
            .build(caKey)
        val leafCert = JcaX509CertificateConverter()
            .setProvider(bcProvider)
            .getCertificate(builder.build(signer))

        lastLeaf = leafCert

        val ks = KeyStore.getInstance("PKCS12", bcProvider).apply { load(null, null) }
        ks.setKeyEntry("leaf", kp.private, DEFAULT_PASSWORD, arrayOf(leafCert, caCert))
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, DEFAULT_PASSWORD)

        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, null, SecureRandom())
        return ctx
    }
}
'''

FILES["RelayClient.kt"] = r'''package com.velum.vpn.net

import android.util.Base64
import com.velum.vpn.core.LogBus
import com.velum.vpn.core.MultiIdDispatcher
import com.velum.vpn.core.RelayConfig
import com.velum.vpn.core.RuntimeRelayConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate
import kotlin.math.min

class RelayClient(
    private val cfg: RelayConfig,
    private val rcfg: RuntimeRelayConfig,
    private val multiId: MultiIdDispatcher,
    private val onLatency: ((gas: Double, cf: Double, relay: Double) -> Unit)? = null,
) {
    private val gasHost = "script.google.com"
    private val frontDomain = rcfg.frontDomain
    private val googleIp = rcfg.googleIp
    private val authKey = rcfg.authKey

    private val concurrencySem = Semaphore(cfg.maxRelayConcurrency)

    private val batchLock = Mutex()
    private val batchPending = mutableListOf<PendingRequest>()
    private var batchTimerJob: Job? = null
    @Volatile private var batchEnabled = true
    private var batchDisabledAt = 0L
    private val batchReEnableMs = 30_000L
    private var consecutiveBatchFails = 0

    private data class CoalesceEntry(
        val leaderDef: CompletableDeferred<Response>,
        val followers: MutableList<CompletableDeferred<Response>> = mutableListOf(),
    )
    private val coalesce = ConcurrentHashMap<String, CoalesceEntry>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val http: OkHttpClient = run {
        val trustAll: TrustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ctx = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustAll), java.security.SecureRandom())
        }
        val ssf: SSLSocketFactory = SniRewriteSocketFactory(ctx.socketFactory, frontDomain)

        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(cfg.relayTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(cfg.relayTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .callTimeout(cfg.relayTimeoutMs.toLong() + 5_000, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .connectionPool(ConnectionPool(cfg.poolMinIdle, 45, TimeUnit.SECONDS))
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .sslSocketFactory(ssf, trustAll as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .dns(object : Dns {
                override fun lookup(hostname: String): List<java.net.InetAddress> {
                    return try {
                        if (hostname.equals(gasHost, ignoreCase = true)) {
                            listOf(java.net.InetAddress.getByName(googleIp))
                        } else {
                            Dns.SYSTEM.lookup(hostname)
                        }
                    } catch (e: Throwable) {
                        LogBus.w("Relay", "DNS lookup failed for $hostname: ${e.message}")
                        Dns.SYSTEM.lookup(hostname)
                    }
                }
            })
            .build()
    }

    private val jsonMedia = "application/json".toMediaType()

    private val youtubeSuffixes = listOf(
        "youtube.com", "youtu.be", "youtube-nocookie.com",
        "googlevideo.com", "ytimg.com", "ggpht.com", "gvt1.com", "gvt2.com",
    )

    private val largeExts = setOf(
        "zip", "tar", "gz", "bz2", "xz", "7z", "rar",
        "exe", "msi", "dmg", "deb", "rpm", "apk",
        "iso", "img",
        "mp4", "mkv", "avi", "mov", "webm",
        "mp3", "flac", "wav", "aac",
        "pdf", "doc", "docx", "ppt", "pptx", "wasm",
    )

    init {
        runCatching {
            multiId.configure(
                rcfg.scriptIds,
                failThreshold = cfg.multiIdFailThreshold,
                cooldownSec = cfg.multiIdCooldownSec.toLong(),
            )
        }
    }

    fun shutdown() {
        runCatching { scope.cancel() }
        runCatching { http.dispatcher.executorService.shutdown() }
        runCatching { http.connectionPool.evictAll() }
    }

    suspend fun relay(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray = ByteArray(0),
    ): Response {
        return try {
            val payload = buildPayload(method, url, headers, body)
            val hasRange = headers.keys.any { it.equals("range", true) }
            if (method.equals("GET", true) && body.isEmpty() && !hasRange) {
                coalescedSubmit(url, payload)
            } else {
                batchSubmit(payload)
            }
        } catch (e: Throwable) {
            LogBus.e("Relay", "relay() top-level failure: ${e.message}", e)
            Response.error(502, "relay error: ${e.message ?: e.toString()}")
        }
    }

    suspend fun relaySmart(
        method: String, url: String, headers: Map<String, String>, body: ByteArray,
    ): Response {
        return try {
            if (method.equals("GET", true) && body.isEmpty()) {
                if (headers.keys.any { it.equals("range", true) }) {
                    return relay(method, url, headers, body)
                }
                if (isLikelyDownload(url)) {
                    return relayParallel(method, url, headers, body)
                }
            }
            relay(method, url, headers, body)
        } catch (e: Throwable) {
            LogBus.e("Relay", "relaySmart() failure: ${e.message}", e)
            Response.error(502, "relay error: ${e.message ?: e.toString()}")
        }
    }

    private fun isLikelyDownload(url: String): Boolean {
        val path = url.substringBefore("?").lowercase()
        val ext = path.substringAfterLast('.', "")
        return ext in largeExts
    }

    private fun isYoutubeUrl(url: String): Boolean {
        val host = try {
            URI(url).host?.lowercase()?.trimEnd('.') ?: return false
        } catch (_: Throwable) { return false }
        return youtubeSuffixes.any { host == it || host.endsWith(".$it") }
    }

    private fun buildPayload(
        method: String, url: String, headers: Map<String, String>, body: ByteArray,
    ): JsonObject = buildJsonObject {
        put("m", method.uppercase())
        put("u", url)
        put("r", true)
        if (cfg.youtubeViaRelay && isYoutubeUrl(url)) put("youtube_via_relay", true)
        if (headers.isNotEmpty()) {
            put("h", buildJsonObject {
                headers.forEach { (k, v) ->
                    if (!k.equals("accept-encoding", true)) put(k, v)
                }
            })
        }
        if (body.isNotEmpty()) {
            put("b", Base64.encodeToString(body, Base64.NO_WRAP))
            (headers["Content-Type"] ?: headers["content-type"])?.let { put("ct", it) }
        }
    }

    private suspend fun coalescedSubmit(url: String, payload: JsonObject): Response {
        val mine = CompletableDeferred<Response>()
        val entry: CoalesceEntry = coalesce.compute(url) { _, existing ->
            if (existing != null) {
                existing.followers += mine
                existing
            } else {
                CoalesceEntry(leaderDef = mine)
            }
        } ?: return Response.error(502, "coalesce returned null")

        if (entry.leaderDef !== mine) {
            return mine.await()
        }

        return try {
            val r = batchSubmit(payload)
            coalesce.remove(url)
            for (f in entry.followers) runCatching { f.complete(r) }
            entry.leaderDef.complete(r)
            r
        } catch (e: Throwable) {
            coalesce.remove(url)
            for (f in entry.followers) runCatching { f.completeExceptionally(e) }
            entry.leaderDef.completeExceptionally(e)
            throw e
        }
    }

    private data class PendingRequest(
        val payload: JsonObject,
        val deferred: CompletableDeferred<Response>,
    )

    private suspend fun batchSubmit(payload: JsonObject): Response {
        if (!batchEnabled) {
            if (System.currentTimeMillis() - batchDisabledAt > batchReEnableMs) {
                batchEnabled = true
                consecutiveBatchFails = 0
                LogBus.i("Relay", "Batching re-enabled after TTL")
            }
        }
        if (!batchEnabled) return relayWithRetry(payload)

        val def = CompletableDeferred<Response>()
        batchLock.withLock {
            batchPending += PendingRequest(payload, def)
            if (batchPending.size >= cfg.batchMax) {
                val batch = batchPending.toList()
                batchPending.clear()
                batchTimerJob?.cancel(); batchTimerJob = null
                scope.launch { batchSend(batch) }
            } else if (batchTimerJob == null) {
                batchTimerJob = scope.launch { batchTimer() }
            }
            Unit
        }
        return def.await()
    }

    private suspend fun batchTimer() {
        delay(cfg.batchWindowMicroMs.toLong())
        batchLock.withLock {
            if (batchPending.size <= 1) {
                if (batchPending.isNotEmpty()) {
                    val batch = batchPending.toList()
                    batchPending.clear()
                    batchTimerJob = null
                    scope.launch { batchSend(batch) }
                }
                return
            }
        }
        delay((cfg.batchWindowMacroMs - cfg.batchWindowMicroMs).toLong().coerceAtLeast(1))
        batchLock.withLock {
            if (batchPending.isNotEmpty()) {
                val batch = batchPending.toList()
                batchPending.clear()
                batchTimerJob = null
                scope.launch { batchSend(batch) }
            }
            Unit
        }
    }

    private suspend fun batchSend(batch: List<PendingRequest>) {
        if (batch.size == 1) {
            val item = batch[0]
            try {
                item.deferred.complete(relayWithRetry(item.payload))
            } catch (e: Throwable) {
                runCatching { item.deferred.complete(Response.error(502, "relay error: ${e.message}")) }
            }
            return
        }
        try {
            val results = relayBatch(batch.map { it.payload })
            results.forEachIndexed { i, r -> runCatching { batch[i].deferred.complete(r) } }
        } catch (e: Throwable) {
            consecutiveBatchFails++
            if (consecutiveBatchFails >= 3 && batchEnabled) {
                batchEnabled = false
                batchDisabledAt = System.currentTimeMillis()
                LogBus.w("Relay", "Batching disabled after $consecutiveBatchFails fails; TTL ${batchReEnableMs}ms")
            }
            for (item in batch) {
                try {
                    item.deferred.complete(relayWithRetry(item.payload))
                } catch (e2: Throwable) {
                    runCatching { item.deferred.complete(Response.error(502, e2.message ?: "relay error")) }
                }
            }
        }
    }

    private suspend fun relayWithRetry(payload: JsonObject): Response = concurrencySem.withPermit {
        var last: Throwable? = null
        for (attempt in 0 until cfg.relayRetryCount) {
            val sid = multiId.nextId() ?: rcfg.scriptIds.firstOrNull()
                ?: return Response.error(502, "no relay endpoints configured")
            val t0 = System.currentTimeMillis()
            try {
                val resp = postJson(execPath(sid), payload)
                val latency = (System.currentTimeMillis() - t0).toDouble()
                multiId.reportOk(sid, latency)
                runCatching { onLatency?.invoke(latency, parseCfLatency(resp), latency) }
                return parseRelayResponse(resp.body)
            } catch (e: Throwable) {
                last = e
                runCatching { multiId.reportErr(sid) }
            }
        }
        throw last ?: RuntimeException("relay failed")
    }

    private suspend fun relayBatch(payloads: List<JsonObject>): List<Response> = concurrencySem.withPermit {
        val sid = multiId.nextId() ?: rcfg.scriptIds.firstOrNull()
            ?: throw RuntimeException("no relay endpoints configured")
        val body = buildJsonObject {
            put("k", authKey)
            put("q", JsonArray(payloads))
        }
        val t0 = System.currentTimeMillis()
        try {
            val raw = postJson(execPath(sid), body)
            val latency = (System.currentTimeMillis() - t0).toDouble()
            multiId.reportOk(sid, latency)
            runCatching { onLatency?.invoke(latency, parseCfLatency(raw), latency) }
            val text = raw.body.toString(Charsets.UTF_8).trim()
            val data = Json.parseToJsonElement(text).jsonObject
            data["e"]?.let { throw RuntimeException("batch error: $it") }
            val items = data["q"]?.jsonArray ?: throw RuntimeException("missing q[]")
            return items.map { parseRelayJson(it.jsonObject) }
        } catch (e: Throwable) {
            runCatching { multiId.reportErr(sid) }
            throw e
        }
    }

    private fun execPath(sid: String) = "/macros/s/$sid/exec"

    private suspend fun postJson(path: String, payload: JsonObject): RawResponse =
        withContext(Dispatchers.IO) {
            val withKey = JsonObject(payload + ("k" to JsonPrimitive(authKey)))
            val req = Request.Builder()
                .url("https://$gasHost$path")
                .post(withKey.toString().toRequestBody(jsonMedia))
                .header("Accept-Encoding", "gzip")
                .header("Connection", "keep-alive")
                .build()
            http.newCall(req).execute().use { r ->
                val bytes = r.body?.bytes() ?: ByteArray(0)
                val hdrs = r.headers.toMultimap().mapValues { it.value.firstOrNull() ?: "" }
                RawResponse(r.code, hdrs, bytes)
            }
        }

    private fun parseCfLatency(raw: RawResponse): Double =
        raw.headers["x-cf-relay"]?.toDoubleOrNull() ?: 0.0

    private fun parseRelayResponse(body: ByteArray): Response {
        val text = body.toString(Charsets.UTF_8).trim()
        if (text.isEmpty()) return Response.error(502, "empty relay response")
        val data = try {
            Json.parseToJsonElement(text).jsonObject
        } catch (e: Throwable) { return Response.error(502, "bad relay JSON: ${e.message}") }
        return parseRelayJson(data)
    }

    private fun parseRelayJson(data: JsonObject): Response {
        data["e"]?.let { return Response.error(502, "relay error: $it") }
        val status = data["s"]?.jsonPrimitive?.intOrNull ?: 200
        val headers = mutableMapOf<String, String>()
        (data["h"] as? JsonObject)?.forEach { (k, v) ->
            when (v) {
                is JsonArray -> headers[k] = v.joinToString(", ") {
                    (it as? JsonPrimitive)?.contentOrNull ?: it.toString()
                }
                is JsonPrimitive -> headers[k] = v.contentOrNull ?: ""
                else -> headers[k] = v.toString()
            }
        }
        val bodyB64 = (data["b"] as? JsonPrimitive)?.contentOrNull ?: ""
        val parsedBody = if (bodyB64.isEmpty()) ByteArray(0)
        else try { Base64.decode(bodyB64, Base64.DEFAULT) } catch (_: Throwable) { ByteArray(0) }
        return Response(status, headers, parsedBody)
    }

    suspend fun relayParallel(
        method: String, url: String, headers: Map<String, String>, body: ByteArray,
        chunkSize: Int = cfg.chunkSize, maxParallel: Int = cfg.maxParallel,
    ): Response {
        if (!method.equals("GET", true) || body.isNotEmpty()) {
            return relay(method, url, headers, body)
        }
        val firstHeaders = headers.toMutableMap().apply { put("Range", "bytes=0-${chunkSize - 1}") }
        val first = relay("GET", url, firstHeaders, ByteArray(0))
        if (first.status != 206) return first
        val totalSize = (first.headers.entries.firstOrNull { it.key.equals("content-range", true) }
            ?.value ?: "")
            .substringAfter('/').toLongOrNull() ?: return rewrite206To200(first)
        if (totalSize <= chunkSize || first.body.size >= totalSize) return rewrite206To200(first)

        val ranges = mutableListOf<Pair<Long, Long>>()
        var start = first.body.size.toLong()
        while (start < totalSize) {
            val end = min(start + chunkSize - 1, totalSize - 1)
            ranges += start to end
            start = end + 1
        }
        val sem = Semaphore(maxParallel)
        val parts = ArrayList<ByteArray>(ranges.size + 1)
        parts.add(first.body)
        coroutineScope {
            val deferreds = ranges.map { (s, e) ->
                async {
                    sem.withPermit {
                        val rh = headers.toMutableMap().apply { put("Range", "bytes=$s-$e") }
                        var lastErr: Throwable? = null
                        repeat(3) { attempt ->
                            try {
                                val r = relay("GET", url, rh, ByteArray(0))
                                val expected = (e - s + 1).toInt()
                                if (r.body.size == expected) return@withPermit r.body
                            } catch (t: Throwable) { lastErr = t }
                            delay(300L * (attempt + 1))
                        }
                        throw RuntimeException("range $s-$e failed", lastErr)
                    }
                }
            }
            for (d in deferreds) parts.add(d.await())
        }

        val totalBytes = parts.sumOf { it.size }
        val full = ByteArray(totalBytes)
        var off = 0
        for (p in parts) {
            System.arraycopy(p, 0, full, off, p.size)
            off += p.size
        }

        val outHeaders = first.headers.toMutableMap().also { hdrs ->
            hdrs.entries.removeAll { e ->
                e.key.lowercase() in setOf(
                    "content-length", "content-range", "content-encoding",
                    "transfer-encoding", "connection",
                )
            }
            hdrs["Content-Length"] = full.size.toString()
        }
        return Response(200, outHeaders, full)
    }

    private fun rewrite206To200(r: Response): Response {
        val h = r.headers.toMutableMap()
        h.entries.removeAll { it.key.equals("content-range", true) }
        h["Content-Length"] = r.body.size.toString()
        return Response(200, h, r.body)
    }
}

data class RawResponse(val code: Int, val headers: Map<String, String>, val body: ByteArray)

data class Response(
    val status: Int,
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    companion object {
        fun error(status: Int, message: String): Response =
            Response(status, mapOf("Content-Type" to "text/plain"), message.toByteArray())
    }

    fun toHttpBytes(): ByteArray {
        val sb = StringBuilder()
        val statusText = when (status) {
            200 -> "OK"; 206 -> "Partial Content"
            301 -> "Moved"; 302 -> "Found"; 304 -> "Not Modified"
            400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"
            404 -> "Not Found"; 429 -> "Too Many Requests"
            500 -> "Internal Server Error"; 502 -> "Bad Gateway"
            else -> "OK"
        }
        sb.append("HTTP/1.1 $status $statusText\r\n")
        val skip = setOf(
            "transfer-encoding", "connection", "keep-alive",
            "content-length", "content-encoding"
        )
        for ((k, v) in headers) {
            if (k.lowercase() in skip) continue
            sb.append("$k: $v\r\n")
        }
        sb.append("Content-Length: ${body.size}\r\n\r\n")
        return sb.toString().toByteArray() + body
    }
}
'''

FILES["HttpProxyServer.kt"] = r'''package com.velum.vpn.proxy

import com.velum.vpn.core.LogBus
import com.velum.vpn.cert.MitmCa
import com.velum.vpn.core.RelayConfig
import com.velum.vpn.core.Stats
import com.velum.vpn.net.RelayClient
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.io.InputStream
import java.io.OutputStream
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class HttpProxyServer(
    private val cfg: RelayConfig,
    private val relay: RelayClient,
    private val mitm: MitmCa,
    private val stats: Stats,
) {
    private val tag = "VELUM-Proxy"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: ServerSocket? = null

    @Volatile var isRunning = false
        private set

    fun start() {
        if (isRunning) return
        try {
            val s = ServerSocket()
            s.reuseAddress = true
            val bind = if (cfg.lanShare) "0.0.0.0" else cfg.listenHost
            s.bind(InetSocketAddress(bind, cfg.listenPort), 256)
            server = s
            isRunning = true
            scope.launch { acceptLoop(s) }
            LogBus.i(tag, "HTTP proxy listening on $bind:${cfg.listenPort}")
        } catch (e: Throwable) {
            LogBus.e(tag, "Failed to start HTTP proxy", e)
        }
    }

    fun stop() {
        isRunning = false
        runCatching { server?.close() }
        server = null
        runCatching { scope.cancel() }
    }

    private suspend fun acceptLoop(s: ServerSocket) {
        while (isRunning && !s.isClosed) {
            val client = try { s.accept() } catch (_: Throwable) { break }
            stats.connOpened()
            scope.launch {
                try {
                    handleClient(client)
                } catch (e: Throwable) {
                    if (e !is CancellationException) {
                        LogBus.d(tag, "client session error: ${e.message}")
                    }
                } finally {
                    runCatching { client.close() }
                    stats.connClosed()
                }
            }
        }
    }

    private suspend fun handleClient(client: Socket) {
        runCatching { client.tcpNoDelay = true }
        val input = client.getInputStream()
        val output = client.getOutputStream()

        val firstLine = readLine(input) ?: return
        val headerBlock = StringBuilder().append(firstLine).append("\r\n")
        val hdrs = mutableMapOf<String, String>()

        while (true) {
            val line = readLine(input) ?: break
            headerBlock.append(line).append("\r\n")
            if (line.isEmpty()) break
            val sep = line.indexOf(':')
            if (sep > 0) {
                hdrs[line.substring(0, sep).trim().lowercase()] = line.substring(sep + 1).trim()
            }
        }

        val parts = firstLine.split(" ", limit = 3)
        if (parts.size < 2) return
        val method = parts[0].uppercase()
        stats.incrRequests()

        if (method == "CONNECT") {
            handleConnect(parts[1], client, input, output)
        } else {
            handleHttp(method, parts[1], hdrs, headerBlock.toString(), input, output)
        }
    }

    private suspend fun handleConnect(
        target: String,
        client: Socket,
        input: InputStream,
        output: OutputStream,
    ) {
        val (host, port) = parseHostPort(target, 443)
        LogBus.i(tag, "CONNECT -> $host:$port")

        try {
            output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            output.flush()
        } catch (e: Throwable) {
            LogBus.d(tag, "Failed to send CONNECT 200: ${e.message}")
            return
        }

        val ctx = try {
            mitm.contextFor(host)
        } catch (e: Throwable) {
            LogBus.e(tag, "MITM context generation failed for $host: ${e.message}", e)
            return
        }

        val ssf: SSLSocketFactory = ctx.socketFactory
        val tls: SSLSocket = try {
            (ssf.createSocket(client, host, port, true) as SSLSocket).apply {
                useClientMode = false
                soTimeout = 60_000
            }
        } catch (e: Throwable) {
            LogBus.e(tag, "Failed to wrap socket in TLS for $host: ${e.message}", e)
            return
        }

        try {
            try {
                tls.startHandshake()
            } catch (e: Throwable) {
                val msg = e.message ?: e.toString()
                LogBus.d(tag, "TLS handshake FAILED for $host: $msg")
                if (msg.contains("alert", true)) {
                    LogBus.e(
                        tag,
                        "Client REJECTED certificate for $host. Causes: CA not installed, " +
                            "non-compliant leaf, or cert pinning."
                    )
                }
                return
            }

            val tlsIn = tls.inputStream
            val tlsOut = tls.outputStream

            while (true) {
                val firstReq = readLine(tlsIn) ?: break
                if (firstReq.isEmpty()) continue

                val rHdrs = mutableMapOf<String, String>()
                val sb = StringBuilder().append(firstReq).append("\r\n")
                while (true) {
                    val line = readLine(tlsIn) ?: break
                    sb.append(line).append("\r\n")
                    if (line.isEmpty()) break
                    val sep = line.indexOf(':')
                    if (sep > 0) {
                        rHdrs[line.substring(0, sep).trim().lowercase()] =
                            line.substring(sep + 1).trim()
                    }
                }

                val rParts = firstReq.split(" ", limit = 3)
                if (rParts.size < 2) break
                val rMethod = rParts[0]
                val rPath = rParts[1]

                val te = rHdrs["transfer-encoding"]?.lowercase()
                val body = if (te != null && te.contains("chunked")) {
                    runCatching { readChunkedBody(tlsIn) }.getOrDefault(ByteArray(0))
                } else {
                    val cl = rHdrs["content-length"]?.toIntOrNull() ?: 0
                    if (cl > 0) runCatching { readNBytesCompat(tlsIn, cl) }.getOrDefault(ByteArray(0))
                    else ByteArray(0)
                }
                stats.addUp(sb.length.toLong() + body.size)

                val url = if (port == 443) "https://$host$rPath" else "https://$host:$port$rPath"
                val resp = try {
                    relay.relaySmart(rMethod, url, rHdrs, body)
                } catch (e: Throwable) {
                    com.velum.vpn.net.Response.error(502, "relay error: ${e.message}")
                }

                try {
                    val bytes = resp.toHttpBytes()
                    tlsOut.write(bytes)
                    tlsOut.flush()
                    stats.addDown(bytes.size.toLong())
                } catch (e: Throwable) {
                    LogBus.d(tag, "TLS write failed for $host: ${e.message}")
                    break
                }
            }
        } finally {
            runCatching { tls.close() }
        }
    }

    private suspend fun handleHttp(
        method: String,
        url: String,
        hdrs: Map<String, String>,
        headerBlock: String,
        input: InputStream,
        output: OutputStream,
    ) {
        val te = hdrs["transfer-encoding"]?.lowercase()
        val body = if (te != null && te.contains("chunked")) {
            runCatching { readChunkedBody(input) }.getOrDefault(ByteArray(0))
        } else {
            val cl = hdrs["content-length"]?.toIntOrNull() ?: 0
            if (cl > 0) runCatching { readNBytesCompat(input, cl) }.getOrDefault(ByteArray(0))
            else ByteArray(0)
        }
        stats.addUp(headerBlock.length.toLong() + body.size)

        val resp = try {
            relay.relaySmart(method, url, hdrs, body)
        } catch (e: Throwable) {
            com.velum.vpn.net.Response.error(502, "relay error: ${e.message}")
        }

        try {
            val bytes = resp.toHttpBytes()
            output.write(bytes)
            output.flush()
            stats.addDown(bytes.size.toLong())
        } catch (e: Throwable) {
            LogBus.d(tag, "HTTP write failed: ${e.message}")
        }
    }

    private fun parseHostPort(spec: String, defaultPort: Int): Pair<String, Int> {
        val idx = spec.lastIndexOf(':')
        return if (idx > 0) {
            spec.substring(0, idx) to (spec.substring(idx + 1).toIntOrNull() ?: defaultPort)
        } else {
            spec to defaultPort
        }
    }

    private fun readNBytesCompat(input: InputStream, n: Int): ByteArray {
        if (n <= 0) return ByteArray(0)
        val buffer = ByteArray(n)
        var offset = 0
        while (offset < n) {
            val read = try { input.read(buffer, offset, n - offset) } catch (_: Throwable) { -1 }
            if (read == -1) return buffer.copyOf(offset)
            offset += read
        }
        return buffer
    }

    private fun readChunkedBody(input: InputStream): ByteArray {
        val chunks = mutableListOf<ByteArray>()
        while (true) {
            val sizeLine = readLine(input) ?: break
            val chunkSize = sizeLine.trim().substringBefore(';').toIntOrNull(16) ?: 0
            if (chunkSize == 0) {
                readLine(input)
                break
            }
            val chunk = readNBytesCompat(input, chunkSize)
            chunks += chunk
            readLine(input)
        }
        val total = chunks.sumOf { it.size }
        val result = ByteArray(total)
        var off = 0
        for (c in chunks) {
            System.arraycopy(c, 0, result, off, c.size)
            off += c.size
        }
        return result
    }

    private fun readLine(input: InputStream): String? {
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val b = try { input.read() } catch (_: Throwable) { return null }
            if (b < 0) return if (out.size() == 0) null else out.toString()
            if (b == '\n'.code) {
                return out.toString().trimEnd('\r')
            }
            out.write(b)
            if (out.size() > 16384) break
        }
        return out.toString()
    }
}
'''

FILES["SniRewriteSocketFactory.kt"] = r'''package com.velum.vpn.net

import java.net.InetAddress
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class SniRewriteSocketFactory(
    private val delegate: SSLSocketFactory,
    private val sniHost: String,
) : SSLSocketFactory() {

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
        rewrite(delegate.createSocket(s, sniHost, port, autoClose))

    override fun createSocket(host: String, port: Int): Socket =
        rewrite(delegate.createSocket(sniHost, port))

    override fun createSocket(host: String, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        rewrite(delegate.createSocket(sniHost, port, localHost, localPort))

    override fun createSocket(host: InetAddress, port: Int): Socket =
        rewrite(delegate.createSocket(host, port))

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        rewrite(delegate.createSocket(address, port, localAddress, localPort))

    private fun rewrite(socket: Socket): Socket {
        if (socket is SSLSocket) {
            try {
                val m = socket.javaClass.getMethod("setHostname", String::class.java)
                m.invoke(socket, sniHost)
            } catch (_: Throwable) {
                // Not Conscrypt — JSSE fallback below handles it.
            }
            try {
                val params: SSLParameters = socket.sslParameters
                params.serverNames = listOf(SNIHostName(sniHost))
                socket.sslParameters = params
            } catch (_: Throwable) {
                // Some stacks reject SSLParameters mutation; setHostname above is enough.
            }
        }
        return socket
    }
}
'''


# ─────────────────────────────────────────────────────────────────────────────
# Driver
# ─────────────────────────────────────────────────────────────────────────────

def find_target(app_dir: Path, basename: str) -> Path | None:
    """Locate a Kotlin file by basename anywhere under app_dir."""
    matches = list(app_dir.rglob(basename))
    if not matches:
        return None
    if len(matches) == 1:
        return matches[0]
    # Prefer the path under /main/ over /test/ if multiple matches.
    main_matches = [m for m in matches if "/src/main/" in m.as_posix()]
    if main_matches:
        return main_matches[0]
    return matches[0]


def overwrite(target: Path, content: str, ts: str) -> None:
    """Backup then overwrite a file atomically."""
    if target.exists():
        backup = target.with_suffix(target.suffix + f".bak.{ts}")
        backup.write_bytes(target.read_bytes())
        print(f"  backup -> {backup.relative_to(target.parents[3]) if len(target.parents) >= 4 else backup}")
    target.parent.mkdir(parents=True, exist_ok=True)
    # Write to a temp file in the same directory then atomically rename.
    tmp = target.with_suffix(target.suffix + ".tmp")
    tmp.write_text(content, encoding="utf-8")
    os.replace(tmp, target)
    print(f"  wrote  -> {target}")


def zip_app_dir(app_dir: Path, out_zip: Path) -> None:
    """Zip the entire app/ directory, excluding build/ and *.bak.* files."""
    excluded_dirs = {"build", ".gradle", ".idea", ".cxx"}
    count = 0
    with zipfile.ZipFile(out_zip, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as zf:
        for root, dirs, files in os.walk(app_dir):
            dirs[:] = [d for d in dirs if d not in excluded_dirs]
            for f in files:
                if ".bak." in f:
                    continue
                full = Path(root) / f
                arc = full.relative_to(app_dir.parent)
                zf.write(full, arcname=arc.as_posix())
                count += 1
    print(f"\nZipped {count} files into {out_zip}")


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Bulletproof rewriter for MasterHttpRelay/VelumVPN."
    )
    parser.add_argument(
        "--project-dir",
        type=Path,
        default=Path.cwd(),
        help="Path to the project root (the directory that contains app/). "
             "Defaults to the current directory.",
    )
    args = parser.parse_args()

    project_dir: Path = args.project_dir.resolve()
    app_dir = project_dir / "app"

    print(f"Project root: {project_dir}")
    if not app_dir.is_dir():
        print(f"ERROR: 'app/' directory not found under {project_dir}")
        print("Run this script from the project root, or pass --project-dir.")
        return 1

    ts = time.strftime("%Y%m%d-%H%M%S")
    print(f"Backup timestamp: {ts}\n")

    missing: list[str] = []
    written: list[str] = []

    for basename, content in FILES.items():
        target = find_target(app_dir, basename)
        if target is None:
            print(f"[SKIP] {basename} not found anywhere under {app_dir}")
            missing.append(basename)
            continue
        print(f"[FIX]  {basename}")
        try:
            overwrite(target, content, ts)
            written.append(basename)
        except Exception as e:
            print(f"  ERROR writing {target}: {e}")
            missing.append(basename)
        print()

    print("─" * 60)
    print(f"Files written : {len(written)} / {len(FILES)}")
    if written:
        for n in written:
            print(f"   ok   {n}")
    if missing:
        print("Files missing or failed:")
        for n in missing:
            print(f"   FAIL {n}")

    # Sanity-check: confirm every written file actually exists and is non-empty.
    print("\nVerifying written files...")
    all_ok = True
    for basename in written:
        target = find_target(app_dir, basename)
        if target is None or not target.is_file() or target.stat().st_size == 0:
            print(f"   FAIL verify {basename}")
            all_ok = False
        else:
            print(f"   ok   verify {basename} ({target.stat().st_size} bytes)")

    if not all_ok:
        print("\nVerification FAILED — not creating zip.")
        return 2

    out_zip = project_dir / "MasterHttpRelay_Bulletproof.zip"
    print(f"\nCreating zip: {out_zip}")
    try:
        zip_app_dir(app_dir, out_zip)
    except Exception as e:
        print(f"ERROR zipping: {e}")
        return 3

    print("\n" + "=" * 60)
    print("DONE.")
    print(f"  Patched files : {len(written)}")
    print(f"  Zip          : {out_zip}")
    print(f"  Backups      : *.bak.{ts}")
    print("=" * 60)
    return 0


if __name__ == "__main__":
    sys.exit(main())
