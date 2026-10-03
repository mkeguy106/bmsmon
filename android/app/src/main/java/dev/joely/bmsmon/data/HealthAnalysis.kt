package dev.joely.bmsmon.data

import dev.joely.bmsmon.data.db.CellSessionStats
import dev.joely.bmsmon.data.db.IvBinMoments
import dev.joely.bmsmon.data.db.IvPoint
import dev.joely.bmsmon.data.db.SampleEntity
import dev.joely.bmsmon.data.db.SessionEntity
import kotlin.math.roundToInt

/**
 * Derived battery-health analysis for the redesigned history surfaces (Health & Usage Review, Group
 * health, Session timeline). All functions are pure over Room rows — no DB columns are added; the
 * results are computed live from samples and cached by the caller.
 *
 * The headline metric is **effective internal resistance**, derived from logged voltage & current
 * via Ohm's law (R = ΔV/ΔI). A naïve global V-on-I regression fails because the open-circuit voltage
 * falls as the pack drains and that SOC drift masquerades as resistance, so we bin by integer SOC%
 * (OCV ≈ constant within a bin), regress within each bin, keep only well-conditioned bins, and take
 * the median of the surviving per-bin slopes. This mirrors the design handoff and reproduces the
 * reference values (A02214 ≈ 5.3 mΩ / 6 bins, A02345 ≈ 5.6 mΩ / 7 bins).
 *
 * Bounded reads (DATA-15 / UI-17): the app no longer loads a pack's rows to do this. The regression
 * runs on raw per-SOC-bin moments and cell Δ on per-session sums, both aggregated by SQLite, and the
 * V–I cloud is an exact keyset+OFFSET row stride. The list-input functions below are the in-memory
 * twins of those queries, kept for tests and small inputs.
 */

// --- per-bin resistance filter thresholds (from the handoff) ---
/** Minimum current spread (A) within a SOC bin for its V-on-I slope to be trustworthy. */
const val BIN_MIN_SPREAD_A = 8f
/** Minimum regression r² within a SOC bin to keep its slope. */
const val BIN_MIN_R2 = 0.5f
/** Plausible effective-resistance window (mΩ); slopes outside are rejected as noise. */
const val BIN_MIN_MOHM = 0f
const val BIN_MAX_MOHM = 60f

/** A pack is "active duty" if it has ever pulled a run harder than this (W). */
const val DUTY_PEAK_W = 100f

/** Target point counts for downsampling (keeps the UI light without dropping spikes). */
const val SCATTER_MAX_POINTS = 700
const val TIMELINE_BUCKETS = 340

/** One surviving SOC bin's resistance fit. Mirrors a `perBin` entry in `data/health.json`. */
data class SocBinResistance(val soc: Int, val rMohm: Float, val r2: Float, val spreadA: Float)

/** Pack effective resistance: the median of the surviving per-SOC-bin slopes. */
data class PackResistance(val rMohm: Float, val bins: Int, val perBin: List<SocBinResistance>)

/** Cell imbalance (max−min cell, mV) summarized over the samples that carried per-cell data. */
data class CellImbalance(val meanMv: Float, val maxMv: Float, val sessionsSampled: Int)

/** One terminal-voltage-vs-current sample for the V–I operating cloud. */
data class ScatterPoint(val currentA: Float, val voltageV: Float)

/** The V–I cloud plus the fit it visualizes: `V = ocv + (rMohm/1000)·I`. */
data class ViScatter(val pts: List<ScatterPoint>, val rMohm: Float, val ocv: Float)

/** One peak-pooled bucket of a session's run, for the timeline drill-down. */
data class TimelineBucket(
    val tsMs: Long,
    val dischargeW: Float,   // ≥ 0, max-pooled within the bucket
    val regenW: Float,       // ≥ 0, max-pooled within the bucket
    val voltageV: Float?,    // min-pooled (the deepest sag survives)
    val soc: Float?,         // last in the bucket
    val link: Boolean,       // a BLE link event fell in this bucket
)

/** Per-session view consumed by the usage charts and the runs list. Wraps [SessionEntity]. */
data class SessionRollup(
    val id: Long,
    val startMs: Long,
    val durMin: Int,
    val disc: Boolean,          // was this run actually loaded?
    val energyWh: Float,
    val peakW: Float,
    val p95W: Float,
    val meanW: Float,
    val regenW: Float,
    val minVload: Float?,       // null on idle runs (no discharge)
    val socStart: Int,
    val socEnd: Int,
    val cellDeltaMv: Float?,    // mean cell Δ over this run, null if unsampled
)

