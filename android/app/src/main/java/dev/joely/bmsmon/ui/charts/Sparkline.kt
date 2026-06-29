package dev.joely.bmsmon.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * A bare mini area+line chart (no axes/labels) — the in-app port of the prototype's `spark`, used in
 * the Group-health expand rows. Nulls render as gaps. Auto-scales to its own min/max with headroom.
 */
@Composable
fun Sparkline(values: List<Float?>, color: Color, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    fun dp(v: Float) = with(density) { v.dp.toPx() }
    Canvas(modifier) {
        val present = values.filterNotNull()
        if (present.size < 2) return@Canvas
        val mn = present.min(); val mx = present.max()
        val pad = (mx - mn) * 0.14f + 1e-6f
        val lo = mn - pad; val hi = mx + pad
        val pl = dp(2f)
        val w = size.width - pl * 2; val h = size.height - pl * 2
        val n = values.size
        fun fx(i: Int) = pl + if (n <= 1) w / 2f else i * (w / (n - 1))
        fun fy(v: Float) = pl + (1f - (v - lo) / ((hi - lo).takeIf { it != 0f } ?: 1f)) * h

        val ypx = values.map { it?.let { v -> fy(v) } }
        // area
        val area = androidx.compose.ui.graphics.Path()
        run {
            var i = 0
            while (i < n) {
                if (ypx[i] == null) { i++; continue }
                var j = i
                area.moveTo(fx(i), pl + h)
                while (j < n && ypx[j] != null) { area.lineTo(fx(j), ypx[j]!!); j++ }
                area.lineTo(fx(j - 1), pl + h); area.close(); i = j
            }
        }
        drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.22f), color.copy(alpha = 0f)), startY = pl, endY = pl + h))
        // line
        val line = androidx.compose.ui.graphics.Path()
        var pen = false
        for (i in 0 until n) {
            val y = ypx[i]
            if (y == null) { pen = false; continue }
            if (!pen) line.moveTo(fx(i), y) else line.lineTo(fx(i), y)
            pen = true
        }
        drawPath(line, color = color, style = Stroke(width = dp(1.6f)))
    }
}
