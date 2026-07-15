package dev.joely.bmsmon

import dev.joely.bmsmon.model.ChargeSample
import dev.joely.bmsmon.model.chargeSample
import dev.joely.bmsmon.model.foldTailEma
import dev.joely.bmsmon.model.learnTailFold
import dev.joely.bmsmon.model.observedChargeTail
import dev.joely.bmsmon.model.observedChargeTailMinutes
import dev.joely.bmsmon.model.shouldFoldTail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargeTailLearnTest {
    private fun climb(startSoc: Int, endSoc: Int, stepMs: Long = 60_000L, t0: Long = 0L) =
        (startSoc..endSoc).mapIndexed { i, s -> ChargeSample(t0 + i * stepMs, s.toFloat(), true) }

    @Test fun measuresMinutesFrom98To100() {
        // 96..100 at 60s steps: 98 at index 2, 100 at index 4 -> 2 minutes.
        assertEquals(2.0f, observedChargeTailMinutes(climb(96, 100))!!, 0.001f)
    }

    @Test fun cutoffAt99CompletesTheTail() {
        // This BMS never reports 100 while Charging — it caps at 99 and flips to Idle at
        // charger cutoff (SOC 100 appears right after). A run that climbed through 98 and
        // ENDED at >=99 is a completed tail: 98 at index 2, run end (99) at index 3 -> 1 min.
        assertEquals(1.0f, observedChargeTailMinutes(climb(96, 99))!!, 0.001f)
    }

    @Test fun cutoffAt99TerminatedByIdleRowStillCompletes() {
        val a = climb(96, 99)
        val idle = listOf(ChargeSample(a.last().tsMs + 60_000L, 100f, false))
        assertEquals(1.0f, observedChargeTailMinutes(a + idle)!!, 0.001f)
    }

    @Test fun nullWhenRunEndsBelow99() {
        // Unplugged mid-tail at 98 — not a completed tail.
        assertNull(observedChargeTailMinutes(climb(96, 98)))
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

    // --- UI-10: null-SOC rows are dropped, never treated as "below 98%" evidence ---

    @Test fun chargeSampleDropsNullSoc() {
        assertNull(chargeSample(1L, null, charging = true))
        assertEquals(ChargeSample(1L, 99f, true), chargeSample(1L, 99f, charging = true))
    }

    @Test fun nullSocRowsDoNotFabricateClimbThroughEvidence() {
        // A run that starts already in the tail (99→100) with a null-SOC charging row in front:
        // the old `soc ?: -1f` mapping turned that row into "below 98%" and produced a bogus
        // (and tiny) tail observation. Dropping the row keeps the run disqualified.
        val rows = listOf(
            Triple(0L, null as Float?, true),          // null-SOC charging row (BMS hiccup)
            Triple(60_000L, 99f, true),
            Triple(120_000L, 100f, true),
        )
        val mapped = rows.mapNotNull { (ts, soc, chg) -> chargeSample(ts, soc, chg) }
        assertNull(observedChargeTailMinutes(mapped))
        // The old mapping (null → -1f) fabricated exactly that evidence — pin the contrast:
        val old = rows.map { (ts, soc, chg) -> ChargeSample(ts, soc ?: -1f, chg) }
        assertEquals(1.0f, observedChargeTailMinutes(old)!!, 0.001f)
    }

    // --- run-identity dedup (the 2026-07-15 prod double-fold bug) ---

    @Test fun shouldFoldTailAcceptsWhenNeverLearned() {
        // Migration/compat: pre-upgrade installs have a learned tailMin but no run-end entry —
        // a null persisted end accepts any qualifying run.
        assertTrue(shouldFoldTail(runEndMs = 1_000L, lastLearnedRunEndMs = null))
    }

    @Test fun shouldFoldTailAcceptsStrictlyNewerRun() {
        assertTrue(shouldFoldTail(runEndMs = 2_000L, lastLearnedRunEndMs = 1_000L))
    }

    @Test fun shouldFoldTailSkipsSameRun() {
        assertFalse(shouldFoldTail(runEndMs = 1_000L, lastLearnedRunEndMs = 1_000L))
    }

    @Test fun shouldFoldTailSkipsOlderRun() {
        assertFalse(shouldFoldTail(runEndMs = 500L, lastLearnedRunEndMs = 1_000L))
    }

    @Test fun observedChargeTailCarriesTheRunEndTs() {
        // Cutoff branch (run ends >=99, no 100): identity = the run's last charging sample.
        val cutoff = climb(96, 99)
        val obs = observedChargeTail(cutoff)!!
        assertEquals(1.0f, obs.minutes, 0.001f)
        assertEquals(cutoff.last().tsMs, obs.runEndMs)
        // Reported-100 branch: identity is still the run's last charging sample.
        val full = climb(96, 100)
        assertEquals(full.last().tsMs, observedChargeTail(full)!!.runEndMs)
    }

    @Test fun blipRescanOfSameRunFoldsExactlyOnce() {
        // Prod repro (pack 2012-B, 2026-07-15 check-in): the real charger cutoff at 05:19 folded
        // the overnight tail once. A single-sample Charging(99)→Discharging regen blip at 10:05
        // passed the 30-min wall-clock dedup, re-scanned the 6-h window, re-found the SAME run,
        // and folded the SAME tail AGAIN (persisted 52.554 = exactly two folds; one = 54.796).
        val cutoff = climb(96, 99)
        val first = learnTailFold(cutoff, prevTailMin = 58f, lastLearnedRunEndMs = null)!!
        assertEquals(foldTailEma(58f, 1f), first.tailMin, 0.001f)
        assertEquals(cutoff.last().tsMs, first.runEndMs)
        // 5 h later the blip's re-scan sees the same run plus the blip itself (a single 99%
        // charging sample — no climb-through, not a qualifying run). Same run end → skipped.
        val blipAt = cutoff.last().tsMs + 5 * 60 * 60_000L
        val rescan = cutoff +
            ChargeSample(blipAt, 99f, true) +
            ChargeSample(blipAt + 1_500L, 99f, false)
        assertNull(learnTailFold(rescan, prevTailMin = first.tailMin, lastLearnedRunEndMs = first.runEndMs))
    }

    @Test fun engineRestartDoesNotRefoldPersistedRun() {
        // Fresh process: the in-memory 30-min wall-clock map is gone, but the persisted run end
        // survives — the same run re-found after a restart must not fold again.
        val run = climb(96, 99)
        assertNull(learnTailFold(run, prevTailMin = 54.8f, lastLearnedRunEndMs = run.last().tsMs))
    }

    @Test fun genuinelyNewLaterRunFolds() {
        val run1 = climb(96, 99, t0 = 0L)
        val first = learnTailFold(run1, prevTailMin = 58f, lastLearnedRunEndMs = null)!!
        // A real second charge hours later: qualifies, ends strictly newer → folds.
        val run2 = climb(95, 99, t0 = 8 * 60 * 60_000L)
        val second = learnTailFold(run1 + run2, first.tailMin, first.runEndMs)!!
        assertEquals(run2.last().tsMs, second.runEndMs)
        assertEquals(foldTailEma(first.tailMin, 1f), second.tailMin, 0.001f)
    }
}