/** Full per-pack health aggregate consumed by the Review and Group-health screens. */
data class PackHealth(
    val address: String,
    val alias: String,
    val active: Boolean,
    val sessions: List<SessionRollup>,
    val totEnergyWh: Float,
    val peakW: Float,
    val peakRegenW: Float,
    val minVload: Float?,
    val dischCount: Int,
    val sessionCount: Int,
    val cell: CellImbalance?,
    val resistance: PackResistance?,
    val scatter: ViScatter?,
) {
    val rMohm: Float? get() = resistance?.rMohm
    val cellDeltaMv: Float? get() = cell?.meanMv?.let { (it).roundToInt().toFloat() }
}

enum class Verdict { Healthy, Watch, Service }

/**
 * Health verdict from the derived resistance and cell imbalance (handoff thresholds):
 * Service if R ≥ 30 mΩ or cellΔ ≥ 40 mV; Watch if R ≥ 15 or cellΔ ≥ 20; else Healthy.
 */
fun verdictFor(rMohm: Float?, cellDeltaMv: Float?): Verdict = when {
    (rMohm != null && rMohm >= 30f) || (cellDeltaMv != null && cellDeltaMv >= 40f) -> Verdict.Service
    (rMohm != null && rMohm >= 15f) || (cellDeltaMv != null && cellDeltaMv >= 20f) -> Verdict.Watch
    else -> Verdict.Healthy
}

// --- internals ----------------------------------------------------------------------------------

/** A V-on-I least-squares fit: slope = effective R (Ω), intercept = OCV (V), spread of current (A). */
internal data class Fit(val slopeOhm: Double, val interceptV: Double, val r2: Double, val spreadA: Float)

/**
 * Least-squares regression of terminal voltage on current from RAW moments (DATA-15) — the shape
 * SQLite aggregates in one pass, so History never materializes rows. With the discharge-negative
 * sign convention V ≈ Voc + I·R, so the slope is the resistance in ohms and the intercept the OCV.
 * Centred sums are recovered as Σxy − ΣxΣy/n; against the old two-pass fit this agrees to ~1e-12
 * (slope, relative) and ~1e-6 (r², absolute) even in a 50k-row, 1 mV-wide idle bin
 * (HealthEquivalenceTest). Null when current has no spread — tested exactly via min == max, the
 * old `sxx == 0` case, rather than through rounding.
 */
internal fun fitOf(m: IvBinMoments): Fit? {
    if (m.n < 2 || m.maxI == m.minI) return null
    val n = m.n.toDouble()
    val meanX = m.sumI / n
    val meanY = m.sumV / n
    val sxx = (m.sumII - m.sumI * meanX).coerceAtLeast(0.0)
    if (sxx == 0.0) return null
    val sxy = m.sumIV - m.sumI * meanY
    val syy = if (m.maxV == m.minV) 0.0 else (m.sumVV - m.sumV * meanY).coerceAtLeast(0.0)
    val slope = sxy / sxx
    return Fit(
        slopeOhm = slope,
        interceptV = meanY - slope * meanX,
        r2 = if (syy == 0.0) 0.0 else (sxy * sxy) / (sxx * syy),
        spreadA = m.maxI.toFloat() - m.minI.toFloat(),
    )
}

private fun round1(x: Float): Float = (x * 10f).roundToInt() / 10f
private fun round3(x: Float): Float = (x * 1000f).roundToInt() / 1000f

private fun median(xs: List<Float>): Float {
    val s = xs.sorted()
    val n = s.size
    return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2f
}

/** Telemetry rows (link events dropped) that carry both current and voltage. */
private fun ivRows(samples: List<SampleEntity>): List<SampleEntity> =
    samples.filter { it.linkEvent == null && it.currentA != null && it.voltageV != null }

// --- in-memory twins of the history aggregates ---------------------------------------------------

/** Twin of IV_MOMENTS_BY_SOC_BIN_SQL over a sample list: I/V telemetry rows grouped by integer SOC
 *  (Float.toInt() truncates exactly like SQLite's CAST(soc AS INTEGER)); NULL SOC is its own group. */
