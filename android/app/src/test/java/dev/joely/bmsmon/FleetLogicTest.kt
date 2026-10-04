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
        manualStage: StageTarget? = null,
        seizeThreshold: Int? = null,
    ) = StageInputs(fleet, "2012", true, manualStage, now, lastDischargeAt, hold, current, now,
        roster.groupViews(), seizeThreshold)

    /** A pack with a chosen SOC (Idle) at every target of each named group. */
    private fun fleetWithSoc(vararg groupSoc: Pair<String, Float>): Map<String, BatteryStatus> =
        groupSoc.flatMap { (gid, soc) ->
            roster.groupById(gid)!!.targets.map {
                it.address to BatteryStatus(tel(BatteryState.Idle).copy(soc = soc), reachable = true)
            }
        }.toMap()

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

    // --- low-pack stage seize (safety override) ---

    @Test fun lowPackSeizesStageOverManualPin() {
        val fleet = fleetWithSoc("2024" to 20f) + fleetWith("2012" to BatteryState.Idle)
        val r = resolveStage(inputs(fleet, manualStage = StageTarget.Base("2012"), seizeThreshold = 30))
        assertEquals(StageTarget.Base("2024"), r)
    }

    @Test fun lowestSocPackWinsAmongSeveralLow() {
        val fleet = fleetWithSoc("2016" to 28f, "2024" to 12f)
        val r = resolveStage(inputs(fleet, seizeThreshold = 30))
        assertEquals(StageTarget.Base("2024"), r)
    }

    @Test fun dailyDriverBreaksSeizeSocTie() {
        val fleet = fleetWithSoc("2012" to 20f, "2016" to 20f)  // tie at 20 → daily driver 2012 wins
        val r = resolveStage(inputs(fleet, seizeThreshold = 30))
        assertEquals(StageTarget.Base("2012"), r)
    }

    @Test fun unreachableLowPackDoesNotSeize() {
        // Nothing is in use, so only reachability can keep this pack from seizing.
        val addr = roster.groupById("2024")!!.targets.first().address
        val fleet = mapOf(addr to BatteryStatus(tel(BatteryState.Idle).copy(soc = 8f), reachable = false)) +
            fleetWith("2016" to BatteryState.Idle)
        val r = resolveStage(inputs(fleet, seizeThreshold = 30))
        assertEquals(StageTarget.Base("2012"), r)  // no live SOC → normal resolution (all idle: stays put)
    }

    // --- UI-19: the seize never displaces the base in use, and a charging pack never seizes ---

    @Test fun idleLowSpareNeverDisplacesTheDischargingBase() {
        // Riding 2016 at home with spare 2024 idle on the shelf at 25 %: the stage stays on the chair
        // (2024's own low notification still fires fleet-wide — the seize is only the visual override).
        val fleet = fleetWith("2016" to BatteryState.Discharging) + fleetWithSoc("2024" to 25f)
        assertEquals(StageTarget.Base("2016"), resolveStage(inputs(fleet, seizeThreshold = 30)))
    }

    @Test fun chargingLowPackNeverSeizesOverAPin() {
        val charging = fleetWithSoc("2024" to 15f).mapValues {
            it.value.copy(telemetry = it.value.telemetry!!.copy(state = BatteryState.Charging, current = 3f))
        }
        val fleet = charging + fleetWith("2012" to BatteryState.Idle)
        val r = resolveStage(inputs(fleet, manualStage = StageTarget.Base("2012"), seizeThreshold = 30))
        assertEquals(StageTarget.Base("2012"), r)
    }

    @Test fun aLowInUseBaseStillSeizesOverAManualPin() {
        val low2016 = fleetWith("2016" to BatteryState.Discharging)
            .mapValues { it.value.copy(telemetry = it.value.telemetry!!.copy(soc = 20f)) }
        val r = resolveStage(
            inputs(low2016 + fleetWith("2012" to BatteryState.Idle), manualStage = StageTarget.Base("2012"), seizeThreshold = 30),
        )
        assertEquals(StageTarget.Base("2016"), r)
    }

    @Test fun regenOnTheInUseBaseStillCountsAsDriving() {
        // A regen burst reads state=Charging with charge-direction current; within REGEN_WINDOW_MS of
        // the base's last discharge it is driving, not charging, so the low base keeps the seize.
        val regen = fleetWithSoc("2016" to 20f).mapValues {
            it.value.copy(telemetry = it.value.telemetry!!.copy(state = BatteryState.Charging, current = 4f))
        }
        val r = resolveStage(
            inputs(
                regen + fleetWith("2012" to BatteryState.Idle),
                lastDischargeAt = mapOf("2016" to now - 5_000L),
                manualStage = StageTarget.Base("2012"), seizeThreshold = 30,
            ),
        )
        assertEquals(StageTarget.Base("2016"), r)
    }

    @Test fun anIdleLowSpareWaitsForTheChairsStageHoldToExpire() {
        val fleet = fleetWith("2016" to BatteryState.Idle) + fleetWithSoc("2024" to 20f)
        // Parked 5 min ago: still the base in use (inside the 15 min hold) — no seize.
        assertEquals(
            StageTarget.Base("2016"),
            resolveStage(inputs(fleet, mapOf("2016" to now - 5 * 60_000L), StageTarget.Base("2016"), seizeThreshold = 30)),
        )
        // Parked 20 min ago: nothing is in use, so the low spare seizes.
        assertEquals(
            StageTarget.Base("2024"),
            resolveStage(inputs(fleet, mapOf("2016" to now - 20 * 60_000L), StageTarget.Base("2016"), seizeThreshold = 30)),
        )
    }

    @Test fun seizeFiresAtThresholdAndReleasesAbove() {
        val idle2012 = fleetWith("2012" to BatteryState.Idle)
        // 30 ≤ 30 → seize
        assertEquals(
            StageTarget.Base("2024"),
            resolveStage(inputs(fleetWithSoc("2024" to 30f) + idle2012, seizeThreshold = 30)),
        )
        // 31 > 30 → no seize; everything idle keeps the current stage (the pin here)
        assertEquals(
            StageTarget.Base("2012"),
            resolveStage(inputs(fleetWithSoc("2024" to 31f) + idle2012,
                manualStage = StageTarget.Base("2012"), seizeThreshold = 30)),
        )
    }

    @Test fun nullSeizeThresholdDisablesSeize() {
        // Nothing is in use, so only the threshold can keep this 5 % pack from seizing.
        val fleet = fleetWithSoc("2024" to 5f) + fleetWith("2016" to BatteryState.Idle)
        assertEquals(StageTarget.Base("2024"), resolveStage(inputs(fleet, seizeThreshold = 30)))
        assertEquals(StageTarget.Base("2012"), resolveStage(inputs(fleet, seizeThreshold = null)))  // stays put
    }

    // --- "base in use" is the SET of discharging bases (else the one held base) ---

    /** Every pack of [gid] discharging at [soc]. */
    private fun discharging(gid: String, soc: Float): Map<String, BatteryStatus> =
        fleetWith(gid to BatteryState.Discharging).mapValues { it.value.copy(telemetry = it.value.telemetry!!.copy(soc = soc)) }

    @Test fun aLowPackOnASecondDischargingBaseSeizes() {
        // The daily driver is discharging too, but a draining pack on the other base must not stay hidden.
        val fleet = discharging("2012", 50f) + discharging("2016", 20f)
        assertEquals(StageTarget.Base("2016"), resolveStage(inputs(fleet, seizeThreshold = 30)))
    }

    @Test fun withTwoBasesDischargingAndBothLowTheLowestWins() {
        assertEquals(
            StageTarget.Base("2016"),
            resolveStage(inputs(discharging("2012", 25f) + discharging("2016", 15f), seizeThreshold = 30)),
        )
        // A tie goes to the daily driver.
        assertEquals(
            StageTarget.Base("2012"),
            resolveStage(inputs(discharging("2012", 20f) + discharging("2016", 20f), seizeThreshold = 30)),
        )
    }

    @Test fun anIdleSpareStillNeverSeizesWhileTwoBasesDischarge() {
        val fleet = discharging("2012", 50f) + discharging("2016", 60f) + fleetWithSoc("2024" to 10f)
        assertEquals(StageTarget.Base("2012"), resolveStage(inputs(fleet, seizeThreshold = 30)))
    }

    @Test fun aLowPackOnTheHeldBaseSeizesOverAPin() {
        // Parked 5 min ago, idle at 20 %: still the base in use, and it outranks the pin.
        val fleet = fleetWithSoc("2016" to 20f) + fleetWith("2012" to BatteryState.Idle)
        val r = resolveStage(
            inputs(fleet, mapOf("2016" to now - 5 * 60_000L), manualStage = StageTarget.Base("2012"), seizeThreshold = 30),
        )
        assertEquals(StageTarget.Base("2016"), r)
    }

    @Test fun theBaseInUseSeizesEvenWhenAnIdleSpareIsLower() {
        val fleet = discharging("2016", 25f) + fleetWithSoc("2024" to 10f) + fleetWith("2012" to BatteryState.Idle)
        val r = resolveStage(inputs(fleet, manualStage = StageTarget.Base("2012"), seizeThreshold = 30))
        assertEquals(StageTarget.Base("2016"), r)
    }

    @Test fun theSeizeRegenWindowEndsAtThirtySeconds() {
        val regen = fleetWithSoc("2016" to 20f).mapValues {
            it.value.copy(telemetry = it.value.telemetry!!.copy(state = BatteryState.Charging, current = 4f))
        }
        val fleet = regen + fleetWith("2012" to BatteryState.Idle)
        fun at(lastDischargeAgoMs: Long) = resolveStage(
            inputs(fleet, mapOf("2016" to now - lastDischargeAgoMs), manualStage = StageTarget.Base("2012"), seizeThreshold = 30),
        )
        assertEquals(StageTarget.Base("2016"), at(29_999L))   // still regen: driving, so it seizes
        assertEquals(StageTarget.Base("2012"), at(30_000L))   // charging: no seize, the pin holds
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
            it.address to BatteryStatus(tel(BatteryState.Charging), reachable = true, etaFullMin = 42f, lastFrameAtElapsedMs = 0L)
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
