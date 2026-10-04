package dev.joely.bmsmon.ui

import dev.joely.bmsmon.Screen
import dev.joely.bmsmon.StageAlert

enum class AlertPresentation { NONE, OVERLAY, STATUS_PILL, BANNER }

/**
 * Where the stage alert shows (UI-18). A flashing alert is the full-screen overlay on EVERY screen
 * (it never yanks navigation — the user may be mid-edit); once acknowledged it is the status-line
 * pill on Home (both pager pages) and a banner everywhere else, until the condition clears.
 */
fun alertPresentation(alert: StageAlert, screen: Screen): AlertPresentation = when {
    alert.flashing -> AlertPresentation.OVERLAY
    !alert.present -> AlertPresentation.NONE
    screen == Screen.Home -> AlertPresentation.STATUS_PILL
    else -> AlertPresentation.BANNER
}

/**
 * Whether open transients close for [presentation]: a destructive-action confirmation and the scan sheet
 * open in their own windows, above the full-screen overlay, so while the alert flashes they close. An
 * active danger alert outranks anything transient and re-openable. The overlay stays in the activity
 * window; App provides this to the confirmations as [LocalDismissTransients] and closes the scan sheet.
 */
fun dismissTransientsFor(presentation: AlertPresentation): Boolean = presentation == AlertPresentation.OVERLAY
