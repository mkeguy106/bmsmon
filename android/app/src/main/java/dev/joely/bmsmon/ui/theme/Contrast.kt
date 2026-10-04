package dev.joely.bmsmon.ui.theme

import kotlin.math.pow
import kotlin.math.roundToInt

/** WCAG minimum for normal-size text. Every readout on this chair-mounted display is held to it. */
const val MIN_TEXT_CONTRAST = 4.5

private fun channel(c: Int): Double {
    val s = c / 255.0
    return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
}

/** WCAG relative luminance of 0xRRGGBB (alpha ignored). */
fun relativeLuminance(rgb: Int): Double =
    0.2126 * channel((rgb shr 16) and 0xFF) + 0.7152 * channel((rgb shr 8) and 0xFF) + 0.0722 * channel(rgb and 0xFF)

/** WCAG contrast ratio of two 0xRRGGBB colors, 1.0..21.0. */
fun contrastRatio(a: Int, b: Int): Double {
    val la = relativeLuminance(a and 0xFFFFFF)
    val lb = relativeLuminance(b and 0xFFFFFF)
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

private fun mix(rgb: Int, toward: Int, t: Double): Int {
    fun ch(shift: Int): Int {
        val a = (rgb shr shift) and 0xFF
        val b = (toward shr shift) and 0xFF
        return (a + (b - a) * t).roundToInt().coerceIn(0, 255)
    }
    return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/**
 * [fg] made readable on EVERY one of [surfaces] (UI-21): returned unchanged when it already reaches
 * [minRatio] against all of them, else shifted toward black (light surfaces) or white (dark ones) in
 * 2 % steps until it does. Shifting toward black or white keeps the hue, so the user's accent stays
 * recognisably theirs. Returns 0xRRGGBB.
 */
fun readableOn(fg: Int, surfaces: List<Int>, minRatio: Double = MIN_TEXT_CONTRAST): Int {
    val color = fg and 0xFFFFFF
    fun ok(c: Int) = surfaces.all { contrastRatio(c, it) >= minRatio }
    if (ok(color)) return color
    val toward = if (surfaces.map { relativeLuminance(it and 0xFFFFFF) }.average() > 0.5) 0x000000 else 0xFFFFFF
    for (i in 1..50) {
        val c = mix(color, toward, i / 50.0)
        if (ok(c)) return c
    }
    return toward
}
