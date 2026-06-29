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

data class IrEstimate(val mohm: Float, val confidence: Float)
fun estimateInternalResistanceMohm(dischargeSamples: List<SampleEntity>): IrEstimate? = null

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
        else dischargePowers[((dischargePowers.size - 1) * 0.95f).toInt()]
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
