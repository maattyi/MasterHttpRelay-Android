package com.velum.vpn.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.velum.vpn.R
import com.velum.vpn.core.Profile
import com.velum.vpn.core.ProfileManager
import com.velum.vpn.ui.components.GlassSurface
import com.velum.vpn.ui.theme.*

@Composable
fun ProfilesScreen(pm: ProfileManager) {
    var version by remember { mutableStateOf(0) }
    val profiles = remember(version) { pm.list() }
    val active = remember(version) { pm.active() }
    var smartBalance by remember(version) { mutableStateOf(pm.isSmartBalance()) }
    var editing by remember { mutableStateOf<Profile?>(null) }
    var showCreate by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 18.dp)
            .padding(bottom = 130.dp),
    ) {
        Spacer(Modifier.height(22.dp))
        Text(
            stringResource(R.string.profiles).uppercase(),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 2.sp,
        )
        Text(
            "Relay endpoints",
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.5).sp,
        )

        Spacer(Modifier.height(16.dp))

        // Smart-balance toggle card
        GlassSurface(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 28.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Hub,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Smart-balance across all profiles",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Round-robin every script ID with health scoring",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                    )
                }
                Switch(
                    checked = smartBalance,
                    onCheckedChange = {
                        smartBalance = it; pm.setSmartBalance(it); version++
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                        uncheckedBorderColor = Color.Transparent,
                    ),
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        if (profiles.isEmpty()) {
            EmptyState(onAdd = { editing = null; showCreate = true })
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(profiles, key = { it.name }) { p ->
                    ProfileCard(
                        profile = p,
                        active = active?.name == p.name,
                        onActivate = { pm.setActive(p.name); version++ },
                        onEdit = { editing = p },
                        onDelete = { pm.remove(p.name); version++ },
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { editing = null; showCreate = true },
            modifier = Modifier
                .fillMaxWidth().height(56.dp)
                .shadow(18.dp, RoundedCornerShape(32.dp),
                    spotColor = NeonGreen, ambientColor = NeonGreen)
                .clip(RoundedCornerShape(32.dp)),
            shape = RoundedCornerShape(32.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = NeonGreen,
                contentColor = TextOnNeon,
            ),
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null,
                 modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "NEW PROFILE",
                fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 2.sp,
            )
        }
    }

    val target = editing
    if (target != null || showCreate) {
        ProfileEditor(
            initial = target,
            onCancel = { editing = null; showCreate = false },
            onSave = { p -> pm.upsert(p); editing = null; showCreate = false; version++ },
        )
    }
}

@Composable
private fun EmptyState(onAdd: () -> Unit) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 28.dp,
        tint = MaterialTheme.colorScheme.surface
    ) {
        Column(
            Modifier.fillMaxWidth().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(72.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Layers, contentDescription = null,
                     tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "NO PROFILES YET",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 2.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Add your Apps Script deployment ID and auth key to get started.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun ProfileCard(
    profile: Profile,
    active: Boolean,
    onActivate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val border by animateColorAsState(
        if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.1f),
        label = "card-border",
    )
    val bg = if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surface

    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 28.dp,
        tint = bg,
        borderColor = border,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(8.dp).background(
                        if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, CircleShape,
                    )
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    profile.name,
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
                Spacer(Modifier.weight(1f))
                if (active) {
                    Text(
                        "ACTIVE",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 2.sp,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "${profile.scriptIds.size} relay ID(s)" +
                    if (profile.cloudflareChain) "  ·  Cloudflare chain" else "",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!active) {
                    TextButton(
                        onClick = onActivate,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                    ) {
                        Text(
                            "ACTIVATE",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.5.sp,
                        )
                    }
                }
                TextButton(
                    onClick = onEdit,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                ) {
                    Icon(Icons.Outlined.Edit, contentDescription = null,
                         modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("EDIT", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold,
                         letterSpacing = 1.5.sp)
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Outlined.Delete, contentDescription = null,
                         modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("DELETE", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold,
                         letterSpacing = 1.5.sp)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileEditor(
    initial: Profile?,
    onCancel: () -> Unit,
    onSave: (Profile) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "New profile") }
    var auth by remember { mutableStateOf(initial?.authKey ?: "") }
    var tunnel by remember { mutableStateOf(initial?.tunnelKey ?: "") }
    var ids by remember { mutableStateOf(initial?.scriptIds?.joinToString("\n") ?: "") }
    var front by remember { mutableStateOf(initial?.frontDomain ?: "www.google.com") }
    var ip by remember { mutableStateOf(initial?.googleIp ?: "216.239.38.120") }
    var workerHost by remember { mutableStateOf(initial?.workerHost ?: "") }
    var workerPath by remember { mutableStateOf(initial?.workerPath ?: "") }
    var chain by remember { mutableStateOf(initial?.cloudflareChain ?: false) }
    var notes by remember { mutableStateOf(initial?.notes ?: "") }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f),
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        cursorColor = MaterialTheme.colorScheme.primary,
        focusedLabelColor = MaterialTheme.colorScheme.primary,
        unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        focusedTextColor = MaterialTheme.colorScheme.onSurface,
        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    )

    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(28.dp),
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        (initial ?: Profile(name = name)).copy(
                            name = name.ifBlank { "Unnamed" },
                            authKey = auth, tunnelKey = tunnel,
                            scriptIds = ids.lines().map { it.trim() }.filter { it.isNotEmpty() },
                            frontDomain = front, googleIp = ip,
                            workerHost = workerHost, workerPath = workerPath,
                            cloudflareChain = chain, notes = notes,
                        )
                    )
                },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary),
            ) {
                Text("SAVE", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold,
                     letterSpacing = 1.5.sp)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
            ) {
                Text("CANCEL", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold,
                     letterSpacing = 1.5.sp)
            }
        },
        title = {
            Text(
                if (initial != null) "Edit profile" else "New profile",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") },
                                  modifier = Modifier.fillMaxWidth(), colors = fieldColors,
                                  shape = RoundedCornerShape(16.dp))
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(auth, { auth = it },
                    label = { Text("Auth key (matches GAS)") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(), colors = fieldColors,
                    shape = RoundedCornerShape(16.dp))
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(tunnel, { tunnel = it },
                    label = { Text("Tunnel key (Goose, optional)") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(), colors = fieldColors,
                    shape = RoundedCornerShape(16.dp))
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(ids, { ids = it },
                    label = { Text("Apps Script IDs (one per line)") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp),
                    maxLines = 6, colors = fieldColors,
                    shape = RoundedCornerShape(16.dp))
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(front, { front = it },
                    label = { Text("Front domain") },
                    modifier = Modifier.fillMaxWidth(), colors = fieldColors,
                    shape = RoundedCornerShape(16.dp))
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(ip, { ip = it }, label = { Text("Google IP") },
                    modifier = Modifier.fillMaxWidth(), colors = fieldColors,
                    shape = RoundedCornerShape(16.dp))
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(workerHost, { workerHost = it },
                    label = { Text("Worker host") },
                    modifier = Modifier.fillMaxWidth(), colors = fieldColors,
                    shape = RoundedCornerShape(16.dp))
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(workerPath, { workerPath = it },
                    label = { Text("Worker path") },
                    modifier = Modifier.fillMaxWidth(), colors = fieldColors,
                    shape = RoundedCornerShape(16.dp))
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = chain, onCheckedChange = { chain = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                            uncheckedBorderColor = Color.Transparent,
                        ),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Cloudflare Worker chain",
                         color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp,
                         fontWeight = FontWeight.SemiBold)
                }
            }
        },
    )
}
