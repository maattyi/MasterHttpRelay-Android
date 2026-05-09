package com.velum.vpn.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import com.velum.vpn.ui.theme.NeonGreen
import kotlin.math.max

/**
 * Smooth Bezier-curve bandwidth graph.
 *
 * Strokes a 3.5 dp neon-green curve and fills underneath with a
 * vertical gradient (neon green → transparent). Catmull-Rom-style
 * smoothing prevents the jagged look of straight polylines.
 */
@Composable
fun BezierBandwidthChart(
    series: List<Pair<Long, Double>>,
    modifier: Modifier = Modifier,
    minScale: Double = 1024.0,
) {
    Canvas(modifier = modifier) {
        if (series.size < 2) return@Canvas
        val w = size.width
        val h = size.height
        val n = max(60, series.size)

        // Vertical scale
        val maxV = (series.maxOf { it.second } * 1.25).coerceAtLeast(minScale)

        // Build smoothed path (cubic bezier through control points)
        val pts = series.mapIndexed { i, (_, v) ->
            val x = i / (n - 1).toFloat() * w
            val y = h - (v / maxV).toFloat().coerceIn(0f, 1f) * h
            x to y
        }

        val curve = Path()
        curve.moveTo(pts.first().first, pts.first().second)
        for (i in 0 until pts.size - 1) {
            val (x0, y0) = pts[i]
            val (x1, y1) = pts[i + 1]
            val midX = (x0 + x1) / 2f
            curve.cubicTo(
                midX, y0,        // c1: same y as start, x = midpoint
                midX, y1,        // c2: same y as end,   x = midpoint
                x1, y1,
            )
        }

        // Filled area (gradient)
        val fillPath = Path().apply {
            addPath(curve)
            lineTo(pts.last().first, h)
            lineTo(pts.first().first, h)
            close()
        }
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(
                    NeonGreen.copy(alpha = 0.55f),
                    NeonGreen.copy(alpha = 0.18f),
                    Color.Transparent,
                ),
            ),
        )

        // Outline stroke
        drawPath(
            path = curve,
            color = NeonGreen,
            style = Stroke(
                width = 3.5.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )

        // Subtle dashed mid-line for a grid hint
        drawLine(
            color = Color.White.copy(alpha = 0.05f),
            start = androidx.compose.ui.geometry.Offset(0f, h / 2f),
            end = androidx.compose.ui.geometry.Offset(w, h / 2f),
            strokeWidth = 1f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 12f), 0f),
        )
    }
}

private val Number.dp: androidx.compose.ui.unit.Dp
    get() = androidx.compose.ui.unit.Dp(this.toFloat())
