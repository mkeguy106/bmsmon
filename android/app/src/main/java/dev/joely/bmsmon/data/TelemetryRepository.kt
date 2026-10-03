package dev.joely.bmsmon.data

import android.util.Log
import androidx.room.withTransaction
import dev.joely.bmsmon.data.db.BmsDatabase
import dev.joely.bmsmon.data.db.RangeRowColumns
import dev.joely.bmsmon.data.db.RawFrameEntity
import dev.joely.bmsmon.data.db.SampleEntity
import dev.joely.bmsmon.data.db.SessionEntity
import dev.joely.bmsmon.location.GpsFix
import dev.joely.bmsmon.model.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Samples per transaction during the legacy CSV backfill (DATA-8). */
private const val IMPORT_CHUNK = 500

private const val TAG = "TelemetryRepository"

/** Rows per rollup page (DATA-16): ~1000 lean rows ≈ 150–200 KB, the only rows ever held. */
private const val ROLLUP_PAGE = 1_000

/**
 * Single facade for telemetry persistence (replaces TelemetryLogger). Writes are serialized through
 * an unlimited channel consumed by one coroutine, so callers (the BLE poll loop) never block and DB
 * access is single-threaded. Per-pack session continuity is tracked in memory: a gap or disconnect
 * finalizes the open session (computing its rollups from its samples) and opens a new one.
 */
