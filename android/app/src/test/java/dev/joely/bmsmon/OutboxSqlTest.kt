package dev.joely.bmsmon

import dev.joely.bmsmon.data.db.OUTBOX_DELETE_ONE_SQL
import dev.joely.bmsmon.data.db.OUTBOX_DROP_OLDEST_SQL
import dev.joely.bmsmon.data.db.OUTBOX_OLDEST_SPAN_SQL
import dev.joely.bmsmon.data.db.TsSpan
import org.junit.Assert.assertEquals
import org.junit.Test

class OutboxSqlTest {
    private fun SqlTestDb.fill(vararg enqueuedAt: Long): List<Long> = enqueuedAt.map { insertOutbox("{}", it) }

    private fun SqlTestDb.span(n: Int) =
        query(OUTBOX_OLDEST_SPAN_SQL, mapOf("n" to n)) { TsSpan(it.longOrNull("fromMs"), it.longOrNull("toMs")) }.single()

    // DATA-19: the cap's eviction is recorded as the exact span of sample times it drops, so the
    // re-sync can re-send it from local history.
    @Test fun theOldestSpanCoversExactlyTheRowsTheCapEvicts() {
        SqlTestDb().use { db ->
            db.fill(500, 100, 300, 900, 200)    // ids 1..5; the three oldest by id carry 500, 100, 300
            assertEquals(TsSpan(100L, 500L), db.span(3))
            assertEquals(3, db.update(OUTBOX_DROP_OLDEST_SQL, mapOf("n" to 3)))
            assertEquals(TsSpan(200L, 900L), db.span(10))
        }
    }

    @Test fun anEmptyOutboxHasNoSpan() {
        SqlTestDb().use { db -> assertEquals(TsSpan(null, null), db.span(3)) }
    }

    // DATA-22 skip-counter race: the cap can evict the head between the peek and the skip. The skip
    // then deletes — and counts — nothing, instead of counting a row it did not remove.
    @Test fun deletingOneRowReportsWhetherItWasStillThere() {
        SqlTestDb().use { db ->
            val (first) = db.fill(100, 200)
            assertEquals(1, db.update(OUTBOX_DELETE_ONE_SQL, mapOf("id" to first)))
            assertEquals(0, db.update(OUTBOX_DELETE_ONE_SQL, mapOf("id" to first)))
        }
    }
}
