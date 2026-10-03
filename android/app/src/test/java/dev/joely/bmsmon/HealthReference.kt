package dev.joely.bmsmon

import dev.joely.bmsmon.data.BIN_MAX_MOHM
import dev.joely.bmsmon.data.BIN_MIN_MOHM
import dev.joely.bmsmon.data.BIN_MIN_R2
import dev.joely.bmsmon.data.BIN_MIN_SPREAD_A
import dev.joely.bmsmon.data.CellImbalance
import dev.joely.bmsmon.data.DUTY_PEAK_W
import dev.joely.bmsmon.data.PackHealth
import dev.joely.bmsmon.data.PackResistance
import dev.joely.bmsmon.data.SCATTER_MAX_POINTS
import dev.joely.bmsmon.data.ScatterPoint
import dev.joely.bmsmon.data.SessionRollup
import dev.joely.bmsmon.data.SocBinResistance
import dev.joely.bmsmon.data.ViScatter
import dev.joely.bmsmon.data.db.SampleEntity
import dev.joely.bmsmon.data.db.SessionEntity
import kotlin.math.roundToInt

/*
 * Frozen oracles: the History analysis exactly as it stood at 7cec11c (list-based, two-pass
 * regression), before the SQL-aggregate rewrite (DATA-15). Do NOT "fix" these — they define
 * equivalence for the moment-based code.
 */

internal data class RefFit(val slopeOhm: Double, val interceptV: Double, val r2: Double, val spreadA: Float)

internal fun referenceRegress(pts: List<Pair<Float, Float>>): RefFit? {
    if (pts.size < 2) return null
    var sx = 0.0; var sy = 0.0
    for ((i, v) in pts) { sx += i; sy += v }
    val n = pts.size
    val meanX = sx / n; val meanY = sy / n
    var sxx = 0.0; var sxy = 0.0; var syy = 0.0
    var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
    for ((i, v) in pts) {
        val dx = i - meanX; val dy = v - meanY
        sxx += dx * dx; sxy += dx * dy; syy += dy * dy
        if (i < minX) minX = i; if (i > maxX) maxX = i
    }
    if (sxx == 0.0) return null
    val slope = sxy / sxx
    val intercept = meanY - slope * meanX
    val r2 = if (syy == 0.0) 0.0 else (sxy * sxy) / (sxx * syy)
    return RefFit(slope, intercept, r2, (maxX - minX))
}

private fun refRound1(x: Float): Float = (x * 10f).roundToInt() / 10f
private fun refRound3(x: Float): Float = (x * 1000f).roundToInt() / 1000f

private fun refMedian(xs: List<Float>): Float {
    val s = xs.sorted()
    val n = s.size
    return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2f
}

private fun refIvRows(samples: List<SampleEntity>): List<SampleEntity> =
    samples.filter { it.linkEvent == null && it.currentA != null && it.voltageV != null }

internal fun referenceEffectiveResistance(samples: List<SampleEntity>): PackResistance? {
    val byBin = HashMap<Int, MutableList<Pair<Float, Float>>>()
    for (s in refIvRows(samples)) {
        val soc = s.soc ?: continue
        byBin.getOrPut(soc.toInt()) { mutableListOf() }.add(s.currentA!! to s.voltageV!!)
    }
    val perBin = ArrayList<SocBinResistance>()
    for ((soc, pts) in byBin) {
        val fit = referenceRegress(pts) ?: continue
        if (fit.spreadA < BIN_MIN_SPREAD_A) continue
        if (fit.r2 < BIN_MIN_R2) continue
        val mohm = (fit.slopeOhm * 1000.0).toFloat()
        if (mohm <= BIN_MIN_MOHM || mohm >= BIN_MAX_MOHM) continue
        perBin.add(SocBinResistance(soc, refRound1(mohm), refRound1(fit.r2.toFloat() * 100f) / 100f, refRound1(fit.spreadA)))
    }
    if (perBin.isEmpty()) return null
    return PackResistance(
        rMohm = refRound1(refMedian(perBin.map { it.rMohm })),
        bins = perBin.size,
        perBin = perBin.sortedBy { it.soc },
    )
}

internal fun referenceCellImbalance(samples: List<SampleEntity>): CellImbalance? {
    val deltas = ArrayList<Float>()
    val sessions = HashSet<Long>()
    for (s in samples) {
        val mn = s.cellMinV ?: continue
        val mx = s.cellMaxV ?: continue
        val d = (mx - mn) * 1000f
        if (d < 0f) continue
        deltas.add(d)
        sessions.add(s.sessionId)
    }
    if (deltas.isEmpty()) return null
    return CellImbalance(
        meanMv = refRound1(deltas.average().toFloat()),
        maxMv = refRound1(deltas.max()),
        sessionsSampled = sessions.size,
    )
}