class TelemetryRepository(private val db: BmsDatabase) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ops = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    // Per-address session bookkeeping (touched only on the single writer coroutine).
    private val openSessionId = HashMap<String, Long>()
    private val lastSampleTs = HashMap<String, Long>()
    private val pendingDisconnect = HashMap<String, Boolean>()
    private val sessionStartTs = HashMap<String, Long>()
    private var sinceLastPrune = 0

    init {
        // Startup retention pass FIRST (DATA-6, reordered for DATA-16): bound the tables before any
        // sweep work, so even a stub left by an enormous multi-day run is swept over at most the
        // retention window. Its own op, so a prune failure cannot skip the sweep (or vice versa).
        // (DATA-6 itself: pruning previously only ran from ingest's every-200th counter, so an
        // install that mostly sits idle never pruned at all.)
        ops.trySend { prune(System.currentTimeMillis()) }
        // Startup finalize-sweep for sessions orphaned by process death (DATA-2): rows inserted as
        // emptySession (sampleCount = 0) whose finalize never ran are invisible to the history DAOs
        // and their samples retention-prune away. Safe to finalize ALL zero-count stubs here:
        // nothing is open at construction (openSessionId starts empty) and every new run opens a
        // NEW session row (advanceSession: no lastSampleTs → isNewSession → insert). Enqueued
        // before the consumer starts, so it runs ahead of any new ingest and never blocks the
        // constructing caller. (importCsvOnce bypasses the channel, but it's a one-time legacy
        // backfill triggered later by the engine.)
        // Each stub is streamed in bounded pages and individually guarded: a stub that throws —
        // anything, Errors included — is logged and deleted (a zero-count stub is invisible to
        // History anyway) instead of escaping and crash-looping every launch (DATA-16).
        ops.trySend {
            for (stub in db.sessions().zeroCountStubs()) {
                try {
                    when (val action = orphanedSessionAction(accumulateSession(stub.address, stub.id))) {
                        is OrphanedSessionAction.Finalize -> db.sessions().update(action.rollup)
                        OrphanedSessionAction.Delete -> db.sessions().deleteById(stub.id)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w(TAG, "orphan sweep: stub id=${stub.id} (${stub.address}) failed — deleting it", t)
                    try {
                        db.sessions().deleteById(stub.id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t2: Throwable) {
                        // Skip it; the next stub must still be swept (it is retried next launch).
                        Log.w(TAG, "orphan sweep: stub id=${stub.id} could not be deleted — skipping it", t2)
                    }
                }
            }
        }
        scope.launch {
            for (op in ops) {
                try {
                    op()
                } catch (e: CancellationException) {
                    throw e   // never swallow cancellation — the consumer must die with its scope
                } catch (t: Throwable) {
                    // One failed op — a transient DB error, or an Error — must not kill the writer
                    // loop, and must never escape this process-lifetime scope: there is no exception
                    // handler, so an escape kills the process, and the foreground service and BLE
                    // monitoring with it.
                    Log.w(TAG, "telemetry op failed", t)
                }
            }
        }
    }

    private fun hex(raw: ByteArray): String = raw.joinToString("") { "%02x".format(it) }

    fun ingest(address: String, t: Telemetry, raw: ByteArray, reason: String, regen: Boolean, tsMs: Long, fix: GpsFix? = null) {
        ops.trySend {
            val sessionId = advanceSession(address, tsMs)
            db.samples().insert(sampleFrom(address, t, sessionId, regen, tsMs, fix))
            db.rawFrames().insert(RawFrameEntity(address = address, tsMs = tsMs, hex = hex(raw), reason = reason))
            lastSampleTs[address] = tsMs
            pendingDisconnect[address] = false
            maybePrune(tsMs)
        }
    }

    fun ingestRawOnly(address: String, raw: ByteArray, reason: String, tsMs: Long) {
        ops.trySend {
            db.rawFrames().insert(RawFrameEntity(address = address, tsMs = tsMs, hex = hex(raw), reason = reason))
            // Raw-only inserts grow the same tables — count them toward the same prune counter
            // (DATA-6; previously a decode_fail flood never triggered retention).
            maybePrune(tsMs)
        }
    }

    fun logLink(address: String, connected: Boolean, tsMs: Long) {
        ops.trySend {
            val sessionId = openSessionId[address] ?: 0L
            db.samples().insert(
                SampleEntity(
                    address = address, tsMs = tsMs, sessionId = sessionId, state = null, soc = null,
                    currentA = null, powerW = null, voltageV = null, tempC = null, mosfetTempC = null,
                    soh = null, fullChargeAh = null, remainingAh = null, cycles = null,
                    cellMinV = null, cellMaxV = null, regen = false,
                    linkEvent = if (connected) "Connected" else "Disconnected",
                ),
            )
            if (!connected) {
                pendingDisconnect[address] = true
                finalizeSession(address)   // close the run; next sample opens a fresh session
            }
        }
    }

    /** Decide the session for this sample, finalizing+opening as needed. Returns the open id. */
    private suspend fun advanceSession(address: String, tsMs: Long): Long {
        val isNew = isNewSession(
            lastSampleTs[address], pendingDisconnect[address] == true, tsMs,
            sessionStartMs = sessionStartTs[address],
        )
        if (isNew) {
            finalizeSession(address)
            openSession(address, tsMs)
        }
        return openSessionId[address] ?: openSession(address, tsMs)
    }

    /** Insert a fresh session stub for [address] starting at [tsMs] and make it the open one. */
    private suspend fun openSession(address: String, tsMs: Long): Long {
        val id = db.sessions().insert(emptySession(address, tsMs))
        openSessionId[address] = id
        sessionStartTs[address] = tsMs
        return id
    }

    /** Stream rollups for the pack's currently-open session into its row, then forget it (DATA-16:
     *  never loads the session — a stable link used to keep one open for days, ~800k rows). */
    private suspend fun finalizeSession(address: String) {
        val id = openSessionId.remove(address) ?: return
        sessionStartTs.remove(address)
        val acc = accumulateSession(address, id)
        if (acc.count == 0) return
        db.sessions().update(acc.toRollup())
    }

    /** One session's telemetry folded through [streamRollup] in [ROLLUP_PAGE]-row pages. The page
     *  DAO is blocking, so this pins itself to IO (a no-op hop on the writer, which is already IO). */
    private suspend fun accumulateSession(address: String, sessionId: Long): RollupAccumulator =
        withContext(Dispatchers.IO) {
            streamRollup(address, sessionId, ROLLUP_PAGE) { afterId, limit ->
                db.samples().rollupPage(sessionId, afterId, limit)
            }
        }

    private fun emptySession(address: String, tsMs: Long) = SessionEntity(
        id = 0, address = address, startMs = tsMs, endMs = tsMs, sampleCount = 0,
        peakPowerW = 0f, p95PowerW = 0f, meanPowerW = 0f, peakCurrentA = 0f, peakRegenW = 0f,
        energyWh = 0f, socStart = 0f, socEnd = 0f, minSoc = 0f, maxSoc = 0f,
        minVoltageUnderLoad = 0f, estInternalResistanceMohm = null, irConfidence = 0f,
        sohEnd = 0, fullChargeAhEnd = 0f, cyclesEnd = 0, maxTempC = 0f,
    )

    private fun sampleFrom(address: String, t: Telemetry, sessionId: Long, regen: Boolean, tsMs: Long, fix: GpsFix?) =
        SampleEntity(
            address = address, tsMs = tsMs, sessionId = sessionId, state = t.state.name,
            soc = t.soc, currentA = t.current, powerW = t.powerW, voltageV = t.voltage,
            tempC = t.temp, mosfetTempC = t.mosfetTemp, soh = t.soh, fullChargeAh = t.fullChargeAh,
            remainingAh = t.capacityAh, cycles = t.cycles,
            cellMinV = t.cells.minOrNull(), cellMaxV = t.cells.maxOrNull() ?: t.cellV,
            regen = regen, lat = fix?.lat, lon = fix?.lon, gpsAccuracyM = fix?.accuracyM,
            linkEvent = null,
        )

    private suspend fun maybePrune(nowMs: Long) {
        if (++sinceLastPrune < 200) return
        sinceLastPrune = 0
        prune(nowMs)
    }

    private suspend fun prune(nowMs: Long) {
        db.samples().deleteOlderThan(cutoffMs(nowMs, SAMPLE_RETENTION_DAYS))
        db.rawFrames().deleteOlderThan(cutoffMs(nowMs, RAW_FRAME_RETENTION_DAYS))
        // The DAO sums hex CHARS (2 per byte) — convert before comparing against the byte cap.
        while (rawFrameBytes(db.rawFrames().totalHexBytes()) > RAW_FRAME_MAX_BYTES) {
            if (db.rawFrames().deleteOldest(1000) == 0) break
        }
    }

    fun sessions(address: String): Flow<List<SessionEntity>> = db.sessions().forAddress(address)
    fun allSessions(): Flow<List<SessionEntity>> = db.sessions().all()

    /**
     * Everything History/Review needs for one pack, without materializing its rows (DATA-15): two
     * aggregate queries (per-SOC-bin V/I moments, per-session cell Δ) and an exact row-stride walk
     * for the ≤ ~700-point V–I cloud. Replaces `telemetry(address)`, which loaded the pack's whole
     * 14-day history (~800k full rows ≈ 220–350 MB against a 256 MB heap) — an OOM that took the
     * foreground service and BLE monitoring down with the History screen. Off-main (IO).
     */
    suspend fun healthInputs(address: String): HealthInputs = withContext(Dispatchers.IO) {
        healthInputsFrom(
            bins = db.samples().ivMomentsBySocBin(address),
            cells = db.samples().cellStatsBySession(address),
        ) { afterTs, afterId, skip -> db.samples().ivRowAfter(address, afterTs, afterId, skip) }
    }

    /** Telemetry rows for one pack since [sinceMs] (link rows excluded), oldest first — for tail learning. */
    suspend fun recentSamples(address: String, sinceMs: Long): List<SampleEntity> =
        db.samples().since(address, sinceMs)

    /** Lean 7-column rows for the range learner (linkEvent rows excluded), oldest first. */
    suspend fun rangeRows(address: String, sinceMs: Long): List<RangeRowColumns> =
        db.samples().rangeRowsSince(address, sinceMs)

    /** All rows (telemetry + link events) for one session, oldest first — for the timeline pooler. */
    suspend fun samplesForSession(sessionId: Long): List<SampleEntity> = db.samples().forSession(sessionId)

    /** One session's rollups by id (for the timeline drill-down header/summary). */
    suspend fun session(sessionId: Long): SessionEntity? = db.sessions().byId(sessionId)

    /**
     * One-time backfill of legacy CSV files (oldest first). Segments via the same gap rule.
     * Samples are inserted in chunked transactions (DATA-8) instead of row-by-row autocommit —
     * one journal commit per [IMPORT_CHUNK] rows instead of per row. Deliberately still bypasses
     * the ops channel: it's a legacy one-shot whose local session bookkeeping is fully decoupled
     * from the live-path shared maps, so channel ops can't race it.
     */
    suspend fun importCsvOnce(files: List<File>) {
        val openId = HashMap<String, Long>()
        val lastTs = HashMap<String, Long>()
        val wasDisconnect = HashMap<String, Boolean>()
        val startTs = HashMap<String, Long>()
        val pending = ArrayList<SampleEntity>(IMPORT_CHUNK)

        suspend fun flush() {
            if (pending.isEmpty()) return
            val batch = pending.toList()
            pending.clear()
            db.withTransaction { db.samples().insertAll(batch) }
        }

        suspend fun localFinalize(addr: String) {
            val id = openId.remove(addr) ?: return
            startTs.remove(addr)
            flush()   // the rollup reads this session's samples — they must be persisted first
            val acc = accumulateSession(addr, id)
            if (acc.count == 0) return
            db.sessions().update(acc.toRollup())
        }

        for (file in files) {
            if (!file.exists()) continue
            for (line in file.readLines()) {
                val parsed = parseCsvLine(line) ?: continue
                val addr = parsed.address
                val isNew = isNewSession(
                    lastTs[addr], wasDisconnect[addr] == true, parsed.tsMs,
                    sessionStartMs = startTs[addr],
                )
                if (isNew) {
                    localFinalize(addr)
                    val id = db.sessions().insert(emptySession(addr, parsed.tsMs))
                    openId[addr] = id
                    startTs[addr] = parsed.tsMs
                }
                val sessionId = openId[addr]
                    ?: db.sessions().insert(emptySession(addr, parsed.tsMs)).also {
                        openId[addr] = it
                        startTs[addr] = parsed.tsMs
                    }
                pending += parsed.copy(sessionId = sessionId)
                if (pending.size >= IMPORT_CHUNK) flush()
                lastTs[addr] = parsed.tsMs
                wasDisconnect[addr] = parsed.linkEvent == "Disconnected"
            }
        }
        flush()
        openId.keys.toList().forEach { localFinalize(it) }
    }

    fun finalizeOpenSessions() {
        ops.trySend {
            openSessionId.keys.toList().forEach { finalizeSession(it) }
        }
    }

    fun clearAll() {
        ops.trySend {
            db.samples().clear(); db.sessions().clear(); db.rawFrames().clear()
            openSessionId.clear(); lastSampleTs.clear(); pendingDisconnect.clear(); sessionStartTs.clear()
            sinceLastPrune = 0
        }
    }

    /**
     * True on-disk footprint of `bms.db`: the main file plus its WAL/SHM sidecars. Room's
     * default journal mode is AUTOMATIC (never overridden in `BmsDatabase.create()`), which
     * resolves to write-ahead logging on-device, so live data can sit in `bms.db-wal` until
     * SQLite checkpoints it back into the main file — `File.length()` on `bms.db` alone
     * undercounts the real footprint by whatever hasn't been checkpointed yet.
     *
     * This is the single source of truth for every "database size" readout in the app (the
     * Battery Saver diagnostics row and the Data & Logging stat tile both call this) — it
     * replaces an earlier `count() * 80 bytes/row` estimate that measured logical row count,
     * not physical file size, and read ~2.2x low against the real file on device (183.6 MB
     * estimated vs 403.7 MB actual) because it never accounted for SQLite index/page overhead
     * or the WAL sidecar. Runs file I/O, so always off the main thread.
     */
    suspend fun dbSizeBytes(): Long = withContext(Dispatchers.IO) {
        val path = db.openHelper.writableDatabase.path ?: return@withContext 0L
        listOf(File(path), File("$path-wal"), File("$path-shm"))
            .sumOf { f -> if (f.exists()) f.length() else 0L }
    }
}

/**
 * The single "database size" readout formatter, shared by the Data & Logging summary/tile and
 * the Battery Saver "Local database" row — both used to format [dbSizeBytes] independently
 * (`%.1f` vs `%.0f`), so the same byte count could read "403.7 MB" on one settings page and
 * "404 MB" one tap away. Binary units (÷ 1,048,576, i.e. MiB) labelled "MB" is a deliberate,
 * documented convention (CLAUDE.md) — not changed here, only unified.
 */
fun formatDbSizeMb(bytes: Long): String = "%.1f MB".format(bytes / 1_048_576.0)
