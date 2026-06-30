package dev.joely.bmsmon.model

import kotlin.math.roundToInt

/** Alert settings, pushed from the ViewModel down to the headless engine. */
data class AlertConfig(
    val alertsOn: Boolean,
    val enabledThresholds: Set<Int>,
    val criticalThreshold: Int,
)

/** A stage pack's inputs to alert evaluation. */
data class PackSoc(val soc: Float, val charging: Boolean)

/**
 * Result of evaluating the stage against [AlertConfig].
 * [activeThreshold] is the lowest enabled threshold the stage has dropped to **or below**
 * (null = no alert). [critical] is true when that band is at or below the critical level.
 */
data class AlertEval(
    val activeThreshold: Int?,
    val critical: Boolean,
    val lowSoc: Int,
    val charging: Boolean,
    val crossed: Set<Int>,
)

/**
 * Pure stage-alert evaluation, shared by the in-app flash (BatteryViewModel) and the headless
 * notifier (MonitorEngine). The lowest reachable pack drives the alert. Uses `<=` so a threshold
 * of N% fires AT N%, matching the "at or below this level" wording (the old `<` only fired at N-1).
 */
fun evalStageAlert(packs: List<PackSoc>, cfg: AlertConfig): AlertEval {
    val low = packs.minByOrNull { it.soc }
        ?: return AlertEval(null, false, 100, false, emptySet())
    val lowSoc = low.soc.roundToInt()
    if (!cfg.alertsOn) return AlertEval(null, false, lowSoc, low.charging, emptySet())
    val crossed = cfg.enabledThresholds.filter { low.soc <= it }.toSet()
    val active = crossed.minOrNull()
    val critical = active != null && active <= cfg.criticalThreshold
    return AlertEval(active, critical, lowSoc, low.charging, crossed)
}

/** Outcome of the notification dedup logic. */
data class NotifyDecision(val notify: Boolean, val cancel: Boolean, val newLastNotified: Int?)

/**
 * Pure dedup for headless notifications. Fire on the first crossing and on each escalation to a
 * more-severe (lower) band; stay quiet while sitting in the same band; cancel when the stage
 * recovers above all thresholds or starts charging. The baseline always tracks the current band,
 * so a recovery-then-re-drop re-notifies.
 */
fun nextNotifyDecision(eval: AlertEval, lastNotified: Int?): NotifyDecision {
    val active = eval.activeThreshold
    if (active == null || eval.charging) return NotifyDecision(notify = false, cancel = true, newLastNotified = null)
    val notify = lastNotified == null || active < lastNotified
    return NotifyDecision(notify = notify, cancel = false, newLastNotified = active)
}
