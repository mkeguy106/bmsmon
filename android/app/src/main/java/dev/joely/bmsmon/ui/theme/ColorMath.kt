package dev.joely.bmsmon.ui.theme

/**
 * Pure color math for the custom color picker — hex parsing/formatting and RGB↔HSV conversion,
 * all on plain `Int` (0xRRGGBB, alpha ignored) and `Float` so it's unit-testable without Android
 * or Compose. The Compose layer converts `Int ↔ androidx.compose.ui.graphics.Color` at the edges.
 */

/**
 * Parse a user-typed hex color into a 0xRRGGBB int, or null if it isn't a valid color.
 * Accepts an optional leading `#`, surrounding whitespace, any case, and both shorthand `RGB`
 * (each nibble doubled, e.g. `#0af` → `#00aaff`) and full `RRGGBB`. Alpha is not accepted.
 */
fun parseHexColor(input: String): Int? {
    val h = input.trim().removePrefix("#").ifEmpty { return null }
    if (!h.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return null
    val rrggbb = when (h.length) {
        3 -> buildString { h.forEach { append(it); append(it) } }  // RGB → RRGGBB
        6 -> h
        else -> return null
    }
    return rrggbb.toInt(16) and 0xFFFFFF
}

/** Format a 0xAARRGGBB (or 0xRRGGBB) int as an uppercase `#RRGGBB` string (alpha dropped). */
fun hexOf(rgb: Int): String = "#%06X".format(rgb and 0xFFFFFF)

/**
 * Convert 0xRRGGBB to HSV: hue 0..360, saturation 0..1, value 0..1. For greys (saturation 0)
 * hue is reported as 0. Mirrors the standard hexcone conversion (no Android dependency).
 */
fun rgbToHsv(rgb: Int): FloatArray {
    val r = ((rgb shr 16) and 0xFF) / 255f
    val g = ((rgb shr 8) and 0xFF) / 255f
    val b = (rgb and 0xFF) / 255f
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min
    val hue = when {
        delta == 0f -> 0f
        max == r -> 60f * (((g - b) / delta) % 6f)
        max == g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }.let { if (it < 0f) it + 360f else it }
    val sat = if (max == 0f) 0f else delta / max
    return floatArrayOf(hue, sat, max)
}

/** Convert HSV (hue 0..360, sat/value 0..1, each clamped) to a 0xRRGGBB int. */
fun hsvToRgb(hue: Float, sat: Float, value: Float): Int {
    val h = ((hue % 360f) + 360f) % 360f
    val s = sat.coerceIn(0f, 1f)
    val v = value.coerceIn(0f, 1f)
    val c = v * s
    val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
    val m = v - c
    val (r1, g1, b1) = when {
        h < 60f -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    fun ch(f: Float) = ((f + m) * 255f).roundToIntSafe() and 0xFF
    return (ch(r1) shl 16) or (ch(g1) shl 8) or ch(b1)
}

/** Round-half-up to Int, clamping NaN/Inf to 0 — JSON/format safe and dependency-free. */
private fun Float.roundToIntSafe(): Int = if (isFinite()) kotlin.math.round(this).toInt() else 0
