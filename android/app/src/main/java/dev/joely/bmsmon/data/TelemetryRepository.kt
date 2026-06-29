package dev.joely.bmsmon.data

import dev.joely.bmsmon.data.db.BmsDatabase
import dev.joely.bmsmon.data.db.RawFrameEntity
import dev.joely.bmsmon.data.db.SampleEntity
import dev.joely.bmsmon.data.db.SessionEntity
import dev.joely.bmsmon.model.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.io.File

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
        scope.launch { for (op in ops) runCatching { op() } }
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
        db.samples().deleteOlderThan(cutoffMs(nowMs, SAMPLE_RETENTION_DAYS))
        db.rawFrames().deleteOlderThan(cutoffMs(nowMs, RAW_FRAME_RETENTION_DAYS))
        while (db.rawFrames().totalHexBytes() > RAW_FRAME_MAX_BYTES) {
            if (db.rawFrames().deleteOldest(1000) == 0) break
        }
    }

    fun sessions(address: String): Flow<List<SessionEntity>> = db.sessions().forAddress(address)
    fun allSessions(): Flow<List<SessionEntity>> = db.sessions().all()

    /** One-time backfill of legacy CSV files (oldest first). Segments via the same gap rule. */
    suspend fun importCsvOnce(files: List<File>) {
        // Local bookkeeping — fully decoupled from the live-path shared maps so concurrent
        // ingest ops flowing through the channel cannot race with this import.
        val openId = HashMap<String, Long>()
        val lastTs = HashMap<String, Long>()
        val wasDisconnect = HashMap<String, Boolean>()

        suspend fun localFinalize(addr: String) {
            val id = openId.remove(addr) ?: return
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
                db.samples().insert(parsed.copy(sessionId = sessionId))
                lastTs[addr] = parsed.tsMs
                wasDisconnect[addr] = parsed.linkEvent == "Disconnected"
            }
        }
        openId.keys.toList().forEach { localFinalize(it) }
    }

    suspend fun clearAll() {
        db.samples().clear(); db.sessions().clear(); db.rawFrames().clear()
        openSessionId.clear(); lastSampleTs.clear(); pendingDisconnect.clear()
        sinceLastPrune = 0
    }

    suspend fun approxSizeBytes(): Long =
        db.samples().count() * 80L + db.rawFrames().totalHexBytes()
}
