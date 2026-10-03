package dev.joely.bmsmon

import dev.joely.bmsmon.data.OrphanedSessionAction
import dev.joely.bmsmon.data.RollupAccumulator
import dev.joely.bmsmon.data.computeRollup
import dev.joely.bmsmon.data.db.RollupRow
import dev.joely.bmsmon.data.db.SampleEntity
import dev.joely.bmsmon.data.orphanedSessionAction
import dev.joely.bmsmon.data.streamRollup
import dev.joely.bmsmon.data.toRollupRow
import org.junit.Assert.assertEquals
import org.junit.Test

/** DATA-16: the streaming fold must equal the old whole-list rollup EXACTLY (data-class equals). */
class RollupEquivalenceTest {

    @Test fun accumulatorMatchesReferenceBitForBitOnRandomSessions() {
        for (seed in 1L..40L) {
            val s = randomSession(seed, n = 50 + (seed * 37).toInt())
            assertEquals("seed $seed", referenceRollup("A", 9, s), computeRollup("A", 9, s))
        }
    }

    @Test fun pagedFeedMatchesWholeListFeed() {
        val s = randomSession(seed = 99, n = 2_345).mapIndexed { i, e -> e.copy(id = i + 1L) }
        val tel = s.filter { it.linkEvent == null }
        for (page in listOf(1, 7, 100, 10_000)) {
            val acc = streamRollup("A", 9, page) { after, limit ->
                tel.filter { it.id > after }.take(limit).map { it.toRollupRow() }
            }
            assertEquals("page $page", referenceRollup("A", 9, s), acc.toRollup())
        }
    }

    @Test fun emptySessionMatchesReference() {
        assertEquals(referenceRollup("A", 9, emptyList()), computeRollup("A", 9, emptyList()))
    }

    @Test fun linkOnlySessionMatchesReferenceAndIsDeletedBySweep() {
        val s = listOf(linkSample("A", 1_000, 9, "Connected"), linkSample("A", 2_000, 9, "Disconnected"))
        assertEquals(referenceRollup("A", 9, s), computeRollup("A", 9, s))
        assertEquals(OrphanedSessionAction.Delete, orphanedSessionAction("A", 9, s))
    }

    @Test fun allNullTelemetryColumnsMatchReference() {
        val s = (0 until 10).map { k ->
            SampleEntity(
                address = "A", tsMs = 1_000L * k, sessionId = 9, state = null, soc = null,
                currentA = null, powerW = null, voltageV = null, tempC = null, mosfetTempC = null,
                soh = null, fullChargeAh = null, remainingAh = null, cycles = null,
                cellMinV = null, cellMaxV = null, regen = k % 2 == 0, linkEvent = null,
            )
        }
        assertEquals(referenceRollup("A", 9, s), computeRollup("A", 9, s))
    }

    @Test fun nanPowersSortAndAverageLikeTheReference() {
        val s = randomSession(seed = 7, n = 200).map {
            if (it.linkEvent == null && (it.currentA ?: 0f) < -1f && it.tsMs % 3L == 0L) it.copy(powerW = Float.NaN) else it
        }
        assertEquals(referenceRollup("A", 9, s), computeRollup("A", 9, s))
    }

    // Production feeds id order; if the wall clock stepped back mid-session, id order is not time
    // order. The span must still be min/max ts, and the negative interval must add no energy (the
    // reference's coerceAtLeast(0), applied pairwise in feed order).
    @Test fun clockStepBackUsesMinMaxSpanAndClampsNegativeInterval() {
        fun row(id: Long, ts: Long) = RollupRow(
            id = id, tsMs = ts, soc = 80f, currentA = -10f, powerW = 130f, voltageV = 13f,
            tempC = 25f, soh = 100, fullChargeAh = 105f, cycles = 40, regen = false,
        )
        val acc = RollupAccumulator("A", 9)
        listOf(row(1, 10_000), row(2, 12_000), row(3, 9_000), row(4, 11_000)).forEach(acc::add)
        val r = acc.toRollup()
        assertEquals(9_000L, r.startMs)
        assertEquals(12_000L, r.endMs)
        assertEquals(4, r.sampleCount)
        // 1→2: +2 s, 2→3: −3 s → 0, 3→4: +2 s.
        assertEquals(130f * 2_000L / 3_600_000f + 130f * 2_000L / 3_600_000f, r.energyWh, 1e-6f)
    }
}
