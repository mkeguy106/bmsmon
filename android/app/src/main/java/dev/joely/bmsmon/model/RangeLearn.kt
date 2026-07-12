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

/** Minimum qualified drive distance for a day to teach Wh/mile. */
private const val MIN_DAY_DRIVE_M = 80.4672f  // 0.05 mi

/** Days of history needed before a learned band replaces its seed. */
private const val MIN_LEARN_DAYS = 3

// Burn-stat gap bounds (s): a gap larger than a poll hiccup teaches nothing.
private const val BURN_DT_MIN_S = 0.5f
private const val BURN_DT_MAX_S = 60f

// Qualified drive segment bounds (see the spec's data findings on GPS jitter).
private const val DRIVE_DT_MIN_S = 0.5f
private const val DRIVE_DT_MAX_S = 15f
private const val DRIVE_MIN_POWER_W = 40f
private const val DRIVE_MAX_ACCURACY_M = 20f
private const val DRIVE_MIN_SPEED_MPS = 0.4f
private const val DRIVE_MAX_SPEED_MPS = 4.0f

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
    var driveWh: Float = 0f,
    var driveM: Float = 0f,
)

/** Equirectangular distance in meters between two fixes — fine at wheelchair scale. */
private fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
    val dy = (lat2 - lat1) * METERS_PER_DEG
    val dx = (lon2 - lon1) * METERS_PER_DEG * cos(Math.toRadians((lat1 + lat2) / 2))
    return sqrt(dx * dx + dy * dy).toFloat()
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
            // Qualified drive segment: tight GPS on both fixes, wheelchair-speed movement,
            // real draw. Anything else is indoor jitter — it must not teach Wh/mile.
            if (dt <= DRIVE_DT_MAX_S && p > DRIVE_MIN_POWER_W &&
                cur.lat != null && cur.lon != null && prev.lat != null && prev.lon != null &&
                (cur.gpsAccuracyM ?: Float.MAX_VALUE) < DRIVE_MAX_ACCURACY_M &&
                (prev.gpsAccuracyM ?: Float.MAX_VALUE) < DRIVE_MAX_ACCURACY_M
            ) {
                val d = distanceM(prev.lat, prev.lon, cur.lat, cur.lon)
                val speed = d / dt
                if (speed in DRIVE_MIN_SPEED_MPS..DRIVE_MAX_SPEED_MPS) {
                    s.driveWh += p * dt / 3600f
                    s.driveM += d
                }
            }
        }
    }
    return days
}

/** p20/p80 band across per-day values, or [seed] with fewer than [MIN_LEARN_DAYS] days. */
private fun bandOf(values: List<Float>, seed: Band): Band {
    if (values.size < MIN_LEARN_DAYS) return seed
    val sorted = values.sorted()
    val lo = percentile(sorted, 0.2f).coerceAtLeast(0.01f)
    val hi = percentile(sorted, 0.8f)
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
    val whPerMile = days.values.filter { it.driveM >= MIN_DAY_DRIVE_M }
        .map { it.driveWh / (it.driveM / METERS_PER_MILE) }
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
