package dev.joely.bmsmon.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface OutboxDao {
    @Insert suspend fun insert(rows: List<OutboxEntity>)
    @Query("SELECT * FROM outbox ORDER BY id ASC LIMIT :limit") suspend fun peek(limit: Int): List<OutboxEntity>
    @Query("DELETE FROM outbox WHERE id <= :id") suspend fun deleteUpTo(id: Long)
    @Query("SELECT COUNT(*) FROM outbox") suspend fun count(): Int
    @Query("SELECT MIN(enqueuedAt) FROM outbox") suspend fun oldestEnqueuedAt(): Long?
    @Query("DELETE FROM outbox WHERE id IN (SELECT id FROM outbox ORDER BY id ASC LIMIT :n)") suspend fun dropOldest(n: Int)
}
