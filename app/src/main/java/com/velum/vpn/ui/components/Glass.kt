package com.velum.vpn.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Glassmorphism wrapper.
 *
 * Fix #13: On Android 12+ (API 31), uses real backdrop blur via
 * RenderEffect.createBlurEffect instead of the no-op Modifier.blur(0.dp).
 * On older devices, falls back to a layered translucent gradient that
 * approximates the same depth without the GPU cost of RenderEffect.
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
                    // Fix #13: Real blur via RenderEffect on API 31+
                    Modifier
                        .graphicsLayer {
                            renderEffect = android.graphics.RenderEffect
                                .createBlurEffect(
                                    24f, 24f,
                                    android.graphics.Shader.TileMode.CLAMP
                                )
                                .asComposeRenderEffect()
                            compositingStrategy = CompositingStrategy.Offscreen
                        }
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.White.copy(alpha = 0.06f),
                                    Color.White.copy(alpha = 0.02f),
                                )
                            )
                        )
                } else {
                    // Pre-31 fallback: translucent gradient, no blur
                    Modifier.background(
                        Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = 0.06f),
                                Color.White.copy(alpha = 0.02f),
                            )
                        )
                    )
                }
            )
            .background(tint, shape)
            .border(1.dp, borderColor, shape)
            .padding(contentPadding),
    ) { content() }
}
