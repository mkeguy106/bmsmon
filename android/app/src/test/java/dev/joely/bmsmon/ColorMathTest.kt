package dev.joely.bmsmon

import dev.joely.bmsmon.ui.theme.hexOf
import dev.joely.bmsmon.ui.theme.hsvToRgb
import dev.joely.bmsmon.ui.theme.parseHexColor
import dev.joely.bmsmon.ui.theme.rgbToHsv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorMathTest {

    // --- parseHexColor ---

    @Test fun parsesSixDigitWithAndWithoutHash() {
        assertEquals(0xFF8C1A, parseHexColor("#FF8C1A"))
        assertEquals(0xFF8C1A, parseHexColor("ff8c1a"))
        assertEquals(0xFF8C1A, parseHexColor("  #Ff8C1a  "))
    }

    @Test fun parsesThreeDigitShorthand() {
        assertEquals(0x00AAFF, parseHexColor("#0af"))
        assertEquals(0xFFFFFF, parseHexColor("fff"))
        assertEquals(0x000000, parseHexColor("#000"))
    }

    @Test fun rejectsBadInput() {
        assertNull(parseHexColor(""))
        assertNull(parseHexColor("#"))
        assertNull(parseHexColor("xyz"))
        assertNull(parseHexColor("#12"))        // 2 digits
        assertNull(parseHexColor("#12345"))     // 5 digits
        assertNull(parseHexColor("#GG0011"))    // non-hex
        assertNull(parseHexColor("#12345678"))  // alpha not accepted
    }

    @Test fun dropsAlphaOnParse() {
        // A leading alpha byte isn't valid input, but a 6-digit value is masked to RGB regardless.
        assertEquals(0x8C1A05, parseHexColor("8C1A05"))
    }

    // --- hexOf ---

    @Test fun formatsUppercaseSixDigitAndDropsAlpha() {
        assertEquals("#FF8C1A", hexOf(0xFF8C1A))
        assertEquals("#FF8C1A", hexOf(0xAB.shl(24) or 0xFF8C1A))  // alpha stripped
        assertEquals("#000000", hexOf(0))
    }

    @Test fun parseFormatRoundTrips() {
        for (s in listOf("#FF8C1A", "#000000", "#FFFFFF", "#3E86C9", "#12AB34")) {
            assertEquals(s, hexOf(parseHexColor(s)!!))
        }
    }

    // --- rgbToHsv / hsvToRgb ---

    @Test fun knownHsvAnchors() {
        assertArrayHsv(floatArrayOf(0f, 0f, 0f), rgbToHsv(0x000000))       // black
        assertArrayHsv(floatArrayOf(0f, 0f, 1f), rgbToHsv(0xFFFFFF))       // white
        assertArrayHsv(floatArrayOf(0f, 1f, 1f), rgbToHsv(0xFF0000))       // red
        assertArrayHsv(floatArrayOf(120f, 1f, 1f), rgbToHsv(0x00FF00))     // green
        assertArrayHsv(floatArrayOf(240f, 1f, 1f), rgbToHsv(0x0000FF))     // blue
    }

    @Test fun greyReportsZeroHueAndSaturation() {
        val hsv = rgbToHsv(0x808080)
        assertEquals(0f, hsv[0], 0.001f)
        assertEquals(0f, hsv[1], 0.001f)
        assertTrue(hsv[2] > 0.49f && hsv[2] < 0.51f)
    }

    @Test fun hsvToRgbRoundTripsWithinOneStep() {
        for (rgb in listOf(0xFF8C1A, 0x3E86C9, 0x8B6BC9, 0x12AB34, 0xC0FFEE, 0x102030)) {
            val hsv = rgbToHsv(rgb)
            val back = hsvToRgb(hsv[0], hsv[1], hsv[2])
            assertChannelsClose(rgb, back)
        }
    }

    @Test fun hsvToRgbClampsAndWrapsHue() {
        assertEquals(0xFF0000, hsvToRgb(360f, 1f, 1f))   // 360 wraps to 0 = red
        assertEquals(0xFF0000, hsvToRgb(-360f, 1f, 1f))  // negative wraps
        assertEquals(0xFFFFFF, hsvToRgb(0f, -1f, 2f))    // sat/value clamped → white
    }

    private fun assertArrayHsv(expected: FloatArray, actual: FloatArray) {
        assertEquals(expected[0], actual[0], 0.5f)
        assertEquals(expected[1], actual[1], 0.005f)
        assertEquals(expected[2], actual[2], 0.005f)
    }

    private fun assertChannelsClose(a: Int, b: Int) {
        for (shift in intArrayOf(16, 8, 0)) {
            val ca = (a shr shift) and 0xFF
            val cb = (b shr shift) and 0xFF
            assertTrue("channel@$shift $ca vs $cb", kotlin.math.abs(ca - cb) <= 1)
        }
    }
}
