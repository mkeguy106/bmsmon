package dev.joely.bmsmon.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/** The sample-time span ([fromMs], [toMs]) of a set of outbox rows; both null when there are none. */
data class TsSpan(val fromMs: Long?, val toMs: Long?)

/** The enqueuedAt (= sample ts) span of the [n] oldest rows — what an eviction is about to drop (DATA-19). */
internal const val OUTBOX_OLDEST_SPAN_SQL =
    "SELECT MIN(enqueuedAt) AS fromMs, MAX(enqueuedAt) AS toMs FROM (SELECT enqueuedAt FROM outbox ORDER BY id ASC LIMIT :n)"

/** The [n] oldest rows (the cap's eviction); the returned count is the rows actually deleted. */
internal const val OUTBOX_DROP_OLDEST_SQL =
    "DELETE FROM outbox WHERE id IN (SELECT id FROM outbox ORDER BY id ASC LIMIT :n)"

/** Exactly one row; the returned count says whether it was still there (DATA-22 skip count). */
internal const val OUTBOX_DELETE_ONE_SQL = "DELETE FROM outbox WHERE id = :id"

@Dao
interface OutboxDao {
    @Insert suspend fun insert(rows: List<OutboxEntity>)
    @Query("SELECT * FROM outbox ORDER BY id ASC LIMIT :limit") suspend fun peek(limit: Int): List<OutboxEntity>
    @Query("DELETE FROM outbox WHERE id <= :id") suspend fun deleteUpTo(id: Long)
    @Query("SELECT COUNT(*) FROM outbox") suspend fun count(): Int
    @Query("SELECT MIN(enqueuedAt) FROM outbox") suspend fun oldestEnqueuedAt(): Long?
    @Query(OUTBOX_DROP_OLDEST_SQL) suspend fun dropOldest(n: Int): Int
    @Query(OUTBOX_OLDEST_SPAN_SQL) suspend fun oldestSpan(n: Int): TsSpan
    @Query(OUTBOX_DELETE_ONE_SQL) suspend fun deleteOne(id: Long): Int
}
