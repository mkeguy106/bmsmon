package dev.joely.bmsmon

import dev.joely.bmsmon.model.AlertConfig
import dev.joely.bmsmon.model.AlertEval
import dev.joely.bmsmon.model.CAP_SEVERITY_CRITICAL
import dev.joely.bmsmon.model.CAP_SEVERITY_WARNING
import dev.joely.bmsmon.model.CHARGE_SUPPRESS_HOLD_MS
import dev.joely.bmsmon.model.PackSoc
import dev.joely.bmsmon.model.SEVERITY_NONE
import dev.joely.bmsmon.model.TempRank
import dev.joely.bmsmon.model.evalStageAlert
import dev.joely.bmsmon.model.nextChargeHold
import dev.joely.bmsmon.model.nextNotifyDecision
import dev.joely.bmsmon.model.tempSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertsTest {
    private fun cfg(thresholds: Set<Int>, critical: Int, on: Boolean = true) =
        AlertConfig(alertsOn = on, enabledThresholds = thresholds, criticalThreshold = critical)

    private fun packs(vararg socs: Float, charging: Boolean = false) =
        socs.map { PackSoc(it, charging) }

    // --- evalStageAlert: the boundary fix (<=) ---

    @Test fun firesAtExactThreshold() {
        // The whole point: a 75% threshold fires AT 75%, not only at 74%.
        val e = evalStageAlert(packs(75f), cfg(setOf(75), critical = 75))
        assertEquals(75, e.activeThreshold)
        assertTrue(e.critical)
    }

    @Test fun firesBelowThreshold() {
        val e = evalStageAlert(packs(74f), cfg(setOf(75), critical = 75))
        assertEquals(75, e.activeThreshold)
    }

    @Test fun doesNotFireAboveThreshold() {
        val e = evalStageAlert(packs(76f), cfg(setOf(75), critical = 75))
        assertNull(e.activeThreshold)
        assertFalse(e.critical)
    }

    // --- critical is a severity classifier, not a trigger ---

    @Test fun crossingAboveCriticalLevelIsWarningNotCritical() {
        // The exact trap: enabled 80, critical 75, SOC 75 -> warning (80), never critical.
        val e = evalStageAlert(packs(75f), cfg(setOf(80, 30), critical = 75))
        assertEquals(80, e.activeThreshold)
        assertFalse(e.critical)   // 80 <= 75 is false
    }

    @Test fun escalatesToCriticalWhenLowerBandCrossed() {
        val e = evalStageAlert(packs(25f), cfg(setOf(80, 30), critical = 75))
        assertEquals(30, e.activeThreshold)  // lowest crossed band
        assertTrue(e.critical)               // 30 <= 75
    }

    // --- selection / suppression / edges ---

    @Test fun usesLowestReachablePack() {
        val e = evalStageAlert(packs(90f, 72f), cfg(setOf(75), critical = 75))
        assertEquals(72, e.lowSoc)
        assertEquals(75, e.activeThreshold)
    }

    @Test fun reportsChargingFromLowestPack() {
        val e = evalStageAlert(packs(70f, charging = true), cfg(setOf(75), critical = 75))
        assertTrue(e.charging)
    }

    @Test fun noPacksMeansNoAlert() {
        val e = evalStageAlert(emptyList(), cfg(setOf(75), critical = 75))
        assertNull(e.activeThreshold)
        assertEquals(100, e.lowSoc)
    }

    @Test fun alertsOffMeansNoAlert() {
        val e = evalStageAlert(packs(10f), cfg(setOf(75, 30), critical = 75, on = false))
        assertNull(e.activeThreshold)
        assertFalse(e.critical)
    }

    // --- nextNotifyDecision: dedup / escalation / recovery ---

    private fun eval(active: Int?, charging: Boolean = false) =
        AlertEval(active, critical = active != null && active <= 75, lowSoc = active ?: 100,
            charging = charging, crossed = active?.let { setOf(it) } ?: emptySet())

    @Test fun firesOnFirstCrossing() {
        val d = nextNotifyDecision(eval(80), lastNotified = null)
        assertTrue(d.notify); assertEquals(80, d.newLastNotified)
    }

    @Test fun staysQuietWithinSameBand() {
        val d = nextNotifyDecision(eval(80), lastNotified = 80)
        assertFalse(d.notify); assertEquals(80, d.newLastNotified)
    }

    @Test fun firesOnEscalationToMoreSevereBand() {
        val d = nextNotifyDecision(eval(30), lastNotified = 80)
        assertTrue(d.notify); assertEquals(30, d.newLastNotified)
    }

    @Test fun quietOnRecoveryToLessSevereBand() {
        val d = nextNotifyDecision(eval(80), lastNotified = 30)
        assertFalse(d.notify); assertEquals(80, d.newLastNotified)  // baseline reset, no spam
    }

    @Test fun reFiresAfterRecoveryThenReDrop() {
        val recovered = nextNotifyDecision(eval(80), lastNotified = 30)  // last -> 80
        val reDrop = nextNotifyDecision(eval(30), lastNotified = recovered.newLastNotified)
        assertTrue(reDrop.notify)
    }

    @Test fun cancelsWhenRecoveredAboveAllThresholds() {
        val d = nextNotifyDecision(eval(null), lastNotified = 30)
        assertTrue(d.cancel); assertFalse(d.notify); assertNull(d.newLastNotified)
    }

    @Test fun cancelsWhenCharging() {
        val d = nextNotifyDecision(eval(30, charging = true), lastNotified = 30)
        assertTrue(d.cancel); assertFalse(d.notify); assertNull(d.newLastNotified)
    }

    // --- nextChargeHold: charging-suppression hysteresis (UI-9) ---

    @Test fun chargingLatchesTheHold() {
        val h = nextChargeHold(charging = true, discharging = false, lastChargingAt = 0L, now = 10_000L)
        assertTrue(h.holdActive)
        assertEquals(10_000L, h.lastChargingAt)
    }

    // lastChargingAt uses 0 as the "never charged" sentinel (epoch-ms timestamps are never 0 in
    // production), so these tests run on a t0-based clock.
    private val t0 = 1_000_000L

    @Test fun idleFlapWithinWindowStaysSuppressed() {
        // Charging → Idle → Charging → Idle at the charger: hold stays active throughout.
        var h = nextChargeHold(true, false, 0L, now = t0)
        h = nextChargeHold(false, false, h.lastChargingAt, now = t0 + 5_000L)     // Idle flap
        assertTrue(h.holdActive)
        h = nextChargeHold(true, false, h.lastChargingAt, now = t0 + 10_000L)     // back to Charging
        h = nextChargeHold(false, false, h.lastChargingAt, now = t0 + 10_000L + CHARGE_SUPPRESS_HOLD_MS - 1)
        assertTrue(h.holdActive)                                                  // still within window
    }

    @Test fun holdExpiresAfterTheWindow() {
        var h = nextChargeHold(true, false, 0L, now = t0)
        h = nextChargeHold(false, false, h.lastChargingAt, now = t0 + CHARGE_SUPPRESS_HOLD_MS)
        assertFalse(h.holdActive)   // unplugged and idle long enough → alerts re-arm
    }

    @Test fun genuineDischargeUnsuppressesImmediatelyAndClearsLatch() {
        var h = nextChargeHold(true, false, 0L, now = t0)
        // Unplugged and driving 1 s later: no 30 s delay on a real low-battery alert.
        h = nextChargeHold(false, true, h.lastChargingAt, now = t0 + 1_000L)
        assertFalse(h.holdActive)
        assertEquals(0L, h.lastChargingAt)
        // Subsequent Idle isn't retro-suppressed by the cleared latch.
        h = nextChargeHold(false, false, h.lastChargingAt, now = t0 + 2_000L)
        assertFalse(h.holdActive)
    }

    @Test fun neverChargedMeansNoHold() {
        assertFalse(nextChargeHold(false, false, 0L, now = 123L).holdActive)
    }

    // --- tempSeverity: pins the TempRank → shared-severity mapping (UI-12) ---
    // Fails loudly if TempRank is reordered or the scale drifts from the capacity constants.

    @Test fun tempSeverityMappingIsPinned() {
        assertEquals(SEVERITY_NONE, tempSeverity(TempRank.SAFE))
        assertEquals(0, tempSeverity(TempRank.CAUTION))
        assertEquals(1, tempSeverity(TempRank.WARNING))
        assertEquals(3, tempSeverity(TempRank.CRITICAL))
        assertEquals(4, tempSeverity(TempRank.CUTOFF))
        // Scale invariants the arbitration depends on:
        assertEquals(2, CAP_SEVERITY_WARNING)
        assertEquals(3, CAP_SEVERITY_CRITICAL)
        // temp CRITICAL ties capacity critical (tie → temperature); CUTOFF outranks everything.
        assertEquals(CAP_SEVERITY_CRITICAL, tempSeverity(TempRank.CRITICAL))
        assertTrue(tempSeverity(TempRank.CUTOFF) > CAP_SEVERITY_CRITICAL)
        // caution/warning stay below both capacity severities (they never take the stage).
        assertTrue(tempSeverity(TempRank.WARNING) < CAP_SEVERITY_WARNING)
    }
}
