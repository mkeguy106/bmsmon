package dev.joely.bmsmon

import dev.joely.bmsmon.data.forEachKeysetPage
import org.junit.Assert.assertEquals
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
}
