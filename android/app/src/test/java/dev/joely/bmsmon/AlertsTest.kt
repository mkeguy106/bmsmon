package dev.joely.bmsmon

import dev.joely.bmsmon.model.AlertConfig
import dev.joely.bmsmon.model.AlertEval
import dev.joely.bmsmon.model.BatteryState
import dev.joely.bmsmon.model.BatteryStatus
import dev.joely.bmsmon.model.CAP_SEVERITY_CRITICAL
import dev.joely.bmsmon.model.CAP_SEVERITY_WARNING
import dev.joely.bmsmon.model.CHARGE_SUPPRESS_HOLD_MS
import dev.joely.bmsmon.model.NOTIFY_VANISH_GRACE_MS
import dev.joely.bmsmon.model.PackSoc
import dev.joely.bmsmon.model.SEVERITY_NONE
import dev.joely.bmsmon.model.TempRank
import dev.joely.bmsmon.model.Telemetry
import dev.joely.bmsmon.model.evalStageAlert
import dev.joely.bmsmon.model.fleetCapacityEvals
import dev.joely.bmsmon.model.nextChargeHold
import dev.joely.bmsmon.model.nextNotifyDecision
import dev.joely.bmsmon.model.reconcileFleetNotifications
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

    // --- reconcileFleetNotifications: per-pack, fleet-wide dedup (a 2nd low pack isn't masked) ---

    @Test fun twoLowPacksEachFireIndependently() {
        val r = reconcileFleetNotifications(mapOf("A" to eval(30), "B" to eval(80)), emptyMap())
        assertEquals(setOf("A", "B"), r.notify)
        assertTrue(r.cancel.isEmpty())
        assertEquals(30, r.newLast["A"]); assertEquals(80, r.newLast["B"])
    }

    @Test fun eachPackDedupsAgainstItsOwnBaseline() {
        // A sits in the same band (quiet); B escalates to a lower band (fires) — independently.
        val r = reconcileFleetNotifications(
            mapOf("A" to eval(80), "B" to eval(30)),
            last = mapOf("A" to 80, "B" to 80),
        )
        assertEquals(setOf("B"), r.notify)
        assertEquals(80, r.newLast["A"]); assertEquals(30, r.newLast["B"])
    }

    @Test fun recoveredPackCancelsWithoutAffectingItsPeer() {
        val r = reconcileFleetNotifications(
            mapOf("A" to eval(null), "B" to eval(30)),
            last = mapOf("A" to 30, "B" to 30),
        )
        assertEquals(setOf("A"), r.cancel)
        assertFalse("B" in r.cancel)
        assertFalse("B" in r.notify)   // B still in its band → quiet, baseline kept
        assertEquals(30, r.newLast["B"])
    }

    @Test fun packThatVanishesFromTheFleetIsCancelled() {
        // B went unreachable (dropped out of the eval map) → its notification is cancelled.
        val r = reconcileFleetNotifications(mapOf("A" to eval(30)), last = mapOf("A" to 30, "B" to 30))
        assertEquals(setOf("B"), r.cancel)
        assertFalse("A" in r.notify)   // A unchanged band
    }

    @Test fun chargingPackCancelsFleetWide() {
        val r = reconcileFleetNotifications(mapOf("A" to eval(30, charging = true)), last = mapOf("A" to 30))
        assertEquals(setOf("A"), r.cancel)
        assertNull(r.newLast["A"])
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

    // --- BLE-28: only packs that were actually notified are ever cancelled ---

    @Test fun healthyPackThatWasNeverNotifiedIsNotCancelled() {
        // Previously every healthy/charging pack landed in the cancel set on every evaluation; the
        // notifier skips packs it never posted, but a once-notified pack that recovered kept
        // costing an nm.cancel IPC per evaluation — with BLE-14's every-frame evaluation that is
        // ~2/s per such pack, for nothing.
        val r = reconcileFleetNotifications(mapOf("A" to eval(null), "B" to eval(30, charging = true)), emptyMap())
        assertTrue(r.cancel.isEmpty())
    }

    // --- fleetCapacityEvals: the engine's per-pack evaluation, extracted verbatim ---

    private fun capTel(soc: Float, state: BatteryState) = Telemetry(
        "x", soc = soc, powerW = 0f, current = 0f, voltage = 13f, capacityAh = soc,
        cellV = 3.3f, temp = 25f, state = state,
    )

    @Test fun fleetEvalSkipsUnreachableAndEvaluatesEachPackAlone() {
        val fleet = mapOf(
            "A" to BatteryStatus(capTel(12f, BatteryState.Discharging), reachable = true),
            "B" to BatteryStatus(capTel(80f, BatteryState.Idle), reachable = true),
            "C" to BatteryStatus(capTel(5f, BatteryState.Idle), reachable = false),
        )
        val fc = fleetCapacityEvals(fleet, cfg(setOf(30, 15), critical = 15), emptyMap(), nowMs = 1_000L)
        assertEquals(setOf("A", "B"), fc.evals.keys)
        assertEquals(15, fc.evals.getValue("A").activeThreshold)
        assertNull(fc.evals.getValue("B").activeThreshold)
    }

    @Test fun fleetEvalHoldsTheChargeLatchThroughAnIdleFlap() {
        val charging = mapOf("A" to BatteryStatus(capTel(12f, BatteryState.Charging), reachable = true))
        val first = fleetCapacityEvals(charging, cfg(setOf(15), critical = 15), emptyMap(), nowMs = 1_000L)
        val idle = mapOf("A" to BatteryStatus(capTel(12f, BatteryState.Idle), reachable = true))
        val second = fleetCapacityEvals(idle, cfg(setOf(15), critical = 15), first.chargeAt, nowMs = 2_000L)
        assertTrue(second.evals.getValue("A").charging)   // latched: no cancel/re-notify strobe
    }

    // --- Tier-2 follow-up: a regen burst reporting state=Charging is not a charger ---
    // Production: 86 of 531 regen samples (16 %) carry BMS state=Charging.

    @Test fun aRegenFrameReportingChargingStillAlerts() {
        val fleet = mapOf("A" to BatteryStatus(capTel(12f, BatteryState.Charging), reachable = true))
        val fc = fleetCapacityEvals(
            fleet, cfg(setOf(30, 15), critical = 15), emptyMap(), nowMs = 1_000L, regenAddrs = setOf("A"),
        )
        val e = fc.evals.getValue("A")
        assertFalse(e.charging)
        assertEquals(15, e.activeThreshold)
        assertEquals("and it never arms the charge latch", 0L, fc.chargeAt.getValue("A"))
    }

    // Review Focus 5: mid-drive, a low pack's notification must survive the burst untouched.
    @Test fun regenBurstReportingChargingKeepsTheNotification() {
        val cfg = cfg(setOf(30, 15), critical = 15)
        val regen = mapOf("A" to BatteryStatus(capTel(12f, BatteryState.Charging), reachable = true))
        val idle = mapOf("A" to BatteryStatus(capTel(12f, BatteryState.Idle), reachable = true))
        val during = fleetCapacityEvals(regen, cfg, emptyMap(), nowMs = 1_000L, regenAddrs = setOf("A"))
        val plan1 = reconcileFleetNotifications(during.evals, last = mapOf("A" to 15))
        assertTrue(plan1.cancel.isEmpty())
        assertTrue(plan1.notify.isEmpty())
        val after = fleetCapacityEvals(idle, cfg, during.chargeAt, nowMs = 3_000L)
        val plan2 = reconcileFleetNotifications(after.evals, plan1.newLast)
        assertTrue(plan2.cancel.isEmpty())
        assertTrue(plan2.notify.isEmpty())
    }

    // --- BLE-24: a low pack on a flapping link keeps its notification through a short absence ---

    private val grace = NOTIFY_VANISH_GRACE_MS

    @Test fun aLowPackThatDropsOffKeepsItsNotificationAndBaseline() {
        val r = reconcileFleetNotifications(
            emptyMap(), last = mapOf("A" to 15), holdable = setOf("A"), nowMs = 1_000L, graceMs = grace,
        )
        assertTrue(r.cancel.isEmpty())
        assertEquals(15, r.newLast["A"])
        assertEquals(mapOf("A" to 1_000L), r.vanishedAt)
    }

    // Review Focus 3: five reconnect cycles, two minutes out of range each — one alarm, no cancel.
    @Test fun flappingLowPackAlarmsOnceAcrossReconnects() {
        var last: Map<String, Int?> = emptyMap()
        var gone: Map<String, Long> = emptyMap()
        var alarms = 0
        var cancels = 0
        var t = 0L
        repeat(5) {
            val up = reconcileFleetNotifications(mapOf("A" to eval(15)), last, gone, setOf("A"), t, grace)
            alarms += up.notify.size; cancels += up.cancel.size; last = up.newLast; gone = up.vanishedAt
            t += 60_000L
            val down = reconcileFleetNotifications(emptyMap(), last, gone, setOf("A"), t, grace)
            alarms += down.notify.size; cancels += down.cancel.size; last = down.newLast; gone = down.vanishedAt
            t += 120_000L
        }
        assertEquals(1, alarms)
        assertEquals(0, cancels)
    }

    @Test fun aReturnToALowerBandStillAlarms() {
        val r = reconcileFleetNotifications(
            mapOf("A" to eval(10)), last = mapOf("A" to 15), vanishedAt = mapOf("A" to 0L),
            holdable = setOf("A"), nowMs = 60_000L, graceMs = grace,
        )
        assertEquals(setOf("A"), r.notify)
        assertTrue(r.vanishedAt.isEmpty())
    }

    @Test fun theGraceRunsFromTheFirstDropThenCancels() {
        val held = reconcileFleetNotifications(emptyMap(), mapOf("A" to 15), mapOf("A" to 0L), setOf("A"), grace - 1, grace)
        assertTrue(held.cancel.isEmpty())
        assertEquals("the clock is not restarted by later evaluations", 0L, held.vanishedAt["A"])
        val expired = reconcileFleetNotifications(emptyMap(), held.newLast, held.vanishedAt, setOf("A"), grace, grace)
        assertEquals(setOf("A"), expired.cancel)
        assertNull(expired.newLast["A"])
        assertTrue(expired.vanishedAt.isEmpty())
    }

    @Test fun aPackOutsideTheHoldableSetCancelsAtOnce() {
        // user-disconnected, removed, or alerts off — the engine leaves it out of holdable
        val r = reconcileFleetNotifications(emptyMap(), mapOf("A" to 15), holdable = emptySet(), nowMs = 1_000L, graceMs = grace)
        assertEquals(setOf("A"), r.cancel)
    }

    @Test fun aPackThatReturnsChargingCancelsAtOnce() {
        val r = reconcileFleetNotifications(
            mapOf("A" to eval(15, charging = true)), mapOf("A" to 15), mapOf("A" to 0L), setOf("A"), 60_000L, grace,
        )
        assertEquals(setOf("A"), r.cancel)
    }
}
