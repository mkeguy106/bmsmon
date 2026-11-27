package dev.joely.bmsmon.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Pure SOC estimator. The BMS's own SOC/remaining-Ah is never altered; the estimate is the BMS
// reading minus a held per-pack correction (Ah), so it can only ever read lower than the BMS.
// No Android imports, no clock: callers pass every timestamp in.

/** A charge counts as full when its charger taper (>= this voltage ...) */
const val TAPER_MIN_V = 13.90f
/** ... with current below this (A) was seen within [TAPER_WINDOW_MS] of the charge end. */
const val TAPER_MAX_A = 3.0f
const val TAPER_WINDOW_MS = 15 * 60_000L
/** The BMS's full-detect clamp: remaining within this of full means "clamped to full". */
const val CLAMP_TOL_AH = 0.1f
/** Remaining above full by more than this is a counter overshoot. */
const val OVERSHOOT_AH = 0.5f
/** A raw counter rise bigger than this between sightings, with no Charging seen, is an unseen charge. */
const val UNSEEN_CHARGE_RISE_AH = 2.0f
/** Zero current for this long before the voltage rule may apply. */
const val REST_MIN_MS = 30 * 60_000L
/** A silence longer than this restarts rest tracking. */
const val REST_GAP_MS = 10 * 60_000L
/** At or above this the rested voltage is the top plateau: no estimate. */
const val VOLTAGE_PLATEAU_V = 13.30f
const val FIRST_VOLTAGE_MARGIN_PTS = 10f
const val FOLLOW_DOWN_MARGIN_PTS = 3f
/** Standby drain before a pack has learned its own (2012 packs ran 2.1-2.9 Ah/day in 2026-11). */
const val SEED_DRAIN_AH_PER_DAY = 2.5f
/** The drain stops growing this long after the anchor (longest real gap between full charges: 9 days). */
const val MAX_DRAIN_DAYS = 14f
const val DRAIN_EMA_ALPHA = 0.5f
/** A shorter interval gives too noisy a rate to learn from. */
const val MIN_DRAIN_LEARN_DAYS = 1f
/** A measured rate outside 0..this is not a standby drain; it is not learned. */
const val MAX_DRAIN_AH_PER_DAY = 8f
/** Charging frames further apart than this leave Ah unseen: that charge teaches nothing. */
const val CHARGE_GAP_MS = 2 * 60_000L
/** Delivered Ah the counter did not register only count past this: below it is rounding. Over
 *  November 2026's charges the counter's rise matched current x time within 0.02 Ah in 50. */
const val UNCOUNTED_MIN_AH = 0.1f

enum class EstSource { COUNTER, VOLTAGE }

/** The held correction: estimateAh = rawRemainingAh - correctionAh. */
data class SocCorrection(
    val correctionAh: Float = 0f,
    val source: EstSource? = null,
    val setAtMs: Long = 0L,
)

/** The reading fields the estimator needs (current positive = charging). */
data class EstReading(
    val tsMs: Long,
    val state: BatteryState,
    val regen: Boolean,
    val currentA: Float,
    val voltageV: Float,
    val remainingAh: Float,
    val fullChargeAh: Float,
    val soc: Float,
    /** The BMS's pending correction to its own counter (Ah); negative while it is lowering it. */
    val pendingAh: Float = 0f,
)

/**
 * The standby-drain model. The BMS counter is right at a full charge and then misses every load
 * under its ~1.04 A deadband (the chair's electronics, the phone's charger): ~2-4 Ah a day,
 * proportional to elapsed time. [anchorAtMs] is the end of the last full charge seen (wall clock);
 * null means no drain is subtracted. [ahPerDay] is the learned rate; null means the seed.
 * [anchorRawAh] is the raw counter at the anchor, kept only to learn the next rate from how far
 * the counter then falls; null when that fall is no longer known.
 */
data class DrainModel(val anchorAtMs: Long? = null, val ahPerDay: Float? = null, val anchorRawAh: Float? = null)

