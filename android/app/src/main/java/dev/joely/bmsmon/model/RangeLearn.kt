package dev.joely.bmsmon.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Learns the per-pack discharge-range parameter bands from the local 14-day sample history.
 * Pure — the engine maps Room rows to [RangeRow] and calls this off the poll path.
 * Design: docs/superpowers/specs/2026-07-11-discharge-estimate-design.md
 */

/** One telemetry row, pre-filtered to linkEvent == null (recentSamples does that). */
data class RangeRow(
    val tsMs: Long,
    val state: String?,
    val powerW: Float?,
    val lat: Double?,
    val lon: Double?,
    val gpsAccuracyM: Float?,
    val regen: Boolean,
)

/** A day must have this much sample coverage to teach daily-burn stats. */
private const val MIN_DAY_COVERAGE_S = 12f * 3600f

/** Minimum discharge time for a day to teach the active-draw mean. */
private const val MIN_DAY_DIS_H = 0.25f

/** Minimum clean outdoor distance for a day to count as an "outing day" for Wh/mile. A day
 *  with only a token outdoor sliver must not divide its whole burn by a few hundred meters. */
private const val OUTING_MIN_DRIVE_M = 804.67f  // 0.5 mi

/** Days of history needed before a learned band replaces its seed. */
private const val MIN_LEARN_DAYS = 3

// Burn-stat gap bounds (s): a gap larger than a poll hiccup teaches nothing.
private const val BURN_DT_MIN_S = 0.5f
private const val BURN_DT_MAX_S = 60f

// Windowed drive measurement (2026-07-12). The fused provider refreshes fixes every ~10-30 s
// (balanced power) while telemetry samples every 1.5 s, so consecutive-sample distances read
// as freeze-then-teleport — inflated speeds that misfiled real driving as vehicle movement
// (a 4.8-mile Milwaukee outing measured 0.02 mi pairwise). Distance is instead taken between
// one representative fix per 30-s bucket; displacement over such windows recovers true speed
// regardless of fix latching.
private const val WIN_BUCKET_MS = 30_000L
private const val WIN_DT_MIN_S = 15f
private const val WIN_DT_MAX_S = 90f
private const val WIN_MAX_ACCURACY_M = 50f

// Chair windows: the chair tops out ~9 mph (4.0 m/s) with a little more downhill, so 4.5 is
// the ceiling; the 0.4 floor (12 m per 30 s) sits above stationary-jitter random walk.
// Vehicle discrimination is the DISCHARGE GATE below: in the van or on a train the chair
// draws nothing (user-confirmed), so GPS movement without discharge is a vehicle ride —
// no speed heuristics or context windows needed.
private const val CHAIR_MIN_SPEED_MPS = 0.4f
private const val CHAIR_MAX_SPEED_MPS = 4.5f

// Spike rejection bounds (TS sibling: web/src/v2/model/cleanTrack.ts — keep in sync).
private const val VEHICLE_MAX_MPS = 45f
private const val ABSURD_MPS = 60f

private const val METERS_PER_MILE = 1609.34f
private const val METERS_PER_DEG = 111_320.0

/** Linear-interpolated percentile of pre-sorted [sortedValues]; [p] in 0..1. */
fun percentile(sortedValues: List<Float>, p: Float): Float {
    if (sortedValues.isEmpty()) return 0f
    val idx = p.coerceIn(0f, 1f) * (sortedValues.size - 1)
    val lo = idx.toInt()
    val hi = minOf(lo + 1, sortedValues.size - 1)
    val frac = idx - lo
    return sortedValues[lo] + frac * (sortedValues[hi] - sortedValues[lo])
}

private data class DayStats(
    var coverageS: Float = 0f,
    var disWh: Float = 0f,
    var disS: Float = 0f,
    var driveM: Float = 0f,
)

/** Equirectangular distance in meters between two fixes — fine at wheelchair scale. */
private fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
    val dy = (lat2 - lat1) * METERS_PER_DEG
    val dx = (lon2 - lon1) * METERS_PER_DEG * cos(Math.toRadians((lat1 + lat2) / 2))
    return sqrt(dx * dx + dy * dy).toFloat()
}

private data class Fix(val tsMs: Long, val lat: Double, val lon: Double, val discharging: Boolean)

/** One representative fix per 30-s bucket (the bucket's first accuracy-gated GPS row). */
private fun bucketedFixes(rows: List<RangeRow>): List<Fix> {
    val out = ArrayList<Fix>()
    var lastBucket = Long.MIN_VALUE
    for (r in rows) {
        if (r.lat == null || r.lon == null) continue
        if ((r.gpsAccuracyM ?: Float.MAX_VALUE) >= WIN_MAX_ACCURACY_M) continue
        val bucket = r.tsMs / WIN_BUCKET_MS
        if (bucket == lastBucket) continue
        lastBucket = bucket
        out.add(Fix(r.tsMs, r.lat, r.lon, r.state == "Discharging"))
    }
    return out
}

private data class WinSeg(val tsMs: Long, val dM: Float, val vel: Float, val discharging: Boolean)

/** Displacement between consecutive bucketed fixes — immune to fix-latching teleports. */
private fun windowedSegments(fixes: List<Fix>): List<WinSeg> {
    val out = ArrayList<WinSeg>()
    for (i in 1 until fixes.size) {
        val a = fixes[i - 1]
        val b = fixes[i]
        val dt = (b.tsMs - a.tsMs) / 1000f
        if (dt < WIN_DT_MIN_S || dt > WIN_DT_MAX_S) continue
        val d = distanceM(a.lat, a.lon, b.lat, b.lon)
        out.add(WinSeg(b.tsMs, d, d / dt, a.discharging || b.discharging))
    }
    return out
}

