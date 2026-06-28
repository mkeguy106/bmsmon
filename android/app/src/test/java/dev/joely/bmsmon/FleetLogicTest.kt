package dev.joely.bmsmon

import dev.joely.bmsmon.model.BatteryState
import dev.joely.bmsmon.model.BatteryStatus
import dev.joely.bmsmon.model.StageInputs
import dev.joely.bmsmon.model.StageTarget
import dev.joely.bmsmon.model.Telemetry
import dev.joely.bmsmon.model.DEFAULT_ROSTER
import dev.joely.bmsmon.model.groupById
import dev.joely.bmsmon.model.groupViews
import dev.joely.bmsmon.model.isRegen
import dev.joely.bmsmon.model.resolveStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FleetLogicTest {

    private val now = 1_000_000_000L
    private val hold = 15 * 60_000L

    private fun tel(state: BatteryState): Telemetry {
        val current = when (state) {
            BatteryState.Discharging -> -2f
            BatteryState.Charging -> 2f
            else -> 0f
        }
        return Telemetry("x", soc = 50f, powerW = 26f, current = current, voltage = 13f,
            capacityAh = 50f, cellV = 3.3f, temp = 25f, state = state)
    }

    private val roster = DEFAULT_ROSTER

    private fun fleetWith(vararg groupStates: Pair<String, BatteryState>): Map<String, BatteryStatus> =
        groupStates.flatMap { (gid, st) ->
            roster.groupById(gid)!!.targets.map { it.address to BatteryStatus(tel(st), reachable = true) }
        }.toMap()

    private fun inputs(
        fleet: Map<String, BatteryStatus>,
        lastDischargeAt: Map<String, Long> = emptyMap(),
        current: StageTarget = StageTarget.Base("2012"),
    ) = StageInputs(fleet, "2012", true, null, 0, lastDischargeAt, hold, current, now, roster.groupViews())

    // --- regen detection ---

    @Test fun chargeCurrentRightAfterDischargeIsRegen() {
        assertTrue(isRegen(tel(BatteryState.Charging), now - 5_000, now))
    }

    @Test fun steadyChargeWithoutRecentDischargeIsNotRegen() {
        assertFalse(isRegen(tel(BatteryState.Charging), now - 60_000, now))
        assertFalse(isRegen(tel(BatteryState.Charging), null, now))
    }

    @Test fun dischargeIsNeverRegen() {
        assertFalse(isRegen(tel(BatteryState.Discharging), now - 5_000, now))
    }

    // --- stage hysteresis ---

    @Test fun dischargingBaseTakesStage() {
        val r = resolveStage(inputs(fleetWith("2016" to BatteryState.Discharging)))
        assertEquals(StageTarget.Base("2016"), r)
    }

    @Test fun holdKeepsIdleChairOverChargingBase() {
        val fleet = fleetWith("2016" to BatteryState.Idle, "2023" to BatteryState.Charging)
        // 2016 discharged 5 min ago, hold is 15 min -> stays on 2016, not the charging 2023
        val r = resolveStage(inputs(fleet, mapOf("2016" to now - 5 * 60_000L), StageTarget.Base("2016")))
        assertEquals(StageTarget.Base("2016"), r)
    }

    @Test fun afterHoldExpiresChargingBaseTakesOver() {
        val fleet = fleetWith("2016" to BatteryState.Idle, "2023" to BatteryState.Charging)
        val r = resolveStage(inputs(fleet, mapOf("2016" to now - 20 * 60_000L), StageTarget.Base("2016")))
        assertEquals(StageTarget.Base("2023"), r)
    }

    @Test fun everythingIdleKeepsCurrentStage() {
        val fleet = fleetWith("2016" to BatteryState.Idle, "2023" to BatteryState.Idle)
        val r = resolveStage(inputs(fleet, emptyMap(), StageTarget.Base("2024")))
        assertEquals(StageTarget.Base("2024"), r)
    }
}
