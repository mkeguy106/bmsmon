package dev.joely.bmsmon

import dev.joely.bmsmon.model.AlertKind
import dev.joely.bmsmon.model.BatteryState
import dev.joely.bmsmon.model.BatteryStatus
import dev.joely.bmsmon.model.CHARGE_SUPPRESS_HOLD_MS
import dev.joely.bmsmon.model.DEFAULT_ROSTER
import dev.joely.bmsmon.model.StageTarget
import dev.joely.bmsmon.model.Telemetry
import dev.joely.bmsmon.model.applyDisabled
import dev.joely.bmsmon.model.groupById
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Worst-of arbitration between capacity and temperature stage alerts, plus temp-ack re-arming.
 * Regression coverage for two safety bugs:
 *  - an acknowledged temp CRITICAL must not mask an un-acked (flashing) capacity alert;
 *  - temp acks must clear once the condition recovers, so a recurrence flashes again.
 */
class StageAlertArbitrationTest {

    private fun tel(soc: Float, tempC: Float, charging: Boolean) = Telemetry(
        "x", soc = soc, powerW = 26f, current = if (charging) 5f else -2f, voltage = 13f,
        capacityAh = 50f, cellV = 3.3f, temp = tempC,
        state = if (charging) BatteryState.Charging else BatteryState.Discharging,
    )

    /** Fleet where every pack of base [gid] reports [soc] / [tempC], all reachable. */
    private fun fleetAt(gid: String, soc: Float, tempC: Float, charging: Boolean = false): Map<String, BatteryStatus> =
        DEFAULT_ROSTER.groupById(gid)!!.targets.associate {
            it.address to BatteryStatus(tel(soc, tempC, charging), reachable = true)
        }

    // Redodo default temp thresholds: hotCrit 53 °C, hotCutoff 60 °C — 55 °C = CRITICAL, 60 °C = CUTOFF.
    private fun stateAt(
        soc: Float,
        tempC: Float,
        charging: Boolean = false,
        ackedTemp: Set<String> = emptySet(),
        ackedCap: Set<Int> = emptySet(),
        enabled: Set<Int> = setOf(30, 25, 20, 15, 10, 5),
        critical: Int = 15,
    ) = UiState(
        monitoring = true,
        roster = DEFAULT_ROSTER,
        fleet = fleetAt("2012", soc, tempC, charging),
        stageTarget = StageTarget.Base("2012"),
        alertsOn = true,
        enabledThresholds = enabled,
        criticalThreshold = critical,
        acknowledgedThresholds = ackedCap,
        acknowledgedTempKeys = ackedTemp,
    )

    // --- Bug B: an acked alert must never mask an un-acked (flashing) one ---

    @Test fun ackedTempDoesNotMaskUnackedCapacity() {
        val a = stateAt(soc = 12f, tempC = 55f, ackedTemp = setOf("temp:HOT:CRITICAL")).stageAlert()
        assertEquals(AlertKind.CAPACITY, a.kind)
        assertTrue(a.flashing)
        assertTrue(a.critical)
        // activeThreshold must be set so acknowledgeAlert() can actually ack the capacity alert
        assertEquals(15, a.activeThreshold)
    }

    @Test fun ackedTempCutoffDoesNotMaskUnackedCapacityWarning() {
        // Even a higher-severity acked CUTOFF must yield the stage to a flashing capacity warning.
        val a = stateAt(soc = 22f, tempC = 65f, ackedTemp = setOf("temp:HOT:CUTOFF")).stageAlert()
        assertEquals(AlertKind.CAPACITY, a.kind)
        assertTrue(a.flashing)
        assertEquals(25, a.activeThreshold)
    }

    @Test fun ackedCapacityDoesNotMaskUnackedTemp() {
        val a = stateAt(soc = 12f, tempC = 55f, ackedCap = setOf(15)).stageAlert()
        assertEquals(AlertKind.TEMPERATURE, a.kind)
        assertTrue(a.flashing)
    }

    // --- Bug A: temp acks re-arm once the condition recovers below CRITICAL ---

