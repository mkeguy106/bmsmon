package dev.joely.bmsmon.model

/** One time-ordered charge observation for tail learning. */
data class ChargeSample(val tsMs: Long, val soc: Float, val charging: Boolean)

/** Max gap within a single contiguous charging run before it's treated as broken. */
private const val TAIL_MAX_GAP_MS = 5 * 60_000L

/**
 * Minutes from first reaching [TAIL_START_SOC] (98%) to first reaching [TARGET_SOC] (100%) within
 * the most recent contiguous charging run that cleanly climbed through the tail (had a sample below
 * 98% first). Returns null if no such completed tail exists — unplugged early, started already full,
 * or a gap/non-charging sample broke the run. [samples] must be ascending by tsMs.
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
        if (sawBelow && t98 != null && t100 != null && t100 > t98) {
            best = (t100 - t98) / 60_000f   // most recent qualifying run wins
        }
        i = j + 1
    }
    return best
}

/** Fold a new tail observation into the running per-pack EMA. */
fun foldTailEma(prev: Float, observed: Float): Float =
    TAIL_EMA_ALPHA * observed + (1f - TAIL_EMA_ALPHA) * prev
