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

    /** [n] qualified drive segments at 3 m/s (30 m / 10 s), 72 W, starting 9:00 on [day].
     *  27 segments = 810 m = 0.503 mi — just over the 0.5 mi outing-day bar. */
    private fun outingDrive(day: Int, n: Int): List<RangeRow> = (0..n).map { i ->
        RangeRow(ts(day, 9) + i * 10_000L, "Discharging", 72f,
            lat = 40.0 + i * 30 / 111_320.0, lon = -75.0, gpsAccuracyM = 10f, regen = false)
    }

    /** [n] vehicle-speed windows (300 m / 30 s = 10 m/s, Idle) starting [offsetS] after 9:00,
     *  continuing the path from [fromLatSteps] 30-m drive steps. */
    private fun vehicleRun(day: Int, offsetS: Int, fromLatSteps: Int, n: Int = 3): List<RangeRow> {
        val lat0 = 40.0 + fromLatSteps * 30 / 111_320.0
        return (0..n).map { i ->
            RangeRow(ts(day, 9) + (offsetS + i * 30) * 1000L, "Idle", 0f,
                lat = lat0 + i * 300 / 111_320.0, lon = -75.0, gpsAccuracyM = 15f, regen = false)
        }
    }

    @Test fun outingDayLearnsWhPerMileFromFullDayBurn() {
        // Outing-day semantics: the day's TOTAL discharge (drive 5.4 Wh + 100 Wh of GPS-less
        // indoor burn) divides by the day's clean outdoor miles (810 m = 0.5033 mi), so all
        // overhead lands in the per-mile cost: 105.4 / 0.5033 ≈ 209.4 Wh/mi.
        val rows = (1..3).flatMap { d ->
            outingDrive(d, 27) + dischargeDay(d, 12, 1f, 100f) + idleFiller(d, 13, 11)
        }
        val p = learnRangeParams(rows, zone, nowMs = ts(4, 0))
        assertEquals(209.4f, (p.whPerMile.lo + p.whPerMile.hi) / 2f, 2f)
    }

    @Test fun outingDriveAloneLearns() {
        // Control for the vehicle-context tests: 27 segments (810 m ≥ 0.5 mi) with only the
        // drive's own burn → 5.4 Wh / 0.5033 mi ≈ 10.73 Wh/mi, learned not seeded.
        val rows = (1..3).flatMap { d -> outingDrive(d, 27) + idleFiller(d, 10, 13) }
        val p = learnRangeParams(rows, zone, nowMs = ts(4, 0))
        assertEquals(10.73f, (p.whPerMile.lo + p.whPerMile.hi) / 2f, 0.3f)
    }

    @Test fun subOutingDistanceStaysSeeded() {
        // A day with only a token outdoor sliver (10 segments = 300 m < 0.5 mi) must not
        // divide the whole day's burn by it — whPerMile stays seeded.
        val rows = (1..3).flatMap { d -> outingDrive(d, 10) + idleFiller(d, 10, 13) }
        val p = learnRangeParams(rows, zone, nowMs = ts(4, 0))
        assertEquals(SEED_RANGE_PARAMS.whPerMile.lo, p.whPerMile.lo, 0f)
        assertEquals(SEED_RANGE_PARAMS.whPerMile.hi, p.whPerMile.hi, 0f)
    }

    @Test fun vehicleRideDoesNotCount() {
        // In the van/train the chair has ZERO discharge (user-confirmed) — GPS movement
        // without discharge is a vehicle ride, no matter the speed. Idle 10 m/s windows
        // teach nothing; whPerMile stays seeded.
        val rows = (1..3).flatMap { d ->
            vehicleRun(d, offsetS = 0, fromLatSteps = 0, n = 40) + idleFiller(d, 10, 13)
        }
        val p = learnRangeParams(rows, zone, nowMs = ts(4, 0))
        assertEquals(SEED_RANGE_PARAMS.whPerMile.lo, p.whPerMile.lo, 0f)
        assertEquals(SEED_RANGE_PARAMS.whPerMile.hi, p.whPerMile.hi, 0f)
    }

    @Test fun idleChairSpeedMovementDoesNotCount() {
        // Chair-speed movement WITHOUT discharge (train creeping, chair pushed) is still a
        // vehicle/passive ride — the discharge gate excludes it even inside the speed band.
        val rows = (1..3).flatMap { d ->
            (0..30).map { i ->
                RangeRow(ts(d, 9) + i * 30_000L, "Idle", 0f,
                    lat = 40.0 + i * 60 / 111_320.0, lon = -75.0,   // 2 m/s windowed
                    gpsAccuracyM = 10f, regen = false)
            } + idleFiller(d, 10, 13)
        }
        val p = learnRangeParams(rows, zone, nowMs = ts(4, 0))
        assertEquals(SEED_RANGE_PARAMS.whPerMile.lo, p.whPerMile.lo, 0f)
        assertEquals(SEED_RANGE_PARAMS.whPerMile.hi, p.whPerMile.hi, 0f)
    }

    @Test fun drivingAdjacentToVehicleRideStillCounts() {
        // Rolling to the van and immediately riding away must not erase the rolling — the
        // discharge gate separates them per-window; no blast radius around the vehicle ride.
        val rows = (1..3).flatMap { d ->
            outingDrive(d, 27) + vehicleRun(d, offsetS = 300, fromLatSteps = 27) + idleFiller(d, 10, 13)
        }
        val p = learnRangeParams(rows, zone, nowMs = ts(4, 0))
        assertEquals(10.73f, (p.whPerMile.lo + p.whPerMile.hi) / 2f, 0.3f)
    }

    @Test fun frozenFixCruiseStillMeasured() {
        // The fused provider refreshes fixes every ~10-30 s while telemetry samples faster,
        // so raw consecutive samples read freeze-freeze-teleport. A 3 m/s cruise whose fix
        // only updates every 30 s (three identical fixes, then a 90 m jump) must still
        // measure: windowed displacement recovers the true speed. 81 rows = 2430 m ≥ 0.5 mi.
        val rows = (1..3).flatMap { d ->
            (0..81).map { i ->
                RangeRow(ts(d, 9) + i * 10_000L, "Discharging", 72f,
                    lat = 40.0 + (i / 3) * 90 / 111_320.0, lon = -75.0,
                    gpsAccuracyM = 10f, regen = false)
            } + idleFiller(d, 10, 13)
        }
        val p = learnRangeParams(rows, zone, nowMs = ts(4, 0))
        assertEquals(10.73f, (p.whPerMile.lo + p.whPerMile.hi) / 2f, 0.4f)
    }

    @Test fun fullSpeedCruiseCounts() {
        // The chair tops out around 9 mph (~4.0 m/s, a bit more downhill) — a 4.2 m/s
        // windowed cruise must count as chair driving, not vehicle. 126 m / 30 s steps.
        val rows = (1..3).flatMap { d ->
            (0..8).map { i ->
                RangeRow(ts(d, 9) + i * 30_000L, "Discharging", 72f,
                    lat = 40.0 + i * 126 / 111_320.0, lon = -75.0,
                    gpsAccuracyM = 10f, regen = false)
            } + idleFiller(d, 10, 13)
        }
        // 8 windows × 126 m = 1008 m = 0.6263 mi; burn = 8 × 30 s × 72 W = 4.8 Wh → 7.66 Wh/mi.
        val p = learnRangeParams(rows, zone, nowMs = ts(4, 0))
        assertEquals(7.66f, (p.whPerMile.lo + p.whPerMile.hi) / 2f, 0.3f)
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

    @Test fun zeroDischargeQualifyingDaysFallBackToSeedWhPerDay() {
        // 3 qualifying days (13 h coverage each) with ZERO discharge — a pack staged but parked.
        // A learned hi of 0 would divide to Infinity downstream, so bandOf must fall back to seed.
        val rows = (1..3).flatMap { d -> idleFiller(d, 8, 13) }
        val p = learnRangeParams(rows, zone, nowMs = ts(4, 0))
        assertEquals(SEED_RANGE_PARAMS.whPerDay.lo, p.whPerDay.lo, 0f)
        assertEquals(SEED_RANGE_PARAMS.whPerDay.hi, p.whPerDay.hi, 0f)
        assertEquals(3, p.learnedDays)
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