fun ivBinMomentsOf(samples: List<SampleEntity>): List<IvBinMoments> {
    class Acc {
        var n = 0L
        var sI = 0.0; var sV = 0.0; var sII = 0.0; var sIV = 0.0; var sVV = 0.0
        var minI = Double.POSITIVE_INFINITY; var maxI = Double.NEGATIVE_INFINITY
        var minV = Double.POSITIVE_INFINITY; var maxV = Double.NEGATIVE_INFINITY
    }
    val byBin = LinkedHashMap<Int?, Acc>()
    for (s in samples) {
        if (s.linkEvent != null) continue
        val i = s.currentA?.toDouble() ?: continue
        val v = s.voltageV?.toDouble() ?: continue
        val a = byBin.getOrPut(s.soc?.toInt()) { Acc() }
        a.n++
        a.sI += i; a.sV += v; a.sII += i * i; a.sIV += i * v; a.sVV += v * v
        a.minI = minOf(a.minI, i); a.maxI = maxOf(a.maxI, i)
        a.minV = minOf(a.minV, v); a.maxV = maxOf(a.maxV, v)
    }
    return byBin.map { (bin, a) -> IvBinMoments(bin, a.n, a.sI, a.sV, a.sII, a.sIV, a.sVV, a.minI, a.maxI, a.minV, a.maxV) }
}

/** Pool per-bin moments into one global row (bin = null): the scatter's fit spans every I/V row. */
fun pooledMoments(bins: List<IvBinMoments>): IvBinMoments? {
    if (bins.isEmpty()) return null
    return IvBinMoments(
        bin = null,
        n = bins.sumOf { it.n },
        sumI = bins.sumOf { it.sumI },
        sumV = bins.sumOf { it.sumV },
        sumII = bins.sumOf { it.sumII },
        sumIV = bins.sumOf { it.sumIV },
        sumVV = bins.sumOf { it.sumVV },
        minI = bins.minOf { it.minI },
        maxI = bins.maxOf { it.maxI },
        minV = bins.minOf { it.minV },
        maxV = bins.maxOf { it.maxV },
    )
}

/** Twin of CELL_STATS_BY_SESSION_SQL: per-session sums of (cellMax − cellMin)·1000 mV over rows
 *  carrying both, skipping impossible negative deltas (also applied to the per-session means now). */
fun cellSessionStatsOf(samples: List<SampleEntity>): List<CellSessionStats> {
    class Acc { var n = 0L; var sum = 0.0; var max = Double.NEGATIVE_INFINITY }
    val bySession = LinkedHashMap<Long, Acc>()
    for (s in samples) {
        if (s.linkEvent != null) continue
        val mn = s.cellMinV ?: continue
        val mx = s.cellMaxV ?: continue
        if (mx < mn) continue
        val d = (mx.toDouble() - mn.toDouble()) * 1000.0
        val a = bySession.getOrPut(s.sessionId) { Acc() }
        a.n++
        a.sum += d
        a.max = maxOf(a.max, d)
    }
    return bySession.map { (id, a) -> CellSessionStats(id, a.n, a.sum, a.max) }
}

// --- public analysis ----------------------------------------------------------------------------

/**
 * Effective internal resistance: regress V on I within each integer-SOC bin, keep bins with current
 * spread ≥ [BIN_MIN_SPREAD_A], r² ≥ [BIN_MIN_R2] and a plausible slope, then take the median of the
 * surviving slopes. Returns null if no bin survives.
 */
fun effectiveResistanceFromBins(bins: List<IvBinMoments>): PackResistance? {
    val perBin = ArrayList<SocBinResistance>()
    for (m in bins) {
        val soc = m.bin ?: continue
        val fit = fitOf(m) ?: continue
        if (fit.spreadA < BIN_MIN_SPREAD_A) continue
        if (fit.r2 < BIN_MIN_R2) continue
        val mohm = (fit.slopeOhm * 1000.0).toFloat()
        if (mohm <= BIN_MIN_MOHM || mohm >= BIN_MAX_MOHM) continue
        perBin.add(SocBinResistance(soc, round1(mohm), round1(fit.r2.toFloat() * 100f) / 100f, round1(fit.spreadA)))
    }
    if (perBin.isEmpty()) return null
    return PackResistance(
        rMohm = round1(median(perBin.map { it.rMohm })),
        bins = perBin.size,
        perBin = perBin.sortedBy { it.soc },
    )
}

/** List-input form of [effectiveResistanceFromBins]. */
fun effectiveResistance(samples: List<SampleEntity>): PackResistance? =
    effectiveResistanceFromBins(ivBinMomentsOf(samples))

