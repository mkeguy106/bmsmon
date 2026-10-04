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
 * Re-arm hysteresis for an acknowledged capacity rung (M4): the ack for rung r is kept while the
 * stage's lowest alert-driving pack reads below r + this margin. The BMS reports SOC as an integer
 * percent and regen braking ticks it up by 1 % at a time, so without a margin a regen uptick at a
 * rung boundary cleared the ack and the very next downtick re-flashed the rung mid-drive. 2 % is
 * the smallest margin a single 1 % uptick can't cross. A stage change or real (non-regen) charging
 * still clears acks at once.
 */
const val ACK_REARM_MARGIN_PCT = 2

/** The acknowledged rungs still held at [lowSoc] (the stage's lowest alert-driving pack): an enabled
 *  rung stays acknowledged until [lowSoc] reaches rung + [ACK_REARM_MARGIN_PCT]; with alerts off
 *  nothing is held. Charging and stage changes are the caller's (they clear everything). */
fun heldCapAcks(acks: Set<Int>, lowSoc: Float, cfg: AlertConfig): Set<Int> =
    if (!cfg.alertsOn) emptySet()
    else acks.filterTo(mutableSetOf()) { it in cfg.enabledThresholds && lowSoc < it + ACK_REARM_MARGIN_PCT }

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

/**
 * How long a low pack that drops out of the decision view (a link flap, or silent past the freshness
 * backstop) keeps its notification and baseline (BLE-24) — see [reconcileFleetNotifications].
 */
const val NOTIFY_VANISH_GRACE_MS = 10 * 60_000L

/** Per-address plan for the fleet-wide notifier: who to [notify], who to [cancel], the new
 *  per-address baselines to carry forward, and — for packs held through an absence — when each
 *  vanished ([vanishedAt], to pass back in on the next call). */
data class FleetNotify(
    val newLast: Map<String, Int?>,
    val cancel: Set<String>,
    val notify: Set<String>,
    val vanishedAt: Map<String, Long> = emptyMap(),
)

/**
 * Fleet-wide notification reconciliation (pure). Every reachable pack is evaluated independently
 * (its own [AlertEval]) and deduped against its own baseline in [last] via [nextNotifyDecision],
 * so two packs can hold two live low-battery notifications at once and a second low pack is never
 * masked by the first. A pack that has recovered/charged cancels at once. Only packs present in
 * [last] (i.e. currently notified) are ever cancelled (BLE-28).
 *
 * A notified pack that drops out of [evals] (unreachable, or silent past the backstop) is NOT a
 * recovery (BLE-24): if it is in [holdable] it keeps its notification and its baseline until
 * [graceMs] after it first vanished ([vanishedAt]), so a link that flaps every few minutes no longer
 * cancels and re-alarms the sound+vibration critical alert each time — a reconnect in the same band
 * stays quiet, a lower band still alarms. Past the grace, or outside [holdable] (disconnected by the
 * user, removed, alerts off), it cancels: we alert on live data, not on absence. The defaults
 * (nothing holdable, no grace) cancel every vanished pack at once.
 */
fun reconcileFleetNotifications(
    evals: Map<String, AlertEval>,
    last: Map<String, Int?>,
    vanishedAt: Map<String, Long> = emptyMap(),
    holdable: Set<String> = emptySet(),
    nowMs: Long = 0L,
    graceMs: Long = 0L,
): FleetNotify {
    val newLast = mutableMapOf<String, Int?>()
    val cancel = mutableSetOf<String>()
    val notify = mutableSetOf<String>()
    val held = mutableMapOf<String, Long>()
    for (addr in last.keys - evals.keys) {   // packs that dropped out this round
        val since = vanishedAt[addr] ?: nowMs
        if (addr.uppercase() in holdable && nowMs - since < graceMs) {
            newLast[addr] = last[addr]       // keep the notification AND its baseline
            held[addr] = since
        } else {
            cancel += addr
        }
    }
    for ((addr, eval) in evals) {
        val d = nextNotifyDecision(eval, last[addr])
        when {
            // BLE-28: cancel only a pack that is actually showing a notification (has a baseline).
            d.cancel -> if (addr in last) cancel += addr
            d.notify -> { notify += addr; newLast[addr] = d.newLastNotified }
            else -> newLast[addr] = d.newLastNotified  // same band → keep baseline, stay quiet
        }
    }
    return FleetNotify(newLast, cancel, notify, held)
}

/** Every alert-driving pack's own [AlertEval] plus the advanced per-pack charge latch. */
data class FleetCapacity(val evals: Map<String, AlertEval>, val chargeAt: Map<String, Long>)

/**
 * Fleet-wide capacity evaluation (moved verbatim out of MonitorEngine.evaluateAlerts): every
 * reachable pack in [fleet] — pass the freshness decision view, so seeds and silent packs are
 * already unreachable — is evaluated against [cfg] on its own, with the per-pack charging
 * hysteresis (UI-9) presented as `charging` so an Idle/Charging flap can't strobe notifications; a
 * regen pack never counts as charging, nor arms that latch.
 */
fun fleetCapacityEvals(
    fleet: Map<String, BatteryStatus>,
    cfg: AlertConfig,
    chargeAt: Map<String, Long>,
    nowMs: Long,
    regenAddrs: Set<String> = emptySet(),
): FleetCapacity {
    val nextCharge = chargeAt.toMutableMap()
    val evals = fleet.mapNotNull { (addr, s) ->
        val tel = s.telemetry?.takeIf { s.reachable } ?: return@mapNotNull null
        // A pack inside its regen window ([regenAddrs]) is not on a charger, whatever its state
        // field says: 16 % of production regen samples carry state=Charging, and counting one as
        // charging cancelled a low pack's notification mid-drive, then re-alarmed it once the latch
        // that frame armed had expired. Same rule as the stage's ack re-arm.
        val charging = tel.state == BatteryState.Charging && addr !in regenAddrs
        val hold = nextChargeHold(
            charging = charging,
            discharging = tel.state == BatteryState.Discharging,
            lastChargingAt = chargeAt[addr] ?: 0L,
            now = nowMs,
        )
        nextCharge[addr] = hold.lastChargingAt
        val eval = evalStageAlert(listOf(PackSoc(tel.soc, charging)), cfg)
        addr to (if (hold.holdActive && !eval.charging) eval.copy(charging = true) else eval)
    }.toMap()
    return FleetCapacity(evals, nextCharge)
}
