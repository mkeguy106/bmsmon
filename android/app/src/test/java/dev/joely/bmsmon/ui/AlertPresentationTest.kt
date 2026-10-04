package dev.joely.bmsmon.ui

import dev.joely.bmsmon.Screen
import dev.joely.bmsmon.StageAlert
import dev.joely.bmsmon.model.StageTarget
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    // A confirmation dialog and the scan sheet open in their own windows, above the overlay: while the
    // alert flashes they close, so the alert is seen. Both are transient and can be opened again.
    @Test fun onlyTheOverlayClosesTransients() {
        assertTrue(dismissTransientsFor(AlertPresentation.OVERLAY))
        for (p in AlertPresentation.values().filter { it != AlertPresentation.OVERLAY }) {
            assertFalse("$p", dismissTransientsFor(p))
        }
    }

    @Test fun transientsCloseExactlyWhileAnAlertFlashesOnAnyScreen() {
        for (flash in listOf(true, false)) for (present in listOf(true, false)) for (screen in Screen.values()) {
            val alert = flashing.copy(flashing = flash, present = present)
            assertEquals("flashing=$flash present=$present $screen", flash, dismissTransientsFor(alertPresentation(alert, screen)))
        }
    }

    private fun source(path: String): String =
        listOf("src/main/java", "app/src/main/java")
            .map { File(it, path) }
            .first { it.isFile }
            .readText()

    // The wiring: App provides the decision and closes the scan sheet; every confirmation reads it.
    @Test fun appAndTheConfirmationsAreWiredToTheDecision() {
        val app = source("dev/joely/bmsmon/ui/App.kt")
        assertTrue(app.contains("val dismissTransients = dismissTransientsFor(presentation)"))
        assertTrue(app.contains("LocalDismissTransients provides dismissTransients"))
        assertTrue(app.contains("if (dismissTransients) showScan = false"))
        assertTrue(app.contains("if (showScan && !dismissTransients)"))
        val dialog = source("dev/joely/bmsmon/ui/ConfirmDialog.kt")
        val body = dialog.substringAfter("fun ConfirmDialog(")
        assertTrue(body.indexOf("LocalDismissTransients.current") in 0 until body.indexOf("AlertDialog("))
    }
}
