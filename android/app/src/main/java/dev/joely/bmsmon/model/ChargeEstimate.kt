package dev.joely.bmsmon.model

import kotlin.math.roundToInt

/**
 * Charge-time estimate tunables. Design: docs/superpowers/specs/2026-07-01-charge-time-estimate-design.md
 * The 98..100% plateau (CV/absorption) is charger-dependent and not coulomb-estimable, so it is a
 * learned per-pack constant; the bulk below 98% is straight coulomb counting on live current.
 */
const val TAIL_START_SOC = 98f
const val TARGET_SOC = 100f
// Reseeded 45 -> 58 at the 2026-07-12 calibration check: 8 completed full charges measured
// median tails of 53 min (2012-B) and 64 min (2012-A) from 98% to charger cutoff.
// Reseeded 58 -> 70 at the 2026-08-04 check: 28 completed charges (both daily drivers, 5 weeks)
// measured a mean tail of 70.6 min (SD 25.8, range 40.6-129.6). Only the first few charges of a
// fresh install use this — the EMA converged fine from 58 (it reached ~79 min for 2012-B) — but
// 58 sat a fifth below the real mean, so a fresh install started biased low.
// NOTE the tail is genuinely high-variance because it is not a fixed CV plateau: the charger
// holds a flat ~8 A right through 98->99 (a rock-steady 7.7-8.1 min) and on past the BMS's
// rated full_charge_ah (absorbing 7-9 Ah more) before a real ~6-min taper and cutoff. Tail
// length tracks how deep the session was (r = +0.67 vs total charge time, -0.48 vs start SOC),
// which a single scalar cannot express — see docs/calibration-checkin-2026-08-04.md.
const val SEED_TAIL_MIN = 70f
const val TAIL_EMA_ALPHA = 0.3f
private const val MIN_CHARGE_CURRENT_A = 0.2f
/**
 * What the charger's taper adds to a charge counted in real Ah. Measured on 30 full charges (both
 * 2012 packs, 2026-09..11): the last 10.5 Ah took 80.2-84.3 min against 78.3-80.8 at the bulk
 * current, the last 2.1 Ah 17.6-20.0 against 15.6-16.2.
 */
const val TAPER_EXTRA_MIN = 2.5f
/** Once the charger is on its taper ([TAPER_MIN_V], under [TAPER_MAX_A]) the end is this close at most. */
const val TAPER_CAP_MIN = 5f

/**
 * Estimated minutes to reach [TARGET_SOC] (100%), or null when no estimate applies (not steadily
 * charging, regen burst, already full, or implausible/unknown inputs). [remainingAh] is the BMS
 * remaining capacity, [tailMin] the learned per-pack 98->100 duration.
 *
 * [estimated]: [soc] and [remainingAh] are our estimate, not the BMS's counter. The estimate is in
 * real Ah and a taper-ended charge ends at full, so the time left is the Ah still missing at the
 * live current plus [TAPER_EXTRA_MIN], with no learned tail: that tail is the time the raw counter
 * spends above 98 % while the charger puts back the drift the counter never saw, and adding it to
 * an estimate that already counts the drift read ~45 min long. On the taper itself ([voltageV])
 * a falling current would stretch the figure, so it is capped at [TAPER_CAP_MIN].
 */
fun estimateChargeMinutes(
    state: BatteryState,
    soc: Float,
    currentA: Float,
    fullChargeAh: Float,
    remainingAh: Float,
    regen: Boolean,
    tailMin: Float,
    estimated: Boolean = false,
    voltageV: Float? = null,
): Float? {
    if (state != BatteryState.Charging || regen) return null
    if (estimated) {
        // By Ah, not by the rounded SOC: 99.6 % shows as 100 with minutes still to go.
        val toGoAh = fullChargeAh - remainingAh
        if (fullChargeAh <= 0f || soc < 0f || toGoAh <= 0f || currentA < MIN_CHARGE_CURRENT_A) return null
        val minutes = toGoAh / currentA * 60f + TAPER_EXTRA_MIN
        val onTaper = voltageV != null && voltageV >= TAPER_MIN_V && currentA < TAPER_MAX_A
        return if (onTaper) minOf(minutes, TAPER_CAP_MIN) else minutes
    }
    if (fullChargeAh <= 0f || soc < 0f || soc >= TARGET_SOC) return null
    val tail = tailMin.coerceAtLeast(0f)
    if (soc >= TAIL_START_SOC) {
        val frac = ((TARGET_SOC - soc) / (TARGET_SOC - TAIL_START_SOC)).coerceIn(0f, 1f)
        return tail * frac
    }
    if (currentA < MIN_CHARGE_CURRENT_A) return null
    val tailStartAh = TAIL_START_SOC / 100f * fullChargeAh
    val bulkAh = (tailStartAh - remainingAh).coerceAtLeast(0f)
    val bulkMin = bulkAh / currentA * 60f
    return bulkMin + tail
}

/** Compact "2h 14m" / "45m" for a minutes estimate (clamped at 0). */
fun formatEtaMinutes(minutes: Float): String {
    val total = minutes.roundToInt().coerceAtLeast(0)
    val h = total / 60
    val m = total % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
