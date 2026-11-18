package dev.joely.bmsmon

import dev.joely.bmsmon.data.decodeTelemetry
import dev.joely.bmsmon.data.encodeTelemetry
import dev.joely.bmsmon.model.BatteryState
import dev.joely.bmsmon.model.EstSource
import dev.joely.bmsmon.model.Telemetry
import dev.joely.bmsmon.model.rawCapacityAh
import dev.joely.bmsmon.model.rawSoc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The last-known seed is the fleet's EFFECTIVE telemetry (the estimate in soc/capacityAh), so it
 * must carry the BMS's own values beside it: without them a restarted app shows the estimate
 * labelled as the BMS reading, and the tail trigger compares the estimate against TAIL_START_SOC.
 */
class TelemetrySeedCodecTest {

    private val base = Telemetry(
        name = "R-12100", soc = 92f, powerW = 0f, current = 0f, voltage = 13.3f, capacityAh = 96.84f,
        cellV = 3.33f, temp = 21f, soh = 100, cycles = 12, state = BatteryState.Idle, fullChargeAh = 105f,
    )

    @Test fun seedRoundTripsTheBmsValuesAndSource() {
        for (src in listOf(EstSource.COUNTER, EstSource.VOLTAGE, null)) {
            val t = base.copy(bmsSoc = 99f, bmsCapacityAh = 113.1f, estSource = src)
            val back = decodeTelemetry(encodeTelemetry(mapOf("A" to t)))["A"]!!
            assertEquals(t, back)
            assertEquals(99f, back.rawSoc, 0f)
            assertEquals(113.1f, back.rawCapacityAh, 1e-4f)
        }
    }

    @Test fun aRawSeedRoundTripsWithNulls() {
        val back = decodeTelemetry(encodeTelemetry(mapOf("A" to base)))["A"]!!
        assertEquals(base, back)
        assertNull(back.bmsSoc)
        assertNull(back.bmsCapacityAh)
        assertNull(back.estSource)
    }

    @Test fun anOldFormatSeedStillDecodes() {
        val old = """{"A":{"name":"R-12100","soc":99,"powerW":0,"current":0,"voltage":13.3,""" +
            """"capacityAh":113.1,"fullChargeAh":105,"cellV":3.33,"temp":21,"soh":100,"cycles":12,"state":"Idle"}}"""
        val t = decodeTelemetry(old)["A"]!!
        assertEquals(99f, t.soc, 0f)
        assertEquals(113.1f, t.capacityAh, 1e-4f)
        assertNull(t.bmsSoc)
        assertNull(t.bmsCapacityAh)
        assertNull(t.estSource)
        assertEquals(99f, t.rawSoc, 0f)
    }

    @Test fun anUnknownSourceOrBadNumberDecodesToNullNeverThrows() {
        val s = """{"A":{"name":"x","soc":92,"capacityAh":96.84,"bmsSoc":"oops","bmsCapacityAh":null,""" +
            """"estSource":"BOGUS","state":"Idle"}}"""
        val t = decodeTelemetry(s)["A"]!!
        assertEquals(92f, t.soc, 0f)
        assertNull(t.bmsSoc)
        assertNull(t.bmsCapacityAh)
        assertNull(t.estSource)
    }
}
