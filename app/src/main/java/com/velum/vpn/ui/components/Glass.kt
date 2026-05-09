package com.velum.vpn.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
 * CRITICAL FIX: The previous implementation attached
 * RenderEffect.createBlurEffect to the SAME Box that hosts the content,
 * which blurred every card's text, icons and chart strokes by 24 px on
 * Android 12+ (the visible "everything is unreadable" bug).
 *
 * Correct glassmorphism applies blur to a *decorative ambient backdrop*
 * that sits BEHIND the content, never to the content itself.
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
            .background(tint, shape)
            .border(1.dp, borderColor, shape),
    ) {
        // Layer 1 — decorative ambient backdrop. Blur ONLY this layer.
        Box(
            modifier = Modifier
                .matchParentSize()
                .then(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        Modifier.graphicsLayer {
                            renderEffect = android.graphics.RenderEffect
                                .createBlurEffect(
                                    24f, 24f,
                                    android.graphics.Shader.TileMode.CLAMP,
                                )
                                .asComposeRenderEffect()
                            compositingStrategy = CompositingStrategy.Offscreen
                        }
                    } else {
                        Modifier
                    },
                )
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.06f),
                            Color.White.copy(alpha = 0.02f),
                        ),
                    ),
                ),
        )

        // Layer 2 — content. Crisp, never blurred.
        Box(modifier = Modifier.padding(contentPadding)) {
            content()
        }
    }
}
