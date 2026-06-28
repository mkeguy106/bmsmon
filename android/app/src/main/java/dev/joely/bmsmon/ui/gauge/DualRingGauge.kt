package dev.joely.bmsmon.ui.gauge

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import dev.joely.bmsmon.model.POWER_RING_FULL_W
import dev.joely.bmsmon.ui.theme.RegenGreen
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Two-ring battery gauge — a 1:1 port of the prototype's `gauge()`:
 *  - Outer = 16-segment SOC donut (radius 84, stroke 15, 3° gaps, clockwise from 12 o'clock).
 *  - Inner = wattage arc (radius 60, stroke 7.5, round cap), full at ~80 W — weighted so the
 *    live charge/discharge rate reads at a glance while staying subordinate to the SOC ring.
 * Geometry is defined in a 200x200 design space and scaled to the actual size.
 */
@Composable
fun DualRingGauge(
    soc: Float,
    powerW: Float,
    accent: Color,
    power: Color,
    segEmpty: Color,
    innerTrack: Color,
    modifier: Modifier = Modifier,
    regen: Boolean = false,
) {
    val n = 16
    val filled = (soc.coerceIn(0f, 100f) / 100f * n).roundToInt()
    val gapDeg = 3f
    val segStep = 360f / n

    Canvas(modifier = modifier) {
        val scale = min(size.width, size.height) / 200f
        val center = Offset(size.width / 2f, size.height / 2f)

        // --- Outer ring: 16 SOC segments ---
        val ringR = 84f * scale
        val ringW = 15f * scale
        val ringTopLeft = Offset(center.x - ringR, center.y - ringR)
        val ringSize = Size(ringR * 2f, ringR * 2f)
        for (i in 0 until n) {
            // design angles measured from 12 o'clock, clockwise; drawArc 0° = 3 o'clock => -90 offset
            val start = i * segStep + gapDeg / 2f - 90f
            val sweep = segStep - gapDeg
            drawArc(
                color = if (i < filled) accent else segEmpty,
                startAngle = start,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = ringTopLeft,
                size = ringSize,
                style = Stroke(width = ringW, cap = StrokeCap.Butt),
            )
        }

        // --- Inner ring: wattage arc over a full-circle track ---
        val innerR = 60f * scale
        val innerW = 7.5f * scale
        val innerTopLeft = Offset(center.x - innerR, center.y - innerR)
        val innerSize = Size(innerR * 2f, innerR * 2f)
        val frac = (powerW / POWER_RING_FULL_W).coerceIn(0f, 1f)
        drawArc(
            color = innerTrack,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = innerTopLeft,
            size = innerSize,
            style = Stroke(width = innerW, cap = StrokeCap.Butt),
        )
        if (frac > 0f) {
            // Regen (current dumped in) sweeps the OTHER way, in green.
            drawArc(
                color = if (regen) RegenGreen else power.copy(alpha = 0.9f),
                startAngle = -90f,
                sweepAngle = (if (regen) -360f else 360f) * frac,
                useCenter = false,
                topLeft = innerTopLeft,
                size = innerSize,
                style = Stroke(width = innerW, cap = StrokeCap.Round),
            )
        }
    }
}
