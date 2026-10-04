package dev.joely.bmsmon

import dev.joely.bmsmon.model.RangeAccumulator
import dev.joely.bmsmon.model.RangeRow
import dev.joely.bmsmon.model.SEED_RANGE_PARAMS
import dev.joely.bmsmon.model.learnRangeParams
import dev.joely.bmsmon.model.todayUsage
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BLE-25: the range learner now streams Room pages through a RangeAccumulator instead of holding a
 * pack's whole 14-day window. It must produce EXACTLY what the list learner did — checked against
 * a frozen copy of it (RangeLearnReference.kt) on synthetic fleets that cross a DST change and carry
 * gaps, ties, NaN/null fields, regen, spikes, coarse fixes and outing drives.
 */
class RangeAccumulatorTest {

    private val zone = ZoneId.of("America/Chicago")

    /** 16 days at a 10 s cadence from 2026-03-01 (spans the 2026-03-08 DST change). */
    private fun rows(seed: Long): List<RangeRow> {
        val rnd = java.util.Random(seed)
        val out = ArrayList<RangeRow>(16 * 8_640)
        var ts = ZonedDateTime.of(2026, 3, 1, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        var lat = 43.03
        var lon = -87.91
        for (i in 0 until 16 * 8_640) {
            ts += when (rnd.nextInt(400)) {
                0 -> 120_000L   // longer than the 60 s burn bound: teaches nothing
                1 -> 200L       // shorter than the 0.5 s bound
                2 -> 0L         // a timestamp tie
                else -> 10_000L
            }
            val hour = (i / 360) % 24
            val driving = hour in 9..11 && rnd.nextInt(10) != 0
            val current: Float? = when {
                rnd.nextInt(200) == 0 -> null
                driving -> -(1.044f + rnd.nextFloat() * 30f)
                rnd.nextInt(50) == 0 -> 2f + rnd.nextFloat() * 10f
                else -> 0f
            }
            val power: Float? = when (rnd.nextInt(300)) {
                0 -> null
                1 -> Float.NaN
                else -> current?.let { kotlin.math.abs(it) * 13.1f }
            }
            val gps = driving && i % 3 == 0
            if (gps) {
                lat += (rnd.nextDouble() - 0.3) * 0.0003
                lon += (rnd.nextDouble() - 0.5) * 0.0003
            }
            val spike = gps && rnd.nextInt(40) == 0
            val accuracy: Float? = if (!gps) null else when (rnd.nextInt(10)) {
                0 -> 80f
                1 -> null
                2 -> Float.NaN
                else -> 5f + rnd.nextFloat() * 20f
            }
            out += RangeRow(
                tsMs = ts,
                currentA = current,
                powerW = power,
                lat = if (gps) (if (spike) lat + 0.05 else lat) else null,
                lon = if (gps) lon else null,
                gpsAccuracyM = accuracy,
                regen = current != null && current > 0.1f && rnd.nextBoolean(),
            )
        }
        return out
    }

    @Test fun streamingMatchesTheListLearnerExactly() {
        for (seed in 1L..3L) {
            val rows = rows(seed)
            val now = rows.last().tsMs + 3_600_000L
            val expected = refLearnRangeParams(rows, zone, now)
            // Fixture sanity: the bands must actually be LEARNED here, or the equality proves little.
            assertTrue("seed $seed learned ${expected.learnedDays} days", expected.learnedDays >= 3)
            assertEquals("seed $seed", expected, learnRangeParams(rows, zone, now))
            assertEquals("seed $seed", refTodayUsage(rows, zone, now), todayUsage(rows, zone, now))
        }
    }

    /**
     * Whole days at a 10 s cadence from 2026-04-01 (no DST change), built to drive the learner's
     * edge rules the random fleet rarely reaches (Task 9 review carry):
     * - GPS on every row, three fixes per 30-s bucket — only the bucket's first may count;
     * - a chair drive 09:00–10:00 at 1.5 m/s, discharging 130 W, with (when [spikes]) two
     *   out-and-back fixes 300 m sideways (~10 m/s: over the 4.5 m/s chair bound, under the 60 m/s
     *   absurd cutoff);
     * - with [vehicle], a ride drawing nothing (the discharge gate): 14:00–14:30 at 20 m/s with one
     *   fix 1.45 km sideways (~49 m/s: over the 45 m/s bound, under 60) when [spikes], then
     *   14:30–15:00 at a chair-like 2 m/s, which only the discharge gate keeps from teaching miles;
     * - on day [shortDay], only a 5-minute drive (450 m, under the 0.5-mi outing gate) plus two hours
     *   of indoor discharge — counted as an outing day it would read ~10× the per-mile cost.
     */
    private fun outings(days: Int, shortDay: Int = -1, spikes: Boolean = true, vehicle: Boolean = true): List<RangeRow> {
        val out = ArrayList<RangeRow>(days * 8_640)
        val start = ZonedDateTime.of(2026, 4, 1, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        val metersPerDegLat = 111_320.0
        val metersPerDegLon = metersPerDegLat * kotlin.math.cos(Math.toRadians(43.03))
        for (d in 0 until days) {
            var lat = 43.03
            val lon = -87.91
            for (k in 0 until 8_640) {
                val ts = start + d * 86_400_000L + k * 10_000L
                val sec = k * 10                       // seconds since local midnight
                val driveEnd = if (d == shortDay) 9 * 3600 + 300 else 10 * 3600
                val driving = sec in 9 * 3600 until driveEnd
                val indoor = d == shortDay && sec in 11 * 3600 until 13 * 3600
                val fast = vehicle && sec in 14 * 3600 until 14 * 3600 + 1_800
                val slow = vehicle && sec in 14 * 3600 + 1_800 until 15 * 3600
                if (driving) lat += 15.0 / metersPerDegLat
                if (fast) lat += 200.0 / metersPerDegLat
                if (slow) lat += 20.0 / metersPerDegLat
                val sideways = when {
                    spikes && driving && (sec == 9 * 3600 + 120 || sec == 9 * 3600 + 1_200) -> 300.0
                    spikes && fast && sec == 14 * 3600 + 900 -> 1_450.0
                    else -> 0.0
                }
                val gps = driving || fast || slow
                out += RangeRow(
                    tsMs = ts,
                    currentA = when { driving -> -10f; indoor -> -4f; else -> 0f },
                    powerW = when { driving -> 130f; indoor -> 50f; else -> 0f },
                    lat = if (gps) lat else null,
                    lon = if (gps) lon + sideways / metersPerDegLon else null,
                    gpsAccuracyM = if (gps) 5f else null,
                    regen = false,
                )
            }
        }
        return out
    }

    private fun Float.near(other: Float) = kotlin.math.abs(this - other) <= 0.01f * kotlin.math.abs(other)

    @Test fun streamingMatchesTheListLearnerOnTheEdgeRules() {
        val rows = outings(days = 5, shortDay = 2)
        val now = rows.last().tsMs + 12 * 3_600_000L
        val learned = learnRangeParams(rows, zone, now)
        assertEquals(refLearnRangeParams(rows, zone, now), learned)
        assertEquals(refTodayUsage(rows, zone, now), todayUsage(rows, zone, now))
        // The per-mile band is LEARNED here (bucket dedup works: 10 s apart, every pair would fall
        // under the 15 s window floor and teach no miles)…
        assertNotEquals(SEED_RANGE_PARAMS.whPerMile, learned.whPerMile)
        // …from the four outing days alone, ~38.7 Wh/mi: the short day (~10× that) is gated out.
        assertTrue("whPerMile ${learned.whPerMile}", learned.whPerMile.hi < 45f)
        // Spikes are rejected and their windows bridged, and the ride teaches no miles: the band is
        // the one the same days give with neither.
        val clean = learnRangeParams(outings(days = 5, shortDay = 2, spikes = false, vehicle = false), zone, now)
        assertTrue("${learned.whPerMile} vs ${clean.whPerMile}", learned.whPerMile.lo.near(clean.whPerMile.lo))
        assertTrue("${learned.whPerMile} vs ${clean.whPerMile}", learned.whPerMile.hi.near(clean.whPerMile.hi))
    }

    @Test fun fewerThanThreeDaysKeepTheSeeds() {
        val rows = outings(days = 2)
        val now = rows.last().tsMs + 12 * 3_600_000L
        val learned = learnRangeParams(rows, zone, now)
        assertEquals(refLearnRangeParams(rows, zone, now), learned)
        assertEquals(SEED_RANGE_PARAMS.whPerMile, learned.whPerMile)
        assertEquals(SEED_RANGE_PARAMS.whPerDay, learned.whPerDay)
        assertEquals(0, learned.learnedDays)
    }

    @Test fun oneAccumulatorServesTheLearnAndTodaysUsage() {
        val rows = rows(7L)
        val now = rows.last().tsMs + 3_600_000L
        val acc = RangeAccumulator(zone)
        rows.forEach { acc.add(it) }
        assertEquals(rows.size, acc.rows)
        assertEquals(refLearnRangeParams(rows, zone, now), learnRangeParams(acc, now))
        assertEquals(refTodayUsage(rows, zone, now), todayUsage(acc, now))
        // Reading it again must not double-count the distance pass.
        assertEquals(refLearnRangeParams(rows, zone, now), learnRangeParams(acc, now))
    }
}
