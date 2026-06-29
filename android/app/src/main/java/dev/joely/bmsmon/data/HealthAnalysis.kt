package dev.joely.bmsmon.data

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

private data class Fit(val slopeOhm: Double, val interceptV: Double, val r2: Double, val spreadA: Float)

/**
 * Least-squares regression of terminal voltage on current. With the discharge-negative sign
 * convention V ≈ Voc + I·R, so the slope is the resistance in ohms and the intercept is the OCV.
 * Returns null when current has no spread (a vertical fit). Same math as
 * [estimateInternalResistanceMohm], reused here per SOC bin and globally for the scatter fit.
 */
private fun regress(pts: List<Pair<Float, Float>>): Fit? {
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
    return Fit(slope, intercept, r2, (maxX - minX))
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

// --- public analysis ----------------------------------------------------------------------------

/**
 * Effective internal resistance: bin samples by integer SOC%, regress V on I within each bin, keep
 * bins with current spread ≥ [BIN_MIN_SPREAD_A], r² ≥ [BIN_MIN_R2] and a plausible slope, then take
 * the median of the surviving slopes. Returns null if no bin survives.
 */
fun effectiveResistance(samples: List<SampleEntity>): PackResistance? {
    val byBin = HashMap<Int, MutableList<Pair<Float, Float>>>()
    for (s in ivRows(samples)) {
        val soc = s.soc ?: continue
        byBin.getOrPut(soc.toInt()) { mutableListOf() }.add(s.currentA!! to s.voltageV!!)
    }
    val perBin = ArrayList<SocBinResistance>()
    for ((soc, pts) in byBin) {
        val fit = regress(pts) ?: continue
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

/** Cell imbalance (mV) over the samples that carried per-cell min/max. Null if never sampled. */
fun cellImbalance(samples: List<SampleEntity>): CellImbalance? {
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
        meanMv = round1(deltas.average().toFloat()),
        maxMv = round1(deltas.max()),
        sessionsSampled = sessions.size,
    )
}

/**
 * Downsampled V–I operating cloud plus the fit line to draw. The fit's [ViScatter.ocv] is the
 * global regression intercept (the open-circuit voltage at I=0); [ViScatter.rMohm] is the binned
 * median resistance when available (the headline number), falling back to the global slope.
 */
fun viScatter(samples: List<SampleEntity>, rMohm: Float?, maxPoints: Int = SCATTER_MAX_POINTS): ViScatter? {
    val rows = ivRows(samples)
    if (rows.size < 2) return null
    val iv = rows.map { it.currentA!! to it.voltageV!! }
    val fit = regress(iv) ?: return null
    val step = maxOf(1, rows.size / maxPoints)
    val pts = ArrayList<ScatterPoint>()
    var k = 0
    while (k < iv.size) { pts.add(ScatterPoint(iv[k].first, iv[k].second)); k += step }
    return ViScatter(
        pts = pts,
        rMohm = rMohm ?: round1((fit.slopeOhm * 1000.0).toFloat()),
        ocv = round3(fit.interceptV.toFloat()),
    )
}

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

/**
 * Assemble a [PackHealth] from a pack's stored session rollups ([sessions]) and its full-resolution
 * telemetry ([samples], all sessions, link rows included). Derived R / scatter / cell-imbalance come
 * from the raw samples; per-session cell Δ is computed by grouping samples by session.
 */
fun buildPackHealth(
    address: String,
    alias: String,
    sessions: List<SessionEntity>,
    samples: List<SampleEntity>,
): PackHealth {
    val cellBySession = samples
        .filter { it.cellMinV != null && it.cellMaxV != null }
        .groupBy { it.sessionId }
        .mapValues { (_, rows) -> round1(rows.map { (it.cellMaxV!! - it.cellMinV!!) * 1000f }.average().toFloat()) }

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
    val resistance = effectiveResistance(samples)
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
        cell = cellImbalance(samples),
        resistance = resistance,
        scatter = viScatter(samples, resistance?.rMohm),
    )
}
