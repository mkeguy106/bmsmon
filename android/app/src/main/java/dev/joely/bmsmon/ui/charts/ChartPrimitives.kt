package dev.joely.bmsmon.ui.charts

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * Shared chart building blocks for the redesigned history surfaces — ports of the helpers in the
 * design prototype's chart engine (`tsChart` / `scatterChart` / `spark`). Pure geometry & math; the
 * Compose drawing lives in [TimeSeriesChart], [ScatterPlot] and [Sparkline].
 */

/** One value axis. Several axes can share an x-axis; [band] selects a vertical sub-band (0-based). */
data class ChartAxis(
    val id: String,
    val min: Float,
    val max: Float,
    val band: Int = 0,
    val title: String? = null,
    val ticks: Int = 3,
    val rightSide: Boolean = false,
    val fmt: (Float) -> String = { it.toString() },
)

/** One data series bound to an axis. [values] may contain nulls (rendered as gaps, not zeros). */
data class ChartSeries(
    val key: String,
    val axisId: String,
    val color: Color,
    val values: List<Float?>,
    val area: Boolean = false,
    val fillAlpha: Float = 0.28f,
    val widthDp: Float = 2f,
    val dots: Boolean = false,
    val dotR: Float = 2.4f,
    val label: String = key,
    val fmt: (Float) -> String = { it.toString() },
)

/** A dashed horizontal reference line (e.g. the median-R baseline). */
data class Baseline(val axisId: String, val value: Float)

/** A vertical marker at a sample index (e.g. a BLE link-loss event in the timeline). */
data class ChartMarker(val index: Int, val color: Color)

/**
 * "Nice" axis ticks between [min] and [max], aiming for about [n] of them — the 1/2/5×10ⁿ rounding
 * the prototype uses so labels land on readable values.
 */
fun niceTicks(min: Float, max: Float, n: Int): List<Float> {
    if (!min.isFinite() || !max.isFinite()) return listOf(min)
    if (min == max) return listOf(min)
    val span = max - min
    var step = 10.0.pow(floor(log10(span / n.toDouble()))).toFloat()
    val err = (span / n) / step
    when {
        err >= 7.5 -> step *= 10f
        err >= 3.5 -> step *= 5f
        err >= 1.5 -> step *= 2f
    }
    val start = ceil(min / step) * step
    val out = ArrayList<Float>()
    var v = start
    while (v <= max + step * 0.001f) {
        out.add((v * 10000f).toInt() / 10000f)
        v += step
    }
    return if (out.isEmpty()) listOf(min) else out
}

/** Round [max] up to a tidy ceiling on a [step] grid (e.g. peak watts → next 100). */
fun niceCeil(max: Float, step: Float): Float = if (max <= 0f) step else ceil(max / step) * step

/** Indices spread evenly across [len] points, for x-axis tick labels (≈ the prototype's `spread`). */
fun spreadIndices(count: Int, len: Int): List<Int> {
    if (len <= 1) return listOf(0)
    val out = LinkedHashSet<Int>()
    val k = count.coerceAtLeast(1)
    for (i in 0 until k) out.add(Math.round(i * (len - 1).toFloat() / (k - 1).coerceAtLeast(1)))
    return out.toList()
}

/** Map a value onto a band's pixel y-range (top = max value, bottom = min value). */
fun mapY(v: Float, min: Float, max: Float, bandTop: Float, bandBottom: Float): Float {
    val span = (max - min).let { if (it == 0f) 1f else it }
    return bandTop + (1f - (v - min) / span) * (bandBottom - bandTop)
}

/** Pixel x for each x-value, scaled across the plot width by value (handles uneven spacing). */
fun xPixels(xs: List<Float>, x0: Float, plotW: Float): List<Float> {
    if (xs.isEmpty()) return emptyList()
    val xmin = xs.first()
    val xmax = xs.last()
    return if (xmax == xmin) xs.map { x0 + plotW / 2f }
    else xs.map { x0 + (it - xmin) / (xmax - xmin) * plotW }
}

/** The vertical [top,bottom] pixel range of band [band] of [bands], inside [top]..[bottom]. */
fun bandRange(band: Int, bands: Int, top: Float, bottom: Float, gapPx: Float): Pair<Float, Float> {
    if (bands <= 1) return top to bottom
    val th = (bottom - top - gapPx * (bands - 1)) / bands
    val b0 = top + band * (th + gapPx)
    return b0 to (b0 + th)
}

/** Index of the x-pixel nearest [px] (for scrub snapping). */
fun nearestIndex(xpx: List<Float>, px: Float): Int {
    var best = 0
    var bestD = Float.MAX_VALUE
    for (i in xpx.indices) {
        val d = abs(xpx[i] - px)
        if (d < bestD) { bestD = d; best = i }
    }
    return best
}