    @Test fun tempAckRearmsAfterRecovery() {
        // hot-critical flashes
        var s = stateAt(soc = 80f, tempC = 55f)
        assertEquals(AlertKind.TEMPERATURE, s.stageAlert().kind)
        assertTrue(s.stageAlert().flashing)
        // ack silences it
        val key = s.stageAlert().tempAckKey!!
        s = s.copy(acknowledgedTempKeys = s.acknowledgedTempKeys + key)
        assertFalse(s.stageAlert().flashing)
        // while still critical, the ack must NOT be pruned
        assertEquals(setOf(key), s.withTempAcksPruned().acknowledgedTempKeys)
        // pack recovers to safe -> acks clear
        s = s.copy(fleet = fleetAt("2012", 80f, 25f)).withTempAcksPruned()
        assertTrue(s.acknowledgedTempKeys.isEmpty())
        // same zone re-enters later -> flashes again
        s = s.copy(fleet = fleetAt("2012", 80f, 55f))
        assertTrue(s.stageAlert().flashing)
    }

    @Test fun transientDisconnectDoesNotPruneTempAcks() {
        // A BLE dropout gives no live reading — that must not clear acks (no false re-arm churn).
        val s = stateAt(soc = 80f, tempC = 55f, ackedTemp = setOf("temp:HOT:CRITICAL"))
        val dropped = s.copy(fleet = s.fleet.mapValues { (_, st) -> st.copy(reachable = false) })
        assertEquals(setOf("temp:HOT:CRITICAL"), dropped.withTempAcksPruned().acknowledgedTempKeys)
    }

    // --- escalation: a NEW higher rank is a different key, so it flashes past an acked CRITICAL ---

    @Test fun cutoffEscalatesPastAckedCritical() {
        val a = stateAt(soc = 80f, tempC = 60f, ackedTemp = setOf("temp:HOT:CRITICAL")).stageAlert()
        assertEquals(AlertKind.TEMPERATURE, a.kind)
        assertTrue(a.flashing)
        assertEquals("temp:HOT:CUTOFF", a.tempAckKey)
    }

    // --- existing semantics preserved ---

    @Test fun worstOfWhenNothingAcked() {
        // temp CRITICAL (sev 3) beats a non-critical capacity warning (sev 2)
        var a = stateAt(soc = 22f, tempC = 55f).stageAlert()
        assertEquals(AlertKind.TEMPERATURE, a.kind)
        assertTrue(a.flashing)
        // tie (both critical) -> temperature takes the stage
        a = stateAt(soc = 12f, tempC = 55f).stageAlert()
        assertEquals(AlertKind.TEMPERATURE, a.kind)
        assertTrue(a.flashing)
        // capacity alone
        a = stateAt(soc = 12f, tempC = 25f).stageAlert()
        assertEquals(AlertKind.CAPACITY, a.kind)
        assertTrue(a.flashing)
        assertTrue(a.critical)
    }

    @Test fun chargingSuppressesCapacityFlashButNotTemp() {
        var a = stateAt(soc = 12f, tempC = 25f, charging = true).stageAlert()
        assertFalse(a.flashing)
        assertFalse(a.present)
        // a temp critical still flashes while charging
        a = stateAt(soc = 12f, tempC = 55f, charging = true).stageAlert()
        assertEquals(AlertKind.TEMPERATURE, a.kind)
        assertTrue(a.flashing)
    }

    @Test fun capAckSilencesUntilNextLevel() {
        var a = stateAt(soc = 22f, tempC = 25f, ackedCap = setOf(25)).stageAlert()
        assertFalse(a.flashing)
        assertTrue(a.present)
        // dropping to the next enabled band re-flashes
        a = stateAt(soc = 19f, tempC = 25f, ackedCap = setOf(25)).stageAlert()
        assertTrue(a.flashing)
        assertEquals(20, a.activeThreshold)
    }

    @Test fun bothAckedShowsWorstNonFlashing() {
        val a = stateAt(
            soc = 12f, tempC = 55f,
            ackedTemp = setOf("temp:HOT:CRITICAL"), ackedCap = setOf(15),
        ).stageAlert()
        assertFalse(a.flashing)
        assertEquals(AlertKind.TEMPERATURE, a.kind)  // worst raw severity still shown (acked strip)
        assertTrue(a.present)
    }

