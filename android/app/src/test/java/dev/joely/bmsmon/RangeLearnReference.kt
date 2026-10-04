package dev.joely.bmsmon

import dev.joely.bmsmon.model.Band
import dev.joely.bmsmon.model.RangeParams
import dev.joely.bmsmon.model.RangeRow
import dev.joely.bmsmon.model.SEED_RANGE_PARAMS
import dev.joely.bmsmon.model.TodayUsage
import dev.joely.bmsmon.model.isDischarging
import dev.joely.bmsmon.model.percentile
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.cos
import kotlin.math.sqrt

/*
 * Frozen copy of the list-based range learner as it stood before BLE-25 (model/RangeLearn.kt at
 * main 2e9b6fd) — the oracle RangeAccumulatorTest checks the streaming learner against. Do not
 * "fix" or tidy it: it is the definition of the old behaviour.
 */

private const val R_MIN_DAY_COVERAGE_S = 12f * 3600f
private const val R_MIN_DAY_DIS_H = 0.25f
private const val R_OUTING_MIN_DRIVE_M = 804.67f
private const val R_MIN_LEARN_DAYS = 3
private const val R_BURN_DT_MIN_S = 0.5f
private const val R_BURN_DT_MAX_S = 60f
private const val R_WIN_BUCKET_MS = 30_000L
private const val R_WIN_DT_MIN_S = 15f
private const val R_WIN_DT_MAX_S = 90f
private const val R_WIN_MAX_ACCURACY_M = 50f
private const val R_CHAIR_MIN_SPEED_MPS = 0.4f
private const val R_CHAIR_MAX_SPEED_MPS = 4.5f
private const val R_VEHICLE_MAX_MPS = 45f
private const val R_ABSURD_MPS = 60f
private const val R_METERS_PER_MILE = 1609.34f
private const val R_METERS_PER_DEG = 111_320.0

private class RDay(var coverageS: Float = 0f, var disWh: Float = 0f, var disS: Float = 0f, var driveM: Float = 0f)
private class RFix(val tsMs: Long, val lat: Double, val lon: Double, val discharging: Boolean)
private class RSeg(val tsMs: Long, val dM: Float, val vel: Float, val discharging: Boolean)

private fun rDistanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
    val dy = (lat2 - lat1) * R_METERS_PER_DEG
    val dx = (lon2 - lon1) * R_METERS_PER_DEG * cos(Math.toRadians((lat1 + lat2) / 2))
    return sqrt(dx * dx + dy * dy).toFloat()
}

private fun rBucketedFixes(rows: List<RangeRow>): List<RFix> {
    val out = ArrayList<RFix>()
    var lastBucket = Long.MIN_VALUE
    for (r in rows) {
        val lat = r.lat ?: continue
        val lon = r.lon ?: continue
        if ((r.gpsAccuracyM ?: Float.MAX_VALUE) >= R_WIN_MAX_ACCURACY_M) continue
        val bucket = r.tsMs / R_WIN_BUCKET_MS
        if (bucket == lastBucket) continue
        lastBucket = bucket
        out.add(RFix(r.tsMs, lat, lon, r.isDischarging))
    }
    return out
}

private fun rWindowedSegments(fixes: List<RFix>): List<RSeg> {
    val out = ArrayList<RSeg>()
    for (i in 1 until fixes.size) {
        val a = fixes[i - 1]
        val b = fixes[i]
        val dt = (b.tsMs - a.tsMs) / 1000f
        if (dt < R_WIN_DT_MIN_S || dt > R_WIN_DT_MAX_S) continue
        val d = rDistanceM(a.lat, a.lon, b.lat, b.lon)
        out.add(RSeg(b.tsMs, d, d / dt, a.discharging || b.discharging))
    }
    return out
}

private fun rSpeedMps(a: RFix, b: RFix): Float {
    val dtS = (b.tsMs - a.tsMs) / 1000f
    if (dtS <= 0f) return Float.POSITIVE_INFINITY
    return rDistanceM(a.lat, a.lon, b.lat, b.lon) / dtS
}

