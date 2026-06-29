package dev.joely.bmsmon.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke

/** Map [values] into 0..1 across [min,max]; a zero-width range maps everything to the midline. */
fun normalize(values: List<Float>, min: Float, max: Float): List<Float> {
    val span = max - min
    if (span <= 0f) return values.map { 0.5f }
    return values.map { ((it - min) / span).coerceIn(0f, 1f) }
}

/**
 * Minimal multi-series line chart over a shared index x-axis (so time gaps collapse: point N is
 * just the Nth session, evenly spaced). Each series is scaled against the combined min/max of all
 * series so they share a y-axis. No external chart deps — matches the hand-rolled gauge style.
 */
@Composable
fun LineChart(series: List<ChartSeries>, modifier: Modifier = Modifier, yLabel: String = "") {
    val all = series.flatMap { it.points }
    val lo = all.minOrNull() ?: 0f
    val hi = all.maxOrNull() ?: 1f
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        series.forEach { s ->
            if (s.points.size < 2) return@forEach
            val ys = normalize(s.points, lo, hi)
            val dx = w / (s.points.size - 1)
            val path = Path()
            ys.forEachIndexed { i, y ->
                val px = i * dx
                val py = h - y * h
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            drawPath(path, color = s.color, style = Stroke(width = 3f))
        }
    }
}

data class ChartSeries(val label: String, val color: Color, val points: List<Float>)