/** One wall charge being followed, to learn the drain rate from the Ah it delivers. In memory only. */
data class ChargeSeg(
    /** The raw counter just before the charge began. */
    val startRawAh: Float,
    val startMs: Long,
    /** Ah delivered so far (current x time between frames). */
    val ahIn: Float,
    /** False once any of it went unseen (a gap, or first seen already charging). */
    val valid: Boolean,
    /**
     * Ah delivered that the BMS counter did not register and the correction does not yet hold.
     * The counter stops at a ceiling (120 Ah on these packs), which a pack whose counter starts
     * far too high reaches hours before it is full. Summed frame by frame over seen frames only,
     * and signed: rounding in either direction cancels. See [uncountedAh].
     */
    val uncountedAh: Float = 0f,
)

/** Per-pack state. [correction] and [drain] are persisted; the rest is in-memory tracking. */
data class EstPackState(
    val correction: SocCorrection = SocCorrection(),
    val drain: DrainModel = DrainModel(),
    val charge: ChargeSeg? = null,
    val wasCharging: Boolean = false,
    val taperAtMs: Long? = null,
    val restSinceMs: Long? = null,
    val lastTsMs: Long? = null,
    val lastRawAh: Float? = null,
)

data class SocEstimate(val soc: Float, val remainingAh: Float, val source: EstSource?)

/**
 * What is persisted per pack: the held correction and the last raw remaining Ah seen. The last raw
 * is what the unseen-charge rule compares against, so keeping it lets a charge that happened while
 * the phone was off (or the app restarting) still clear a correction on the first reading after.
 */
data class PersistedEst(
    val correction: SocCorrection = SocCorrection(),
    val lastRawAh: Float? = null,
    val drain: DrainModel = DrainModel(),
)

fun EstPackState.toPersisted(): PersistedEst = PersistedEst(correction, lastRawAh, drain)

/** A pack's state at the start of a session: the persisted part, the rest tracked afresh. */
fun PersistedEst.toPackState(): EstPackState =
    EstPackState(correction = correction, lastRawAh = lastRawAh, drain = drain)

/**
 * Whether stepping [prev] to [next] should write the per-pack state: when the correction changed,
 * or at a charge end (the raw counter has just risen while a held correction may be unchanged, and
 * a stale-low persisted last raw could later read a clamp as an unseen charge). Not on every frame:
 * the last raw moves on every frame and rides along with these writes (and stop()'s final one).
 */
fun estPersistWorthy(prev: EstPackState, next: EstPackState): Boolean =
    next.correction != prev.correction || next.drain != prev.drain || (prev.wasCharging && !next.wasCharging)

private const val DAY_MS = 86_400_000f

/** Ah of standby drain the counter has missed since the anchor, at [tsMs]. 0 with no anchor. */
fun drainAh(d: DrainModel, tsMs: Long): Float {
    val anchor = d.anchorAtMs ?: return 0f
    val days = ((tsMs - anchor) / DAY_MS).coerceIn(0f, MAX_DRAIN_DAYS)
    return (d.ahPerDay ?: SEED_DRAIN_AH_PER_DAY) * days
}

/**
 * The drain model after a full charge ending at [endMs] with the raw counter at [endRawAh]:
 * anchored there, and with the rate learned from [seg] when the whole charge was seen. Full to
 * full, the pack really lost exactly what the charger put back ([ChargeSeg.ahIn]); the counter
 * only fell from [anchorRawAh] to [ChargeSeg.startRawAh]. The difference is the drain it missed.
 * No held correction enters into it, so a pack whose counter is never clamped to full learns the
 * same rate. Not learned from a charge with unseen Ah, without a previous anchor and its counter
 * value, over under a day, or when the rate comes out implausible.
 */
