package dev.joely.bmsmon.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.joely.bmsmon.data.ScatterPoint
import dev.joely.bmsmon.ui.theme.Bm
import dev.joely.bmsmon.ui.theme.MonoFont
import dev.joely.bmsmon.ui.theme.SansFont
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The V–I operating cloud: every sample as a faint dot (discharge in `power`, charge/regen in
 * `regen`, by current sign) with the effective-resistance **fit line** `V = OCV + (R/1000)·I` drawn
 * across the current range. Static (no scrubbing) — it's the evidence the headline R comes from.
 * In-app port of the prototype's `scatterChart`.
 */
@Composable
fun ScatterPlot(
    points: List<ScatterPoint>,
    rMohm: Float,
    ocv: Float,
    modifier: Modifier = Modifier,
    hideDischarge: Boolean = false,
    hideCharge: Boolean = false,
) {
    val c = Bm.colors
    val accent = Bm.accent
    val power = Bm.power
    val tm = rememberTextMeasurer()
    val density = LocalDensity.current
    fun dp(v: Float) = with(density) { v.dp.toPx() }

    Canvas(modifier) {
        if (points.isEmpty()) return@Canvas
        val padL = dp(38f); val padR = dp(12f); val padT = dp(12f); val padB = dp(26f)
        val w = size.width; val h = size.height
        val x0 = padL; val x1 = w - padR; val y0 = padT; val y1 = h - padB
        val plotW = x1 - x0; val plotH = y1 - y0

        val xsRaw = points.map { it.currentA }
        val ysRaw = points.map { it.voltageV }
        val xmin = floor(xsRaw.min() / 10f) * 10f
        val xmax = ceil(xsRaw.max() / 10f) * 10f
        val ymin = ysRaw.min() - 0.03f
        val ymax = ysRaw.max() + 0.03f
        fun fx(v: Float) = x0 + (v - xmin) / ((xmax - xmin).takeIf { it != 0f } ?: 1f) * plotW
        fun fy(v: Float) = y0 + (1f - (v - ymin) / ((ymax - ymin).takeIf { it != 0f } ?: 1f)) * plotH

        // y gridlines + labels
        for (t in niceTicks(ymin, ymax, 3)) {
            val y = fy(t)
            drawLine(c.grid, Offset(x0, y), Offset(x1, y), strokeWidth = dp(1f))
            drawAxisText(tm, "%.1f".format(t), x0 - dp(4f), y, c.text3, 8.5f, MonoFont, TextAnchor.End)
        }
        // x gridlines + labels
        for (t in niceTicks(xmin, xmax, 4)) {
            val x = fx(t)
            drawLine(c.grid, Offset(x, y0), Offset(x, y1), strokeWidth = dp(1f))
            drawAxisText(tm, t.toInt().toString(), x, y1 + dp(9f), c.text3, 8.5f, MonoFont, TextAnchor.Middle)
        }
        // zero-current reference
        if (xmin < 0f && xmax > 0f) {
            val xz = fx(0f)
            drawLine(c.text3.copy(alpha = 0.6f), Offset(xz, y0), Offset(xz, y1), strokeWidth = dp(1f),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(dp(3f), dp(3f))))
        }
        // points
        for (p in points) {
            val discharge = p.currentA < 0f
            if (discharge && hideDischarge) continue
            if (!discharge && hideCharge) continue
            drawCircle(if (discharge) power else c.regen, dp(1.5f), Offset(fx(p.currentA), fy(p.voltageV)), alpha = 0.42f)
        }
        // fit line V = ocv + (R/1000)*I
        val rOhm = rMohm / 1000f
        drawLine(accent, Offset(fx(xmin), fy(ocv + rOhm * xmin)), Offset(fx(xmax), fy(ocv + rOhm * xmax)), strokeWidth = dp(2f))
        // labels
        drawAxisText(tm, "slope ≈ ${trimR(rMohm)} mΩ", x1, y0 + dp(8f), accent, 9f, SansFont, TextAnchor.End, FontWeight.SemiBold)
        drawAxisText(tm, "current (A) — discharge ◂ 0 ▸ charge", x0 + plotW / 2f, y1 + dp(20f), c.text2, 9f, SansFont, TextAnchor.Middle)
    }
}

private fun trimR(r: Float): String = if (r % 1f == 0f) r.toInt().toString() else "%.1f".format(r)
