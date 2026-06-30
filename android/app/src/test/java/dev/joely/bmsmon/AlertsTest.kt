package dev.joely.bmsmon

import dev.joely.bmsmon.model.AlertConfig
import dev.joely.bmsmon.model.AlertEval
import dev.joely.bmsmon.model.PackSoc
import dev.joely.bmsmon.model.evalStageAlert
import dev.joely.bmsmon.model.nextNotifyDecision
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
}
