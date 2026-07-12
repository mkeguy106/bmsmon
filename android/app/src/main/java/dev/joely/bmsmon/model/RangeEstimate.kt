package dev.joely.bmsmon.model

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Discharge-remaining estimate (miles + active-use hours + wall-clock time to empty), high/low
 * bands learned from real usage. Design: docs/superpowers/specs/2026-07-11-discharge-estimate-design.md
 * web/src/range.ts is a line-for-line TypeScript twin — keep the math identical.
 */

const val NOMINAL_PACK_V = 12.8f

/** Max live-tilt weight — history always anchors at least half the band's center. */
const val TILT_MAX_W = 0.5f

/** Today's discharge hours at which the tilt weight saturates. */
const val TILT_FULL_HOURS = 4f

/** Minimum discharge time today before any tilt applies (a 10-min burst must not whipsaw it). */
const val TILT_MIN_HOURS = 0.25f

data class Band(val lo: Float, val hi: Float) {
    val mid: Float get() = (lo + hi) / 2f
}

/** Learned per-pack parameter bands (p20/p80 across qualifying days, else the seeds). */
data class RangeParams(
    val whPerDay: Band,
    val activeW: Band,
    val whPerMile: Band,
    val learnedDays: Int,
    val updatedMs: Long,
)

/** Cold-start bands from the 2026-07 real-data investigation (2 weeks, 2012 daily driver).
 *  whPerMile is OUTING-DAY cost (a day's total burn per clean outdoor mile — see RangeLearn),
 *  seeded at a conservative 15–25 mi full-charge equivalent (1280 Wh/pack ÷ 25..15 mi). */
val SEED_RANGE_PARAMS = RangeParams(
    whPerDay = Band(78f, 182f),      // 130 Wh/day ±40%
    activeW = Band(52.5f, 97.5f),    // 75 W ±30%
    whPerMile = Band(51f, 85f),      // full charge ≈ 15–25 practical miles
    learnedDays = 0,
    updatedMs = 0L,
)

/** Today's observed burn since local midnight (computed from Room by the engine). */
data class TodayUsage(val disWh: Float, val disHours: Float, val hoursSinceMidnight: Float)

/** One pack's estimate: miles, active-use hours, wall-clock hours — each as a lo/hi band. */
data class PackRange(
    val milesLo: Float, val milesHi: Float,
    val activeHLo: Float, val activeHHi: Float,
    val wallHLo: Float, val wallHHi: Float,
)

/** Shift the band's center toward [todayRate] by weight [w]; the width stays from history. */
fun tiltedBand(band: Band, todayRate: Float?, w: Float): Band {
    if (todayRate == null || w <= 0f || !todayRate.isFinite()) return band
    val half = (band.hi - band.lo) / 2f
    val mid = (1f - w) * band.mid + w * todayRate
    return Band((mid - half).coerceAtLeast(0.01f), mid + half)
}

/**
 * Per-pack discharge estimate, or null when it doesn't apply: charging (the recharge ETA owns
 * that display slot) or no usable remaining capacity. Idle packs still estimate — "how far can
 * I go" is meaningful while parked.
 */
fun estimatePackRange(
    state: BatteryState,
    remainingAh: Float,
    params: RangeParams,
    today: TodayUsage?,
): PackRange? {
    if (state == BatteryState.Charging) return null
    if (remainingAh <= 0f || !remainingAh.isFinite()) return null
    // A band edge that is zero/negative/non-finite would divide to Infinity — no estimate
    // beats a nonsense one (also shields the web twin from stale synced rows).
    val bands = listOf(params.whPerDay, params.activeW, params.whPerMile)
    if (bands.any { !it.lo.isFinite() || !it.hi.isFinite() || it.lo <= 0f || it.hi <= 0f }) return null
    val remWh = remainingAh * NOMINAL_PACK_V

    // Live tilt (Android only; the web twin passes today = null): weight grows with how much
    // discharge actually happened today, so a heavy morning slides the band without whipsaw.
    var whPerDay = params.whPerDay
    var activeW = params.activeW
    if (today != null && today.disHours >= TILT_MIN_HOURS && today.hoursSinceMidnight >= 1f) {
        val w = minOf(TILT_MAX_W, today.disHours / TILT_FULL_HOURS * TILT_MAX_W)
        whPerDay = tiltedBand(whPerDay, today.disWh * 24f / today.hoursSinceMidnight, w)
        activeW = tiltedBand(activeW, today.disWh / today.disHours, w)
    }

    return PackRange(
        milesLo = remWh / params.whPerMile.hi, milesHi = remWh / params.whPerMile.lo,
        activeHLo = remWh / activeW.hi, activeHHi = remWh / activeW.lo,
        wallHLo = remWh / whPerDay.hi * 24f, wallHHi = remWh / whPerDay.lo * 24f,
    )
}

/** Base-level readout: the weaker pack bounds each figure (series pair — it ends the trip). */
fun minRange(ranges: List<PackRange>): PackRange = ranges.reduce { a, b ->
    PackRange(
        minOf(a.milesLo, b.milesLo), minOf(a.milesHi, b.milesHi),
        minOf(a.activeHLo, b.activeHLo), minOf(a.activeHHi, b.activeHHi),
        minOf(a.wallHLo, b.wallHLo), minOf(a.wallHHi, b.wallHHi),
    )
}

/** "~37–50 mi · ~9–13h use · ~5–9 days" (days when the low bound exceeds 48 h, else hours). */
fun formatRangeLine(r: PackRange): String {
    val miles = if (r.milesHi < 10f) {
        "~${fmt1(r.milesLo)}–${fmt1(r.milesHi)} mi"
    } else {
        "~${r.milesLo.roundToInt()}–${r.milesHi.roundToInt()} mi"
    }
    val use = "~${r.activeHLo.roundToInt()}–${r.activeHHi.roundToInt()}h use"
    val wall = if (r.wallHLo > 48f) {
        "~${(r.wallHLo / 24f).roundToInt()}–${(r.wallHHi / 24f).roundToInt()} days"
    } else {
        "~${r.wallHLo.roundToInt()}–${r.wallHHi.roundToInt()}h"
    }
    return "$miles · $use · $wall"
}

private fun fmt1(v: Float) = String.format(Locale.US, "%.1f", v)