private fun DrainModel.afterFullCharge(seg: ChargeSeg?, endRawAh: Float, endMs: Long): DrainModel {
    val anchor = anchorAtMs
    val anchorRaw = anchorRawAh
    var rate = ahPerDay
    if (seg != null && seg.valid && anchor != null && anchorRaw != null) {
        val days = (seg.startMs - anchor) / DAY_MS
        if (days >= MIN_DRAIN_LEARN_DAYS && days <= MAX_DRAIN_DAYS) {
            val drift = seg.ahIn - (anchorRaw - seg.startRawAh)
            val measured = drift / days
            if (measured.isFinite() && measured >= 0f && measured <= MAX_DRAIN_AH_PER_DAY) {
                rate = rate?.let { it + DRAIN_EMA_ALPHA * (measured - it) } ?: measured
            }
        }
    }
    return DrainModel(anchorAtMs = endMs, ahPerDay = rate, anchorRawAh = endRawAh)
}

/**
 * Follows a wall charge so its Ah can be counted. A segment opens on the first charging frame and
 * stays open across a pause; any discharge closes it. It is invalid if it was first seen already
 * charging or any two frames around charging are more than [CHARGE_GAP_MS] apart.
 */
private fun followCharge(prev: EstPackState, r: EstReading, charging: Boolean): ChargeSeg? {
    if (r.currentA < 0f) return null
    val seg = prev.charge
    if (!charging) return seg
    val prevTs = prev.lastTsMs
    val dt = if (prevTs == null) null else r.tsMs - prevTs
    val seen = dt != null && dt >= 0L && dt <= CHARGE_GAP_MS
    val ah = if (seen) r.currentA * dt!! / 3_600_000f else 0f
    // This frame's Ah less what the counter rose by. A counter cannot fall while charging, so a
    // lower reading (a last raw left over from before a restart) is no rise: one frame can add
    // at most its own Ah.
    val startRaw = prev.lastRawAh
    val missed = if (seen && startRaw != null) ah - max(r.remainingAh - startRaw, 0f) else 0f
    if (seg != null) {
        return seg.copy(ahIn = seg.ahIn + ah, valid = seg.valid && seen, uncountedAh = seg.uncountedAh + missed)
    }
    return if (seen && startRaw != null && !prev.wasCharging) {
        ChargeSeg(startRaw, prevTs!!, ah, valid = true, uncountedAh = missed)
    } else {
        ChargeSeg(startRaw ?: r.remainingAh, r.tsMs, 0f, valid = false, uncountedAh = missed)
    }
}

/** Ah of [seg] the charger delivered past a counter that had stopped counting; 0 below [UNCOUNTED_MIN_AH]. */
fun uncountedAh(seg: ChargeSeg?): Float {
    val ah = seg?.uncountedAh ?: return 0f
    return if (ah > UNCOUNTED_MIN_AH) ah else 0f
}

/**
 * (millivolts, percent), ascending. Rested >= 30 min after discharge, 2012 packs.
 *
 * Refit 2026-11-25. The truth is charger-delivered Ah, not the BMS counter: between two full
 * charges, true remaining = counter - drift, where the drift at the next charge start is
 * `counter - (full - Ah the charger then delivers)` and accrues linearly in time. Basis: 66
 * inter-charge intervals, 2026-07..11, 27 k rested 5-min buckets; the drift ran a steady
 * ~2-4 Ah/day (current under the BMS's ~1.04 A deadband is never counted). The first table
 * (2026-11-18) was fitted to the counter itself and so read ~15-20 points high below 13.2 V.
 * Spread: tight (+-2) below 13.12 V and from 13.20 V; 13.12-13.18 V is the steep middle, where
 * 20 mV spans ~10 points (p10-p90 about +-8). Below 12.97 V is extrapolated, not measured.
 */
private val VOLTAGE_TABLE = arrayOf(
    12900 to 21, 12970 to 30, 13010 to 34, 13050 to 37, 13110 to 38, 13130 to 46,
    13150 to 51, 13170 to 57, 13190 to 64, 13210 to 68, 13230 to 70, 13290 to 71,
)

