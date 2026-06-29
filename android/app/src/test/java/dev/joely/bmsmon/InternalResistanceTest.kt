package dev.joely.bmsmon

import dev.joely.bmsmon.data.estimateInternalResistanceMohm
import dev.joely.bmsmon.data.db.SampleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InternalResistanceTest {
    private fun s(cur: Float, v: Float) = SampleEntity(
        address = "A", tsMs = 0, sessionId = 1, state = "Discharging", soc = 80f,
        currentA = cur, powerW = v * -cur, voltageV = v, tempC = 25f, mosfetTempC = 26,
        soh = 100, fullChargeAh = 100f, remainingAh = 80f, cycles = 1,
        cellMinV = v / 4f, cellMaxV = v / 4f, regen = false, linkEvent = null,
    )

    @Test fun recoversKnownResistance() {
        // V = 13.2 + I * 0.02  (R = 20 mOhm); I negative under load.
        val samples = listOf(s(-5f, 13.1f), s(-20f, 12.8f), s(-50f, 12.2f))
        val est = estimateInternalResistanceMohm(samples)!!
        assertEquals(20f, est.mohm, 0.5f)
        assertTrue(est.confidence > 0f)
    }

    @Test fun lowCurrentSpreadReturnsNull() {
        // All currents within < 8 A of each other → not enough spread to trust.
        val samples = listOf(s(-10f, 13.0f), s(-12f, 12.99f), s(-14f, 12.98f))
        assertNull(estimateInternalResistanceMohm(samples))
    }

    @Test fun emptyReturnsNull() {
        assertNull(estimateInternalResistanceMohm(emptyList()))
    }
}
