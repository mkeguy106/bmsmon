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
const val SEED_TAIL_MIN = 58f
const val TAIL_EMA_ALPHA = 0.3f
private const val MIN_CHARGE_CURRENT_A = 0.2f

/**
 * Estimated minutes to reach [TARGET_SOC] (100%), or null when no estimate applies (not steadily
 * charging, regen burst, already full, or implausible/unknown inputs). [remainingAh] is the BMS
 * remaining capacity, [tailMin] the learned per-pack 98->100 duration.
 */
fun estimateChargeMinutes(
    state: BatteryState,
    soc: Float,
    currentA: Float,
    fullChargeAh: Float,
    remainingAh: Float,
    regen: Boolean,
    tailMin: Float,
): Float? {
    if (state != BatteryState.Charging || regen) return null
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
