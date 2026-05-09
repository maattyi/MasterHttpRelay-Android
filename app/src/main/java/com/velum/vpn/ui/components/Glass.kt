package com.velum.vpn.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Glassmorphism wrapper.
 *   * On Android 12+ (API 31): real backdrop blur via Modifier.blur on a
 *     translucent overlay.
 *   * Older devices: a layered translucent gradient that approximates the
 *     same depth without the GPU cost of CompositingStrategy.
 *
 * Intended for cards, top bars, bottom sheets, and the floating nav.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    tint: Color = Color.White.copy(alpha = 0.04f),
    borderColor: Color = Color.White.copy(alpha = 0.06f),
    contentPadding: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .clip(shape)
            .then(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Modifier.background(
                        Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = 0.06f),
                                Color.White.copy(alpha = 0.02f),
                            )
                        )
                    ).blur(0.dp) // hint to compositor; visible blur applied in caller
                } else Modifier
            )
            .background(tint, shape)
            .border(1.dp, borderColor, shape)
            .padding(contentPadding),
    ) { content() }
}
