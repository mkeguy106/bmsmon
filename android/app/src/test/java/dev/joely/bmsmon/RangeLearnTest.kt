package dev.joely.bmsmon

import dev.joely.bmsmon.model.RangeRow
import dev.joely.bmsmon.model.SEED_RANGE_PARAMS
import dev.joely.bmsmon.model.learnRangeParams
import dev.joely.bmsmon.model.percentile
import dev.joely.bmsmon.model.todayUsage
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class RangeLearnTest {
    private val zone = ZoneId.of("UTC")

    private fun ts(day: Int, hour: Int, min: Int = 0, sec: Int = 0): Long =
        ZonedDateTime.of(2026, 7, day, hour, min, sec, 0, zone).toInstant().toEpochMilli()

    /** [hours] of samples every 30 s starting [startHour] on [day], discharging at [powerW]. */
    private fun dischargeDay(day: Int, startHour: Int, hours: Float, powerW: Float): List<RangeRow> {
        val n = (hours * 3600 / 30).toInt()
        return (0..n).map { i ->
            RangeRow(ts(day, startHour) + i * 30_000L, "Discharging", powerW,
                lat = null, lon = null, gpsAccuracyM = null, regen = false)
        }
    }

    /** Idle filler so the day passes the 12 h coverage bar (samples every 30 s). */
    private fun idleFiller(day: Int, startHour: Int, hours: Int): List<RangeRow> {
        val n = hours * 3600 / 30
        return (0..n).map { i ->
            RangeRow(ts(day, startHour) + i * 30_000L, "Idle", 0f, null, null, null, regen = false)
        }
    }

    @Test fun percentileInterpolatesLinearly() {
        val v = listOf(100f, 120f, 140f, 160f, 180f)
        assertEquals(116f, percentile(v, 0.2f), 0.01f)  // idx 0.8 → 100 + 0.8×20
        assertEquals(164f, percentile(v, 0.8f), 0.01f)  // idx 3.2 → 160 + 0.2×20
        assertEquals(100f, percentile(v, 0f), 0f)
        assertEquals(180f, percentile(v, 1f), 0f)
    }

    @Test fun learnsWhPerDayBandFromQualifyingDays() {
        // 4 qualifying days (13 h coverage each): 2 h discharge at 50/60/80/100 W
        // → 100/120/160/200 Wh/day. p20 = 112, p80 = 176 (linear interp, idx 0.6/2.4).
        val rows = (1..4).flatMap { d ->
            val w = listOf(50f, 60f, 80f, 100f)[d - 1]
            dischargeDay(d, 8, 2f, w) + idleFiller(d, 10, 11)
        }
        val p = learnRangeParams(rows, zone, nowMs = ts(5, 0))
        assertEquals(112f, p.whPerDay.lo, 1f)
        assertEquals(176f, p.whPerDay.hi, 1f)
        assertEquals(4, p.learnedDays)
        // activeW: day means are exactly 50/60/80/100 → p20 = 56, p80 = 88.
        assertEquals(56f, p.activeW.lo, 1f)
        assertEquals(88f, p.activeW.hi, 1f)
    }

    @Test fun fewerThanThreeQualifyingDaysFallsBackToSeeds() {
        val rows = dischargeDay(1, 8, 2f, 80f) + idleFiller(1, 10, 11) +
            dischargeDay(2, 8, 2f, 90f) + idleFiller(2, 10, 11)
        val p = learnRangeParams(rows, zone, nowMs = ts(3, 0))
        assertEquals(SEED_RANGE_PARAMS.whPerDay.lo, p.whPerDay.lo, 0f)
        assertEquals(SEED_RANGE_PARAMS.whPerDay.hi, p.whPerDay.hi, 0f)
        assertEquals(2, p.learnedDays)
    }

    @Test fun lowCoverageDayDoesNotQualify() {
        // Day with only 2 h of samples (< 12 h coverage) must be excluded entirely.
        val good = (1..3).flatMap { d -> dischargeDay(d, 8, 2f, 80f) + idleFiller(d, 10, 11) }
        val sparse = dischargeDay(4, 8, 2f, 500f)  // huge burn but only 2 h coverage
        val p = learnRangeParams(good + sparse, zone, nowMs = ts(5, 0))
        // All qualifying days burned 160 Wh → a degenerate flat band stays near 160, not 1000.
        assertEquals(160f, p.whPerDay.mid, 5f)
    }

    @Test fun whPerMileFromQualifiedDriveSegments() {
        // 3 days, each: 12+ h coverage, plus a 100-sample drive at 2 m/s (10 s apart, 20 m
        // steps) at 72 W discharging with 10 m accuracy. Per segment: dWh = 72×10/3600 = 0.2 Wh,
        // d = 20 m. Day total: 20 Wh, 2000 m = 1.2427 mi → 16.09 Wh/mi (same all days → flat band).
        fun drive(day: Int): List<RangeRow> = (0..100).map { i ->
            RangeRow(ts(day, 9) + i * 10_000L, "Discharging", 72f,
                lat = 40.0 + i * 20 / 111_320.0, lon = -75.0, gpsAccuracyM = 10f, regen = false)
        }
        val rows = (1..3).flatMap { d -> drive(d) + idleFiller(d, 10, 13) }  // ascending by tsMs
        val p = learnRangeParams(rows, zone, nowMs = ts(4, 0))
        assertEquals(16.09f, p.whPerMile.mid, 0.3f)
    }

    @Test fun regenAndBadGpsSegmentsExcluded() {
        // Regen rows must not count toward burn; accuracy ≥ 20 m must not count toward miles.
        val burn = (1..3).flatMap { d -> dischargeDay(d, 8, 2f, 80f) + idleFiller(d, 10, 11) }
        val regenRows = (0..10).map { i ->
            // Hour 22 — after the idle filler ends (21:00), keeping the row list ascending.
            RangeRow(ts(1, 22) + i * 10_000L, "Discharging", 300f, null, null, null, regen = true)
        }
        val p = learnRangeParams(burn + regenRows, zone, nowMs = ts(4, 0))
        assertEquals(160f, p.whPerDay.mid, 5f)  // regen watts never counted
        // No qualified GPS at all → whPerMile stays seeded.
        assertEquals(SEED_RANGE_PARAMS.whPerMile.lo, p.whPerMile.lo, 0f)
    }

    @Test fun todayUsageSumsSinceLocalMidnight() {
        val rows = dischargeDay(1, 23, 1f, 100f) +   // yesterday — excluded
            dischargeDay(2, 8, 2f, 100f)             // today: 200 Wh over 2 h
        val t = todayUsage(rows, zone, nowMs = ts(2, 12))
        assertEquals(200f, t.disWh, 2f)
        assertEquals(2f, t.disHours, 0.05f)
        assertEquals(12f, t.hoursSinceMidnight, 0.01f)
    }
}
