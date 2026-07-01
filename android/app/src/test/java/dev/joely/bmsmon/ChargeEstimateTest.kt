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

    @Test fun formatsHoursAndMinutes() {
        assertEquals("2h 14m", formatEtaMinutes(134f))
        assertEquals("45m", formatEtaMinutes(45f))
        assertEquals("0m", formatEtaMinutes(-3f))
    }
}
