package dev.joely.bmsmon.ui.settings

/**
 * The Alerts page's summary box. [seizeThreshold] is the seize level in force (UiState.seizeThreshold),
 * null while the seize is off. The seize runs at its own level even with every ladder rung turned off,
 * so the box never calls alerts off while low packs are still pulled onto the stage.
 */
internal fun alertSummaryLine(alertsOn: Boolean, enabledThresholds: Set<Int>, seizeThreshold: Int?): String {
    if (!alertsOn) return "Low-battery alerts are off."
    val next = enabledThresholds.maxOrNull()
    return if (next != null) {
        "Next alert fires when any pack — on stage or not — drops to $next%." +
            (seizeThreshold?.let { " A pack at or below $it% is pulled onto the stage." } ?: "")
    } else {
        "No alert levels are on, so no low-battery alert fires." +
            (seizeThreshold?.let { " A pack at or below $it% is still pulled onto the stage." } ?: "")
    }
}
