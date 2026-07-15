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
 * A completed tail observation: [minutes] from 98% to the end of the charge, plus [runEndMs] —
 * the timestamp of the run's LAST charging sample. That timestamp is the run's *identity*: a
 * later re-scan of the same history window (an SOC>=98 charging blip, an engine restart) finds
 * the same run with the same end, so the learner can refuse to fold it twice (see
 * [shouldFoldTail]).
 */
data class ObservedTail(val minutes: Float, val runEndMs: Long)

/**
 * The most recent completed 98->100 tail, or null. Minutes run from first reaching
 * [TAIL_START_SOC] (98%) to the end of the charge, within the most recent contiguous charging
 * run that cleanly climbed through the tail (had a sample below 98% first). "End of the charge"
 * is the first sample at [TARGET_SOC] when the BMS reports one — but this BMS never reports 100
 * while Charging (it caps at 99; 100 appears only after the charger cuts off and the state
 * flips Idle), so a run that ENDS at >= [CUTOFF_COMPLETE_SOC] is also complete, measured to its
 * last sample (the cutoff — where SOC 100 shows up in the field data). Returns null if no
 * completed tail exists — unplugged early (run ends < 99), started already full, or a
 * gap/non-charging sample broke the run mid-climb. [samples] must be ascending by tsMs.
 */
fun observedChargeTail(samples: List<ChargeSample>): ObservedTail? {
    var best: ObservedTail? = null
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
                // most recent qualifying run wins
                best = ObservedTail((t100 - t98) / 60_000f, run.last().tsMs)
            } else if (t100 == null && run.last().soc >= CUTOFF_COMPLETE_SOC && run.last().tsMs > t98) {
                best = ObservedTail((run.last().tsMs - t98) / 60_000f, run.last().tsMs)
            }
        }
        i = j + 1
    }
    return best
}

/** Minutes of the most recent completed tail (see [observedChargeTail]). */
fun observedChargeTailMinutes(samples: List<ChargeSample>): Float? =
    observedChargeTail(samples)?.minutes

/**
 * Run-identity dedup: fold a qualifying run only when it ends STRICTLY after the last run this
 * pack learned from ([lastLearnedRunEndMs], persisted). The same run re-found later — a
 * Charging(soc>=98) blip past the 30-min wall-clock pre-filter, an engine restart wiping the
 * in-memory dedup map — is skipped forever. Null means the pack never learned (fresh install,
 * or an install upgraded from before run ends were persisted): accept any qualifying run.
 */
fun shouldFoldTail(runEndMs: Long, lastLearnedRunEndMs: Long?): Boolean =
    lastLearnedRunEndMs == null || runEndMs > lastLearnedRunEndMs

/** Fold a new tail observation into the running per-pack EMA. */
fun foldTailEma(prev: Float, observed: Float): Float =
    TAIL_EMA_ALPHA * observed + (1f - TAIL_EMA_ALPHA) * prev

/** Result of one accepted learn pass: the folded EMA and the run end to persist as learned. */
data class TailLearnResult(val tailMin: Float, val runEndMs: Long)

/**
 * One complete learn pass, pure: find the most recent qualifying tail in [samples], accept it
 * only if it's a run this pack hasn't learned from yet ([shouldFoldTail]), and fold it into
 * [prevTailMin]. Null = nothing to learn (no completed tail, or the run was already folded).
 */
fun learnTailFold(
    samples: List<ChargeSample>,
    prevTailMin: Float,
    lastLearnedRunEndMs: Long?,
): TailLearnResult? {
    val observed = observedChargeTail(samples) ?: return null
    if (!shouldFoldTail(observed.runEndMs, lastLearnedRunEndMs)) return null
    return TailLearnResult(foldTailEma(prevTailMin, observed.minutes), observed.runEndMs)
}