/**
 * Rested pack voltage -> SOC %, linear between table points. 21 at or below 12.90 V (a ceiling:
 * the knee is uncalibrated and lower only means a lower estimate). Null at or above 13.30 V,
 * the plateau where a rested pack can be anywhere from ~72 to ~95 %.
 */
fun voltageSoc(voltageV: Float): Float? {
    // The BMS reports whole millivolts; integer mV keeps the interpolation exact at table points.
    if (!voltageV.isFinite()) return null
    val mv = (voltageV * 1000f).roundToInt()
    if (mv >= (VOLTAGE_PLATEAU_V * 1000f).roundToInt()) return null
    if (mv <= VOLTAGE_TABLE.first().first) return VOLTAGE_TABLE.first().second.toFloat()
    for (i in 1 until VOLTAGE_TABLE.size) {
        val (hiMv, hiPct) = VOLTAGE_TABLE[i]
        if (mv <= hiMv) {
            val (loMv, loPct) = VOLTAGE_TABLE[i - 1]
            return loPct + (hiPct - loPct) * ((mv - loMv).toFloat() / (hiMv - loMv))
        }
    }
    // Between the last table point (13.29 V) and the plateau cutoff: hold the last value.
    return VOLTAGE_TABLE.last().second.toFloat()
}

private fun usable(r: EstReading): Boolean =
    r.fullChargeAh > 0f &&
        r.currentA.isFinite() && r.voltageV.isFinite() && r.remainingAh.isFinite() &&
        r.fullChargeAh.isFinite() && r.soc.isFinite() && r.pendingAh.isFinite()

/** The BMS is lowering its own counter toward its rest-voltage estimate (see [EstReading.pendingAh]). */
private fun bmsLowering(r: EstReading): Boolean = r.pendingAh < 0f

/** Sets the correction outright; setAtMs moves only when the amount actually changes. */
private fun EstPackState.withCorrection(ah: Float, source: EstSource?, tsMs: Long): EstPackState =
    if (ah == correction.correctionAh) this
    else copy(correction = SocCorrection(ah, if (ah > 0f) source else null, tsMs))

/** Raises the correction to at least [boundAh]; keeps the source unless there was none. */
private fun EstPackState.raisedTo(boundAh: Float, tsMs: Long): EstPackState =
    if (boundAh <= correction.correctionAh) this
    else withCorrection(boundAh, correction.source ?: EstSource.COUNTER, tsMs)

/** Lower bound from a counter that reads above full: the offset is at least this much. */
private fun EstPackState.lowerBounded(r: EstReading): EstPackState =
    if (r.remainingAh > r.fullChargeAh + OVERSHOOT_AH) raisedTo(r.remainingAh - r.fullChargeAh, r.tsMs) else this

/**
 * At the end of a charge that did not finish: takes the Ah [seg] delivered past a stopped counter
 * out of the correction, so the estimate keeps them once the pack is no longer charging. The one
 * place a correction is lowered other than a full charge; what lowers it is measured charge.
 */
private fun EstPackState.creditedFor(seg: ChargeSeg?, tsMs: Long): EstPackState {
    val missed = uncountedAh(seg)
    val settled = copy(charge = charge?.copy(uncountedAh = 0f))
    if (missed <= 0f) return settled
    return settled.withCorrection(max(correction.correctionAh - missed, 0f), correction.source, tsMs)
}

private fun clampedToFull(r: EstReading): Boolean = abs(r.remainingAh - r.fullChargeAh) <= CLAMP_TOL_AH

