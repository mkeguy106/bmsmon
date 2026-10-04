package dev.joely.bmsmon.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Lean projection for the range learner — avoids materializing full 22-column rows. [id] is the
 *  keyset tie-break for [RANGE_PAGE_SQL].
 *
 *  Carries [currentA], NOT the BMS `state` field: on this hardware state lags current at the
 *  boundaries of a discharge run, so gating on it loses real energy (see RangeLearn.kt). */
data class RangeRowColumns(
    val id: Long,
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
 *  rows the old list analysis used. Fields match the column aliases in IV_MOMENTS_BY_SOC_BIN_SQL. */
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

/** One row of the V–I stride walk (currentA/voltageV non-null by the query's WHERE). */
data class IvPoint(val id: Long, val tsMs: Long, val currentA: Float, val voltageV: Float)

/** Raw V/I moments per integer-SOC bin for one pack (DATA-15) — the regression without the rows.
 *  CAST truncates like Kotlin's Float.toInt(); a NULL soc forms its own (bin = NULL) group. */
internal const val IV_MOMENTS_BY_SOC_BIN_SQL =
    "SELECT CAST(soc AS INTEGER) AS bin, COUNT(*) AS n, " +
        "SUM(currentA) AS sumI, SUM(voltageV) AS sumV, SUM(currentA * currentA) AS sumII, " +
        "SUM(currentA * voltageV) AS sumIV, SUM(voltageV * voltageV) AS sumVV, " +
        "MIN(currentA) AS minI, MAX(currentA) AS maxI, MIN(voltageV) AS minV, MAX(voltageV) AS maxV " +
        "FROM samples WHERE address = :address AND linkEvent IS NULL " +
        "AND currentA IS NOT NULL AND voltageV IS NOT NULL " +
        "GROUP BY bin"

/** Per-session cell-Δ sums for one pack (DATA-15). */
internal const val CELL_STATS_BY_SESSION_SQL =
    "SELECT sessionId, COUNT(*) AS n, " +
        "SUM((cellMaxV - cellMinV) * 1000.0) AS sumMv, MAX((cellMaxV - cellMinV) * 1000.0) AS maxMv " +
        "FROM samples WHERE address = :address AND linkEvent IS NULL " +
        "AND cellMinV IS NOT NULL AND cellMaxV IS NOT NULL AND cellMaxV >= cellMinV " +
        "GROUP BY sessionId"

/** The I/V row [skip] positions after the keyset (afterTs, afterId) in (tsMs, id) order — one
 *  stride step (DATA-15). index_samples_address_tsMs is (address, tsMs, rowid), so SQLite seeks
 *  into the index and walks it in order with no sort (HealthSqlTest pins the plan); skipped rows
 *  are filter-checked but never returned. `id % k` sampling was rejected: packs' rows interleave
 *  in one id sequence, so it can alias to zero rows for a pack. */
internal const val IV_ROW_AFTER_SQL =
    "SELECT id, tsMs, currentA, voltageV FROM samples " +
        "WHERE address = :address AND tsMs >= :afterTs AND (tsMs > :afterTs OR id > :afterId) " +
        "AND linkEvent IS NULL AND currentA IS NOT NULL AND voltageV IS NOT NULL " +
        "ORDER BY tsMs ASC, id ASC LIMIT 1 OFFSET :skip"

/** Row count and time span of one session (incl. link rows) — fixes the timeline bucket geometry. */
data class SessionSpan(val n: Long, val startMs: Long?, val endMs: Long?)

/** Lean timeline projection (DATA-15): link flag + the four pooled columns. */
data class TimelineRow(
    val id: Long,
    val tsMs: Long,
    val isLink: Boolean,
    val currentA: Float?,
    val powerW: Float?,
    val voltageV: Float?,
    val soc: Float?,
)

internal const val SESSION_SPAN_SQL =
    "SELECT COUNT(*) AS n, MIN(tsMs) AS startMs, MAX(tsMs) AS endMs FROM samples WHERE sessionId = :sessionId"

/** One keyset page of a session's rows, link events included, in id order (index seek, no sort). */
internal const val TIMELINE_PAGE_SQL =
    "SELECT id, tsMs, linkEvent IS NOT NULL AS isLink, currentA, powerW, voltageV, soc " +
        "FROM samples WHERE sessionId = :sessionId AND id > :afterId ORDER BY id ASC LIMIT :limit"

/** One keyset page of a session's telemetry rows, in id order (DATA-16). index_samples_sessionId is
 *  (sessionId, rowid), so this is a pure index range seek with no sort (RollupSqlTest pins it). */
internal const val ROLLUP_PAGE_SQL =
    "SELECT id, tsMs, soc, currentA, powerW, voltageV, tempC, soh, fullChargeAh, cycles, regen " +
        "FROM samples WHERE sessionId = :sessionId AND id > :afterId AND linkEvent IS NULL " +
        "ORDER BY id ASC LIMIT :limit"

/** One keyset page of a pack's telemetry rows for the range learner (BLE-25), in (tsMs, id) order —
 *  the order the old whole-window list was read in. index_samples_address_tsMs is (address, tsMs,
 *  rowid), so this is an index range seek walked in order with no sort (RangeSqlTest pins the plan).
 *  The walk starts at (sinceMs, Long.MIN_VALUE): see forEachTsKeysetPage. */
internal const val RANGE_PAGE_SQL =
    "SELECT id, tsMs, currentA, powerW, lat, lon, gpsAccuracyM, regen FROM samples " +
        "WHERE address = :address AND tsMs >= :afterTs AND (tsMs > :afterTs OR id > :afterId) " +
        "AND linkEvent IS NULL ORDER BY tsMs ASC, id ASC LIMIT :limit"

/** One keyset page of local samples inside a re-sync window, oldest first (DATA-19). (tsMs, id) is
 *  index_samples_tsMs order (the index carries the rowid), so this is a range seek with no sort —
 *  ResyncSqlTest pins the plan. One page per POST, the cursor persisted in the window between them.
 *  Link-event rows are included: the cloud keeps them too. */
internal const val RESYNC_PAGE_SQL =
    "SELECT * FROM samples WHERE tsMs <= :toMs AND tsMs >= :afterTs AND (tsMs > :afterTs OR id > :afterId) " +
        "ORDER BY tsMs ASC, id ASC LIMIT :limit"

@Dao
interface SampleDao {
    @Insert suspend fun insert(sample: SampleEntity): Long
    @Insert suspend fun insertAll(samples: List<SampleEntity>)

    @Query(SESSION_SPAN_SQL)
    suspend fun sessionSpan(sessionId: Long): SessionSpan

    /** Blocking — the timeline pager calls it in a loop on IO. Never call on Main. */
    @Query(TIMELINE_PAGE_SQL)
    fun timelinePage(sessionId: Long, afterId: Long, limit: Int): List<TimelineRow>

    /** Blocking — the rollup pager calls it in a loop on the IO writer; never call on Main. */
    @Query(ROLLUP_PAGE_SQL)
    fun rollupPage(sessionId: Long, afterId: Long, limit: Int): List<RollupRow>

    @Query(IV_MOMENTS_BY_SOC_BIN_SQL)
    suspend fun ivMomentsBySocBin(address: String): List<IvBinMoments>

    @Query(CELL_STATS_BY_SESSION_SQL)
    suspend fun cellStatsBySession(address: String): List<CellSessionStats>

    /** Blocking — called ~700–1400 times per pack in a tight loop on IO; a suspend hop per row
     *  would dominate the walk. Never call on Main. */
    @Query(IV_ROW_AFTER_SQL)
    fun ivRowAfter(address: String, afterTs: Long, afterId: Long, skip: Int): IvPoint?

    @Query("SELECT * FROM samples WHERE address = :address AND tsMs >= :sinceMs AND linkEvent IS NULL ORDER BY tsMs ASC")
    suspend fun since(address: String, sinceMs: Long): List<SampleEntity>

    /** Blocking — the range pager calls it in a loop on IO (BLE-25); never call on Main. */
    @Query(RANGE_PAGE_SQL)
    fun rangePage(address: String, afterTs: Long, afterId: Long, limit: Int): List<RangeRowColumns>

    @Query("DELETE FROM samples WHERE tsMs < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long): Int

    @Query("SELECT COUNT(*) FROM samples") suspend fun count(): Long

    @Query("DELETE FROM samples") suspend fun clear()

    /** One bounded page of a re-sync window (DATA-19); see [RESYNC_PAGE_SQL]. */
    @Query(RESYNC_PAGE_SQL)
    suspend fun resyncPage(toMs: Long, afterTs: Long, afterId: Long, limit: Int): List<SampleEntity>
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
