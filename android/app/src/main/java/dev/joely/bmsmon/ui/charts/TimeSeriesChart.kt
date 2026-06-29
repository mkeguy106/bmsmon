package dev.joely.bmsmon.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.joely.bmsmon.ui.theme.Bm
import dev.joely.bmsmon.ui.theme.MonoFont
import dev.joely.bmsmon.ui.theme.SansFont

enum class TextAnchor { Start, Middle, End }

/** Draw [text] with its vertical center at [cy] and horizontal anchor at [x]. */
internal fun DrawScope.drawAxisText(
    tm: TextMeasurer, text: String, x: Float, cy: Float, color: Color,
    sizeSp: Float, font: FontFamily, anchor: TextAnchor, weight: FontWeight = FontWeight.Normal,
) {
    val res = tm.measure(AnnotatedString(text), TextStyle(fontSize = sizeSp.sp, color = color, fontFamily = font, fontWeight = weight))
    val w = res.size.width
    val tx = when (anchor) {
        TextAnchor.End -> x - w
        TextAnchor.Middle -> x - w / 2f
        TextAnchor.Start -> x
    }
    drawText(res, topLeft = Offset(tx, cy - res.size.height / 2f))
}

private fun buildLine(xpx: List<Float>, ypx: List<Float?>): Path {
    val p = Path()
    var pen = false
    for (i in xpx.indices) {
        val y = ypx[i]
        if (y == null) { pen = false; continue }
        if (!pen) p.moveTo(xpx[i], y) else p.lineTo(xpx[i], y)
        pen = true
    }
    return p
}

private fun buildArea(xpx: List<Float>, ypx: List<Float?>, baseY: Float): Path {
    val p = Path()
    var i = 0
    val n = xpx.size
    while (i < n) {
        if (ypx[i] == null) { i++; continue }
        var j = i
        p.moveTo(xpx[i], baseY)
        while (j < n && ypx[j] != null) { p.lineTo(xpx[j], ypx[j]!!); j++ }
        p.lineTo(xpx[j - 1], baseY)
        p.close()
        i = j
    }
    return p
}

/**
 * Multi-series, optionally multi-band time-series chart with an interactive scrub crosshair &
 * floating tooltip — the in-app Compose port of the prototype's `tsChart`. All series share one
 * x-axis ([xs] values, [xLabels] for ticks/tooltip); each binds to a [ChartAxis] which selects a
 * vertical band (for the stacked session timeline). Null series values render as gaps. Hidden series
 * (legend-toggled, by key in [hidden]) are skipped in both drawing and the tooltip.
 */
