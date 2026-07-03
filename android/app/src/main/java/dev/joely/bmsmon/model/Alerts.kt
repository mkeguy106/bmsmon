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

/**
 * Shared severity scale for the capacity-vs-temperature worst-of arbitration (UI-12). These used
 * to be raw literals aligned with `TempRank.ordinal`, which silently broke if the enum was ever
 * reordered — [tempSeverity] pins the mapping explicitly (exhaustive `when`, test-locked).
 */
const val SEVERITY_NONE = -1
const val CAP_SEVERITY_WARNING = 2
const val CAP_SEVERITY_CRITICAL = 3

/** A temperature rank's severity on the shared scale. CRITICAL ties capacity-critical (and the
 *  tie goes to temperature in [pickStageAlert]); CUTOFF outranks everything. */
fun tempSeverity(rank: TempRank): Int = when (rank) {
    TempRank.SAFE -> SEVERITY_NONE
    TempRank.CAUTION -> 0
    TempRank.WARNING -> 1
    TempRank.CRITICAL -> 3
    TempRank.CUTOFF -> 4
}

/**
 * Worst-of arbitration between the capacity and temperature stage alerts. Severities: -1 = no
 * alert present; capacity warning = 2 / capacity critical = 3; temperature per [tempSeverity]
 * (CRITICAL = 3, CUTOFF = 4). Flashing = present AND not acknowledged.
 *
 * An alert that would flash always beats one that is present but acknowledged — an acked temp
 * CRITICAL must not mask an un-acked capacity alert (or vice versa), or a flash the user never
 * silenced would be suppressed. When both (or neither) flash, the higher raw severity wins, and
 * temperature takes a tie (it warns before the BMS cutoff). With neither alert present the
 * capacity kind carries the "no alert" fields.
 */
fun pickStageAlert(
    capSeverity: Int,
    capFlashing: Boolean,
    tempSeverity: Int,
    tempFlashing: Boolean,
): AlertKind {
    val capPresent = capSeverity >= 0
    val tempPresent = tempSeverity >= 0
    return when {
        !capPresent || !tempPresent -> if (tempPresent) AlertKind.TEMPERATURE else AlertKind.CAPACITY
        tempFlashing != capFlashing -> if (tempFlashing) AlertKind.TEMPERATURE else AlertKind.CAPACITY
        else -> if (tempSeverity >= capSeverity) AlertKind.TEMPERATURE else AlertKind.CAPACITY
    }
}

/**
 * Charging-suppression hysteresis (UI-9). At the charger the BMS flaps Idle ↔ Charging as the
 * current tapers, and keying the capacity suppression directly on the instantaneous flag strobed
 * the overlay. Suppression instead latches for [CHARGE_SUPPRESS_HOLD_MS] after the last
 * charging=true evaluation — but a *genuine discharge* (unplugged and driving) clears the latch
 * immediately, so a real low-battery alert is never delayed by the hold.
 */
const val CHARGE_SUPPRESS_HOLD_MS = 30_000L

/** Latch state + resolved suppression for one evaluation. Pure — caller passes time in. */
data class ChargeHold(val lastChargingAt: Long, val holdActive: Boolean)

/**
 * Fold one evaluation of the stage's lowest pack into the charge-suppression latch.
 * [charging]/[discharging] describe that pack's current BMS state; [lastChargingAt] is the
 * previous latch (0 = never). [holdActive] is true while suppression should extend through an
 * Idle flap; the instantaneous `charging` flag itself still suppresses independently.
 */
fun nextChargeHold(charging: Boolean, discharging: Boolean, lastChargingAt: Long, now: Long): ChargeHold = when {
    charging -> ChargeHold(now, true)
    discharging -> ChargeHold(0L, false)   // genuine discharge: un-suppress at once, clear latch
    else -> ChargeHold(lastChargingAt, lastChargingAt > 0 && now - lastChargingAt < CHARGE_SUPPRESS_HOLD_MS)
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

/** Per-address plan for the fleet-wide notifier: who to [notify], who to [cancel], and the new
 *  per-address baselines to carry forward. */
data class FleetNotify(
    val newLast: Map<String, Int?>,
    val cancel: Set<String>,
    val notify: Set<String>,
)

/**
 * Fleet-wide notification reconciliation (pure). Every reachable pack is evaluated independently
 * (its own [AlertEval]) and deduped against its own baseline in [last] via [nextNotifyDecision],
 * so two packs can hold two live low-battery notifications at once and a second low pack is never
 * masked by the first. A pack that has recovered/charged cancels; a pack that has dropped out of
 * [evals] entirely (unreachable / off BLE) also cancels — we alert on live data, not on absence.
 */
fun reconcileFleetNotifications(evals: Map<String, AlertEval>, last: Map<String, Int?>): FleetNotify {
    val newLast = mutableMapOf<String, Int?>()
    val cancel = mutableSetOf<String>()
    val notify = mutableSetOf<String>()
    cancel += last.keys - evals.keys  // packs that vanished this round
    for ((addr, eval) in evals) {
        val d = nextNotifyDecision(eval, last[addr])
        when {
            d.cancel -> cancel += addr
            d.notify -> { notify += addr; newLast[addr] = d.newLastNotified }
            else -> newLast[addr] = d.newLastNotified  // same band → keep baseline, stay quiet
        }
    }
    return FleetNotify(newLast, cancel, notify)
}