/**
 * Folds one reading into the pack's state. Rules apply in this order:
 * 1. taper tracking (charging, >= 13.90 V and < 3 A); a new charge start clears the old taper;
 * 2. charge end: a full charge (taper within 15 min) clears the correction if the BMS clamped to
 *    full, or replaces it (COUNTER) if the counter overshot; a partial charge only raises it to the
 *    lower bound, so a held correction survives a half charge, after lowering it by the Ah the
 *    charge delivered past a stopped counter ([uncountedAh]);
 * 3. an unseen charge (raw counter up > 2 Ah, no Charging seen) clears if clamped, else lower bound;
 * 4. lower bound on any non-charging reading above full + 0.5 Ah;
 * 5. rest tracking (zero current for 30 min, restarts on current or a > 10 min gap);
 * 6. the rested-voltage rule, only ever lowering the estimate.
 * "Charging" excludes regen. A non-positive full charge or a non-finite input leaves the state
 * alone except [EstPackState.lastTsMs].
 *
 * While the BMS is lowering its own counter ([EstReading.pendingAh] < 0) rules 2-4 and 6 are
 * skipped and the held correction and the drain anchor are dropped: the BMS has re-anchored the
 * counter to its own rest-voltage estimate, so a correction or a drain measured against the old
 * counter no longer applies (kept, it would be subtracted a second time as the counter comes
 * down). Tracking (1 and 5) continues.
 *
 * Standby drain ([DrainModel]): every full charge end anchors it there (the estimate is the counter
 * again) and, when the whole charge was seen, learns the rate from the Ah delivered.
 */
fun stepEstimate(prev: EstPackState, r: EstReading): EstPackState {
    if (!usable(r)) return prev.copy(lastTsMs = r.tsMs)

    val charging = r.state == BatteryState.Charging && !r.regen

    // 1. Taper tracking.
    var taper = if (!prev.wasCharging && charging) null else prev.taperAtMs
    if (charging && r.voltageV >= TAPER_MIN_V && r.currentA < TAPER_MAX_A) taper = r.tsMs
    val seg = followCharge(prev, r, charging)
    var s = prev.copy(taperAtMs = taper, charge = seg)

    // 5 (computed here, applied below). Rest tracking.
    val prevTs = prev.lastTsMs
    val gap = prevTs != null && r.tsMs - prevTs > REST_GAP_MS
    val restSince = when {
        r.currentA != 0f -> null
        gap || prev.restSinceMs == null -> r.tsMs
        else -> prev.restSinceMs
    }
    fun EstPackState.tracked() =
        copy(restSinceMs = restSince, wasCharging = charging, lastTsMs = r.tsMs, lastRawAh = r.remainingAh)

    // The BMS is re-anchoring its own counter: drop ours, hold nothing new.
    if (bmsLowering(r)) {
        return s.withCorrection(0f, null, r.tsMs)
            .copy(drain = s.drain.copy(anchorAtMs = null, anchorRawAh = null), charge = null).tracked()
    }

    // 2. Charge end.
    if (prev.wasCharging && !charging) {
        val full = taper != null && r.tsMs - taper <= TAPER_WINDOW_MS
        s = when {
            full && clampedToFull(r) -> s.withCorrection(0f, null, r.tsMs)
            full && r.remainingAh > r.fullChargeAh + OVERSHOOT_AH ->
                s.withCorrection(r.remainingAh - r.fullChargeAh, EstSource.COUNTER, r.tsMs)
            // A discharge frame has already closed the segment: its Ah are in the previous state.
            else -> s.creditedFor(seg ?: prev.charge, r.tsMs).lowerBounded(r)
        }
        if (full) s = s.copy(drain = s.drain.afterFullCharge(seg, r.remainingAh, r.tsMs), charge = null)
    }

    // 3. Unseen charge.
    val last = prev.lastRawAh
    if (!prev.wasCharging && !charging && last != null && r.remainingAh - last > UNSEEN_CHARGE_RISE_AH) {
        // Clamped to full by a charge nobody saw: the counter is right as of now.
        // Otherwise the counter rose by an unknown amount: its fall since the anchor is lost.
        s = if (clampedToFull(r)) {
            s.withCorrection(0f, null, r.tsMs)
                .copy(drain = s.drain.copy(anchorAtMs = r.tsMs, anchorRawAh = r.remainingAh), charge = null)
        } else s.lowerBounded(r).copy(drain = s.drain.copy(anchorRawAh = null))
    }

    // 4. Lower bound after driving past a missed full-detect.
    if (!charging) s = s.lowerBounded(r)

    // 5. Rest tracking.
    s = s.tracked()

    // 6. Rested voltage.
    val rested = restSince != null && r.tsMs - restSince >= REST_MIN_MS
    val vSoc = if (rested) voltageSoc(r.voltageV) else null
    if (vSoc != null) {
        val est = estimateOf(s, r).soc
        val margin = if (s.correction.source == EstSource.VOLTAGE) FOLLOW_DOWN_MARGIN_PTS else FIRST_VOLTAGE_MARGIN_PTS
        if (vSoc < est - margin) {
            // The correction sits beside the drain term, so it holds only what the drain does not.
            val wanted = max(
                s.correction.correctionAh,
                r.remainingAh - vSoc / 100f * r.fullChargeAh - drainAh(s.drain, r.tsMs),
            )
            s = s.withCorrection(wanted, EstSource.VOLTAGE, r.tsMs)
        }
    }
    return s
}

