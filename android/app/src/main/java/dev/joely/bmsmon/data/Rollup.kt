package dev.joely.bmsmon.data

import dev.joely.bmsmon.data.db.RollupRow
import dev.joely.bmsmon.data.db.SessionEntity
import kotlin.math.max
import kotlin.math.min

/** Growable primitive float buffer: 4 B per value instead of a boxed List<Float> entry. */
internal class FloatBuf(initialCapacity: Int = 256) {
    var values = FloatArray(initialCapacity)
        private set
    var size = 0
        private set

    fun add(v: Float) {
        if (size == values.size) values = values.copyOf(maxOf(16, size * 2))
        values[size++] = v
    }

    /** Ascending copy in the SAME total order as List<Float>.sorted() (Float.compareTo: −0.0 < 0.0, NaN last). */
    fun sortedCopy(): FloatArray = values.copyOf(size).also { it.sort() }
}

/**
 * Streaming session rollup (DATA-16). Fed one telemetry row at a time, it produces EXACTLY what
 * [computeRollup] used to produce from the whole session list — same float operations in the same
 * order — while holding O(1) state plus three primitive buffers over the session's DISCHARGE rows
 * (exact p95/mean and the internal-resistance regression): ~12 B per discharge row instead of
 * ~400 B per row of the whole session. Finalize, stop, the startup orphan sweep and the CSV
 * backfill all go through it, fed by [streamRollup]. Rows arrive in id order, which equals time
 * order unless the wall clock stepped back mid-session; the span still uses min/max timestamps.
 */
class RollupAccumulator(private val address: String, private val sessionId: Long) {
    /** Telemetry rows folded so far (the rollup's sampleCount). 0 = the session carries no run. */
    var count = 0
        private set

    private var minTs = Long.MAX_VALUE
    private var maxTs = Long.MIN_VALUE
    private var socFirst: Float? = null
    private var socLast: Float? = null
    private var socMin: Float? = null
    private var socMax: Float? = null
    private val dischargePowers = FloatBuf()
    private val irCurrents = FloatBuf()
    private val irVoltages = FloatBuf()
    private var dischargeCurrentMin: Float? = null
    private var regenPowerMax: Float? = null
    private var dischargeVoltageMin: Float? = null
    private var energyWh = 0f
    private var prevTs = 0L
    private var prevDischarging = false
    private var prevPowerW = 0f
    private var sohLast: Int? = null
    private var fullChargeAhLast: Float? = null
    private var cyclesLast: Int? = null
    private var tempMax: Float? = null

    fun add(r: RollupRow) {
        // Energy: the PREVIOUS row's power over the interval to this one — computeRollup's pairwise
        // loop unrolled. Float arithmetic in the same order, so the sum is bit-identical.
        if (count > 0 && prevDischarging) {
            val dtH = (r.tsMs - prevTs).coerceAtLeast(0) / 3_600_000f
            energyWh += prevPowerW * dtH
        }
        count++
        minTs = min(minTs, r.tsMs)
        maxTs = max(maxTs, r.tsMs)
        r.soc?.let { s ->
            if (socFirst == null) socFirst = s
            socLast = s
            socMin = socMin?.let { min(it, s) } ?: s
            socMax = socMax?.let { max(it, s) } ?: s
        }
        val cur = r.currentA
        val discharging = cur != null && cur < -DISCHARGE_EPS   // == (currentA ?: 0f) < -EPS
        if (cur != null && discharging) {
            r.powerW?.let(dischargePowers::add)
            dischargeCurrentMin = dischargeCurrentMin?.let { min(it, cur) } ?: cur
            r.voltageV?.let { v ->
                dischargeVoltageMin = dischargeVoltageMin?.let { min(it, v) } ?: v
                irCurrents.add(cur)
                irVoltages.add(v)
            }
        }
        if (r.regen) r.powerW?.let { p -> regenPowerMax = regenPowerMax?.let { max(it, p) } ?: p }
        r.soh?.let { sohLast = it }
        r.fullChargeAh?.let { fullChargeAhLast = it }
        r.cycles?.let { cyclesLast = it }
        r.tempC?.let { t -> tempMax = tempMax?.let { max(it, t) } ?: t }
        prevTs = r.tsMs
        prevDischarging = discharging
        prevPowerW = r.powerW ?: 0f
    }

    fun toRollup(): SessionEntity {
        val powers = dischargePowers.sortedCopy()
        val n = powers.size
        var powerSum = 0.0
        for (p in powers) powerSum += p   // List<Float>.average(): a Double sum in (sorted) order
        val ir = estimateInternalResistanceMohm(irCurrents.values, irVoltages.values, irCurrents.size)
        val startMs = if (count == 0) 0L else minTs
        return SessionEntity(
            id = sessionId,
            address = address,
            startMs = startMs,
            endMs = if (count == 0) startMs else maxTs,
            sampleCount = count,
            peakPowerW = if (n == 0) 0f else powers[n - 1],
            p95PowerW = if (n == 0) 0f else powers[(n - 1) * 95 / 100],
            meanPowerW = if (n == 0) 0f else (powerSum / n).toFloat(),
            peakCurrentA = dischargeCurrentMin?.let { -it } ?: 0f,
            peakRegenW = regenPowerMax ?: 0f,
            energyWh = energyWh,
            socStart = socFirst ?: 0f,
            socEnd = socLast ?: 0f,
            minSoc = socMin ?: 0f,
            maxSoc = socMax ?: 0f,
            minVoltageUnderLoad = dischargeVoltageMin ?: 0f,
            estInternalResistanceMohm = ir?.mohm,
            irConfidence = ir?.confidence ?: 0f,
            sohEnd = sohLast ?: 0,
            fullChargeAhEnd = fullChargeAhLast ?: 0f,
            cyclesEnd = cyclesLast ?: 0,
            maxTempC = tempMax ?: 0f,
        )
    }
}

/**
 * Fold one session's telemetry through a [RollupAccumulator], [pageSize] rows at a time (DATA-16).
 * [fetch] is the keyset page query (`SampleDao.rollupPage` in production, an in-memory or JDBC
 * stand-in in tests). Memory: one page plus the accumulator's discharge buffers, however long the
 * session.
 */
fun streamRollup(
    address: String,
    sessionId: Long,
    pageSize: Int,
    fetch: (afterId: Long, limit: Int) -> List<RollupRow>,
): RollupAccumulator {
    val acc = RollupAccumulator(address, sessionId)
    forEachKeysetPage(pageSize, fetch, RollupRow::id, acc::add)
    return acc
}
