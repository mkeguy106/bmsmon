package dev.joely.bmsmon

import dev.joely.bmsmon.model.BatteryState
import dev.joely.bmsmon.model.BatteryStatus
import dev.joely.bmsmon.model.DEFAULT_ROSTER
import dev.joely.bmsmon.model.StageTarget
import dev.joely.bmsmon.model.Telemetry
import dev.joely.bmsmon.model.groupById
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertLogicTest {

    private fun tel(soc: Float) = Telemetry(
        "x", soc = soc, powerW = 26f, current = -2f, voltage = 13f,
        capacityAh = 50f, cellV = 3.3f, temp = 25f, state = BatteryState.Discharging,
    )

    /** Fleet where every address of base [gid] reports [soc], all reachable. */
    private fun fleetAt(gid: String, soc: Float): Map<String, BatteryStatus> =
        DEFAULT_ROSTER.groupById(gid)!!.targets.associate {
            it.address to BatteryStatus(tel(soc), reachable = true)
        }

    private fun stateAt(gid: String, soc: Float, enabled: Set<Int>, critical: Int) = UiState(
        monitoring = true,
        roster = DEFAULT_ROSTER,
        fleet = fleetAt(gid, soc),
        stageTarget = StageTarget.Base(gid),
        alertsOn = true,
        enabledThresholds = enabled,
        criticalThreshold = critical,
    )

    @Test fun criticalWhenActiveThresholdAtOrBelowCriticalLevel() {
        val a = stateAt("2012", soc = 12f, enabled = setOf(15, 10), critical = 15).stageAlert()
        assertEquals(15, a.activeThreshold)
        assertTrue(a.flashing)
        assertTrue(a.critical)
    }

    @Test fun notCriticalWhenCriticalLevelIsLower() {
        val a = stateAt("2012", soc = 12f, enabled = setOf(15, 10), critical = 10).stageAlert()
        assertEquals(15, a.activeThreshold)
        assertFalse(a.critical)
    }

    @Test fun highThresholdFlashesButIsNotCritical() {
        val a = stateAt("2012", soc = 92f, enabled = setOf(95, 90), critical = 15).stageAlert()
        assertEquals(95, a.activeThreshold)
        assertTrue(a.flashing)
        assertFalse(a.critical)
    }
}
