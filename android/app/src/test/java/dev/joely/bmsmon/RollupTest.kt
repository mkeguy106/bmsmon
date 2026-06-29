package dev.joely.bmsmon

import dev.joely.bmsmon.data.computeRollup
import dev.joely.bmsmon.data.db.SampleEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class RollupTest {
    private fun sample(ts: Long, soc: Float, cur: Float, pw: Float, v: Float, regen: Boolean = false) =
        SampleEntity(
            address = "A", tsMs = ts, sessionId = 7, state = if (cur < 0) "Discharging" else "Charging",
            soc = soc, currentA = cur, powerW = pw, voltageV = v, tempC = 25f, mosfetTempC = 26,
            soh = 99, fullChargeAh = 98.5f, remainingAh = 50f, cycles = 12,
            cellMinV = v / 4f, cellMaxV = v / 4f, regen = regen, linkEvent = null,
        )

    @Test fun rollupAggregatesDischargeSamples() {
        // Three discharge samples 1s apart: powers 100, 300, 200 W; currents -10,-30,-20 A.
        val samples = listOf(
            sample(0, 90f, -10f, 100f, 13.2f),
            sample(1000, 89f, -30f, 300f, 13.0f),
            sample(2000, 88f, -20f, 200f, 13.1f),
        )
        val r = computeRollup("A", 7, samples)
        assertEquals(7, r.id)
        assertEquals("A", r.address)
        assertEquals(3, r.sampleCount)
        assertEquals(300f, r.peakPowerW, 0.01f)
        assertEquals(30f, r.peakCurrentA, 0.01f)
        assertEquals(200f, r.meanPowerW, 0.01f)
        assertEquals(90f, r.socStart, 0.01f)
        assertEquals(88f, r.socEnd, 0.01f)
        assertEquals(13.0f, r.minVoltageUnderLoad, 0.01f)
        assertEquals(99, r.sohEnd)
        assertEquals(12, r.cyclesEnd)
        // energy: sample[0] 100W for 1s + sample[1] 300W for 1s = (100+300)/3600 Wh
        assertEquals((100f + 300f) / 3600f, r.energyWh, 0.001f)
    }

    @Test fun regenPeakFromRegenSamplesOnly() {
        val samples = listOf(
            sample(0, 88f, -10f, 100f, 13.1f),
            sample(1000, 88f, 20f, 250f, 13.3f, regen = true),
        )
        val r = computeRollup("A", 7, samples)
        assertEquals(250f, r.peakRegenW, 0.01f)
    }
}
