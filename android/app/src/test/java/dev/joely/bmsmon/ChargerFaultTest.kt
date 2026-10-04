package dev.joely.bmsmon

import dev.joely.bmsmon.model.ChargeReading
import dev.joely.bmsmon.model.ChargerFaultState
import dev.joely.bmsmon.model.foldChargerFault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargerFaultTest {

    private val min = 60_000L

    private fun fold(
        start: ChargerFaultState = ChargerFaultState(),
        steps: Int,
        stepMs: Long = min,
        t0: Long = 0L,
        onExternal: Boolean = true,
        mah: (Int) -> Int?,
    ): Pair<ChargerFaultState, Int?> {
        var s = start
        var firstFault: Int? = null
        for (i in 0..steps) {
            s = foldChargerFault(s, ChargeReading(t0 + i * stepMs, onExternal, mah(i)))
            if (s.fault && firstFault == null) firstFault = i
        }
        return s to firstFault
    }

    // 2026-10-04 trace: counter flat ~3110-3146, then ~45 mAh per 15 min (180 mA).
    @Test fun realTraceFaultsAfterFirstFullWindowOfFalling() {
        var s = ChargerFaultState()
        // Flat phase, 1 reading per minute for an hour: a slow wander from 3146 down to ~3110
        // (about 9 mAh per 15 min), as the real counter did while the pad was still working.
        for (i in 0 until 60) {
            s = foldChargerFault(s, ChargeReading(i * min, true, 3146 - (i * 36) / 60))
            assertFalse("flat phase faulted at $i", s.fault)
        }
        // Falling phase: 3 mAh/min from 3146.
        var faultAtMin: Int? = null
        for (j in 1..40) {
            val at = (59 + j) * min
            s = foldChargerFault(s, ChargeReading(at, true, 3110 - 3 * j))
            if (s.fault && faultAtMin == null) faultAtMin = j
        }
        assertTrue(s.fault)
        // 3 mAh/min is 180 mA: the 25 mAh drop against the 15-min-old anchor lands well inside
        // the first full 15-min window of falling, and not before the fall began.
        assertTrue("fault at $faultAtMin", faultAtMin!! in 1..15)
        assertEquals((59 + faultAtMin) * min, s.faultSinceElapsedMs)
    }

    @Test fun noiseAroundFlatNeverFaultsOverSixHours() {
        val (s, first) = fold(steps = 360) { i -> 3100 + (if (i % 2 == 0) 4 else -4) }
        assertFalse(s.fault)
        assertNull(first)
    }

    @Test fun slowDriftOfFiveMilliampsNeverFaultsOverSixHours() {
        // -5 mA = -5/60 mAh per minute
        val (s, first) = fold(steps = 360) { i -> 3000 - (i * 5) / 60 }
        assertFalse(s.fault)
        assertNull(first)
    }

    @Test fun faultStaysWhileStillFallingAtLowerDrain() {
        var (s, _) = fold(steps = 30) { i -> 3000 - 3 * i }
        assertTrue(s.fault)
        val start = 30 * min
        // Screen off: ~40 mA, i.e. ~0.67 mAh per minute, still falling.
        for (j in 1..120) {
            s = foldChargerFault(s, ChargeReading(start + j * min, true, 2910 - (j * 2) / 3))
            assertTrue("cleared at $j", s.fault)
        }
    }

    @Test fun riseOfSixClearsAndFourDoesNot() {
        val (faulted, _) = fold(steps = 30) { i -> 3000 - 3 * i }
        assertTrue(faulted.fault)
        val low = 3000 - 3 * 30
        val t = 31 * min
        val four = foldChargerFault(faulted, ChargeReading(t, true, low + 4))
        assertTrue(four.fault)
        val six = foldChargerFault(four, ChargeReading(t + min, true, low + 6))
        assertFalse(six.fault)
        assertNull(six.faultSinceElapsedMs)
        // Fresh buffer starting at this reading.
        assertEquals(listOf((t + min) to (low + 6)), six.readings)
    }

    @Test fun minTracksTheLowestSeenWhileFaulted() {
        val (faulted, _) = fold(steps = 30) { i -> 3000 - 3 * i }
        val low = 3000 - 3 * 30
        val s1 = foldChargerFault(faulted, ChargeReading(31 * min, true, low - 10))
        assertEquals(low - 10, s1.minMahSinceFault)
        // +5 above the new minimum is not enough.
        assertTrue(foldChargerFault(s1, ChargeReading(32 * min, true, low - 5)).fault)
    }

    @Test fun unplugClears() {
        val (faulted, _) = fold(steps = 30) { i -> 3000 - 3 * i }
        val s = foldChargerFault(faulted, ChargeReading(31 * min, false, 2900))
        assertEquals(ChargerFaultState(), s)
    }

    @Test fun nullCounterNeverFaults() {
        val (s, first) = fold(steps = 120) { null }
        assertFalse(s.fault)
        assertNull(first)
        assertEquals(ChargerFaultState(), s)
    }

    @Test fun nullCounterResetsABuildingWindow() {
        val (building, _) = fold(steps = 10) { i -> 3000 - 3 * i }
        val s = foldChargerFault(building, ChargeReading(11 * min, true, null))
        assertEquals(ChargerFaultState(), s)
    }

    @Test fun timeGoingBackwardsResets() {
        val (faulted, _) = fold(steps = 30) { i -> 3000 - 3 * i }
        val s = foldChargerFault(faulted, ChargeReading(5 * min, true, 2000))
        assertEquals(ChargerFaultState(), s)
        val (building, _) = fold(steps = 10) { i -> 3000 - 3 * i }
        assertEquals(ChargerFaultState(), foldChargerFault(building, ChargeReading(2 * min, true, 2000)))
    }

    @Test fun bufferIsBoundedInTimeAndCount() {
        // 1 reading per second for an hour, flat.
        val (s, _) = fold(steps = 3600, stepMs = 1_000L) { 3000 }
        assertTrue(s.readings.size <= 256)
        assertFalse(s.fault)
        // Time bound: at 1/min the buffer never holds more than ~21 entries.
        val (m, _) = fold(steps = 600) { 3000 }
        assertTrue(m.readings.size <= 21)
        assertTrue(m.readings.first().first >= m.readings.last().first - 20 * min)
    }

    @Test fun dropBelowThresholdInWindowDoesNotFault() {
        // 24 mAh over 15 min: just under.
        val (s, _) = fold(steps = 40) { i -> if (i < 10) 3000 else 2976 }
        assertFalse(s.fault)
    }

    @Test fun dropExactlyAtThresholdFaults() {
        val (s, first) = fold(steps = 40) { i -> if (i < 10) 3000 else 2975 }
        assertTrue(s.fault)
    }
}
