package dev.joely.bmsmon

import dev.joely.bmsmon.model.BatteryState
import dev.joely.bmsmon.model.estimateChargeMinutes
import dev.joely.bmsmon.model.formatEtaMinutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChargeEstimateTest {
    private val charging = BatteryState.Charging

    @Test fun bulkRegionAddsCoulombPlusTail() {
        // 98% of 105Ah = 102.9Ah; minus 85.8 remaining = 17.1Ah; /8.1A*60 = 126.67min; +45 tail.
        val m = estimateChargeMinutes(charging, soc = 81f, currentA = 8.1f,
            fullChargeAh = 105f, remainingAh = 85.8f, regen = false, tailMin = 45f)!!
        assertEquals(171.67f, m, 0.5f)
    }

    @Test fun tailRegionScalesLearnedConstant() {
        // soc 99 -> half of the 98..100 band remains -> 45 * 0.5.
        val m = estimateChargeMinutes(charging, soc = 99f, currentA = 3f,
            fullChargeAh = 105f, remainingAh = 104f, regen = false, tailMin = 45f)!!
        assertEquals(22.5f, m, 0.01f)
    }

    @Test fun nullWhenNotCharging() {
        assertNull(estimateChargeMinutes(BatteryState.Idle, 80f, 8f, 105f, 84f, false, 45f))
    }

    @Test fun nullWhenRegen() {
        assertNull(estimateChargeMinutes(charging, 80f, 8f, 105f, 84f, regen = true, tailMin = 45f))
    }

    @Test fun nullAtOrAboveFull() {
        assertNull(estimateChargeMinutes(charging, 100f, 8f, 105f, 105f, false, 45f))
    }

    @Test fun nullWhenBulkHasNoCurrent() {
        assertNull(estimateChargeMinutes(charging, 80f, 0f, 105f, 84f, false, 45f))
    }

    @Test fun nullWhenCapacityUnknown() {
        assertNull(estimateChargeMinutes(charging, 80f, 8f, 0f, 0f, false, 45f))
    }

    // An estimated pack is counted in real Ah all the way to full: no learned tail. Measured on
    // 30 full charges (both 2012 packs, 2026-09..11): the last 10.5 Ah took 80.2-84.3 min against
    // 78.3-80.8 at the bulk current, the last 2.1 Ah 17.6-20.0 against 15.6-16.2.
    @Test fun anEstimatedPackCountsAhToFullPlusTheTaper() {
        // 100.44 - 74.72 = 25.72 Ah; / 8.28 A * 60 = 186.4 min; + 2.5.
        val m = estimateChargeMinutes(charging, soc = 74f, currentA = 8.28f, fullChargeAh = 100.44f,
            remainingAh = 74.72f, regen = false, tailMin = 70f, estimated = true)!!
        assertEquals(188.9f, m, 0.1f)
    }

    @Test fun anEstimatedPackAboveNinetyEightStillCountsAh() {
        // 1.05 Ah to go at 8 A = 7.9 min + 2.5, not half of the learned tail.
        val m = estimateChargeMinutes(charging, soc = 99f, currentA = 8f, fullChargeAh = 105f,
            remainingAh = 103.95f, regen = false, tailMin = 70f, estimated = true)!!
        assertEquals(10.4f, m, 0.1f)
    }

    @Test fun anEstimatedPackRoundedToAHundredStillHasItsLastMinutes() {
        val m = estimateChargeMinutes(charging, soc = 100f, currentA = 8f, fullChargeAh = 105f,
            remainingAh = 104.6f, regen = false, tailMin = 70f, estimated = true)!!
        assertEquals(5.5f, m, 0.1f)
    }

    @Test fun anEstimatedPackAtFullHasNoEta() {
        assertNull(estimateChargeMinutes(charging, 100f, 8f, 105f, 105f, false, 70f, estimated = true))
    }

    @Test fun anEstimatedPackOnTheTaperIsMinutesFromDone() {
        // 13.97 V under 3 A is the charger's taper: 1 Ah / 2 A would read 32 min.
        val m = estimateChargeMinutes(charging, soc = 99f, currentA = 2f, fullChargeAh = 105f,
            remainingAh = 104f, regen = false, tailMin = 70f, estimated = true, voltageV = 13.97f)!!
        assertEquals(5f, m, 0f)
        // The same current at a bulk voltage is a small charger, not a taper.
        val slow = estimateChargeMinutes(charging, soc = 99f, currentA = 2f, fullChargeAh = 105f,
            remainingAh = 104f, regen = false, tailMin = 70f, estimated = true, voltageV = 13.5f)!!
        assertEquals(32.5f, slow, 0.1f)
    }

    @Test fun anEstimatedPackWithNoCurrentHasNoEta() {
        assertNull(estimateChargeMinutes(charging, 80f, 0f, 105f, 84f, false, 70f, estimated = true))
        assertNull(estimateChargeMinutes(charging, 80f, 8f, 105f, 84f, regen = true, tailMin = 70f, estimated = true))
    }

    @Test fun formatsHoursAndMinutes() {
        assertEquals("2h 14m", formatEtaMinutes(134f))
        assertEquals("45m", formatEtaMinutes(45f))
        assertEquals("0m", formatEtaMinutes(-3f))
    }
}