internal fun referenceViScatter(samples: List<SampleEntity>, rMohm: Float?, maxPoints: Int = SCATTER_MAX_POINTS): ViScatter? {
    val rows = refIvRows(samples)
    if (rows.size < 2) return null
    val iv = rows.map { it.currentA!! to it.voltageV!! }
    val fit = referenceRegress(iv) ?: return null
    val step = maxOf(1, rows.size / maxPoints)
    val pts = ArrayList<ScatterPoint>()
    var k = 0
    while (k < iv.size) { pts.add(ScatterPoint(iv[k].first, iv[k].second)); k += step }
    return ViScatter(
        pts = pts,
        rMohm = rMohm ?: refRound1((fit.slopeOhm * 1000.0).toFloat()),
        ocv = refRound3(fit.interceptV.toFloat()),
    )
}

internal fun referenceBuildPackHealth(
    address: String,
    alias: String,
    sessions: List<SessionEntity>,
    samples: List<SampleEntity>,
): PackHealth {
    val cellBySession = samples
        .filter { it.cellMinV != null && it.cellMaxV != null }
        .groupBy { it.sessionId }
        .mapValues { (_, rows) -> refRound1(rows.map { (it.cellMaxV!! - it.cellMinV!!) * 1000f }.average().toFloat()) }

    val rollups = sessions.map { s ->
        val disc = s.peakCurrentA > 0f || s.energyWh > 0f
        SessionRollup(
            id = s.id,
            startMs = s.startMs,
            durMin = ((s.endMs - s.startMs) / 60_000L).toInt(),
            disc = disc,
            energyWh = s.energyWh,
            peakW = s.peakPowerW,
            p95W = s.p95PowerW,
            meanW = s.meanPowerW,
            regenW = s.peakRegenW,
            minVload = if (disc && s.minVoltageUnderLoad > 0f) s.minVoltageUnderLoad else null,
            socStart = s.socStart.roundToInt(),
            socEnd = s.socEnd.roundToInt(),
            cellDeltaMv = cellBySession[s.id],
        )
    }
    val loaded = rollups.filter { it.disc }
    val resistance = referenceEffectiveResistance(samples)
    return PackHealth(
        address = address,
        alias = alias,
        active = rollups.any { it.peakW > DUTY_PEAK_W },
        sessions = rollups,
        totEnergyWh = refRound1(rollups.sumOf { it.energyWh.toDouble() }.toFloat()),
        peakW = rollups.maxOfOrNull { it.peakW } ?: 0f,
        peakRegenW = rollups.maxOfOrNull { it.regenW } ?: 0f,
        minVload = loaded.mapNotNull { it.minVload }.minOrNull(),
        dischCount = loaded.size,
        sessionCount = rollups.size,
        cell = referenceCellImbalance(samples),
        resistance = resistance,
        scatter = referenceViScatter(samples, resistance?.rMohm),
    )
}

/**
 * Seeded, realistic pack history, time-ordered at the 1.5 s stage cadence: SOC drifting down
 * through ~25-30 integer bins, ~25% loaded rows (1.044–41 A) on a 5.5 mΩ pack with 4 mV noise,
 * idle rows at exactly 0 A, ~1% NULL SOC, ~1% null current/voltage, ~10% unsampled cells, link
 * rows, and a new session every ~500 rows (session ids start at [firstSession]).
 */
internal fun randomPackSamples(seed: Long, rows: Int, address: String = "A", firstSession: Long = 1L): List<SampleEntity> {
    val rnd = java.util.Random(seed)
    var ts = 1_700_000_000_000L
    var session = firstSession
    var soc = 95f
    val out = ArrayList<SampleEntity>(rows)
    for (k in 0 until rows) {
        ts += 1_500L
        if (rnd.nextInt(500) == 0) session++
        if (rnd.nextInt(60) == 0) {
            out += linkSample(address, ts, session, if (rnd.nextBoolean()) "Connected" else "Disconnected")
            continue
        }
        soc = (soc - rnd.nextFloat() * 0.01f).coerceAtLeast(20f)
        val loaded = rnd.nextInt(4) == 0
        val cur: Float? = when {
            rnd.nextInt(100) == 0 -> null
            loaded -> -(1.044f + rnd.nextFloat() * 40f)
            else -> 0f
        }
        val ocv = 13.0f + soc / 100f * 0.4f
        val v: Float? = if (rnd.nextInt(100) == 0) null
            else ocv + (cur ?: 0f) * 0.0055f + (rnd.nextFloat() - 0.5f) * 0.004f
        val cellMin: Float? = if (rnd.nextInt(10) == 0) null else 3.30f + rnd.nextFloat() * 0.01f
        out += SampleEntity(
            address = address, tsMs = ts, sessionId = session, state = "X",
            soc = if (rnd.nextInt(100) == 0) null else soc,
            currentA = cur, powerW = cur?.let { kotlin.math.abs(it * (v ?: ocv)) }, voltageV = v,
            tempC = 25f, mosfetTempC = 26, soh = 100, fullChargeAh = 105f, remainingAh = 50f, cycles = 40,
            cellMinV = cellMin, cellMaxV = cellMin?.let { it + rnd.nextFloat() * 0.02f },
            regen = false, linkEvent = null,
        )
    }
    return out
}