private fun speedMps(a: Fix, b: Fix): Float {
    val dtS = (b.tsMs - a.tsMs) / 1000f
    if (dtS <= 0f) return Float.POSITIVE_INFINITY
    return distanceM(a.lat, a.lon, b.lat, b.lon) / dtS
}

/** Drop out-and-back spike fixes: a fix demanding impossible speed both to reach AND to
 *  leave — at chair speed while discharging, vehicle speed otherwise — while its neighbors
 *  agree with each other, is a GPS lie. Sustained movement (vehicle legs, reacquires) keeps. */
private fun rejectSpikes(fixes: List<Fix>): List<Fix> {
    val out = ArrayList<Fix>(fixes.size)
    for (i in fixes.indices) {
        val b = fixes[i]
        if (out.isEmpty()) { out.add(b); continue }
        val a = out.last()
        val vIn = speedMps(a, b)
        if (vIn > ABSURD_MPS) continue
        val bound = if (a.discharging || b.discharging) CHAIR_MAX_SPEED_MPS else VEHICLE_MAX_MPS
        if (vIn > bound) {
            val c = fixes.getOrNull(i + 1)
            if (c != null && speedMps(b, c) > bound && speedMps(a, c) <= bound) continue
        }
        out.add(b)
    }
    return out
}

private fun accumulate(rows: List<RangeRow>, zone: ZoneId): Map<LocalDate, DayStats> {
    val days = HashMap<LocalDate, DayStats>()
    for (i in 1 until rows.size) {
        val prev = rows[i - 1]
        val cur = rows[i]
        val dt = (cur.tsMs - prev.tsMs) / 1000f
        if (dt < BURN_DT_MIN_S || dt > BURN_DT_MAX_S) continue
        val day = Instant.ofEpochMilli(cur.tsMs).atZone(zone).toLocalDate()
        val s = days.getOrPut(day) { DayStats() }
        s.coverageS += dt
        val p = cur.powerW
        if (cur.state == "Discharging" && !cur.regen && p != null && p.isFinite() && p > 0f) {
            s.disWh += p * dt / 3600f
            s.disS += dt
        }
    }
    // Chair distance: windowed displacement at chair speeds WHILE DISCHARGING. In the van or
    // on a train the chair draws nothing, so GPS movement without discharge is a vehicle ride
    // and teaches no miles, whatever its speed.
    for (seg in windowedSegments(rejectSpikes(bucketedFixes(rows)))) {
        if (!seg.discharging) continue
        if (seg.vel < CHAIR_MIN_SPEED_MPS || seg.vel > CHAIR_MAX_SPEED_MPS) continue
        val day = Instant.ofEpochMilli(seg.tsMs).atZone(zone).toLocalDate()
        days.getOrPut(day) { DayStats() }.driveM += seg.dM
    }
    return days
}

/** p20/p80 band across per-day values, or [seed] with fewer than [MIN_LEARN_DAYS] days. */
private fun bandOf(values: List<Float>, seed: Band): Band {
    if (values.size < MIN_LEARN_DAYS) return seed
    val sorted = values.sorted()
    val hiRaw = percentile(sorted, 0.8f)
    // All-zero(ish) days carry no signal (e.g. a pack staged but parked for days) — an
    // honest band can't be learned from them, and a zero hi would divide to Infinity.
    if (hiRaw <= 0f) return seed
    val lo = percentile(sorted, 0.2f).coerceAtLeast(0.01f)
    val hi = hiRaw.coerceAtLeast(lo)
    // A degenerate flat band (identical days) still needs width for an honest hi/lo readout.
    return if (hi - lo < lo * 0.1f) Band(lo * 0.95f, hi * 1.05f) else Band(lo, hi)
}

/** Distill [rows] (14-day window, ascending, one pack) into learned parameter bands. */
fun learnRangeParams(rows: List<RangeRow>, zone: ZoneId, nowMs: Long): RangeParams {
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    // Today is still accumulating — a half day would bias every per-day statistic low.
    val days = accumulate(rows, zone).filterKeys { it != today }
    val qualifying = days.values.filter { it.coverageS >= MIN_DAY_COVERAGE_S }
    val whPerDay = qualifying.map { it.disWh }
    val activeW = qualifying.filter { it.disS / 3600f >= MIN_DAY_DIS_H }
        .map { it.disWh / (it.disS / 3600f) }
    // OUTING-DAY semantics: the day's TOTAL burn divides by its clean outdoor miles, so every
    // overhead (indoor maneuvering, idle-on time) lands in the per-mile cost — the estimate
    // converges on lived "how far does it take me" range, not smooth-cruise physics.
    val whPerMile = qualifying.filter { it.driveM >= OUTING_MIN_DRIVE_M }
        .map { it.disWh / (it.driveM / METERS_PER_MILE) }
    return RangeParams(
        whPerDay = bandOf(whPerDay, SEED_RANGE_PARAMS.whPerDay),
        activeW = bandOf(activeW, SEED_RANGE_PARAMS.activeW),
        whPerMile = bandOf(whPerMile, SEED_RANGE_PARAMS.whPerMile),
        learnedDays = whPerDay.size,
        updatedMs = nowMs,
    )
}

/** Today's burn so far (since local midnight) — the live-tilt input. */
fun todayUsage(rows: List<RangeRow>, zone: ZoneId, nowMs: Long): TodayUsage {
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val stats = accumulate(rows, zone)[today] ?: DayStats()
    val midnight = today.atStartOfDay(zone).toInstant().toEpochMilli()
    return TodayUsage(
        disWh = stats.disWh,
        disHours = stats.disS / 3600f,
        hoursSinceMidnight = (nowMs - midnight) / 3_600_000f,
    )
}
