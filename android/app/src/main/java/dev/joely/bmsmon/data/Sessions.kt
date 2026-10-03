package dev.joely.bmsmon.data

import dev.joely.bmsmon.data.db.RollupRow
import dev.joely.bmsmon.data.db.SampleEntity
import dev.joely.bmsmon.data.db.SessionEntity

/** A gap larger than this (or a disconnect) between samples for one pack starts a new session. */
const val SESSION_GAP_MS = 10 * 60 * 1000L

/**
 * Longest a session may run (DATA-16). A stable BLE link never logs a disconnect, so without a cap
 * one session stayed open for days — every finalize read all of it, and a process death mid-run left
 * a giant stub for the startup sweep. 24 h bounds a stage pack's session at ~57.6k rows; the boundary
 * pair's interval (one poll) is credited to neither side, same as a gap.
 */
const val MAX_SESSION_MS = 24 * 60 * 60 * 1000L

/**
 * True when the incoming sample at [nowMs] should open a NEW session for a pack, given the pack's
 * previous sample time ([prevSampleTsMs], null if none), whether it disconnected since that sample
 * ([prevWasDisconnect]) and when the open session started ([sessionStartMs], null if unknown). No
 * prior sample, a disconnect, a gap strictly greater than [gapMs], or a session [maxSessionMs] old
 * (inclusive) all start a new session. A clock that steps backwards never splits on length.
 */
fun isNewSession(
    prevSampleTsMs: Long?,
    prevWasDisconnect: Boolean,
    nowMs: Long,
    gapMs: Long = SESSION_GAP_MS,
    sessionStartMs: Long? = null,
    maxSessionMs: Long = MAX_SESSION_MS,
): Boolean {
    if (prevSampleTsMs == null) return true
    if (prevWasDisconnect) return true
    if (sessionStartMs != null && nowMs - sessionStartMs >= maxSessionMs) return true
    return (nowMs - prevSampleTsMs) > gapMs
}

/** A current more negative than this (A) counts as discharge. */
const val DISCHARGE_EPS = 0.05f

/** Minimum discharge-current spread (A) within a session to trust the resistance estimate. */
const val IR_MIN_CURRENT_SPREAD_A = 8f

data class IrEstimate(val mohm: Float, val confidence: Float)

/**
 * Estimate effective internal resistance from discharge samples by regressing voltage on current.
 * With the discharge-negative sign convention, V ≈ Voc + I·R, so the slope dV/dI is the resistance
 * in ohms (reported here in mΩ). Returns null when the current spread is too small to be reliable.
 * [confidence] scales with spread (1.0 at >= 4× the minimum spread).
 */
fun estimateInternalResistanceMohm(dischargeSamples: List<SampleEntity>): IrEstimate? {
    val currents = FloatBuf()
    val voltages = FloatBuf()
    for (s in dischargeSamples) {
        val i = s.currentA ?: continue
        val v = s.voltageV ?: continue
        currents.add(i)
        voltages.add(v)
    }
    return estimateInternalResistanceMohm(currents.values, voltages.values, currents.size)
}

/** The same two-pass regression over the first [n] entries of primitive arrays — what the
 *  streaming rollup feeds. Operation order matches the list version exactly (bit-identical). */
fun estimateInternalResistanceMohm(currents: FloatArray, voltages: FloatArray, n: Int): IrEstimate? {
    if (n < 2) return null
    var lo = currents[0]
    var hi = currents[0]
    for (k in 1 until n) {
        lo = minOf(lo, currents[k])
        hi = maxOf(hi, currents[k])
    }
    val spread = hi - lo
    if (spread < IR_MIN_CURRENT_SPREAD_A) return null

    var sumI = 0.0
    var sumV = 0.0
    for (k in 0 until n) {
        sumI += currents[k]
        sumV += voltages[k]
    }
    val meanI = sumI / n
    val meanV = sumV / n
    var cov = 0.0
    var varI = 0.0
    for (k in 0 until n) {
        val di = currents[k] - meanI
        cov += di * (voltages[k] - meanV)
        varI += di * di
    }
    if (varI == 0.0) return null
    val slopeOhm = cov / varI            // dV/dI = R (ohms)
    val mohm = (slopeOhm * 1000.0).toFloat()
    val confidence = (spread / (IR_MIN_CURRENT_SPREAD_A * 4f)).coerceIn(0f, 1f)
    return IrEstimate(mohm = mohm, confidence = confidence)
}

/**
 * What the startup finalize-sweep should do with one orphaned session stub — a `sampleCount = 0`
 * row left behind when the process died before the session was finalized. Such stubs are invisible
 * to the history DAOs (`sampleCount > 0` filters) and their samples eventually retention-prune, so
 * the run would silently vanish. Pure decision (unit-testable): if the stub has real telemetry
 * samples, finalize it with proper rollups; if it only ever got link-event rows (or nothing), the
 * stub carries no run and is deleted.
 */
sealed interface OrphanedSessionAction {
    data class Finalize(val rollup: SessionEntity) : OrphanedSessionAction
    object Delete : OrphanedSessionAction
}

/** The streamed decision: a stub whose stream folded no telemetry carries no run → delete. */
fun orphanedSessionAction(acc: RollupAccumulator): OrphanedSessionAction =
    if (acc.count > 0) OrphanedSessionAction.Finalize(acc.toRollup()) else OrphanedSessionAction.Delete

fun orphanedSessionAction(
    address: String,
    sessionId: Long,
    samples: List<SampleEntity>,
): OrphanedSessionAction = orphanedSessionAction(rollupAccumulatorOf(address, sessionId, samples))

/** The SampleEntity → streaming-row projection. Callers must skip link-event rows. */
fun SampleEntity.toRollupRow() = RollupRow(
    id = id, tsMs = tsMs, soc = soc, currentA = currentA, powerW = powerW, voltageV = voltageV,
    tempC = tempC, soh = soh, fullChargeAh = fullChargeAh, cycles = cycles, regen = regen,
)

/** Fold an in-memory, time-ordered list of a session's samples (link rows skipped). */
fun rollupAccumulatorOf(address: String, sessionId: Long, samples: List<SampleEntity>): RollupAccumulator =
    RollupAccumulator(address, sessionId).also { acc ->
        for (s in samples) if (s.linkEvent == null) acc.add(s.toRollupRow())
    }

/**
 * Compute a [SessionEntity] (rollups) from a session's time-ordered [samples]. Link-event rows are
 * ignored. Discharge stats use samples with `currentA < -DISCHARGE_EPS`. Energy integrates each
 * interval's leading-sample power over its duration. List-input form of [RollupAccumulator]; the
 * app itself streams sessions through [streamRollup] and never loads one whole (DATA-16).
 */
fun computeRollup(address: String, sessionId: Long, samples: List<SampleEntity>): SessionEntity =
    rollupAccumulatorOf(address, sessionId, samples).toRollup()
