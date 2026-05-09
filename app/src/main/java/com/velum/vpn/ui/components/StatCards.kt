package com.velum.vpn.ui.components
import androidx.compose.runtime.getValue
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.velum.vpn.ui.theme.MatteGrey
import com.velum.vpn.ui.theme.NeonGreen
import com.velum.vpn.ui.theme.TextFaint
import com.velum.vpn.ui.theme.TextMuted
import com.velum.vpn.ui.theme.TextOnNeon
import com.velum.vpn.ui.theme.TextPrimary

/**
 * Generic stat card — three flavours:
 *   1. NEON: filled with neon-green, black text  (the "hero" metric)
 *   2. MATTE: dark grey background, white text + green icon
 *   3. GHOST: outline only (used for tertiary metrics)
 *
 * Every card is ultra-rounded (28 dp) and animates colour transitions.
 */
enum class StatCardKind { NEON, MATTE, GHOST }

@Composable
fun StatCard(
    label: String,
    value: String,
    unit: String? = null,
    icon: ImageVector? = null,
    kind: StatCardKind = StatCardKind.MATTE,
    glowing: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val containerTarget = when (kind) {
        StatCardKind.NEON  -> NeonGreen
        StatCardKind.MATTE -> MatteGrey
        StatCardKind.GHOST -> Color.Transparent
    }
    val container by animateColorAsState(containerTarget, label = "card-bg")

    val labelColor = when (kind) {
        StatCardKind.NEON -> TextOnNeon.copy(alpha = 0.55f)
        else -> TextFaint
    }
    val valueColor = when (kind) {
        StatCardKind.NEON -> TextOnNeon
        else -> TextPrimary
    }
    val iconColor = when (kind) {
        StatCardKind.NEON -> TextOnNeon
        else -> NeonGreen
    }
    val unitColor = when (kind) {
        StatCardKind.NEON -> TextOnNeon.copy(alpha = 0.6f)
        else -> TextMuted
    }

    val shape = RoundedCornerShape(28.dp)
    GlassSurface(
        modifier = modifier
            .then(if (glowing && kind == StatCardKind.NEON) {
                Modifier.shadow(24.dp, shape, spotColor = NeonGreen, ambientColor = NeonGreen)
            } else Modifier),
        cornerRadius = 28.dp,
        tint = container,
        borderColor = if (kind == StatCardKind.GHOST) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.06f),
        contentPadding = 0.dp
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)
        ) {
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (icon != null) {
                    Icon(icon, null, tint = iconColor, modifier = Modifier.size(14.dp))
                }
                Text(
                    label.uppercase(),
                    color = labelColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.5.sp,
                )
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    value,
                    color = valueColor,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.5).sp,
                )
                if (unit != null) {
                    Text(
                        unit,
                        color = unitColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
            }
        }
    }
}
