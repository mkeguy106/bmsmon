package dev.joely.bmsmon

import dev.joely.bmsmon.cloud.ResyncWindow
import dev.joely.bmsmon.cloud.importWindow
import dev.joely.bmsmon.data.db.RESYNC_PAGE_SQL
import dev.joely.bmsmon.data.db.SampleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** DATA-19: the re-sender's page query on a JVM SQLite built from the checked-in Room schema. */
class ResyncSqlTest {
    private fun sample(address: String, tsMs: Long) = SampleEntity(
        address = address, tsMs = tsMs, sessionId = 1, state = "Idle", soc = 50f, currentA = 0f, powerW = 0f,
        voltageV = 13f, tempC = 20f, mosfetTempC = 20, soh = 100, fullChargeAh = 100f, remainingAh = 50f,
        cycles = 1, cellMinV = 3.3f, cellMaxV = 3.3f, regen = false, linkEvent = null,
    )

    private fun page(db: SqlTestDb, toMs: Long, afterTs: Long, afterId: Long, limit: Int) =
        db.query(RESYNC_PAGE_SQL, mapOf("toMs" to toMs, "afterTs" to afterTs, "afterId" to afterId, "limit" to limit)) {
            it.getLong("id") to it.getLong("tsMs")
        }

    /** Walk a window to its end the way the re-sender does, one page per step; returns the ids sent. */
    private fun walk(db: SqlTestDb, w: ResyncWindow, limit: Int): List<Long> {
        var ts = w.cursorTs
        var id = w.afterId
        val out = mutableListOf<Long>()
        while (true) {
            val p = page(db, w.toMs, ts, id, limit)
            out += p.map { it.first }
            if (p.size < limit) return out
            ts = p.last().second
            id = p.last().first
        }
    }

    @Test fun aWindowWalksEveryRowInsideItExactlyOnceInTimeOrder() {
        SqlTestDb().use { db ->
            // Inserted out of time order (a CSV backfill lands late with old timestamps), several
            // packs sharing one timestamp, and rows on both sides of the window.
            val rows = listOf(500L, 100L, 300L, 300L, 300L, 900L, 50L, 1_200L)
                .mapIndexed { i, ts -> db.insert(sample("P$i", ts)) to ts }
            val inside = rows.filter { it.second in 100L..900L }
                .sortedWith(compareBy({ it.second }, { it.first })).map { it.first }
            for (limit in listOf(1, 2, 3, 500)) {
                assertEquals("limit=$limit", inside, walk(db, ResyncWindow(100, 900), limit))
            }
        }
    }

    // A phone upgraded mid-import re-sends from the very start: nothing older is lost.
    @Test fun theImportWindowCoversHistoryFromBeforeEnrollment() {
        SqlTestDb().use { db ->
            val old = db.insert(sample("A", 1L))
            assertEquals(listOf(old), walk(db, importWindow(nowMs = 10_000L), 500))
        }
    }

    @Test fun aResumedCursorSkipsExactlyWhatWasSent() {
        SqlTestDb().use { db ->
            val a = db.insert(sample("A", 100))
            val b = db.insert(sample("B", 100))
            val c = db.insert(sample("C", 200))
            assertEquals(listOf(b, c), walk(db, ResyncWindow(100, 900, afterTs = 100, afterId = a), 500))
        }
    }

    @Test fun linkEventRowsAreReSentToo() {
        SqlTestDb().use { db ->
            val link = db.insert(sample("A", 150).copy(state = null, soc = null, linkEvent = "Disconnected"))
            assertEquals(listOf(link), walk(db, ResyncWindow(100, 900), 500))
        }
    }

    // One bounded page per step: a range seek on the time index, never a full scan or a sort.
    @Test fun thePageQuerySeeksTheTimeIndexWithoutSorting() {
        SqlTestDb().use { db ->
            repeat(50) { db.insert(sample("P${it % 8}", 1_000L + it / 8)) }
            val plan = db.plan(RESYNC_PAGE_SQL, mapOf("toMs" to 900L, "afterTs" to 100L, "afterId" to -1L, "limit" to 500))
            assertTrue(plan, plan.contains("SEARCH samples USING INDEX index_samples_tsMs"))
            assertFalse(plan, plan.contains("SCAN"))
            assertFalse(plan, plan.contains("TEMP B-TREE"))
        }
    }
}
