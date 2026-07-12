package dev.joely.bmsmon.cloud

import android.content.Context
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import dev.joely.bmsmon.data.Persisted
import dev.joely.bmsmon.data.SettingsStore
import dev.joely.bmsmon.data.db.BmsDatabase
import dev.joely.bmsmon.data.db.OutboxEntity
import dev.joely.bmsmon.model.Roster
import dev.joely.bmsmon.model.Telemetry
import dev.joely.bmsmon.model.batteryAt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val TAG = "TelemetryReporter"
private const val BATCH = 200
private const val OUTBOX_MAX = 200_000
private const val IMPORT_PAGE = 500

/**
 * How many enqueue-path inserts may pass between outbox-cap checks (DATA-5). An exact COUNT per
 * insert would double the write path's DB work for no benefit, so the cap is enforced amortized:
 * the drain loop re-counts every [CAP_CHECK_EVERY] inserts (and once at startup) and drops the
 * oldest overage. Worst-case transient overshoot is CAP_CHECK_EVERY rows (~0.25% of OUTBOX_MAX);
 * the upload loop's own per-iteration check still bounds it while uploading.
 */
private const val CAP_CHECK_EVERY = 500

/** gzip [data] into a standard gzip stream (decompressible by the server's gzip.decompress). */
internal fun gzip(data: ByteArray): ByteArray {
    val bos = ByteArrayOutputStream(maxOf(32, data.size / 2))
    GZIPOutputStream(bos).use { it.write(data) }
    return bos.toByteArray()
}

