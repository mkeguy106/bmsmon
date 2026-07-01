package dev.joely.bmsmon

import dev.joely.bmsmon.model.AlertKind
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
}
