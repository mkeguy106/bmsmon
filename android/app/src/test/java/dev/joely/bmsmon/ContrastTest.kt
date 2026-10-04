package dev.joely.bmsmon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.joely.bmsmon.ui.theme.DarkBmColors
import dev.joely.bmsmon.ui.theme.DefaultAccent
import dev.joely.bmsmon.ui.theme.DefaultPower
import dev.joely.bmsmon.ui.theme.LightBmColors
import dev.joely.bmsmon.ui.theme.MIN_TEXT_CONTRAST
import dev.joely.bmsmon.ui.theme.PowerSwatches
import dev.joely.bmsmon.ui.theme.ThemeSwatches
import dev.joely.bmsmon.ui.theme.contrastRatio
import dev.joely.bmsmon.ui.theme.readableColors
import dev.joely.bmsmon.ui.theme.readableOn
import dev.joely.bmsmon.ui.theme.rgbToHsv
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI-21: in the light theme — which Auto mode switches to in bright sun, the hardest reading
 * condition — the stage number read at 2.57:1. The surfaces below are Theme.kt's bg/card/card2/
 * inputBg for each theme (the runtime reads the live tokens; these pin the algorithm on today's palette).
 */
class ContrastTest {
    private val light = listOf(0xF3F3F5, 0xFFFFFF, 0xFAFAFB, 0xF0F0F2)
    private val dark = listOf(0x121212, 0x161616, 0x151515, 0x1D1D1D)
    private val accent = 0xE67E22
    private val warn = 0xE2B01E
    private val critical = 0xE5342B

    private fun passesAll(c: Int, surfaces: List<Int>) = surfaces.all { contrastRatio(c, it) >= MIN_TEXT_CONTRAST }
    private fun hue(c: Int) = rgbToHsv(c)[0]

    @Test fun ratiosMatchWcag() {
        assertEquals(21.0, contrastRatio(0x000000, 0xFFFFFF), 0.01)
        assertEquals(1.0, contrastRatio(accent, accent), 0.0001)
        assertEquals(2.57, contrastRatio(accent, 0xF3F3F5), 0.05)   // the finding, pinned
    }

    @Test fun theAccentBecomesReadableOnLightSurfacesAndKeepsItsHue() {
        val r = readableOn(accent, light)
        assertTrue(passesAll(r, light))
        assertTrue("hue ${hue(r)} vs ${hue(accent)}", abs(hue(r) - hue(accent)) < 3f)
    }

    @Test fun anAlreadyReadableColorIsUntouched() {
        assertEquals(accent, readableOn(accent, dark))
    }

    @Test fun severityColorsBecomeReadableInBothThemes() {
        for (c in listOf(warn, critical)) {
            assertTrue(passesAll(readableOn(c, light), light))
            assertTrue(passesAll(readableOn(c, dark), dark))
        }
    }

    // Review focus 3: the color picker accepts anything; text drawn in it must still read.
    @Test fun aPaleCustomAccentIsReadableInTheLightTheme() {
        assertTrue(passesAll(readableOn(0xFFFF66, light), light))
    }

    @Test fun aVeryDarkCustomAccentIsReadableInTheDarkTheme() {
        assertTrue(passesAll(readableOn(0x202040, dark), dark))
    }

    @Test fun theStatLabelTokenAlreadyPasses() {
        assertTrue(passesAll(0x5F5F66, light))   // LightBmColors.text2
        assertTrue(passesAll(0x8A8A8A, dark))    // DarkBmColors.text2
    }

    // The wiring, through the live theme tokens rather than copies of them: whatever the palette or
    // the picked accent, every text token the theme hands out reads on every surface it sits on.
    @Test fun everyThemeTextTokenIsReadableOnEveryThemeSurface() {
        val accents = ThemeSwatches + PowerSwatches + DefaultAccent + DefaultPower +
            Color(0xFFFFFF66) + Color(0xFF202040) + Color.White + Color.Black
        for (isDark in listOf(true, false)) {
            val t = if (isDark) DarkBmColors else LightBmColors
            val surfaces = listOf(t.bg, t.card, t.card2, t.inputBg).map { it.toArgb() and 0xFFFFFF }
            assertTrue("text2 dark=$isDark", passesAll(t.text2.toArgb() and 0xFFFFFF, surfaces))
            for (a in accents) {
                val r = readableColors(isDark, accent = a, power = a)
                val tokens = listOf("accent" to r.accent, "power" to r.power, "warn" to r.warn, "critical" to r.critical, "good" to r.good)
                for ((name, c) in tokens) {
                    assertEquals("$name is opaque", 1f, c.alpha)
                    assertTrue("$name dark=$isDark accent=${a.toArgb()}", passesAll(c.toArgb() and 0xFFFFFF, surfaces))
                }
            }
        }
    }
}