/**
 * The estimate for [r] under [state]. With no correction, no drain and the BMS not re-anchoring it
 * is the BMS reading. Otherwise remaining is the lower of `raw - correction - drain` (see
 * [drainAh]) and, while the BMS is lowering its counter, the value it is converging on
 * (`raw + pendingAh`), clamped to [0, full]; the two are never subtracted together. SOC is that
 * over full as an integer %; neither ever exceeds the BMS's own SOC or remaining Ah. The BMS's own
 * target is a rest-voltage estimate, so it reports [EstSource.VOLTAGE]; a drain-only estimate
 * reports [EstSource.COUNTER].
 *
 * While charging, the Ah delivered past a stopped counter ([uncountedAh]) are added back, so the
 * estimate is what the pack held at plug-in plus what the charger has put in.
 */
fun estimateOf(state: EstPackState, r: EstReading): SocEstimate {
    val corr = max(state.correction.correctionAh, 0f)
    if (!usable(r)) return SocEstimate(r.soc, r.remainingAh, null)
    val lowering = bmsLowering(r)
    val drain = drainAh(state.drain, r.tsMs)
    if (corr <= 0f && drain <= 0f && !lowering) return SocEstimate(r.soc, r.remainingAh, null)
    val charging = r.state == BatteryState.Charging && !r.regen
    val byCorrection = r.remainingAh - corr - drain + if (charging) uncountedAh(state.charge) else 0f
    val byBms = if (lowering) r.remainingAh + r.pendingAh else r.remainingAh
    val ah = min(byCorrection, byBms).coerceIn(0f, r.fullChargeAh)
    val soc = min(r.soc, (ah / r.fullChargeAh * 100f).roundToInt().toFloat())
    val source = if (lowering && byBms <= byCorrection) EstSource.VOLTAGE
    else state.correction.source ?: EstSource.COUNTER
    return SocEstimate(soc, min(ah, r.remainingAh), source)
}

/** This telemetry with the estimate in [Telemetry.soc]/[Telemetry.capacityAh] and the BMS's own
 *  values kept beside it. Apply to a raw frame, once. */
fun Telemetry.withEstimate(e: SocEstimate): Telemetry = copy(
    soc = e.soc,
    capacityAh = e.remainingAh,
    bmsSoc = soc,
    bmsCapacityAh = capacityAh,
    estSource = e.source,
)

/** The BMS's SOC, whether or not this telemetry carries an estimate. */
val Telemetry.rawSoc: Float get() = bmsSoc ?: soc

/** The BMS's remaining Ah, whether or not this telemetry carries an estimate. */
val Telemetry.rawCapacityAh: Float get() = bmsCapacityAh ?: capacityAh
