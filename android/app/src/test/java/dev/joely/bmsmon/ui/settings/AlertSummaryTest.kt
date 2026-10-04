package dev.joely.bmsmon.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Alerts page's summary box: what fires next, and whether low packs are still pulled onto the stage. */
class AlertSummaryTest {
    @Test fun theNextAlertAndThePullLevel() {
        assertEquals(
            "Next alert fires when any pack — on stage or not — drops to 30%. A pack at or below 25% is pulled onto the stage.",
            alertSummaryLine(alertsOn = true, enabledThresholds = setOf(30, 20, 10), seizeThreshold = 25),
        )
        assertEquals(
            "Next alert fires when any pack — on stage or not — drops to 30%.",
            alertSummaryLine(alertsOn = true, enabledThresholds = setOf(30, 20, 10), seizeThreshold = null),
        )
    }

    // With every rung off, the seize still runs at its own level: the box must not say alerts are off.
    @Test fun anEmptyLadderStillNamesTheActivePull() {
        val line = alertSummaryLine(alertsOn = true, enabledThresholds = emptySet(), seizeThreshold = 30)
        assertFalse(line, line.contains("alerts are off"))
        assertTrue(line, line.contains("No alert levels are on"))
        assertTrue(line, line.endsWith("A pack at or below 30% is still pulled onto the stage."))
        assertEquals(
            "No alert levels are on, so no low-battery alert fires.",
            alertSummaryLine(alertsOn = true, enabledThresholds = emptySet(), seizeThreshold = null),
        )
    }

    @Test fun alertsOffIsSaidPlainly() {
        assertEquals(
            "Low-battery alerts are off.",
            alertSummaryLine(alertsOn = false, enabledThresholds = setOf(30), seizeThreshold = null),
        )
    }
}
