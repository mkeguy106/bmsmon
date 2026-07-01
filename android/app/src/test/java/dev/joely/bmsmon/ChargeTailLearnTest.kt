package dev.joely.bmsmon

import dev.joely.bmsmon.model.ChargeSample
import dev.joely.bmsmon.model.foldTailEma
import dev.joely.bmsmon.model.observedChargeTailMinutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChargeTailLearnTest {
    private fun climb(startSoc: Int, endSoc: Int, stepMs: Long = 60_000L, t0: Long = 0L) =
        (startSoc..endSoc).mapIndexed { i, s -> ChargeSample(t0 + i * stepMs, s.toFloat(), true) }

    @Test fun measuresMinutesFrom98To100() {
        // 96..100 at 60s steps: 98 at index 2, 100 at index 4 -> 2 minutes.
        assertEquals(2.0f, observedChargeTailMinutes(climb(96, 100))!!, 0.001f)
    }

    @Test fun nullWhenNeverReaches100() {
        assertNull(observedChargeTailMinutes(climb(96, 99)))
    }

    @Test fun nullWhenStartsAlreadyInTail() {
        // No sample below 98% -> not a clean climb-through.
        assertNull(observedChargeTailMinutes(climb(99, 100)))
    }

    @Test fun nullWhenAGapBreaksTheRun() {
        val run1 = climb(96, 98, t0 = 0L)                       // below + 98, no 100
        val run2 = climb(100, 100, t0 = 10 * 60_000L)           // 100 after a 10-min gap
        assertNull(observedChargeTailMinutes(run1 + run2))
    }

    @Test fun nonChargingSampleBreaksTheRun() {
        val a = climb(96, 98)
        val gap = listOf(ChargeSample(a.last().tsMs + 60_000L, 98f, false))
        val b = listOf(ChargeSample(a.last().tsMs + 120_000L, 100f, true))
        assertNull(observedChargeTailMinutes(a + gap + b))
    }

    @Test fun emaFoldsTowardObservation() {
        assertEquals(51.0f, foldTailEma(prev = 45f, observed = 65f), 0.001f)
    }
}
