package dev.joely.bmsmon.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SampleDao {
    @Insert suspend fun insert(sample: SampleEntity): Long
    @Insert suspend fun insertAll(samples: List<SampleEntity>)

    @Query("SELECT * FROM samples WHERE sessionId = :sessionId ORDER BY tsMs ASC")
    suspend fun forSession(sessionId: Long): List<SampleEntity>

    @Query("SELECT * FROM samples WHERE address = :address AND linkEvent IS NULL ORDER BY tsMs ASC")
    suspend fun telemetryFor(address: String): List<SampleEntity>

    @Query("DELETE FROM samples WHERE tsMs < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long): Int

    @Query("SELECT COUNT(*) FROM samples") fun count(): Long

    @Query("DELETE FROM samples") suspend fun clear()
}

@Dao
interface SessionDao {
    @Insert suspend fun insert(session: SessionEntity): Long
    @Update suspend fun update(session: SessionEntity)

    @Query("SELECT * FROM sessions WHERE address = :address ORDER BY startMs ASC")
    fun forAddress(address: String): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions ORDER BY startMs ASC")
    fun all(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id") suspend fun byId(id: Long): SessionEntity?

    @Query("DELETE FROM sessions") suspend fun clear()
}

@Dao
interface RawFrameDao {
    @Insert suspend fun insert(frame: RawFrameEntity)

    @Query("DELETE FROM raw_frames WHERE tsMs < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long): Int

    @Query("SELECT COALESCE(SUM(LENGTH(hex)), 0) FROM raw_frames")
    suspend fun totalHexBytes(): Long

    @Query("DELETE FROM raw_frames WHERE id IN (SELECT id FROM raw_frames ORDER BY tsMs ASC LIMIT :n)")
    suspend fun deleteOldest(n: Int): Int

    @Query("DELETE FROM raw_frames") suspend fun clear()
}
