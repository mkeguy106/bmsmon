package dev.joely.bmsmon.model

/** One time-ordered charge observation for tail learning. */
data class ChargeSample(val tsMs: Long, val soc: Float, val charging: Boolean)

/**
 * Map one raw telemetry row to a [ChargeSample], dropping rows without an SOC (UI-10). A null
 * SOC must yield NO sample — the old `soc ?: -1f` mapping made a null-SOC charging row read as
 * "below 98%", i.e. fabricated climb-through evidence for [observedChargeTailMinutes].
 */
fun chargeSample(tsMs: Long, soc: Float?, charging: Boolean): ChargeSample? =
    soc?.let { ChargeSample(tsMs, it, charging) }

/** Max gap within a single contiguous charging run before it's treated as broken. */
private const val TAIL_MAX_GAP_MS = 5 * 60_000L

/** A run that climbed through 98 and ENDED at/above this SOC completed its tail at cutoff. */
private const val CUTOFF_COMPLETE_SOC = 99f

/**
 * Minutes from first reaching [TAIL_START_SOC] (98%) to the end of the charge, within the most
 * recent contiguous charging run that cleanly climbed through the tail (had a sample below 98%
 * first). "End of the charge" is the first sample at [TARGET_SOC] when the BMS reports one —
 * but this BMS never reports 100 while Charging (it caps at 99; 100 appears only after the
 * charger cuts off and the state flips Idle), so a run that ENDS at >= [CUTOFF_COMPLETE_SOC]
 * is also complete, measured to its last sample (the cutoff — where SOC 100 shows up in the
 * field data). Returns null if no completed tail exists — unplugged early (run ends < 99),
 * started already full, or a gap/non-charging sample broke the run mid-climb. [samples] must
 * be ascending by tsMs.
 */
fun observedChargeTailMinutes(samples: List<ChargeSample>): Float? {
    var best: Float? = null
    var i = 0
    while (i < samples.size) {
        if (!samples[i].charging) { i++; continue }
        var j = i
        while (j + 1 < samples.size &&
            samples[j + 1].charging &&
            samples[j + 1].tsMs - samples[j].tsMs <= TAIL_MAX_GAP_MS
        ) j++
        val run = samples.subList(i, j + 1)
        val sawBelow = run.any { it.soc < TAIL_START_SOC }
        val t98 = run.firstOrNull { it.soc >= TAIL_START_SOC }?.tsMs
        val t100 = run.firstOrNull { it.soc >= TARGET_SOC }?.tsMs
        if (sawBelow && t98 != null) {
            if (t100 != null && t100 > t98) {
                best = (t100 - t98) / 60_000f   // most recent qualifying run wins
            } else if (t100 == null && run.last().soc >= CUTOFF_COMPLETE_SOC && run.last().tsMs > t98) {
                best = (run.last().tsMs - t98) / 60_000f
            }
        }
        i = j + 1
    }
    return best
}

/** Fold a new tail observation into the running per-pack EMA. */
fun foldTailEma(prev: Float, observed: Float): Float =
    TAIL_EMA_ALPHA * observed + (1f - TAIL_EMA_ALPHA) * prev
