package dev.joely.bmsmon.ui.theme

import dev.joely.bmsmon.model.AlertConfig
import dev.joely.bmsmon.model.PackSoc
import dev.joely.bmsmon.model.evalStageAlert

enum class SocSeverity { NORMAL, WARNING, CRITICAL }

/**
 * A pack's SOC severity on the user's own ladder (UI-21), by the alerts' own rule ([evalStageAlert]):
 * WARNING once an enabled rung is crossed (at N %, `<=`), CRITICAL once that rung is at or below the
 * critical level. The old fixed 15/30 bands ignored the ladder. Independent of the alerts master
 * switch: severity is information, not an alarm.
 */
fun socSeverityFor(soc: Float, cfg: AlertConfig): SocSeverity {
    val e = evalStageAlert(listOf(PackSoc(soc, charging = false)), cfg.copy(alertsOn = true))
    return when {
        e.activeThreshold == null -> SocSeverity.NORMAL
        e.critical -> SocSeverity.CRITICAL
        else -> SocSeverity.WARNING
    }
}

/** The word beside a severity-colored number: amber-vs-orange must never be the only cue. */
fun SocSeverity.tag(): String? = when (this) {
    SocSeverity.NORMAL -> null
    SocSeverity.WARNING -> "LOW"
    SocSeverity.CRITICAL -> "CRIT"
}
