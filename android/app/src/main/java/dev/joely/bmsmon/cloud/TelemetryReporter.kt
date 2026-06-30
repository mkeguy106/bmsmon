package dev.joely.bmsmon.cloud

import android.content.Context
import dev.joely.bmsmon.data.SettingsStore
import dev.joely.bmsmon.data.db.BmsDatabase
import dev.joely.bmsmon.data.db.OutboxEntity
import dev.joely.bmsmon.model.Telemetry
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
    private var uploaderJob: Job? = null
    var onStatus: ((outboxCount: Long, lastUploadMs: Long) -> Unit)? = null

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
    ) {
        val payload = CloudJson.sampleJson(
            tsMs, addr, advertisedName, alias, groupId,
            t.state.name, t.soc, t.current, t.powerW, t.voltage, t.temp, t.mosfetTemp,
            t.soh, t.fullChargeAh, t.capacityAh, t.cycles,
            t.cells.minOrNull(), t.cells.maxOrNull(), regen, null,
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

    private suspend fun uploadLoop() {
        var backoff = 1000L
        var seq = 0
        while (true) {
            val p = settings.load()
            val base = p.apiBaseUrl
            if (!p.cloudEnabled || !p.enrolled || p.deviceId == null || base == null) {
                delay(2000)
                continue
            }
            // Cap the outbox — drop oldest rows if over limit.
            val depth = db.outbox().count()
            if (depth > OUTBOX_MAX) db.outbox().dropOldest(depth - OUTBOX_MAX)
            if (!conn.online.value || depth == 0) {
                onStatus?.invoke(depth.toLong(), lastUploadMs)
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
            val ok = runCatching {
                val token = Jwt.signEs256(
                    DeviceKeys.privateKey(), p.deviceId, body, System.currentTimeMillis(),
                )
                val req = Request.Builder()
                    .url(CloudConfig(base).ingestUrl)
                    .header("Authorization", "Bearer $token")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                http.newCall(req).execute().use { it.isSuccessful }
            }.getOrDefault(false)
            if (ok) {
                db.outbox().deleteUpTo(rows.last().id)
                lastUploadMs = System.currentTimeMillis()
                onStatus?.invoke(db.outbox().count().toLong(), lastUploadMs)
                backoff = 1000L
            } else {
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(60_000L)
            }
        }
    }
}
