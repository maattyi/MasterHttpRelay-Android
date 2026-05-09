package com.velum.vpn.ui.components
import androidx.compose.runtime.getValue
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.velum.vpn.ui.theme.NeonGreen
import com.velum.vpn.ui.theme.NeonGreenSoft
import com.velum.vpn.ui.theme.TextMuted
import com.velum.vpn.ui.theme.TextOnNeon

data class PillNavItem(
    val key: String,
    val label: String,
    val icon: ImageVector,
)

/**
 * Floating pill bottom-nav. Sits *above* the system nav bar — uses
 * windowInsets padding so it never collides with gesture handles.
 * Active tab inflates with a neon-green capsule highlight.
 */
@Composable
fun FloatingPillNav(
    items: List<PillNavItem>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        GlassSurface(
            cornerRadius = 40.dp,
            tint = Color(0xFF0E0E0E),
            modifier = Modifier.shadow(20.dp, RoundedCornerShape(40.dp), spotColor = NeonGreen.copy(alpha = 0.3f))
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                items.forEach { item ->
                    NavPill(
                        item = item,
                        selected = selected == item.key,
                        onClick = { onSelect(item.key) },
                    )
                }
            }
        }
    }
}

@Composable
private fun NavPill(item: PillNavItem, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(
        targetValue = if (selected) NeonGreen else Color.Transparent,
        label = "navbg",
    )
    val fg by animateColorAsState(
        targetValue = if (selected) TextOnNeon else TextMuted,
        label = "navfg",
    )
    val padH by animateDpAsState(
        targetValue = if (selected) 18.dp else 14.dp, label = "navpad",
    )

    Row(
        modifier = Modifier
            .background(bg, RoundedCornerShape(32.dp))
            .clip(RoundedCornerShape(32.dp))
            .clickable { onClick() }
            .padding(horizontal = padH, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(item.icon, contentDescription = item.label, tint = fg,
             modifier = Modifier.size(20.dp))
        if (selected) {
            Text(
                item.label.uppercase(),
                color = fg,
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.5.sp,
            )
        }
    }
}
