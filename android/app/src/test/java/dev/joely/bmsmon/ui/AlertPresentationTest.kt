package dev.joely.bmsmon.ui

import dev.joely.bmsmon.Screen
import dev.joely.bmsmon.StageAlert
import dev.joely.bmsmon.model.StageTarget
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * UI-18: the alert used to exist only on Home's stage page. On All Batteries, Settings, Detail or
 * History a live alert — temperature CRITICAL/CUTOFF included — had no in-app presence at all.
 */
class AlertPresentationTest {
    private val target = StageTarget.Base("2012")
    private val flashing = StageAlert(flashing = true, critical = true, lowSoc = 12, activeThreshold = 15, target = target, present = true)
    private val acked = flashing.copy(flashing = false)
    private val none = StageAlert(flashing = false, critical = false, lowSoc = 80, activeThreshold = null, target = target)

    @Test fun aFlashingAlertIsTheOverlayOnEveryScreen() {
        for (screen in Screen.values()) assertEquals("$screen", AlertPresentation.OVERLAY, alertPresentation(flashing, screen))
    }

    @Test fun anAcknowledgedAlertIsThePillOnHomeAndABannerEverywhereElse() {
        assertEquals(AlertPresentation.STATUS_PILL, alertPresentation(acked, Screen.Home))
        for (screen in Screen.values().filter { it != Screen.Home }) {
            assertEquals("$screen", AlertPresentation.BANNER, alertPresentation(acked, screen))
        }
    }

    @Test fun noConditionShowsNothing() {
        for (screen in Screen.values()) assertEquals(AlertPresentation.NONE, alertPresentation(none, screen))
    }
}
