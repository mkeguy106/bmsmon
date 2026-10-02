package dev.joely.bmsmon

import dev.joely.bmsmon.data.OrphanedSessionAction
import dev.joely.bmsmon.data.RollupAccumulator
import dev.joely.bmsmon.data.db.ROLLUP_PAGE_SQL
import dev.joely.bmsmon.data.db.RollupRow
import dev.joely.bmsmon.data.orphanedSessionAction
import dev.joely.bmsmon.data.streamRollup
import java.sql.ResultSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** DATA-16: the real page SQL + pager + accumulator, end to end on a JVM SQLite. */
class RollupSqlTest {

    private fun rollupRow(rs: ResultSet) = RollupRow(
        id = rs.getLong("id"), tsMs = rs.getLong("tsMs"), soc = rs.floatOrNull("soc"),
        currentA = rs.floatOrNull("currentA"), powerW = rs.floatOrNull("powerW"),
        voltageV = rs.floatOrNull("voltageV"), tempC = rs.floatOrNull("tempC"), soh = rs.intOrNull("soh"),
        fullChargeAh = rs.floatOrNull("fullChargeAh"), cycles = rs.intOrNull("cycles"),
        regen = rs.getLong("regen") != 0L,
    )

    private fun stream(db: SqlTestDb, address: String, sessionId: Long, page: Int): RollupAccumulator =
        streamRollup(address, sessionId, page) { after, limit ->
            db.query(ROLLUP_PAGE_SQL, mapOf("sessionId" to sessionId, "afterId" to after, "limit" to limit), ::rollupRow)
        }

    @Test fun streamedRollupOfAnInterleavedSessionEqualsTheReference() {
        SqlTestDb().use { db ->
            // Two packs' sessions interleave in one autoincrement sequence, as in the live table.
            val a = randomSession(seed = 5, n = 3_000, address = "A", sessionId = 9)
            val b = randomSession(seed = 6, n = 1_500, address = "B", sessionId = 10)
            (a + b).sortedBy { it.tsMs }.forEach { db.insert(it) }   // stable: A keeps its order
            for (page in listOf(1, 7, 1_000, 5_000)) {
                assertEquals("page=$page", referenceRollup("A", 9, a), stream(db, "A", 9, page).toRollup())
            }
        }
    }

    // Review Focus 4: a stub that only ever got link events folds nothing → the sweep deletes it.
    @Test fun linkOnlyStubStreamsNothingSoTheSweepDeletesIt() {
        SqlTestDb().use { db ->
            db.insert(linkSample("A", ts = 1_000, sessionId = 3, event = "Connected"))
            db.insert(linkSample("A", ts = 2_000, sessionId = 3, event = "Disconnected"))
            assertEquals(OrphanedSessionAction.Delete, orphanedSessionAction(stream(db, "A", 3, 1_000)))
        }
    }

    @Test fun rollupPageIsAnIndexRangeSeekWithNoSort() {
        SqlTestDb().use { db ->
            val p = db.plan(ROLLUP_PAGE_SQL, mapOf("sessionId" to 9L, "afterId" to 0L, "limit" to 1_000))
            assertTrue(p, p.contains("index_samples_sessionId"))
            assertFalse(p, p.contains("TEMP B-TREE"))
        }
    }
}
