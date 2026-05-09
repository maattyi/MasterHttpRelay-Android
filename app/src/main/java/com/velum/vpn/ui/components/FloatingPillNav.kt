package com.velum.vpn.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.velum.vpn.ui.theme.NeonGreen
import com.velum.vpn.ui.theme.TextMuted
import com.velum.vpn.ui.theme.TextOnNeon

data class PillNavItem(
    val key: String,
    val label: String,
    val icon: ImageVector,
)

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
            .imePadding()
            .padding(horizontal = 18.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .shadow(
                    elevation = 20.dp,
                    shape = RoundedCornerShape(40.dp),
                    spotColor = NeonGreen.copy(alpha = 0.30f),
                    ambientColor = NeonGreen.copy(alpha = 0.15f),
                )
                .clip(RoundedCornerShape(40.dp))
                .background(Color(0xFF0E0E0E), RoundedCornerShape(40.dp))
                .border(
                    1.dp,
                    Color.White.copy(alpha = 0.06f),
                    RoundedCornerShape(40.dp),
                )
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
        targetValue = if (selected) 18.dp else 14.dp,
        label = "navpad",
    )

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(32.dp))
            .background(bg, RoundedCornerShape(32.dp))
            .clickable { onClick() }
            .padding(horizontal = padH, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            item.icon,
            contentDescription = item.label,
            tint = fg,
            modifier = Modifier.size(20.dp),
        )
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
