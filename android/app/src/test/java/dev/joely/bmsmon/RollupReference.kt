package dev.joely.bmsmon

import dev.joely.bmsmon.data.DISCHARGE_EPS
import dev.joely.bmsmon.data.IR_MIN_CURRENT_SPREAD_A
import dev.joely.bmsmon.data.IrEstimate
import dev.joely.bmsmon.data.db.SampleEntity
import dev.joely.bmsmon.data.db.SessionEntity

/*
 * Frozen oracles: computeRollup + estimateInternalResistanceMohm exactly as they stood at 7cec11c,
 * before the streaming rewrite (DATA-16). RollupAccumulator must reproduce these bit for bit on
 * time-ordered input. Do NOT "fix" these — they define equivalence.
 */

internal fun referenceIr(dischargeSamples: List<SampleEntity>): IrEstimate? {
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
    val slopeOhm = cov / varI
    val mohm = (slopeOhm * 1000.0).toFloat()
    val confidence = (spread / (IR_MIN_CURRENT_SPREAD_A * 4f)).coerceIn(0f, 1f)
    return IrEstimate(mohm = mohm, confidence = confidence)
}

internal fun referenceRollup(address: String, sessionId: Long, samples: List<SampleEntity>): SessionEntity {
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

    val ir = referenceIr(discharge)

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

/** A BLE link-event row (telemetry columns null), as TelemetryRepository.logLink writes it. */
internal fun linkSample(address: String, ts: Long, sessionId: Long, event: String) = SampleEntity(
    address = address, tsMs = ts, sessionId = sessionId, state = null, soc = null,
    currentA = null, powerW = null, voltageV = null, tempC = null, mosfetTempC = null,
    soh = null, fullChargeAh = null, remainingAh = null, cycles = null,
    cellMinV = null, cellMaxV = null, regen = false, linkEvent = event,
)

/**
 * Seeded, realistic session, time-ordered (non-decreasing ts, ~5% duplicate timestamps): discharge
 * (incl. the BMS's 1.044 A deadband floor), regen, idle at exactly 0 A, sprinkled nulls in every
 * nullable telemetry column, and interleaved link-event rows.
 */
internal fun randomSession(
    seed: Long,
    n: Int,
    address: String = "A",
    sessionId: Long = 9L,
    startTs: Long = 1_700_000_000_000L,
): List<SampleEntity> {
    val rnd = java.util.Random(seed)
    var ts = startTs
    val out = ArrayList<SampleEntity>(n)
    for (k in 0 until n) {
        ts += if (rnd.nextInt(20) == 0) 0L else 1_000L + rnd.nextInt(9_000)
        if (rnd.nextInt(40) == 0) {
            out += linkSample(address, ts, sessionId, if (rnd.nextBoolean()) "Connected" else "Disconnected")
            continue
        }
        val mode = rnd.nextInt(10)
        val cur: Float? = when {
            rnd.nextInt(50) == 0 -> null
            mode < 3 -> -(1.044f + rnd.nextFloat() * 60f)
            mode < 4 -> rnd.nextFloat() * 20f
            else -> 0f
        }
        val v: Float? = if (rnd.nextInt(50) == 0) null
            else 13.3f + (cur ?: 0f) * 0.0055f + (rnd.nextFloat() - 0.5f) * 0.02f
        val p: Float? = if (rnd.nextInt(50) == 0) null else kotlin.math.abs((cur ?: 0f) * (v ?: 13.3f))
        out += SampleEntity(
            address = address, tsMs = ts, sessionId = sessionId, state = "X",
            soc = if (rnd.nextInt(30) == 0) null else (20 + rnd.nextInt(80)).toFloat(),
            currentA = cur, powerW = p, voltageV = v,
            tempC = if (rnd.nextInt(30) == 0) null else (15 + rnd.nextInt(20)).toFloat(),
            mosfetTempC = 20, soh = if (rnd.nextInt(30) == 0) null else 100,
            fullChargeAh = if (rnd.nextInt(30) == 0) null else 105f,
            remainingAh = 50f, cycles = if (rnd.nextInt(30) == 0) null else 40 + k / 100,
            cellMinV = 3.3f, cellMaxV = 3.31f,
            regen = cur != null && cur > 0.1f && mode == 3, linkEvent = null,
        )
    }
    return out
}
