package dev.joely.bmsmon

import dev.joely.bmsmon.model.EstSource
import dev.joely.bmsmon.model.Telemetry
import dev.joely.bmsmon.ui.bmsCapLine
import dev.joely.bmsmon.ui.bmsListTail
import dev.joely.bmsmon.ui.bmsSocLine
import dev.joely.bmsmon.ui.socText
import org.junit.Assert.assertEquals
import org.junit.Test

/** The estimate / BMS text the three screens share (Task 5). */
class EstimateTextTest {
    private fun tel(soc: Float, cap: Float, bmsSoc: Float? = null, bmsCap: Float? = null, src: EstSource? = null) =
        Telemetry(
            name = "t", soc = soc, powerW = 0f, current = 0f, voltage = 13.2f, capacityAh = cap,
            cellV = 3.3f, temp = 20f, bmsSoc = bmsSoc, bmsCapacityAh = bmsCap, estSource = src,
        )

    private val raw = tel(99f, 111.84f)
    private val counter = tel(92f, 96.84f, bmsSoc = 99f, bmsCap = 111.84f, src = EstSource.COUNTER)
    private val voltage = tel(58f, 58.3f, bmsSoc = 99f, bmsCap = 111.84f, src = EstSource.VOLTAGE)

    @Test fun socTextRawHasNoPrefix() = assertEquals("99%", socText(raw))
    @Test fun socTextCounterHasNoPrefix() = assertEquals("92%", socText(counter))
    @Test fun socTextVoltageIsTilded() = assertEquals("~58%", socText(voltage))

    @Test fun bmsSocLineAlwaysShowsBms() {
        assertEquals("BMS 99%", bmsSocLine(raw))
        assertEquals("BMS 99%", bmsSocLine(counter))
        assertEquals("BMS 99%", bmsSocLine(voltage))
    }

    @Test fun bmsCapLineRoundsToInteger() {
        assertEquals("BMS 112 Ah", bmsCapLine(raw))
        assertEquals("BMS 112 Ah", bmsCapLine(counter))
    }

    @Test fun bmsListTailIsCompact() {
        assertEquals("bms 99% · 112", bmsListTail(raw))
        assertEquals("bms 99% · 112", bmsListTail(voltage))
    }

    @Test fun rawFrameBmsLineEqualsOurs() {
        assertEquals("99%", socText(raw))
        assertEquals("BMS 99%", bmsSocLine(raw))
    }
}
