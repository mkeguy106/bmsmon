package dev.joely.bmsmon.model

import kotlin.math.roundToInt

/** User-tunable temperature thresholds (stored in °C). Defaults are the Redodo factory values. */
data class TempThresholds(
    val coldCautionC: Int = 5,
    val hotCautionC: Int = 45,
    val coldCritC: Int = -12,
    val hotCritC: Int = 53,
)

/**
 * Fixed hardware envelope from the battery profile: the BMS cutoffs and charge lock/resume points,
 * plus the factory default thresholds. Reset-to-defaults reads [defaults] from here.
 */
data class TempEnvelope(
    val coldCutoffC: Int = -20,
    val hotCutoffC: Int = 60,
    val chargeLockColdC: Int = 0,
    val chargeResumeColdC: Int = 5,
    val chargeLockHotC: Int = 50,
    val defaults: TempThresholds = TempThresholds(),
)

enum class TempSide { COLD, HOT, NONE }

/** Severity, ascending. Stage flashes on rank >= CRITICAL. */
enum class TempRank { SAFE, CAUTION, WARNING, CRITICAL, CUTOFF }

data class TempZone(val rank: TempRank, val side: TempSide)

/**
 * Classify a cell temperature against the thresholds + fixed envelope, severity-first (cold→hot).
 * WARNING is the charge-lock point (0°C cold / 50°C hot); CRITICAL fires before the BMS cutoff so the
 * chair is warned with power to spare.
 */
fun tempZone(tempC: Float, t: TempThresholds, env: TempEnvelope): TempZone = when {
    tempC <= env.coldCutoffC -> TempZone(TempRank.CUTOFF, TempSide.COLD)
    tempC <= t.coldCritC -> TempZone(TempRank.CRITICAL, TempSide.COLD)
    tempC <= env.chargeLockColdC -> TempZone(TempRank.WARNING, TempSide.COLD)
    tempC <= t.coldCautionC -> TempZone(TempRank.CAUTION, TempSide.COLD)
    tempC >= env.hotCutoffC -> TempZone(TempRank.CUTOFF, TempSide.HOT)
    tempC >= t.hotCritC -> TempZone(TempRank.CRITICAL, TempSide.HOT)
    tempC >= env.chargeLockHotC -> TempZone(TempRank.WARNING, TempSide.HOT)
    tempC >= t.hotCautionC -> TempZone(TempRank.CAUTION, TempSide.HOT)
    else -> TempZone(TempRank.SAFE, TempSide.NONE)
}

/** Mercury/marker height for the vertical gauge: the −30…+70°C span maps to 0…100%. */
fun tempFillPct(tempC: Float): Float = (tempC + 30f).coerceIn(0f, 100f)

/** °C margin to the relevant BMS cutoff for a [zone] (always >= 0 within the active side). */
fun tempMarginToCutoffC(tempC: Float, side: TempSide, env: TempEnvelope): Int = when (side) {
    TempSide.COLD -> (tempC.roundToInt() - env.coldCutoffC)
    TempSide.HOT -> (env.hotCutoffC - tempC.roundToInt())
    TempSide.NONE -> 0
}

/** Which kind of stage alert is showing (drives the overlay headline + worst-alert selection). */
enum class AlertKind { CAPACITY, TEMPERATURE }

/** Side of the SOC gauge the temperature gauge sits on. */
enum class GaugeSide { LEFT, RIGHT }

enum class TempUnit { C, F }

fun cToF(c: Float): Int = (c * 9f / 5f + 32f).roundToInt()

/** Render an absolute temperature in the user's unit (default °F). */
fun formatTemp(c: Float, unit: TempUnit): String =
    if (unit == TempUnit.F) "${cToF(c)}°F" else "${c.roundToInt()}°C"

/** Render a temperature *difference* (no +32 offset): Δ°F = round(Δ°C × 9/5). */
fun formatDelta(dC: Int, unit: TempUnit): String =
    if (unit == TempUnit.F) "${(dC * 9f / 5f).roundToInt()}°F" else "${dC}°C"

/** One pack's live temperature reading, classified, for the headless temperature notifications. */
data class PackTemp(val addr: String, val telemetry: Telemetry, val zone: TempZone, val env: TempEnvelope)