    // --- UI-8: a user-disconnected (disabled) stage pack raises no alert ---
    // Seam: engine.setDisabled applies applyDisabled to MonitorState synchronously; the VM
    // mirrors that fleet, and stageAlert's reachable-only filter then excludes the pack. This
    // drives the actual applyDisabled → stageAlert pipeline end to end.

    @Test fun disabledStagePackRaisesNoAlert() {
        val s = stateAt(soc = 5f, tempC = 65f)   // both capacity-critical AND temp CUTOFF if live
        val disabled = DEFAULT_ROSTER.groupById("2012")!!.targets.map { it.address }.toSet()
        val (fleet, regen) = applyDisabled(s.fleet, emptySet(), disabled)
        val a = s.copy(fleet = fleet, regenAddrs = regen, disabled = disabled).stageAlert()
        assertFalse(a.flashing)
        assertFalse(a.present)
        assertNull(a.activeThreshold)
        // Documented consequence: DISCONNECTED rendering is the only signal for an absent pack.
    }

    @Test fun disablingOnePackLeavesTheOtherDrivingAlerts() {
        val s = stateAt(soc = 12f, tempC = 25f)
        val one = DEFAULT_ROSTER.groupById("2012")!!.targets.first().address
        val (fleet, regen) = applyDisabled(s.fleet, emptySet(), setOf(one))
        val a = s.copy(fleet = fleet, regenAddrs = regen, disabled = setOf(one)).stageAlert()
        assertTrue(a.flashing)   // the still-reachable low pack keeps alerting
        assertEquals(15, a.activeThreshold)
    }

    // --- UI-9: charging-suppression hysteresis at the UiState seam ---

    /** Fleet where every pack of base 2012 reads Idle at [soc] (the charger-flap resting state). */
    private fun idleFleetAt(soc: Float): Map<String, BatteryStatus> =
        DEFAULT_ROSTER.groupById("2012")!!.targets.associate {
            it.address to BatteryStatus(
                Telemetry("x", soc = soc, powerW = 0f, current = 0f, voltage = 13f,
                    capacityAh = 50f, cellV = 3.3f, temp = 25f, state = BatteryState.Idle),
                reachable = true,
            )
        }

    @Test fun chargeHoldSuppressesFlashDuringIdleFlap() {
        // Charging latches the hold; the flap to Idle (within the window) must NOT strobe the
        // overlay even though the pack is at a critical SOC and no longer reads Charging.
        var s = stateAt(soc = 12f, tempC = 25f, charging = true).withChargeHold(now = 1_000L)
        assertTrue(s.stageChargeHold)
        s = s.copy(fleet = idleFleetAt(12f)).withChargeHold(now = 2_000L)   // Idle flap, 1 s later
        assertTrue(s.stageChargeHold)   // still latched
        val a = s.stageAlert()
        assertFalse("latched hold must suppress the capacity flash", a.flashing)
        assertFalse(a.present)
    }

    @Test fun chargeHoldClearsOnGenuineDischarge() {
        // Charging first — latch on.
        var s = stateAt(soc = 12f, tempC = 25f, charging = true).withChargeHold(now = 1_000L)
        assertTrue(s.stageChargeHold)
        // Then genuinely discharging (unplugged and driving): the latch clears immediately…
        s = s.copy(fleet = fleetAt("2012", 12f, 25f, charging = false)).withChargeHold(now = 2_000L)
        assertFalse(s.stageChargeHold)
        assertEquals(0L, s.stageChargeLastAt)
        // …and the low-battery alert flashes with no 30 s delay.
        assertTrue(s.stageAlert().flashing)
    }

    @Test fun chargeHoldExpiresAfterWindowWhileUnreadable() {
        // Latch on, then the window passes with no discharge signal: hold expires on refresh.
        var s = stateAt(soc = 80f, tempC = 25f, charging = true).withChargeHold(now = 1_000L)
        s = s.copy(fleet = s.fleet.mapValues { (_, st) -> st.copy(reachable = false) })
        s = s.withChargeHold(now = 1_000L + CHARGE_SUPPRESS_HOLD_MS + 1)
        assertFalse(s.stageChargeHold)
    }
}
