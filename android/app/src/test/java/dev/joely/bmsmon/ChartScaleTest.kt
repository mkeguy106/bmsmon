package dev.joely.bmsmon

import dev.joely.bmsmon.ui.charts.normalize
import org.junit.Assert.assertEquals
import org.junit.Test

class ChartScaleTest {
    @Test fun mapsRangeToZeroOne() {
        val out = normalize(listOf(0f, 5f, 10f), min = 0f, max = 10f)
        assertEquals(0f, out[0], 0.001f)
        assertEquals(0.5f, out[1], 0.001f)
        assertEquals(1f, out[2], 0.001f)
    }

    @Test fun flatRangeMapsToMidline() {
        val out = normalize(listOf(7f, 7f), min = 7f, max = 7f)
        assertEquals(0.5f, out[0], 0.001f)
        assertEquals(0.5f, out[1], 0.001f)
    }
}
