package dev.joely.bmsmon

import dev.joely.bmsmon.model.BatteryState
import dev.joely.bmsmon.model.BatteryStatus
import dev.joely.bmsmon.model.StageInputs
import dev.joely.bmsmon.model.StageTarget
import dev.joely.bmsmon.model.Telemetry
import dev.joely.bmsmon.model.DEFAULT_ROSTER
import dev.joely.bmsmon.model.applyDisabled
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

    // --- applyDisabled: single-writer reachability (a just-disconnected pack is unreachable
    // --- immediately, synchronously with the disable — never a transient "connected" flip) ---

    @Test fun applyDisabledMarksPackUnreachableAndKeepsTelemetry() {
        val fleet = fleetWith("2012" to BatteryState.Discharging)
        val addr = roster.groupById("2012")!!.targets.first().address
        val (next, _) = applyDisabled(fleet, emptySet(), setOf(addr))
        assertFalse(next[addr]!!.reachable)
        assertTrue(next[addr]!!.telemetry != null)   // last-known reading kept (renders dimmed)
    }

    @Test fun applyDisabledIsCaseInsensitiveOnAddresses() {
        val fleet = fleetWith("2012" to BatteryState.Discharging)
        val addr = roster.groupById("2012")!!.targets.first().address
        val (next, _) = applyDisabled(fleet, emptySet(), setOf(addr.lowercase()))
        assertFalse(next[addr]!!.reachable)
    }

    @Test fun applyDisabledLeavesOtherPacksReachable() {
        val fleet = fleetWith("2012" to BatteryState.Discharging, "2016" to BatteryState.Idle)
        val disabled = roster.groupById("2012")!!.targets.map { it.address }.toSet()
        val (next, _) = applyDisabled(fleet, emptySet(), disabled)
        roster.groupById("2016")!!.targets.forEach { assertTrue(next[it.address]!!.reachable) }
        roster.groupById("2012")!!.targets.forEach { assertFalse(next[it.address]!!.reachable) }
    }

    @Test fun applyDisabledClearsRegenFlagsForDisabledPacksOnly() {
        val fleet = fleetWith("2012" to BatteryState.Charging, "2016" to BatteryState.Charging)
        val a2012 = roster.groupById("2012")!!.targets.first().address
        val a2016 = roster.groupById("2016")!!.targets.first().address
        val (_, regen) = applyDisabled(fleet, setOf(a2012, a2016), setOf(a2012))
        assertEquals(setOf(a2016), regen)
    }

    // --- stage ETA comes from the engine-carried BatteryStatus (computed once per poll) ---

    private fun stageState(fleet: Map<String, BatteryStatus>) = UiState(
        monitoring = true, roster = roster, fleet = fleet, stageTarget = StageTarget.Base("2012"),
    )

    @Test fun stageItemsReadEngineComputedEta() {
        val fleet = roster.groupById("2012")!!.targets.associate {
            it.address to BatteryStatus(tel(BatteryState.Charging), reachable = true, etaFullMin = 42f)
        }
        val items = stageState(fleet).stageItems()
        assertEquals(2, items.size)
        assertTrue(items.all { it.connected && it.etaFullMin == 42f })
    }

    @Test fun stageItemsNullEtaWhenPackDisconnected() {
        val fleet = roster.groupById("2012")!!.targets.associate {
            // Stale status still carries the last ETA, but the pack is out of reach.
            it.address to BatteryStatus(tel(BatteryState.Charging), reachable = false, etaFullMin = 42f)
        }
        val items = stageState(fleet).stageItems()
        assertTrue(items.all { !it.connected && it.etaFullMin == null })
    }

    // --- DISCONNECTED stageItems mapping (UI-14 seam coverage) ---

    @Test fun stageItemsShowDisconnectedForNeverReportedPacks() {
        // No fleet entry at all (fresh start, nothing has connected): still one item per stage
        // pack, rendered DISCONNECTED — never a fake 0%-looking connected pack.
        val items = stageState(emptyMap()).stageItems()
        assertEquals(2, items.size)
        assertTrue(items.all { !it.connected && !it.regen && it.etaFullMin == null })
        // Placeholder telemetry keeps the roster name so the stage can still label the pack.
        val names = roster.groupById("2012")!!.targets.map { it.name }
        assertEquals(names, items.map { it.telemetry.name })
    }

    @Test fun stageItemsReachableWithoutTelemetryIsStillDisconnected() {
        // Reachable-but-no-frame-yet (connect handshake done, first poll pending) must read
        // DISCONNECTED too: "connected" requires reachable AND actual telemetry.
        val fleet = roster.groupById("2012")!!.targets.associate {
            it.address to BatteryStatus(telemetry = null, reachable = true)
        }
        val items = stageState(fleet).stageItems()
        assertTrue(items.all { !it.connected })
    }
}
