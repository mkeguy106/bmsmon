package dev.joely.bmsmon.cloud

import android.content.Context
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import dev.joely.bmsmon.data.SettingsStore
import dev.joely.bmsmon.data.db.BmsDatabase
import dev.joely.bmsmon.data.db.OutboxEntity
import dev.joely.bmsmon.model.Roster
import dev.joely.bmsmon.model.Telemetry
import dev.joely.bmsmon.model.batteryAt
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

private const val BATCH = 200
private const val OUTBOX_MAX = 200_000
private const val IMPORT_PAGE = 500

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
    @Volatile private var reportingEnabled = false
    @Volatile private var importStarted = false
    private var uploaderJob: Job? = null
    private val uploadRate = UploadRate()
    var onStatus: ((outboxCount: Long, lastUploadMs: Long, kbps: Double) -> Unit)? = null
    var onImportProgress: ((Long) -> Unit)? = null

    init {
        // Single-writer drain from the channel into the outbox — never blocks callers.
        scope.launch {
            for (row in enqueueChannel) {
                runCatching { db.outbox().insert(listOf(row)) }
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
                val ok = postSigned(ingestUrl, p.deviceId, body)
                if (!ok) {
                    delay(5000)
                    continue
                }
                after = page.last().id
                settings.setImportWatermark(after)
                onImportProgress?.invoke(after)
                delay(750)
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

    /** Sign [body] with the device key and POST to [url], gzipped. Returns true on HTTP 2xx. */
    private fun postSigned(url: String, deviceId: String, body: ByteArray): Boolean =
        runCatching {
            // Sign the PLAINTEXT body (the server's body-hash is over the decompressed JSON), then
            // gzip it as a transport layer to cut upload bandwidth (~85% on this repetitive JSON).
            val token = Jwt.signEs256(DeviceKeys.privateKey(), deviceId, body, System.currentTimeMillis())
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("Content-Encoding", "gzip")
                .post(gzip(body).toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)

    private suspend fun uploadLoop() {
        var backoff = 1000L
        var seq = 0
        reportingEnabled = settings.load().let { it.cloudEnabled && it.enrolled && it.deviceId != null }
        while (true) {
            try {
                val p = settings.load()
                reportingEnabled = p.cloudEnabled && p.enrolled && p.deviceId != null
                val base = p.apiBaseUrl
                if (!p.cloudEnabled || !p.enrolled || p.deviceId == null || base == null) {
                    delay(2000)
                    continue
                }
                // One-way temperature-alert config push (latest-wins, durable across restarts):
                // sign the plaintext + gzip like ingest, and clear only on success.
                if (conn.online.value) {
                    p.pendingTempConfig?.let { cfg ->
                        if (postSigned(CloudConfig(base).configUrl, p.deviceId, cfg.toByteArray())) {
                            settings.clearPendingTempConfig()
                        }
                    }
                }
                // Cap the outbox — drop oldest rows if over limit.
                val depth = db.outbox().count()
                if (depth > OUTBOX_MAX) db.outbox().dropOldest(depth - OUTBOX_MAX)
                if (!conn.online.value || depth == 0) {
                    onStatus?.invoke(depth.toLong(), lastUploadMs, uploadRate.kbps(System.currentTimeMillis()))
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
                val ok = postSigned(CloudConfig(base).ingestUrl, p.deviceId, body)
                if (ok) {
                    db.outbox().deleteUpTo(rows.last().id)
                    val now = System.currentTimeMillis()
                    lastUploadMs = now
                    uploadRate.record(now, body.size)
                    onStatus?.invoke(db.outbox().count().toLong(), lastUploadMs, uploadRate.kbps(now))
                    backoff = 1000L
                } else {
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(60_000L)
                }
            } catch (e: Exception) {
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(60_000L)
            }
        }
    }
}
