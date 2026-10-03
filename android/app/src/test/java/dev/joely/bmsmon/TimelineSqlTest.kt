package dev.joely.bmsmon

import dev.joely.bmsmon.data.db.SESSION_SPAN_SQL
import dev.joely.bmsmon.data.db.SessionSpan
import dev.joely.bmsmon.data.db.TIMELINE_PAGE_SQL
import dev.joely.bmsmon.data.db.TimelineRow
import dev.joely.bmsmon.data.poolTimeline
import java.sql.ResultSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineSqlTest {

    private fun span(rs: ResultSet) = SessionSpan(rs.getLong("n"), rs.longOrNull("startMs"), rs.longOrNull("endMs"))

    private fun row(rs: ResultSet) = TimelineRow(
        rs.getLong("id"), rs.getLong("tsMs"), rs.getLong("isLink") != 0L,
        rs.floatOrNull("currentA"), rs.floatOrNull("powerW"), rs.floatOrNull("voltageV"), rs.floatOrNull("soc"),
    )

    private fun timeline(db: SqlTestDb, sessionId: Long, page: Int) =
        poolTimeline(db.query(SESSION_SPAN_SQL, mapOf("sessionId" to sessionId), ::span).single(), page) { after, limit ->
            db.query(TIMELINE_PAGE_SQL, mapOf("sessionId" to sessionId, "afterId" to after, "limit" to limit), ::row)
        }

    @Test fun streamedTimelineOfAnInterleavedSessionEqualsTheReference() {
        SqlTestDb().use { db ->
            val a = randomSession(seed = 21, n = 4_000, address = "A", sessionId = 9)
            val b = randomSession(seed = 22, n = 2_000, address = "B", sessionId = 10)
            (a + b).sortedBy { it.tsMs }.forEach { db.insert(it) }
            for (page in listOf(1, 13, 2_000, 10_000)) {
                assertEquals("page=$page", referencePeakPool(a), timeline(db, 9, page))
            }
        }
    }

    @Test fun anEmptySessionPoolsToNothing() {
        SqlTestDb().use { db -> assertEquals(emptyList<Any>(), timeline(db, 42, 2_000)) }
    }

    @Test fun timelineQueriesSeekTheSessionIndexWithoutSorting() {
        SqlTestDb().use { db ->
            val p = db.plan(TIMELINE_PAGE_SQL, mapOf("sessionId" to 9L, "afterId" to 0L, "limit" to 2_000))
            assertTrue(p, p.contains("index_samples_sessionId"))
            assertFalse(p, p.contains("TEMP B-TREE"))
            assertTrue(db.plan(SESSION_SPAN_SQL, mapOf("sessionId" to 9L)).contains("index_samples_sessionId"))
        }
    }
}
