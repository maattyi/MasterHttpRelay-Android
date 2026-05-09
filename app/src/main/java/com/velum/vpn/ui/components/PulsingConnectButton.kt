package com.velum.vpn.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.velum.vpn.ui.theme.NeonGreen
import com.velum.vpn.ui.theme.TextOnNeon
import com.velum.vpn.ui.theme.WarmAmber

@Composable
fun PulsingConnectButton(
    connected: Boolean,
    enabled: Boolean = true,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (connected) 1.03f else 1.01f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale",
    )
    val pulseAlpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = if (connected) 0.7f else 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )

    val targetFill by animateColorAsState(
        targetValue = if (connected) NeonGreen else Color.Transparent,
        animationSpec = tween(500), label = "fill",
    )
    val targetStroke by animateColorAsState(
        targetValue = if (connected) NeonGreen else (if (isSystemInDarkTheme()) Color.White.copy(alpha = 0.2f) else Color.Black.copy(alpha = 0.1f)),
        animationSpec = tween(500), label = "stroke",
    )
    val targetText by animateColorAsState(
        targetValue = if (connected) TextOnNeon else MaterialTheme.colorScheme.onBackground,
        animationSpec = tween(500), label = "text",
    )
    val glowColor = if (connected) NeonGreen else WarmAmber

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp)
            .scale(pulseScale)
            .drawBehind {
                if (connected) {
                    val radius = size.minDimension / 2 * 1.2f
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(glowColor.copy(alpha = 0.15f * pulseAlpha), Color.Transparent),
                            center = center,
                            radius = radius
                        ),
                        radius = radius,
                        center = center
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val shape = RoundedCornerShape(48.dp)
        Box(
            modifier = Modifier
                .size(160.dp)
                .background(targetFill, shape)
                .border(2.dp, targetStroke, shape)
                .clip(shape)
                .clickable(enabled = enabled) { onToggle() },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    Modifier
                        .size(64.dp)
                        .background(
                            if (connected) Color.Black.copy(alpha = 0.1f)
                            else MaterialTheme.colorScheme.surfaceVariant,
                            CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.PowerSettingsNew,
                        contentDescription = null,
                        tint = targetText,
                        modifier = Modifier.size(32.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = if (connected) androidx.compose.ui.res.stringResource(com.velum.vpn.R.string.status_connected) else "START",
                    color = targetText,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                )
            }
        }
    }
}
