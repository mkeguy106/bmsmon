package dev.joely.bmsmon.cloud

import android.content.Context
import android.os.SystemClock
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
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val TAG = "TelemetryReporter"
private const val BATCH = 200
private const val OUTBOX_MAX = 200_000
private const val IMPORT_PAGE = 500

/**
 * Batch accumulation (bandwidth): don't POST the moment the outbox is non-empty — that turned
 * BATCH=200 into an effective ~2 rows across ~3,000 POSTs/hour, paying ~470 B of per-POST
 * overhead (unique JWT + headers) and a poor gzip ratio on tiny bodies. Flush only once
 * [MIN_BATCH] rows are queued OR the oldest queued row is [FLUSH_AGE_MS] old (bounds latency),
 * then drain to empty. Internal for unit tests.
 */
internal const val MIN_BATCH = 20
internal const val FLUSH_AGE_MS = 15_000L

/**
 * Pure flush decision for the upload loop: flush when the queue is deep enough ([MIN_BATCH]), the
 * head row is old enough ([FLUSH_AGE_MS] — fires AT the threshold), or a drain is already in
 * progress ([draining] — a 250-deep queue goes 200+50 without re-waiting). [oldestAgeMs] is null
 * when the queue emptied between reads; a negative value (clock skew) never triggers the age arm.
 */