@Composable
fun TimeSeriesChart(
    xs: List<Float>,
    xLabels: List<String>,
    xTickIndices: List<Int>,
    axes: List<ChartAxis>,
    series: List<ChartSeries>,
    modifier: Modifier = Modifier,
    bands: Int = 1,
    hidden: Set<String> = emptySet(),
    baseline: Baseline? = null,
    markers: List<ChartMarker> = emptyList(),
    scrubEnabled: Boolean = true,
    padLeftDp: Float = 38f,
    padRightDp: Float = 12f,
    padTopDp: Float = 12f,
    padBottomDp: Float = 18f,
    bandGapDp: Float = 12f,
) {
    val c = Bm.colors
    val power = Bm.power
    val tm = rememberTextMeasurer()
    val density = LocalDensity.current
    var scrub by remember { mutableStateOf<Int?>(null) }
    var sizePx by remember { mutableStateOf(IntOffset(0, 0)) }
    var tipW by remember { mutableStateOf(0) }

    fun dp(v: Float) = with(density) { v.dp.toPx() }

    Box(modifier) {
        Canvas(
            Modifier
                .matchParentSize()
                .onSizeChanged { sizePx = IntOffset(it.width, it.height) }
                .then(
                    if (scrubEnabled && xs.size > 1) Modifier.pointerInput(xs, axes) {
                        val padL = dp(padLeftDp); val padR = dp(padRightDp)
                        fun set(x: Float) {
                            val plotW = size.width - padL - padR
                            val xpx = xPixels(xs, padL, plotW)
                            scrub = nearestIndex(xpx, x)
                        }
                        awaitPointerEventScope {
                            while (true) {
                                val down = awaitPointerEvent()
                                val pos = down.changes.firstOrNull()?.position ?: continue
                                set(pos.x)
                                if (down.changes.all { it.changedToUp() }) scrub = null
                            }
                        }
                    } else Modifier,
                ),
        ) {
            if (xs.isEmpty()) return@Canvas
            val padL = dp(padLeftDp); val padR = dp(padRightDp)
            val padTp = dp(padTopDp); val padB = dp(padBottomDp)
            val gap = dp(bandGapDp)
            val w = size.width; val h = size.height
            val x0 = padL; val x1 = w - padR
            val plotW = w - padL - padR
            val fullTop = padTp; val fullBot = h - padB
            val xpx = xPixels(xs, x0, plotW)

            fun bandOf(axis: ChartAxis) = bandRange(axis.band, bands, fullTop, fullBot, gap)

            // gridlines + axis tick labels + titles
            for (a in axes) {
                val (bTop, bBot) = bandOf(a)
                for (t in niceTicks(a.min, a.max, a.ticks)) {
                    val y = mapY(t, a.min, a.max, bTop, bBot)
                    drawLine(c.grid, Offset(x0, y), Offset(x1, y), strokeWidth = dp(1f))
                    val lx = if (a.rightSide) x1 + dp(4f) else x0 - dp(4f)
                    drawAxisText(
                        tm, a.fmt(t), lx, y, if (a.rightSide) power else c.text3, 8.5f, MonoFont,
                        if (a.rightSide) TextAnchor.Start else TextAnchor.End,
                    )
                }
                a.title?.let {
                    drawAxisText(tm, it, x0, bTop - dp(8f), c.text2, 9.5f, SansFont, TextAnchor.Start, FontWeight.SemiBold)
                }
            }

            // baseline (median R)
            baseline?.let { bl ->
                val a = axes.first { it.id == bl.axisId }
                val (bTop, bBot) = bandOf(a)
                val y = mapY(bl.value, a.min, a.max, bTop, bBot)
                drawLine(c.text3.copy(alpha = 0.5f), Offset(x0, y), Offset(x1, y), strokeWidth = dp(1f))
            }

            // bottom axis line
            drawLine(c.border, Offset(x0, fullBot), Offset(x1, fullBot), strokeWidth = dp(1f))

            // series
            for (s in series) {
                if (s.key in hidden) continue
                val a = axes.first { it.id == s.axisId }
                val (bTop, bBot) = bandOf(a)
                val ypx = s.values.map { v -> v?.let { mapY(it, a.min, a.max, bTop, bBot) } }
                if (s.area) {
                    val area = buildArea(xpx, ypx, bBot)
                    drawPath(
                        area,
                        Brush.verticalGradient(
                            colors = listOf(s.color.copy(alpha = s.fillAlpha), s.color.copy(alpha = 0f)),
                            startY = bTop, endY = bBot,
                        ),
                    )
                }
                drawPath(buildLine(xpx, ypx), color = s.color, style = Stroke(width = dp(s.widthDp)))
                if (s.dots) ypx.forEachIndexed { i, y -> if (y != null) drawCircle(s.color, dp(s.dotR), Offset(xpx[i], y)) }
            }

            // markers (link-loss): dashed vertical + top dot
            val dash = PathEffect.dashPathEffect(floatArrayOf(dp(3f), dp(3f)))
            for (m in markers) {
                if (m.index !in xpx.indices) continue
                val px = xpx[m.index]
                drawLine(m.color.copy(alpha = 0.6f), Offset(px, fullTop), Offset(px, fullBot), strokeWidth = dp(1f), pathEffect = dash)
                drawCircle(m.color, dp(3f), Offset(px, fullTop + dp(3.5f)))
            }

            // x-axis tick labels
            for (i in xTickIndices) {
                if (i in xpx.indices) drawAxisText(tm, xLabels[i], xpx[i], fullBot + dp(9f), c.text3, 8.5f, MonoFont, TextAnchor.Middle)
            }

            // scrub crosshair + per-series dots
            val si = scrub
            if (si != null && si in xpx.indices) {
                val px = xpx[si]
                drawLine(c.text2, Offset(px, fullTop), Offset(px, fullBot), strokeWidth = dp(1f), pathEffect = PathEffect.dashPathEffect(floatArrayOf(dp(4f), dp(3f))))
                for (s in series) {
                    if (s.key in hidden) continue
                    val v = s.values[si] ?: continue
                    val a = axes.first { it.id == s.axisId }
                    val (bTop, bBot) = bandOf(a)
                    val y = mapY(v, a.min, a.max, bTop, bBot)
                    drawCircle(s.color, dp(3.6f), Offset(px, y))
                    drawCircle(c.bg, dp(3.6f), Offset(px, y), style = Stroke(width = dp(1.5f)))
                }
            }
        }

        // floating tooltip overlay
        val si = scrub
        if (si != null && si in xs.indices && sizePx.x > 0) {
            val padL = dp(padLeftDp); val padR = dp(padRightDp)
            val plotW = sizePx.x - padL - padR
            val px = xPixels(xs, padL, plotW).getOrNull(si) ?: 0f
            val rightHalf = px > sizePx.x * 0.55f
            val left = if (rightHalf) (px - tipW - dp(12f)) else (px + dp(12f))
            val visible = series.filter { it.key !in hidden && it.values.getOrNull(si) != null }
            Box(
                Modifier
                    .onSizeChanged { tipW = it.width }
                    .offset(left.coerceAtLeast(dp(2f)), dp(4f))
                    .shadow(6.dp, RoundedCornerShape(8.dp))
                    .background(c.card, RoundedCornerShape(8.dp))
                    .border(1.dp, c.border, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                androidx.compose.foundation.layout.Column {
                    androidx.compose.material3.Text(
                        xLabels.getOrElse(si) { "" }, color = c.text2, fontSize = 10.sp, fontFamily = SansFont,
                    )
                    visible.forEach { s ->
                        androidx.compose.foundation.layout.Row(
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp),
                        ) {
                            androidx.compose.material3.Text(s.label, color = s.color, fontSize = 10.5f.sp, fontFamily = SansFont)
                            androidx.compose.material3.Text(
                                s.fmt(s.values[si]!!), color = c.text, fontSize = 10.5f.sp, fontFamily = MonoFont,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A modifier offset that takes pixel floats (rounds to whole px). */
private fun Modifier.offset(xPx: Float, yPx: Float): Modifier =
    this.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) {
            placeable.place(IntOffset(xPx.toInt(), yPx.toInt()))
        }
    }

private fun androidx.compose.ui.input.pointer.PointerInputChange.changedToUp(): Boolean = !pressed