class TelemetryReporter(
    appContext: Context,
    private val db: BmsDatabase,
    private val settings: SettingsStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient()
    private val conn = Connectivity(appContext)
    private val enqueueChannel = Channel<OutboxEntity>(Channel.UNLIMITED)
    @Volatile private var started = false
    @Volatile var lastUploadMs = 0L
    @Volatile private var importStarted = false
    private var uploaderJob: Job? = null
    private val uploadRate = UploadRate()
    @Volatile private var authFailed = false
    var onStatus: ((outboxCount: Long, lastUploadMs: Long, kbps: Double, authFailed: Boolean) -> Unit)? = null
    var onImportProgress: ((Long) -> Unit)? = null

    /**
     * Cached settings snapshot, kept fresh by collecting the DataStore flow (DATA-5/DATA-7):
     * `report()` gates on it immediately (previously the gate was only refreshed by the upload
     * loop, so samples between process start and its first pass — or after a settings change —
     * were mis-gated), and the upload loop reads it instead of doing a full `settings.load()`
     * decode twice every ~1.5 s iteration.
     */
    @Volatile private var cachedSettings: Persisted? = null
    private val reportingEnabled: Boolean
        get() = cachedSettings.let { it != null && it.cloudEnabled && it.enrolled && it.deviceId != null }

    init {
        // Keep the settings snapshot fresh. DataStore emits the current value on collect, so the
        // gate is correct within milliseconds of construction (before that, report() drops — the
        // same conservative behavior as before, minus the multi-second first-loop-pass window).
        scope.launch {
            settings.persisted.collect { cachedSettings = it }
        }
        // Single-writer drain from the channel into the outbox — never blocks callers.
        scope.launch {
            var sinceCapCheck = CAP_CHECK_EVERY   // force a cap check on the first insert
            for (row in enqueueChannel) {
                try {
                    db.outbox().insert(listOf(row))
                    // Enforce OUTBOX_MAX on the enqueue path too (DATA-5) — amortized, see
                    // CAP_CHECK_EVERY. Previously only the upload loop enforced it, so with the
                    // uploader stopped (cloud off mid-flight, auth backoff) the outbox was unbounded.
                    if (++sinceCapCheck >= CAP_CHECK_EVERY) {
                        sinceCapCheck = 0
                        val depth = db.outbox().count()
                        if (depth > OUTBOX_MAX) db.outbox().dropOldest(depth - OUTBOX_MAX)
                    }
                } catch (e: CancellationException) {
                    throw e   // never swallow cancellation
                } catch (_: Exception) {
                    // drop this row rather than kill the drain loop
                }
            }
        }
    }

    fun report(
        addr: String,
        advertisedName: String?,
        alias: String?,
        groupId: String?,
        t: Telemetry,
        tsMs: Long,
        regen: Boolean,
        lat: Double? = null,
        lon: Double? = null,
        gpsAccuracyM: Float? = null,
        etaFullMin: Float? = null,
    ) {
        if (!reportingEnabled) return
        val payload = CloudJson.sampleJson(
            tsMs, addr, advertisedName, alias, groupId,
            t.state.name, t.soc, t.current, t.powerW, t.voltage, t.temp, t.mosfetTemp,
            t.soh, t.fullChargeAh, t.capacityAh, t.cycles,
            t.cells.minOrNull(), t.cells.maxOrNull(), regen, null,
            lat, lon, gpsAccuracyM, etaFullMin,
            cells = t.cells.takeIf { it.isNotEmpty() },
        )
        enqueueChannel.trySend(OutboxEntity(payload = payload, enqueuedAt = tsMs))
    }

    fun reportLink(
        addr: String,
        alias: String?,
        groupId: String?,
        reachable: Boolean,
        tsMs: Long,
    ) {
        if (!reportingEnabled) return
        val payload = CloudJson.sampleJson(
            tsMs, addr, null, alias, groupId,
            null, null, null, null, null, null, null, null, null, null, null,
            null, null, false, if (reachable) "Connected" else "Disconnected",
        )
        enqueueChannel.trySend(OutboxEntity(payload = payload, enqueuedAt = tsMs))
    }

    /** Start the uploader loop. Idempotent — safe to call multiple times. */
    fun start() {
        if (started) return
        started = true
        uploaderJob = scope.launch { uploadLoop() }
    }

    /** Cancel the uploader loop (drain continues so enqueued rows persist). */
    fun stop() {
        uploaderJob?.cancel()
        started = false
    }

    /**
     * One-time resumable historical importer. Pages through all rows in [samples] after the
     * stored watermark, POSTs them as signed import batches (seq = -1), and marks [importDone]
     * when the table is drained. Idempotent on the server (ON CONFLICT DO NOTHING). Throttled
     * below the live uploader path. Safe to cancel and re-launch — the watermark persists.
     */
    suspend fun runImport(roster: Roster) {
        val p = settings.load()
        if (!p.enrolled || p.importDone || p.deviceId == null || p.apiBaseUrl == null) return
        var after = p.importWatermark
        val ingestUrl = CloudConfig(p.apiBaseUrl).ingestUrl
        while (true) {
            try {
                val page = db.samples().pageAfter(after, IMPORT_PAGE)
                if (page.isEmpty()) {
                    settings.setImportDone(true)
                    break
                }
                val rows = page.map { e ->
                    val bat = roster.batteryAt(e.address)
                    CloudJson.sampleJson(
                        e.tsMs, e.address, bat?.advertisedName, bat?.alias, bat?.groupId,
                        e.state, e.soc, e.currentA, e.powerW, e.voltageV, e.tempC, e.mosfetTempC,
                        e.soh, e.fullChargeAh, e.remainingAh, e.cycles,
                        e.cellMinV, e.cellMaxV, e.regen, e.linkEvent,
                    )
                }
                // seq = -1 marks this as an import batch; the server ignores seq ordering for imports.
                val body = CloudJson.encodeBatch(seq = -1, rows = rows)
                when (postSigned(ingestUrl, p.deviceId, body)) {
                    PostResult.Ok -> {}
                    PostResult.Poison ->
                        // Permanently rejected page: skip it so the import can't stall forever.
                        // The rows themselves stay in the local Room samples table.
                        Log.w(TAG, "import: server permanently rejected page after id=$after (${page.size} rows) — skipping")
                    else -> {
                        delay(5000)
                        continue
                    }
                }
                after = page.last().id
                settings.setImportWatermark(after)
                onImportProgress?.invoke(after)
                delay(750)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                delay(5000)
            }
        }
    }

    /**
     * Launch [runImport] on the reporter's own process-lifetime scope if it hasn't been started yet
     * this process and the persisted flags indicate it's needed. Safe to call multiple times —
     * [importStarted] prevents a concurrent double-run within a process; the watermark + importDone
     * flag make it resumable across process deaths.
     */
    fun startImportIfNeeded(roster: Roster) {
        scope.launch {
            val p = settings.load()
            if (!p.enrolled || p.importDone || importStarted) return@launch
            importStarted = true
            try { runImport(roster) } finally { importStarted = false }
        }
    }

    /** Sign [body] with the device key and POST to [url], gzipped. Classified by HTTP status. */
    private fun postSigned(url: String, deviceId: String, body: ByteArray): PostResult =
        try {
            // Sign the PLAINTEXT body (the server's body-hash is over the decompressed JSON), then
            // gzip it as a transport layer to cut upload bandwidth (~85% on this repetitive JSON).
            val token = Jwt.signEs256(DeviceKeys.privateKey(), deviceId, body, System.currentTimeMillis())
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("Content-Encoding", "gzip")
                .post(gzip(body).toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { classifyPost(it.code) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PostResult.Transient // network/IO — retry with backoff
        }

    private suspend fun uploadLoop() {
        var backoff = 1000L
        var seq = 0
        while (true) {
            try {
                // Hot path (DATA-7): read the flow-fed snapshot — no per-iteration Persisted decode.
                val p = cachedSettings
                val base = p?.apiBaseUrl
                if (p == null || !p.cloudEnabled || !p.enrolled || p.deviceId == null || base == null) {
                    delay(2000)
                    continue
                }
                // One-way temperature-alert config push (latest-wins, durable across restarts):
                // sign the plaintext + gzip like ingest, and clear only on success — except a
                // permanent 4xx reject (WEB-6b): the server will never accept that body, so
                // re-POSTing it every ~1.5 s forever is pure waste. Drop it and log; the next
                // threshold change enqueues a fresh (presumably fixed) config.
                if (conn.online.value) {
                    p.pendingTempConfig?.let { cfg ->
                        when (postSigned(CloudConfig(base).configUrl, p.deviceId, cfg.toByteArray())) {
                            PostResult.Ok -> settings.clearPendingTempConfig()
                            PostResult.Poison -> {
                                Log.w(TAG, "config: server permanently rejected the temp-config push — dropping it (re-enqueued on the next threshold change)")
                                settings.clearPendingTempConfig()
                            }
                            else -> {}   // transient / auth: keep it pending, retry next pass
                        }
                    }
                }
                // Cap the outbox — drop oldest rows if over limit.
                val depth = db.outbox().count()
                if (depth > OUTBOX_MAX) db.outbox().dropOldest(depth - OUTBOX_MAX)
                if (!conn.online.value || depth == 0) {
                    onStatus?.invoke(depth.toLong(), lastUploadMs, uploadRate.kbps(System.currentTimeMillis()), authFailed)
                    delay(1500)
                    continue
                }
                val rows = db.outbox().peek(BATCH)
                if (rows.isEmpty()) {
                    delay(1500)
                    continue
                }
                seq += 1
                // The SAME body bytes are used for both signing and POSTing.
                val body = CloudJson.encodeBatch(seq, rows.map { it.payload })
                when (postSigned(CloudConfig(base).ingestUrl, p.deviceId, body)) {
                    PostResult.Ok -> {
                        authFailed = false
                        db.outbox().deleteUpTo(rows.last().id)
                        val now = System.currentTimeMillis()
                        lastUploadMs = now
                        uploadRate.record(now, body.size)
                        onStatus?.invoke(db.outbox().count().toLong(), lastUploadMs, uploadRate.kbps(now), authFailed)
                        backoff = 1000L
                    }
                    PostResult.Poison -> {
                        // The server permanently rejects this batch (4xx validation) — retrying it
                        // forever would head-of-line block every later sample. Skip past it so the
                        // queue drains; when logging is on the telemetry still lives in the Room
                        // samples table and can be re-sent via the historical import.
                        authFailed = false // a validation reject means auth itself passed
                        Log.w(
                            TAG,
                            "upload: server permanently rejected batch seq=$seq " +
                                "(${rows.size} rows, outbox ids ${rows.first().id}..${rows.last().id}) — skipping past it",
                        )
                        db.outbox().deleteUpTo(rows.last().id)
                        onStatus?.invoke(
                            db.outbox().count().toLong(), lastUploadMs,
                            uploadRate.kbps(System.currentTimeMillis()), authFailed,
                        )
                        backoff = 1000L
                    }
                    PostResult.AuthFailed -> {
                        // Revoked device or >60 s clock skew: NEVER drop the rows — keep buffering
                        // and backing off, but surface the auth state so the UI can show it
                        // instead of "queued" forever.
                        authFailed = true
                        onStatus?.invoke(
                            db.outbox().count().toLong(), lastUploadMs,
                            uploadRate.kbps(System.currentTimeMillis()), authFailed,
                        )
                        delay(backoff)
                        backoff = (backoff * 2).coerceAtMost(60_000L)
                    }
                    PostResult.Transient -> {
                        delay(backoff)
                        backoff = (backoff * 2).coerceAtMost(60_000L)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(60_000L)
            }
        }
    }
}
