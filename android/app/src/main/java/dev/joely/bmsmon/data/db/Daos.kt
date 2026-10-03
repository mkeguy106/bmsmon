package dev.joely.bmsmon.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Lean projection for the range learner — avoids materializing full 22-column rows.
 *
 *  Carries [currentA], NOT the BMS `state` field: on this hardware state lags current at the
 *  boundaries of a discharge run, so gating on it loses real energy (see RangeLearn.accumulate). */
data class RangeRowColumns(
    val tsMs: Long,
    val currentA: Float?,
    val powerW: Float?,
    val lat: Double?,
    val lon: Double?,
    val gpsAccuracyM: Float?,
    val regen: Boolean,
)

/** Lean projection the session rollup streams over (DATA-16) — telemetry rows only (the page query
 *  excludes link events). 11 columns instead of 22, and only one page is ever held. */
data class RollupRow(
    val id: Long,
    val tsMs: Long,
    val soc: Float?,
    val currentA: Float?,
    val powerW: Float?,
    val voltageV: Float?,
    val tempC: Float?,
    val soh: Int?,
    val fullChargeAh: Float?,
    val cycles: Int?,
    val regen: Boolean,
)

/** Raw V-on-I regression moments for one pack's rows in one integer-SOC bin (DATA-15). [bin] null
 *  groups rows with no SOC — kept so the scatter's global fit (all bins pooled) covers exactly the
 *  rows the old list analysis used. Column names match IV_MOMENTS_BY_SOC_BIN_SQL (Task 8). */
data class IvBinMoments(
    val bin: Int?,
    val n: Long,
    val sumI: Double,
    val sumV: Double,
    val sumII: Double,
    val sumIV: Double,
    val sumVV: Double,
    val minI: Double,
    val maxI: Double,
    val minV: Double,
    val maxV: Double,
)

/** Per-session cell-imbalance aggregate (mV = (cellMax − cellMin)·1000) for one pack (DATA-15). */
data class CellSessionStats(val sessionId: Long, val n: Long, val sumMv: Double, val maxMv: Double)

/** One keyset page of a session's telemetry rows, in id order (DATA-16). index_samples_sessionId is
 *  (sessionId, rowid), so this is a pure index range seek with no sort (RollupSqlTest pins it). */
internal const val ROLLUP_PAGE_SQL =
    "SELECT id, tsMs, soc, currentA, powerW, voltageV, tempC, soh, fullChargeAh, cycles, regen " +
        "FROM samples WHERE sessionId = :sessionId AND id > :afterId AND linkEvent IS NULL " +
        "ORDER BY id ASC LIMIT :limit"

@Dao
interface SampleDao {
    @Insert suspend fun insert(sample: SampleEntity): Long
    @Insert suspend fun insertAll(samples: List<SampleEntity>)

    @Query("SELECT * FROM samples WHERE sessionId = :sessionId ORDER BY tsMs ASC")
    suspend fun forSession(sessionId: Long): List<SampleEntity>

    /** Blocking — the rollup pager calls it in a loop on the IO writer; never call on Main. */
    @Query(ROLLUP_PAGE_SQL)
    fun rollupPage(sessionId: Long, afterId: Long, limit: Int): List<RollupRow>

    @Query("SELECT * FROM samples WHERE address = :address AND linkEvent IS NULL ORDER BY tsMs ASC")
    suspend fun telemetryFor(address: String): List<SampleEntity>

    @Query("SELECT * FROM samples WHERE address = :address AND tsMs >= :sinceMs AND linkEvent IS NULL ORDER BY tsMs ASC")
    suspend fun since(address: String, sinceMs: Long): List<SampleEntity>

    @Query(
        "SELECT tsMs, currentA, powerW, lat, lon, gpsAccuracyM, regen FROM samples " +
            "WHERE address = :address AND tsMs >= :sinceMs AND linkEvent IS NULL ORDER BY tsMs ASC"
    )
    suspend fun rangeRowsSince(address: String, sinceMs: Long): List<RangeRowColumns>

    @Query("DELETE FROM samples WHERE tsMs < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long): Int

    @Query("SELECT COUNT(*) FROM samples") suspend fun count(): Long

    @Query("DELETE FROM samples") suspend fun clear()

    @Query("SELECT * FROM samples WHERE id > :afterId ORDER BY id ASC LIMIT :limit")
    suspend fun pageAfter(afterId: Long, limit: Int): List<SampleEntity>
}

@Dao
interface SessionDao {
    @Insert suspend fun insert(session: SessionEntity): Long
    @Update suspend fun update(session: SessionEntity)

    @Query("SELECT * FROM sessions WHERE address = :address AND sampleCount > 0 ORDER BY startMs ASC")
    fun forAddress(address: String): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE sampleCount > 0 ORDER BY startMs ASC")
    fun all(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id") suspend fun byId(id: Long): SessionEntity?

    /** Never-finalized stubs (inserted as emptySession, process died before rollup). */
    @Query("SELECT * FROM sessions WHERE sampleCount = 0")
    suspend fun zeroCountStubs(): List<SessionEntity>

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteById(id: Long)

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