/** Cell imbalance (mV) pooled over every session's sums. Null if never sampled. */
fun cellImbalanceFrom(stats: List<CellSessionStats>): CellImbalance? {
    val sampled = stats.filter { it.n > 0 }
    if (sampled.isEmpty()) return null
    val n = sampled.sumOf { it.n }
    return CellImbalance(
        meanMv = round1((sampled.sumOf { it.sumMv } / n).toFloat()),
        maxMv = round1(sampled.maxOf { it.maxMv }.toFloat()),
        sessionsSampled = sampled.size,
    )
}

/** List-input form of [cellImbalanceFrom]. */
fun cellImbalance(samples: List<SampleEntity>): CellImbalance? = cellImbalanceFrom(cellSessionStatsOf(samples))

/** Mean cell Δ (mV, round1) per session id — the per-run usage metric. */
fun cellDeltaBySession(stats: List<CellSessionStats>): Map<Long, Float> =
    stats.filter { it.n > 0 }.associate { it.sessionId to round1((it.sumMv / it.n).toFloat()) }

/** Row stride of the V–I cloud: every [scatterStep]-th I/V row in time order. */
fun scatterStep(ivRowCount: Long, maxPoints: Int = SCATTER_MAX_POINTS): Int =
    maxOf(1L, ivRowCount / maxPoints).toInt()

/** Exact upper bound on stride output: n/⌊n/m⌋ < 2m for n ≥ m, and n < m points otherwise. */
fun scatterPointCap(maxPoints: Int = SCATTER_MAX_POINTS): Int = 2 * maxPoints

/** List-input stride: rows 0, step, 2·step… of the I/V rows (the points old viScatter drew). */
fun stridedScatter(samples: List<SampleEntity>, maxPoints: Int = SCATTER_MAX_POINTS): List<ScatterPoint> {
    val rows = ivRows(samples)
    val step = scatterStep(rows.size.toLong(), maxPoints)
    return (rows.indices step step).map { ScatterPoint(rows[it].currentA!!, rows[it].voltageV!!) }
}

/**
 * The V–I cloud plus the fit line to draw. [ViScatter.ocv] is the global regression intercept (the
 * open-circuit voltage at I=0) over [global] (all bins pooled); [ViScatter.rMohm] is the binned
 * median resistance when available (the headline number), falling back to the global slope.
 */
fun viScatterFrom(global: IvBinMoments?, pts: List<ScatterPoint>, rMohm: Float?): ViScatter? {
    if (global == null || global.n < 2) return null
    val fit = fitOf(global) ?: return null
    return ViScatter(
        pts = pts,
        rMohm = rMohm ?: round1((fit.slopeOhm * 1000.0).toFloat()),
        ocv = round3(fit.interceptV.toFloat()),
    )
}

/** List-input form of [viScatterFrom]. */
fun viScatter(samples: List<SampleEntity>, rMohm: Float?, maxPoints: Int = SCATTER_MAX_POINTS): ViScatter? =
    viScatterFrom(pooledMoments(ivBinMomentsOf(samples)), stridedScatter(samples, maxPoints), rMohm)

/**
 * Exact row-stride walk for the V–I cloud (DATA-15): rows 0, step, 2·step… of a pack's I/V rows in
 * (tsMs, id) order — the same points [stridedScatter] picks from the full list — fetched ONE at a
 * time by keyset + OFFSET (`SampleDao.ivRowAfter`), so only picked rows are ever materialized and
 * SQLite steps over the rest in index order. [fetch] returns the row `skip` positions after the
 * keyset, or null past the end. Bounded by [cap] even if rows keep arriving mid-walk.
 */
fun stridePoints(
    step: Int,
    cap: Int = scatterPointCap(),
    fetch: (afterTs: Long, afterId: Long, skip: Int) -> IvPoint?,
): List<ScatterPoint> {
    require(step >= 1) { "step must be ≥ 1" }
    val out = ArrayList<ScatterPoint>()
    var afterTs = Long.MIN_VALUE
    var afterId = Long.MIN_VALUE
    var skip = 0
    while (out.size < cap) {
        val r = fetch(afterTs, afterId, skip) ?: break
        out += ScatterPoint(r.currentA, r.voltageV)
        afterTs = r.tsMs
        afterId = r.id
        skip = step - 1
    }
    return out
}

