package dev.joely.bmsmon.data

import androidx.room.withTransaction
import dev.joely.bmsmon.data.db.BmsDatabase
import dev.joely.bmsmon.data.db.RawFrameEntity
import dev.joely.bmsmon.data.db.SampleEntity
import dev.joely.bmsmon.data.db.SessionEntity
import dev.joely.bmsmon.model.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.io.File

/** Samples per transaction during the legacy CSV backfill (DATA-8). */
private const val IMPORT_CHUNK = 500

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
    private var sinceLastPrune = 0

    init {
        // Startup finalize-sweep for sessions orphaned by process death (DATA-2): rows inserted as
        // emptySession (sampleCount = 0) whose finalize never ran are invisible to the history DAOs
        // and their samples retention-prune away. Safe to finalize ALL zero-count stubs here:
        // nothing is open at construction (openSessionId starts empty) and every new run opens a
        // NEW session row (advanceSession: no lastSampleTs → isNewSession → insert). Enqueued as
        // the FIRST op on the serialized channel — before the consumer starts — so it always runs
        // ahead of any new ingest and never blocks the constructing caller. (importCsvOnce bypasses
        // the channel, but it's a one-time legacy backfill triggered later by the engine.)
        ops.trySend {
            for (stub in db.sessions().zeroCountStubs()) {
                when (val action = orphanedSessionAction(stub.address, stub.id, db.samples().forSession(stub.id))) {
                    is OrphanedSessionAction.Finalize -> db.sessions().update(action.rollup)
                    OrphanedSessionAction.Delete -> db.sessions().deleteById(stub.id)
                }
            }
            // Startup retention pass (DATA-6): pruning previously only ever ran from ingest's
            // every-200th counter, so an install that mostly sits idle (logging off, or only
            // decode_fail raw frames arriving) never pruned at all. One unconditional prune here
            // bounds the DB regardless of what happens afterwards.
            prune(System.currentTimeMillis())
        }
        scope.launch {
            for (op in ops) {
                try {
                    op()
                } catch (e: CancellationException) {
                    throw e   // never swallow cancellation — the consumer must die with its scope
                } catch (_: Exception) {
                    // one failed op (e.g. transient DB error) must not kill the writer loop
                }
            }
        }
    }

    private fun hex(raw: ByteArray): String = raw.joinToString("") { "%02x".format(it) }

    fun ingest(address: String, t: Telemetry, raw: ByteArray, reason: String, regen: Boolean, tsMs: Long) {
        ops.trySend {
            val sessionId = advanceSession(address, tsMs)
            db.samples().insert(sampleFrom(address, t, sessionId, regen, tsMs))
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
        val isNew = isNewSession(lastSampleTs[address], pendingDisconnect[address] == true, tsMs)
        if (isNew) {
            finalizeSession(address)
            val id = db.sessions().insert(emptySession(address, tsMs))
            openSessionId[address] = id
        }
        return openSessionId[address] ?: db.sessions().insert(emptySession(address, tsMs))
            .also { openSessionId[address] = it }
    }

    /** Recompute and persist rollups for the pack's currently-open session, then forget it. */
    private suspend fun finalizeSession(address: String) {
        val id = openSessionId.remove(address) ?: return
        val samples = db.samples().forSession(id)
        if (samples.none { it.linkEvent == null }) return
        db.sessions().update(computeRollup(address, id, samples))
    }

    private fun emptySession(address: String, tsMs: Long) = SessionEntity(
        id = 0, address = address, startMs = tsMs, endMs = tsMs, sampleCount = 0,
        peakPowerW = 0f, p95PowerW = 0f, meanPowerW = 0f, peakCurrentA = 0f, peakRegenW = 0f,
        energyWh = 0f, socStart = 0f, socEnd = 0f, minSoc = 0f, maxSoc = 0f,
        minVoltageUnderLoad = 0f, estInternalResistanceMohm = null, irConfidence = 0f,
        sohEnd = 0, fullChargeAhEnd = 0f, cyclesEnd = 0, maxTempC = 0f,
    )

    private fun sampleFrom(address: String, t: Telemetry, sessionId: Long, regen: Boolean, tsMs: Long) =
        SampleEntity(
            address = address, tsMs = tsMs, sessionId = sessionId, state = t.state.name,
            soc = t.soc, currentA = t.current, powerW = t.powerW, voltageV = t.voltage,
            tempC = t.temp, mosfetTempC = t.mosfetTemp, soh = t.soh, fullChargeAh = t.fullChargeAh,
            remainingAh = t.capacityAh, cycles = t.cycles,
            cellMinV = t.cells.minOrNull(), cellMaxV = t.cells.maxOrNull() ?: t.cellV,
            regen = regen, linkEvent = null,
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

    /** All stored telemetry rows for one pack (link rows excluded), oldest first — for derived-R,
     *  the V–I cloud and cell-imbalance analysis. */
    suspend fun telemetry(address: String): List<SampleEntity> = db.samples().telemetryFor(address)

    /** Telemetry rows for one pack since [sinceMs] (link rows excluded), oldest first — for tail learning. */
    suspend fun recentSamples(address: String, sinceMs: Long): List<SampleEntity> =
        db.samples().since(address, sinceMs)

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
        val pending = ArrayList<SampleEntity>(IMPORT_CHUNK)

        suspend fun flush() {
            if (pending.isEmpty()) return
            val batch = pending.toList()
            pending.clear()
            db.withTransaction { db.samples().insertAll(batch) }
        }

        suspend fun localFinalize(addr: String) {
            val id = openId.remove(addr) ?: return
            flush()   // the rollup reads this session's samples — they must be persisted first
            val samples = db.samples().forSession(id)
            if (samples.none { it.linkEvent == null }) return
            db.sessions().update(computeRollup(addr, id, samples))
        }

        for (file in files) {
            if (!file.exists()) continue
            for (line in file.readLines()) {
                val parsed = parseCsvLine(line) ?: continue
                val addr = parsed.address
                val isNew = isNewSession(lastTs[addr], wasDisconnect[addr] == true, parsed.tsMs)
                if (isNew) {
                    localFinalize(addr)
                    val id = db.sessions().insert(emptySession(addr, parsed.tsMs))
                    openId[addr] = id
                }
                val sessionId = openId[addr]
                    ?: db.sessions().insert(emptySession(addr, parsed.tsMs)).also { openId[addr] = it }
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
            openSessionId.clear(); lastSampleTs.clear(); pendingDisconnect.clear()
            sinceLastPrune = 0
        }
    }

    suspend fun approxSizeBytes(): Long =
        db.samples().count() * 80L + db.rawFrames().totalHexBytes()
}