internal fun shouldFlush(depth: Int, oldestAgeMs: Long?, draining: Boolean): Boolean =
    depth > 0 && (draining || depth >= MIN_BATCH || (oldestAgeMs != null && oldestAgeMs >= FLUSH_AGE_MS))

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
    private val http = uploadHttpClient()
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
        motionActivity: String?,
        motionConfidence: Int?,
        motionStill: Boolean?,
        motionAtMs: Long?,
    ) {
        if (!reportingEnabled) return
        val payload = CloudJson.sampleJson(
            tsMs, addr, advertisedName, alias, groupId,
            t.state.name, t.soc, t.current, t.powerW, t.voltage, t.temp, t.mosfetTemp,
            t.soh, t.fullChargeAh, t.capacityAh, t.cycles,
            t.cells.minOrNull(), t.cells.maxOrNull(), regen, null,
            lat, lon, gpsAccuracyM, etaFullMin,
            motionActivity, motionConfidence, motionStill, motionAtMs,
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
        var poisonSkips = 0   // the import's own poison circuit breaker (DATA-14, see decideUpload)
        var heldPageAfter: Long? = null   // the page the breaker is holding — logged once, not every 5 s retry
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
                val result = postSigned(ingestUrl, p.deviceId, body)
                val d = decideUpload(result, poisonSkips, authFailed = false)
                poisonSkips = d.poisonSkipsSinceOk
                when (d.step) {
                    BatchStep.DELETE_ACCEPTED -> {}
                    BatchStep.DELETE_POISON ->
                        // Permanently rejected page: skip it so the import can't stall forever.
                        // The rows themselves stay in the local Room samples table.
                        Log.w(TAG, "import: server permanently rejected page after id=$after (${page.size} rows) — skipping")
                    BatchStep.BACK_OFF, BatchStep.BACK_OFF_AUTH -> {
                        if (result == PostResult.Poison && heldPageAfter != after) {
                            heldPageAfter = after
                            Log.w(TAG, "import: page after id=$after rejected again with no 2xx since the last skip — holding it (poison breaker open)")
                        }
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

    /**
     * Sign [body] with the device key and POST [wire] (its gzipped form) to [url]. Classified by
     * HTTP status. [wire] defaults to gzipping here; the upload loop passes it precomputed so the
     * same bytes feed both the request body and the upload-rate indicator (gzip once).
     */
    private fun postSigned(url: String, deviceId: String, body: ByteArray, wire: ByteArray = gzip(body)): PostResult =
        try {
            // Sign the PLAINTEXT body (the server's body-hash is over the decompressed JSON), then
            // send the gzipped wire bytes as a transport layer (~85% saved on this repetitive JSON).
            val token = Jwt.signEs256(DeviceKeys.privateKey(), deviceId, body, System.currentTimeMillis())
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("Content-Encoding", "gzip")
                .post(wire.toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { classifyPost(it.code, fromApi = it.header(API_MARKER_HEADER) != null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PostResult.Transient // network/IO — retry with backoff
        }

    private suspend fun uploadLoop() {
        var backoff = 1000L
        var seq = 0
        // True while a started flush is draining the queue to empty (see shouldFlush). Stays true
        // across transient/auth retries so a failed batch's remainder never re-waits FLUSH_AGE_MS.
        var draining = false
        // Poison circuit breakers (DATA-14, see decideUpload): one per stream — a reject of the
        // config body says nothing about sample batches, and vice versa.
        var ingestPoisonSkips = 0
        var configPoisonSkips = 0
        var heldConfig: String? = null   // the config the breaker is holding — logged once, not every pass
        // The config push's own retry gate. A config the uploader keeps — Transient, AuthFailed, or a
        // Poison the breaker holds — is not re-sent on every loop pass (that is a POST per drained
        // batch, or per idle ~1.5 s pass); it follows the ingest backoff schedule (1 s doubling to
        // 60 s) on its own clock, so a held config can never throttle sample uploads.
        var configBackoff = 1000L
        var configRetryAt = 0L   // SystemClock.elapsedRealtime(): monotonic, a wall-clock step can't strand it
        // The ingest stream's server-fault bisection and its one-skip breaker (DATA-22, see
        // stepHeadFault). Memory only: a process restart resets it to a full batch and a fresh
        // streak (patience), and re-arms one skip, like the poison breaker.
        var headFault = HeadFaultState(headId = null, limit = BATCH, streak = 0, firstFaultAtMs = null, skipsSinceOk = 0)
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
                // sign the plaintext + gzip like ingest. Cleared on 2xx. The FIRST app-level
                // permanent reject drops it (WEB-6b: re-POSTing a body the server will never accept
                // is pure waste; the next threshold change enqueues a fresh one). A repeat reject
                // before any 2xx trips the breaker — systematic rejection is a server problem, so
                // the config stays pending instead of being thrown away.
                if (conn.online.value && SystemClock.elapsedRealtime() >= configRetryAt) {
                    p.pendingTempConfig?.let { cfg ->
                        val result = postSigned(CloudConfig(base).configUrl, p.deviceId, cfg.toByteArray())
                        val d = decideUpload(result, configPoisonSkips, authFailed)
                        configPoisonSkips = d.poisonSkipsSinceOk
                        when (d.step) {
                            BatchStep.DELETE_ACCEPTED -> {
                                settings.clearPendingTempConfig()
                                configBackoff = 1000L
                            }
                            BatchStep.DELETE_POISON -> {
                                Log.w(TAG, "config: server permanently rejected the temp-config push — dropping it (re-enqueued on the next threshold change)")
                                settings.clearPendingTempConfig()
                                configBackoff = 1000L
                            }
                            BatchStep.BACK_OFF, BatchStep.BACK_OFF_AUTH -> {
                                if (result == PostResult.Poison && heldConfig != cfg) {
                                    heldConfig = cfg
                                    Log.w(TAG, "config: rejected again with no 2xx since the last drop — keeping it pending (poison breaker open)")
                                }
                                configRetryAt = SystemClock.elapsedRealtime() + configBackoff
                                configBackoff = (configBackoff * 2).coerceAtMost(60_000L)
                            }
                        }
                    }
                }
                // Cap the outbox — drop oldest rows if over limit.
                val depth = db.outbox().count()
                if (depth > OUTBOX_MAX) db.outbox().dropOldest(depth - OUTBOX_MAX)
                if (!conn.online.value || depth == 0) {
                    if (depth == 0) draining = false
                    onStatus?.invoke(depth.toLong(), lastUploadMs, uploadRate.kbps(System.currentTimeMillis()), authFailed)
                    delay(1500)
                    continue
                }
                // Batch accumulation: wait for MIN_BATCH rows or a FLUSH_AGE_MS-old head before
                // flushing (then drain to empty). Rows sit durably in the Room outbox meanwhile.
                val oldestAgeMs = db.outbox().oldestEnqueuedAt()
                    ?.let { System.currentTimeMillis() - it }
                if (!shouldFlush(depth, oldestAgeMs, draining)) {
                    onStatus?.invoke(depth.toLong(), lastUploadMs, uploadRate.kbps(System.currentTimeMillis()), authFailed)
                    delay(1500)
                    continue
                }
                draining = true
                val rows = db.outbox().peek(minOf(BATCH, headFault.limit))
                if (rows.isEmpty()) {
                    delay(1500)
                    continue
                }
                seq += 1
                // The SAME body bytes are used for both signing and POSTing; gzip ONCE so the
                // rate indicator records the actual wire bytes, not the plaintext size.
                val body = CloudJson.encodeBatch(seq, rows.map { it.payload })
                val wire = gzip(body)
                val result = postSigned(CloudConfig(base).ingestUrl, p.deviceId, body, wire)
                val (nextFault, faultAction) = stepHeadFault(
                    headFault, rows.first().id, rows.size, result, SystemClock.elapsedRealtime(), BATCH,
                    tailId = rows.last().id,
                )
                // A ServerFault always extends the streak unless the streak tripped, which clears it.
                if (result == PostResult.ServerFault && nextFault.streak == 0 && faultAction == HeadFaultAction.NONE) {
                    if (nextFault.limit < rows.size) {
                        Log.w(
                            TAG,
                            "upload: batch seq=$seq (from outbox id ${rows.first().id}, ${rows.size} rows) drew server " +
                                "faults for $FAULT_STREAK+ tries over ${FAULT_MIN_SPAN_MS / 60_000}+ min — " +
                                "narrowing the batch to ${nextFault.limit} rows",
                        )
                    } else {
                        Log.w(
                            TAG,
                            "upload: server still faulting on outbox id=${rows.first().id} (seq=$seq) with a sample " +
                                "already skipped since the last 2xx — holding it (fault breaker open)",
                        )
                    }
                }
                headFault = nextFault
                val d = decideUpload(result, ingestPoisonSkips, authFailed)
                ingestPoisonSkips = d.poisonSkipsSinceOk
                authFailed = d.authFailed
                when (d.step) {
                    BatchStep.DELETE_ACCEPTED -> {
                        db.outbox().deleteUpTo(rows.last().id)
                        val now = System.currentTimeMillis()
                        lastUploadMs = now
                        uploadRate.record(now, wire.size)
                        val remaining = db.outbox().count()
                        if (remaining == 0) draining = false
                        onStatus?.invoke(remaining.toLong(), lastUploadMs, uploadRate.kbps(now), authFailed)
                        backoff = 1000L
                    }
                    BatchStep.DELETE_POISON -> {
                        // The APP permanently rejects this batch (marker-carrying 400/413/422) and
                        // the breaker allows one skip since the last 2xx — retrying it forever would
                        // head-of-line block every later sample. When logging is on the telemetry
                        // still lives in the Room samples table.
                        Log.w(
                            TAG,
                            "upload: server permanently rejected batch seq=$seq " +
                                "(${rows.size} rows, outbox ids ${rows.first().id}..${rows.last().id}) — skipping past it; " +
                                "another reject before any 2xx will be held",
                        )
                        db.outbox().deleteUpTo(rows.last().id)
                        val remaining = db.outbox().count()
                        if (remaining == 0) draining = false
                        onStatus?.invoke(
                            remaining.toLong(), lastUploadMs,
                            uploadRate.kbps(System.currentTimeMillis()), authFailed,
                        )
                        backoff = 1000L
                    }
                    BatchStep.BACK_OFF_AUTH -> {
                        // Revoked device or >60 s clock skew: NEVER drop the rows — keep buffering
                        // and backing off, but surface the auth state so the UI can show it
                        // instead of "queued" forever.
                        onStatus?.invoke(
                            db.outbox().count().toLong(), lastUploadMs,
                            uploadRate.kbps(System.currentTimeMillis()), authFailed,
                        )
                        delay(backoff)
                        backoff = (backoff * 2).coerceAtMost(60_000L)
                    }
                    BatchStep.BACK_OFF -> if (faultAction == HeadFaultAction.SKIP_HEAD_ROW) {
                        // DATA-22: this one sample has made the server crash (marked 5xx) for 5+
                        // minutes at batch size 1, and no row was skipped since the last 2xx — skip
                        // its OUTBOX row so it can't block every later sample. The next head goes
                        // alone until a 2xx. Log identity and size only, never the payload. When
                        // logging is on the sample still lives in the Room samples table.
                        val head = rows.first()
                        Log.w(
                            TAG,
                            "upload: server keeps faulting on one sample — skipping outbox id=${head.id} " +
                                "(seq=$seq, ${head.payload.toByteArray().size} bytes)",
                        )
                        db.outbox().deleteUpTo(head.id)   // the head is the lowest id: exactly this row
                        settings.incrementServerFaultSkips()
                        val remaining = db.outbox().count()
                        if (remaining == 0) draining = false
                        onStatus?.invoke(
                            remaining.toLong(), lastUploadMs,
                            uploadRate.kbps(System.currentTimeMillis()), authFailed,
                        )
                        backoff = 1000L
                    } else {
                        if (result == PostResult.Poison) {
                            Log.w(TAG, "upload: batch seq=$seq rejected again with no 2xx since the last skip — holding it (poison breaker open)")
                        }
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