/** Assemble [HealthInputs] from the two aggregates plus the stride walk (step from the I/V row count). */
fun healthInputsFrom(
    bins: List<IvBinMoments>,
    cells: List<CellSessionStats>,
    fetchStride: (afterTs: Long, afterId: Long, skip: Int) -> IvPoint?,
): HealthInputs = HealthInputs(bins, cells, stridePoints(scatterStep(bins.sumOf { it.n }), fetch = fetchStride))

/**
 * Peak-pool a session's raw [samples] into ~[buckets] time buckets so transient spikes survive
 * downsampling: discharge & regen power are **max-pooled**, voltage is **min-pooled** (the deepest
 * sag is kept), SOC takes the last value, and a bucket is flagged [TimelineBucket.link] if any BLE
 * link event fell in it. Never stride-samples — that would drop the spikes that matter.
 */
fun peakPool(samples: List<SampleEntity>, buckets: Int = TIMELINE_BUCKETS): List<TimelineBucket> {
    if (samples.isEmpty()) return emptyList()
    val startMs = samples.first().tsMs
    val endMs = samples.last().tsMs
    val span = (endMs - startMs).coerceAtLeast(1L)
    val n = buckets.coerceIn(1, maxOf(1, samples.size))

    val dis = FloatArray(n)
    val reg = FloatArray(n)
    val minV = arrayOfNulls<Float>(n)
    val soc = arrayOfNulls<Float>(n)
    val link = BooleanArray(n)
    val used = BooleanArray(n)
    val ts = LongArray(n) { startMs + (span * it) / n }

    for (s in samples) {
        val b = (((s.tsMs - startMs) * n) / span).toInt().coerceIn(0, n - 1)
        used[b] = true
        if (s.linkEvent != null) { link[b] = true; continue }
        val cur = s.currentA ?: 0f
        val pw = s.powerW ?: 0f                 // magnitude (V·|I|)
        if (cur < -DISCHARGE_EPS) { if (pw > dis[b]) dis[b] = pw }
        else if (cur > DISCHARGE_EPS) { if (pw > reg[b]) reg[b] = pw }
        s.voltageV?.let { v -> if (minV[b] == null || v < minV[b]!!) minV[b] = v }
        s.soc?.let { soc[b] = it }
    }
    val out = ArrayList<TimelineBucket>(n)
    for (b in 0 until n) {
        if (!used[b]) continue
        out.add(TimelineBucket(ts[b], dis[b], reg[b], minV[b], soc[b], link[b]))
    }
    return out
}

/** Everything History needs from a pack's samples, in O(bins + sessions + points) memory (DATA-15). */
data class HealthInputs(
    val bins: List<IvBinMoments>,
    val cells: List<CellSessionStats>,
    val scatter: List<ScatterPoint>,
)

/** In-memory inputs from a sample list (tests and small inputs; the app aggregates in SQL). */
fun healthInputsOf(samples: List<SampleEntity>, maxPoints: Int = SCATTER_MAX_POINTS): HealthInputs =
    HealthInputs(ivBinMomentsOf(samples), cellSessionStatsOf(samples), stridedScatter(samples, maxPoints))

/**
 * Assemble a [PackHealth] from a pack's stored session rollups ([sessions]) and its aggregated
 * [inputs]. Derived R / scatter / cell imbalance come from the aggregates; per-session cell Δ is the
 * per-session mean.
 */
fun buildPackHealth(
    address: String,
    alias: String,
    sessions: List<SessionEntity>,
    inputs: HealthInputs,
): PackHealth {
    val cellBySession = cellDeltaBySession(inputs.cells)
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
    val resistance = effectiveResistanceFromBins(inputs.bins)
    return PackHealth(
        address = address,
        alias = alias,
        active = rollups.any { it.peakW > DUTY_PEAK_W },
        sessions = rollups,
        totEnergyWh = round1(rollups.sumOf { it.energyWh.toDouble() }.toFloat()),
        peakW = rollups.maxOfOrNull { it.peakW } ?: 0f,
        peakRegenW = rollups.maxOfOrNull { it.regenW } ?: 0f,
        minVload = loaded.mapNotNull { it.minVload }.minOrNull(),
        dischCount = loaded.size,
        sessionCount = rollups.size,
        cell = cellImbalanceFrom(inputs.cells),
        resistance = resistance,
        scatter = viScatterFrom(pooledMoments(inputs.bins), inputs.scatter, resistance?.rMohm),
    )
}
