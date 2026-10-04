package dev.joely.bmsmon

import dev.joely.bmsmon.data.forEachKeysetPage
import dev.joely.bmsmon.data.forEachTsKeysetPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class KeysetPagerTest {

    private fun walk(ids: List<Long>, page: Int): Pair<List<Long>, List<Long>> {
        val calls = ArrayList<Long>()
        val seen = ArrayList<Long>()
        forEachKeysetPage(
            pageSize = page,
            fetch = { after, limit -> calls += after; ids.filter { it > after }.take(limit) },
            idOf = { it },
            consume = { seen += it },
        )
        return calls to seen
    }

    @Test fun pagesThroughEveryRowInOrder() {
        val ids = (1L..2_500L).toList()
        val (calls, seen) = walk(ids, 1_000)
        assertEquals(ids, seen)
        assertEquals(listOf(Long.MIN_VALUE, 1_000L, 2_000L), calls)
    }

    @Test fun anExactMultipleEndsWithOneEmptyFetch() {
        val (calls, seen) = walk((1L..2_000L).toList(), 1_000)
        assertEquals(2_000, seen.size)
        assertEquals(listOf(Long.MIN_VALUE, 1_000L, 2_000L), calls)
    }

    @Test fun anEmptyTableFetchesOnce() {
        val (calls, seen) = walk(emptyList(), 1_000)
        assertEquals(listOf(Long.MIN_VALUE), calls)
        assertEquals(emptyList<Long>(), seen)
    }

    @Test fun aNonAdvancingFetchFailsFastInsteadOfLoopingForever() {
        // A broken fetch that ignores the keyset: the first page advances MIN → 5, the second
        // returns 5 again and must trip the check instead of spinning forever.
        try {
            forEachKeysetPage(pageSize = 2, fetch = { _, _ -> listOf(5L, 5L) }, idOf = { it }, consume = {})
            fail("expected IllegalStateException")
        } catch (_: IllegalStateException) {
        }
    }

    // --- (ts, id) keyset (BLE-25): time order where id order isn't ---

    // Not named `R`: that would shadow the app's generated dev.joely.bmsmon.R inside this class.
    private data class TsRow(val ts: Long, val id: Long)

    private fun tsWalk(rows: List<TsRow>, startTs: Long, page: Int): Pair<List<Pair<Long, Long>>, List<TsRow>> {
        val sorted = rows.sortedWith(compareBy({ it.ts }, { it.id }))
        val calls = ArrayList<Pair<Long, Long>>()
        val seen = ArrayList<TsRow>()
        forEachTsKeysetPage(page, startTs, { afterTs, afterId, limit ->
            calls += afterTs to afterId
            sorted.filter { it.ts >= afterTs && (it.ts > afterTs || it.id > afterId) }.take(limit)
        }, TsRow::ts, TsRow::id) { seen += it }
        return calls to seen
    }

    @Test fun tsKeysetWalksTiesAndABackwardClockStepInTimeThenIdOrder() {
        // ids 1..40 in runs of three equal stamps; then ids 41..60 stepped back among them.
        val rows = (1L..40L).map { TsRow((it - 1) / 3, it) } + (41L..60L).map { TsRow(it - 39, it) }
        val expected = rows.sortedWith(compareBy({ it.ts }, { it.id }))
        for (page in listOf(1, 2, 3, 7, 100)) assertEquals("page=$page", expected, tsWalk(rows, 0L, page).second)
    }

    @Test fun tsKeysetIncludesRowsAtTheStartStamp() {
        val rows = listOf(TsRow(5, 1), TsRow(5, 2), TsRow(4, 3), TsRow(6, 4))
        assertEquals(listOf(TsRow(5, 1), TsRow(5, 2), TsRow(6, 4)), tsWalk(rows, 5L, 2).second)
    }

    @Test fun tsKeysetOnAnEmptyWindowFetchesOnce() {
        val (calls, seen) = tsWalk(emptyList(), 0L, 10)
        assertEquals(listOf(0L to Long.MIN_VALUE), calls)
        assertTrue(seen.isEmpty())
    }

    @Test fun aNonAdvancingTsFetchFailsFast() {
        try {
            forEachTsKeysetPage(2, 0L, { _, _, _ -> listOf(TsRow(0, 0), TsRow(0, 0)) }, TsRow::ts, TsRow::id) {}
            fail("expected IllegalStateException")
        } catch (_: IllegalStateException) {
        }
    }
}
