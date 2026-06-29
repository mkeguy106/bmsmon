package dev.joely.bmsmon.data

import dev.joely.bmsmon.data.db.SampleEntity
import dev.joely.bmsmon.data.db.SessionEntity

/** A gap larger than this (or a disconnect) between samples for one pack starts a new session. */
const val SESSION_GAP_MS = 10 * 60 * 1000L

/**
 * True when the incoming sample at [nowMs] should open a NEW session for a pack, given the pack's
 * previous sample time ([prevSampleTsMs], null if none) and whether the pack disconnected since
 * that sample ([prevWasDisconnect]). A gap strictly greater than [gapMs], a disconnect, or no
 * prior sample all start a new session.
 */
fun isNewSession(
    prevSampleTsMs: Long?,
    prevWasDisconnect: Boolean,
    nowMs: Long,
    gapMs: Long = SESSION_GAP_MS,
): Boolean {
    if (prevSampleTsMs == null) return true
    if (prevWasDisconnect) return true
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
    val pts = dischargeSamples.mapNotNull { sample ->
        val i = sample.currentA ?: return@mapNotNull null
        val v = sample.voltageV ?: return@mapNotNull null
        i to v
    }
    if (pts.size < 2) return null
    val currents = pts.map { it.first }
    val spread = (currents.maxOrNull()!! - currents.minOrNull()!!)
    if (spread < IR_MIN_CURRENT_SPREAD_A) return null

    val meanI = currents.average()
    val meanV = pts.map { it.second }.average()
    var cov = 0.0
    var varI = 0.0
    for ((i, v) in pts) {
        cov += (i - meanI) * (v - meanV)
        varI += (i - meanI) * (i - meanI)
    }
    if (varI == 0.0) return null
    val slopeOhm = cov / varI            // dV/dI = R (ohms)
    val mohm = (slopeOhm * 1000.0).toFloat()
    val confidence = (spread / (IR_MIN_CURRENT_SPREAD_A * 4f)).coerceIn(0f, 1f)
    return IrEstimate(mohm = mohm, confidence = confidence)
}

/**
 * Compute a [SessionEntity] (rollups) from a session's [samples]. Link-event rows are ignored.
 * Discharge stats use samples with `currentA < -DISCHARGE_EPS`. Energy integrates each interval's
 * leading-sample power over its duration (gaps are already bounded by session segmentation).
 */
fun computeRollup(address: String, sessionId: Long, samples: List<SampleEntity>): SessionEntity {
    val tel = samples.filter { it.linkEvent == null }
    val startMs = tel.firstOrNull()?.tsMs ?: 0L
    val endMs = tel.lastOrNull()?.tsMs ?: startMs
    val socs = tel.mapNotNull { it.soc }
    val discharge = tel.filter { (it.currentA ?: 0f) < -DISCHARGE_EPS }
    val dischargePowers = discharge.mapNotNull { it.powerW }.sorted()

    val peakPowerW = dischargePowers.lastOrNull() ?: 0f
    val p95PowerW = if (dischargePowers.isEmpty()) 0f
        else dischargePowers[(dischargePowers.size - 1) * 95 / 100]
    val meanPowerW = if (dischargePowers.isEmpty()) 0f else dischargePowers.average().toFloat()
    val peakCurrentA = discharge.mapNotNull { it.currentA }.minOrNull()?.let { -it } ?: 0f
    val peakRegenW = tel.filter { it.regen }.mapNotNull { it.powerW }.maxOrNull() ?: 0f
    val minVUnderLoad = discharge.mapNotNull { it.voltageV }.minOrNull() ?: 0f

    var energyWh = 0f
    for (i in 0 until tel.size - 1) {
        val a = tel[i]
        val dtH = (tel[i + 1].tsMs - a.tsMs).coerceAtLeast(0) / 3_600_000f
        if ((a.currentA ?: 0f) < -DISCHARGE_EPS) energyWh += (a.powerW ?: 0f) * dtH
    }

    val ir = estimateInternalResistanceMohm(discharge)

    return SessionEntity(
        id = sessionId,
        address = address,
        startMs = startMs,
        endMs = endMs,
        sampleCount = tel.size,
        peakPowerW = peakPowerW,
        p95PowerW = p95PowerW,
        meanPowerW = meanPowerW,
        peakCurrentA = peakCurrentA,
        peakRegenW = peakRegenW,
        energyWh = energyWh,
        socStart = socs.firstOrNull() ?: 0f,
        socEnd = socs.lastOrNull() ?: 0f,
        minSoc = socs.minOrNull() ?: 0f,
        maxSoc = socs.maxOrNull() ?: 0f,
        minVoltageUnderLoad = minVUnderLoad,
        estInternalResistanceMohm = ir?.mohm,
        irConfidence = ir?.confidence ?: 0f,
        sohEnd = tel.mapNotNull { it.soh }.lastOrNull() ?: 0,
        fullChargeAhEnd = tel.mapNotNull { it.fullChargeAh }.lastOrNull() ?: 0f,
        cyclesEnd = tel.mapNotNull { it.cycles }.lastOrNull() ?: 0,
        maxTempC = tel.mapNotNull { it.tempC }.maxOrNull() ?: 0f,
    )
}