/**
 * Every pack whose reading may drive alerts, classified — pass the freshness decision view
 * ([decisionView]), so a carried seed or a silent pack never raises (or holds) an alarm. [limitsFor]
 * gives a pack's thresholds and envelope (its battery profile).
 */
fun packTemps(
    view: Map<String, BatteryStatus>,
    limitsFor: (String) -> Pair<TempThresholds, TempEnvelope>,
): Map<String, PackTemp> = view
    .mapNotNull { (a, s) -> s.telemetry?.takeIf { s.reachable }?.let { a to it } }
    .associate { (a, t) ->
        val (thr, env) = limitsFor(a)
        a to PackTemp(a, t, tempZone(t.temp, thr, env), env)
    }

/** The side and rank a pack's temperature notification was posted at — its dedup key. */
data class TempAlarm(val side: TempSide, val rank: TempRank)

/** Per-pack plan for the temperature notifier, like [FleetNotify]: who to [notify], who to [cancel],
 *  the baselines to carry forward, and when each held pack's absence began ([heldSince]). */
data class TempFleetNotify(
    val newLast: Map<String, TempAlarm>,
    val cancel: Set<String>,
    val notify: Set<String>,
    val heldSince: Map<String, Long> = emptyMap(),
)

/**
 * The headless temperature notifications (BLE-24), one per alarming pack — keyed, deduped and held
 * per (pack, side, rank), like the capacity ones ([reconcileFleetNotifications]).
 *
 * A [stage] pack whose reading in [zones] is CRITICAL or CUTOFF posts when its side/rank differs
 * from the one it last posted at ([last]): a first crossing, an escalation, a change of side — and a
 * different pack reaching the same side/rank posts its own. The same side/rank stays quiet.
 *
 * A notified pack that is no longer an alarming stage pack:
 * - has a reading below CRITICAL on the stage → it recovered: cancelled at once;
 * - is still on the stage with no alert-driving reading → a link flap, which is not a recovery: held
 *   (notification and baseline kept) for [graceMs] from when it began, so a reconnect at the same
 *   side/rank stays quiet — even while its cooler partner stays live, since the hold follows the
 *   pack that alarmed, not the stage;
 * - left the stage because the stage moved → not a flap: cancelled, unless the pack is still read
 *   at CRITICAL or worse on the same side, which holds it for the grace (errs toward alerting).
 *
 * Only a pack in [holdable] — one the user has not disconnected or removed — is ever held, and a
 * hold past the grace cancels. Only packs actually showing a notification are cancelled. The
 * defaults (nothing holdable, no grace) cancel every pack that stops alarming at once.
 */
fun reconcileTempNotifications(
    zones: Map<String, TempZone>,
    stage: Set<String>,
    last: Map<String, TempAlarm>,
    heldSince: Map<String, Long> = emptyMap(),
    holdable: Set<String> = emptySet(),
    nowMs: Long = 0L,
    graceMs: Long = 0L,
): TempFleetNotify {
    val onStage = stage.map { it.uppercase() }.toSet()
    val canHold = holdable.map { it.uppercase() }.toSet()
    val newLast = mutableMapOf<String, TempAlarm>()
    val notify = mutableSetOf<String>()
    val cancel = mutableSetOf<String>()
    val held = mutableMapOf<String, Long>()
    for ((addr, z) in zones) {
        if (addr.uppercase() !in onStage || z.rank < TempRank.CRITICAL) continue
        val key = TempAlarm(z.side, z.rank)
        if (last[addr] != key) notify += addr
        newLast[addr] = key
    }
    for ((addr, shown) in last) {
        if (addr in newLast) continue
        val live = zones[addr]
        val absent = if (addr.uppercase() in onStage) {
            live == null                                                  // a flap
        } else {
            live != null && live.side == shown.side && live.rank >= TempRank.CRITICAL   // moved away, still hot
        }
        val since = heldSince[addr] ?: nowMs
        if (absent && addr.uppercase() in canHold && nowMs - since < graceMs) {
            newLast[addr] = shown
            held[addr] = since
        } else {
            cancel += addr
        }
    }
    return TempFleetNotify(newLast, cancel, notify, held)
}
