package dev.joely.bmsmon.ui.theme

import dev.joely.bmsmon.model.AlertConfig
import dev.joely.bmsmon.model.BatteryState
import dev.joely.bmsmon.model.PackSoc
import dev.joely.bmsmon.model.StageItem
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

/**
 * The word under a stage pack's number, or null. Only a LIVE reading carries one: a STALE number is
 * muted with its age and a DISCONNECTED pack shows no number, so neither may look live. Nor while the
 * pack is genuinely charging: charging already suppresses the alert flash, the bolt and the "to full"
 * ETA say what is happening, and the ETA needs the room (the word would push the number onto the
 * inner ring and the ETA into the outer one). A Charging frame from regen braking is not charging —
 * the ETA's own rule — so the word, and the number above it, can't flicker on a regen burst mid-drive.
 */
fun stageSeverityWord(item: StageItem, cfg: AlertConfig): String? {
    val live = item.connected && item.staleAgeMs == null
    val charging = item.telemetry.state == BatteryState.Charging && !item.regen
    if (!live || charging) return null
    return socSeverityFor(item.telemetry.soc, cfg).tag()
}
