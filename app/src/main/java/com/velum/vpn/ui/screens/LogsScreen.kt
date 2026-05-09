package com.velum.vpn.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.velum.vpn.core.LogBus
import com.velum.vpn.ui.theme.*

@Composable
fun LogsScreen() {
    val lines by LogBus.flow.collectAsState()
    val state = rememberLazyListState()

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) state.animateScrollToItem(lines.size - 1)
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 18.dp)
            .padding(bottom = 130.dp),
    ) {
        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                        Text(
                            androidx.compose.ui.res.stringResource(com.velum.vpn.R.string.logs).uppercase(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 2.sp,
                        )
                        Text(
                            "Live diagnostics",
                            color = MaterialTheme.colorScheme.onBackground,
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = (-0.5).sp,
                        )
                    }
                    TextButton(
                        onClick = LogBus::clear,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                    ) {
                        Text("CLEAR", style = MaterialTheme.typography.labelMedium,
                             fontWeight = FontWeight.ExtraBold, letterSpacing = 1.5.sp)
                    }
                }
                Spacer(Modifier.height(10.dp))
                LazyColumn(
                    state = state,
                    modifier = Modifier.weight(1f)
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(lines, key = { it.id }) { line ->
                        Text(
                            line.text,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = when (line.level) {
                                "ERROR" -> MaterialTheme.colorScheme.error
                                "WARNING" -> Color(0xFFFFE08A)
                                "DEBUG" -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }    }
}
