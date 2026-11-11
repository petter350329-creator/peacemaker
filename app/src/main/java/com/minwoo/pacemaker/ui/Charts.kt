package com.minwoo.pacemaker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import com.minwoo.pacemaker.TrackPoint
import kotlin.math.max

/** 거리(x) 대비 속도(y) 그래프 + 목표 속도 점선 */
@Composable
fun SpeedChart(points: List<TrackPoint>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val pts = points.filter { it.kmh > 0 }
        if (pts.size < 2) return@Canvas
        val maxD = pts.last().distM.coerceAtLeast(1.0)
        val targets = pts.map { it.targetKmh }
        val hi = max(pts.maxOf { it.kmh }, targets.max()) * 1.12
        val lo = (minOf(pts.minOf { it.kmh }, targets.min()) * 0.85).coerceAtLeast(0.0)
        val range = (hi - lo).coerceAtLeast(1.0)
        fun x(d: Double) = (d / maxD * size.width).toFloat()
        fun y(v: Double) = (size.height - (v - lo) / range * size.height).toFloat()

        // 가로 보조선
        for (i in 1..3) {
            val yy = size.height * i / 4f
            drawLine(P.Line, Offset(0f, yy), Offset(size.width, yy), strokeWidth = 1f)
        }

        // 속도 영역 채우기
        val area = Path().apply {
            moveTo(x(pts.first().distM), size.height)
            pts.forEach { lineTo(x(it.distM), y(it.kmh)) }
            lineTo(x(pts.last().distM), size.height)
            close()
        }
        drawPath(area, Brush.verticalGradient(listOf(P.Accent.copy(alpha = 0.35f), P.Accent.copy(alpha = 0f))))

        // 속도 선 (목표 대비 색)
        for (i in 1 until pts.size) {
            val a = pts[i - 1]; val b = pts[i]
            drawLine(
                P.speedColor(b.kmh, b.targetKmh),
                Offset(x(a.distM), y(a.kmh)), Offset(x(b.distM), y(b.kmh)),
                strokeWidth = 2.5f * density, cap = StrokeCap.Round,
            )
        }

        // 목표 속도 점선 (계단형)
        val tp = Path()
        tp.moveTo(x(pts.first().distM), y(pts.first().targetKmh))
        for (i in 1 until pts.size) {
            val a = pts[i - 1]; val b = pts[i]
            if (b.targetKmh != a.targetKmh) tp.lineTo(x(b.distM), y(a.targetKmh))
            tp.lineTo(x(b.distM), y(b.targetKmh))
        }
        drawPath(
            tp, P.Text.copy(alpha = 0.7f),
            style = Stroke(width = 1.5f * density, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))),
        )
    }
}