private fun rRejectSpikes(fixes: List<RFix>): List<RFix> {
    val out = ArrayList<RFix>(fixes.size)
    for (i in fixes.indices) {
        val b = fixes[i]
        if (out.isEmpty()) { out.add(b); continue }
        val a = out.last()
        val vIn = rSpeedMps(a, b)
        if (vIn > R_ABSURD_MPS) continue
        val bound = if (a.discharging || b.discharging) R_CHAIR_MAX_SPEED_MPS else R_VEHICLE_MAX_MPS
        if (vIn > bound) {
            val c = fixes.getOrNull(i + 1)
            if (c != null && rSpeedMps(b, c) > bound && rSpeedMps(a, c) <= bound) continue
        }
        out.add(b)
    }
    return out
}

private fun rAccumulate(rows: List<RangeRow>, zone: ZoneId): Map<LocalDate, RDay> {
    val days = HashMap<LocalDate, RDay>()
    for (i in 1 until rows.size) {
        val prev = rows[i - 1]
        val cur = rows[i]
        val dt = (cur.tsMs - prev.tsMs) / 1000f
        if (dt < R_BURN_DT_MIN_S || dt > R_BURN_DT_MAX_S) continue
        val day = Instant.ofEpochMilli(cur.tsMs).atZone(zone).toLocalDate()
        val s = days.getOrPut(day) { RDay() }
        s.coverageS += dt
        val p = cur.powerW
        if (cur.isDischarging && !cur.regen && p != null && p.isFinite() && p > 0f) {
            s.disWh += p * dt / 3600f
            s.disS += dt
        }
    }
    for (seg in rWindowedSegments(rRejectSpikes(rBucketedFixes(rows)))) {
        if (!seg.discharging) continue
        if (seg.vel < R_CHAIR_MIN_SPEED_MPS || seg.vel > R_CHAIR_MAX_SPEED_MPS) continue
        val day = Instant.ofEpochMilli(seg.tsMs).atZone(zone).toLocalDate()
        days.getOrPut(day) { RDay() }.driveM += seg.dM
    }
    return days
}

private fun rBandOf(values: List<Float>, seed: Band): Pair<Band, Boolean> {
    if (values.size < R_MIN_LEARN_DAYS) return seed to false
    val sorted = values.sorted()
    val hiRaw = percentile(sorted, 0.8f)
    if (hiRaw <= 0f) return seed to false
    val lo = percentile(sorted, 0.2f).coerceAtLeast(0.01f)
    val hi = hiRaw.coerceAtLeast(lo)
    val band = if (hi - lo < lo * 0.1f) Band(lo * 0.95f, hi * 1.05f) else Band(lo, hi)
    return band to true
}

internal fun refLearnRangeParams(rows: List<RangeRow>, zone: ZoneId, nowMs: Long): RangeParams {
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val days = rAccumulate(rows, zone).filterKeys { it != today }
    val qualifying = days.values.filter { it.coverageS >= R_MIN_DAY_COVERAGE_S }
    val whPerDay = qualifying.map { it.disWh }
    val activeW = qualifying.filter { it.disS / 3600f >= R_MIN_DAY_DIS_H }
        .map { it.disWh / (it.disS / 3600f) }
    val whPerMile = qualifying.filter { it.driveM >= R_OUTING_MIN_DRIVE_M }
        .map { it.disWh / (it.driveM / R_METERS_PER_MILE) }
    val whPerDayBand = rBandOf(whPerDay, SEED_RANGE_PARAMS.whPerDay)
    return RangeParams(
        whPerDay = whPerDayBand.first,
        activeW = rBandOf(activeW, SEED_RANGE_PARAMS.activeW).first,
        whPerMile = rBandOf(whPerMile, SEED_RANGE_PARAMS.whPerMile).first,
        learnedDays = if (whPerDayBand.second) whPerDay.size else 0,
        updatedMs = nowMs,
    )
}

internal fun refTodayUsage(rows: List<RangeRow>, zone: ZoneId, nowMs: Long): TodayUsage {
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val stats = rAccumulate(rows, zone)[today] ?: RDay()
    val midnight = today.atStartOfDay(zone).toInstant().toEpochMilli()
    return TodayUsage(
        disWh = stats.disWh,
        disHours = stats.disS / 3600f,
        hoursSinceMidnight = (nowMs - midnight) / 3_600_000f,
    )
}
