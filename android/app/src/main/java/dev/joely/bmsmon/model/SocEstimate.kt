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
)

/** Per-pack state. [correction] is persisted; the rest is in-memory tracking. */
data class EstPackState(
    val correction: SocCorrection = SocCorrection(),
    val wasCharging: Boolean = false,
    val taperAtMs: Long? = null,
    val restSinceMs: Long? = null,
    val lastTsMs: Long? = null,
    val lastRawAh: Float? = null,
)

data class SocEstimate(val soc: Float, val remainingAh: Float, val source: EstSource?)

/** (millivolts, percent), ascending. Rested >= 30 min after discharge, 2012 packs, 2026-11-18. */
private val VOLTAGE_TABLE = arrayOf(
    12900 to 28, 12980 to 48, 13000 to 51, 13040 to 56, 13100 to 58,
    13140 to 66, 13180 to 72, 13200 to 76, 13280 to 80,
)

/**
 * Rested pack voltage -> SOC %, linear between table points. 28 below 12.90 V (a ceiling:
 * the knee is uncalibrated and lower only means a lower estimate). Null at or above 13.30 V,
 * the plateau where a rested pack can be anywhere from ~78 to ~95 %.
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
    // Between the last table point (13.28 V) and the plateau cutoff: hold the last value.
    return VOLTAGE_TABLE.last().second.toFloat()
}

private fun usable(r: EstReading): Boolean =
    r.fullChargeAh > 0f &&
        r.currentA.isFinite() && r.voltageV.isFinite() && r.remainingAh.isFinite() &&
        r.fullChargeAh.isFinite() && r.soc.isFinite()

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

private fun clampedToFull(r: EstReading): Boolean = abs(r.remainingAh - r.fullChargeAh) <= CLAMP_TOL_AH

/**
 * Folds one reading into the pack's state. Rules apply in this order:
 * 1. taper tracking (charging, >= 13.90 V and < 3 A); a new charge start clears the old taper;
 * 2. charge end: a full charge (taper within 15 min) clears the correction if the BMS clamped to
 *    full, or replaces it (COUNTER) if the counter overshot; a partial charge only raises it to the
 *    lower bound, so a held correction survives a half charge;
 * 3. an unseen charge (raw counter up > 2 Ah, no Charging seen) clears if clamped, else lower bound;
 * 4. lower bound on any non-charging reading above full + 0.5 Ah;
 * 5. rest tracking (zero current for 30 min, restarts on current or a > 10 min gap);
 * 6. the rested-voltage rule, only ever lowering the estimate.
 * "Charging" excludes regen. A non-positive full charge or a non-finite input leaves the state
 * alone except [EstPackState.lastTsMs].
 */
fun stepEstimate(prev: EstPackState, r: EstReading): EstPackState {
    if (!usable(r)) return prev.copy(lastTsMs = r.tsMs)

    val charging = r.state == BatteryState.Charging && !r.regen

    // 1. Taper tracking.
    var taper = if (!prev.wasCharging && charging) null else prev.taperAtMs
    if (charging && r.voltageV >= TAPER_MIN_V && r.currentA < TAPER_MAX_A) taper = r.tsMs
    var s = prev.copy(taperAtMs = taper)

    // 2. Charge end.
    if (prev.wasCharging && !charging) {
        val full = taper != null && r.tsMs - taper <= TAPER_WINDOW_MS
        s = when {
            full && clampedToFull(r) -> s.withCorrection(0f, null, r.tsMs)
            full && r.remainingAh > r.fullChargeAh + OVERSHOOT_AH ->
                s.withCorrection(r.remainingAh - r.fullChargeAh, EstSource.COUNTER, r.tsMs)
            else -> s.lowerBounded(r)
        }
    }

    // 3. Unseen charge.
    val last = prev.lastRawAh
    if (!prev.wasCharging && !charging && last != null && r.remainingAh - last > UNSEEN_CHARGE_RISE_AH) {
        s = if (clampedToFull(r)) s.withCorrection(0f, null, r.tsMs) else s.lowerBounded(r)
    }

    // 4. Lower bound after driving past a missed full-detect.
    if (!charging) s = s.lowerBounded(r)

    // 5. Rest tracking.
    val prevTs = prev.lastTsMs
    val gap = prevTs != null && r.tsMs - prevTs > REST_GAP_MS
    val restSince = when {
        r.currentA != 0f -> null
        gap || prev.restSinceMs == null -> r.tsMs
        else -> prev.restSinceMs
    }
    s = s.copy(restSinceMs = restSince, wasCharging = charging, lastTsMs = r.tsMs, lastRawAh = r.remainingAh)

    // 6. Rested voltage.
    val rested = restSince != null && r.tsMs - restSince >= REST_MIN_MS
    val vSoc = if (rested) voltageSoc(r.voltageV) else null
    if (vSoc != null) {
        val est = estimateOf(s, r).soc
        val margin = if (s.correction.source == EstSource.VOLTAGE) FOLLOW_DOWN_MARGIN_PTS else FIRST_VOLTAGE_MARGIN_PTS
        if (vSoc < est - margin) {
            val wanted = max(s.correction.correctionAh, r.remainingAh - vSoc / 100f * r.fullChargeAh)
            s = s.withCorrection(wanted, EstSource.VOLTAGE, r.tsMs)
        }
    }
    return s
}

/**
 * The estimate for [r] under [state]. With no correction it is the BMS reading. Otherwise
 * remaining is `raw - correction` clamped to [0, full], and SOC is that over full as an integer %;
 * neither ever exceeds the BMS's own SOC or remaining Ah.
 */
fun estimateOf(state: EstPackState, r: EstReading): SocEstimate {
    val corr = state.correction.correctionAh
    if (corr <= 0f || !usable(r)) return SocEstimate(r.soc, r.remainingAh, null)
    val ah = (r.remainingAh - corr).coerceIn(0f, r.fullChargeAh)
    val soc = min(r.soc, (ah / r.fullChargeAh * 100f).roundToInt().toFloat())
    return SocEstimate(soc, min(ah, r.remainingAh), state.correction.source)
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
