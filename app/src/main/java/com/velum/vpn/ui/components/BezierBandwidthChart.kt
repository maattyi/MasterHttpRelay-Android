package com.velum.vpn.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import com.velum.vpn.ui.theme.NeonGreen
import kotlin.math.max

@Composable
fun BezierBandwidthChart(
    series: List<Pair<Long, Double>>,
    modifier: Modifier = Modifier,
    minScale: Double = 1024.0,
) {
    var maxV by remember { mutableDoubleStateOf(minScale) }
    
    LaunchedEffect(series) {
        if (series.isNotEmpty()) {
            val currentMax = (series.maxOf { it.second } * 1.25).coerceAtLeast(minScale)
            maxV = maxV + (currentMax - maxV) * 0.2
        }
    }

    Canvas(modifier = modifier) {
        if (series.size < 2) return@Canvas
        val w = size.width
        val h = size.height
        val n = max(60, series.size)
        val safeMax = maxV.coerceAtLeast(1.0)

        val pts = series.mapIndexed { i, (_, v) ->
            val x = (i.toFloat() / (n - 1).toFloat()) * w
            val normalizedY = (v / safeMax).toFloat().coerceIn(0f, 1f)
            val y = h - (normalizedY * h)
            x to y
        }

        val curve = Path()
        curve.moveTo(pts.first().first, pts.first().second)
        for (i in 0 until pts.size - 1) {
            val (x0, y0) = pts[i]
            val (x1, y1) = pts[i + 1]
            val midX = (x0 + x1) / 2f
            curve.cubicTo(midX, y0, midX, y1, x1, y1)
        }

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
                )
            )
        )

        drawPath(
            path = curve,
            color = NeonGreen,
            style = Stroke(
                width = 3.5f * density,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            )
        )

        drawLine(
            color = Color.White.copy(alpha = 0.05f),
            start = Offset(0f, h / 2f),
            end = Offset(w, h / 2f),
            strokeWidth = 1f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 12f), 0f),
        )
    }
}
