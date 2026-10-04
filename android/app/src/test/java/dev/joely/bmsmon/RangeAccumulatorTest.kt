package dev.joely.bmsmon

import dev.joely.bmsmon.model.RangeAccumulator
import dev.joely.bmsmon.model.RangeRow
import dev.joely.bmsmon.model.learnRangeParams
import dev.joely.bmsmon.model.todayUsage
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
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
