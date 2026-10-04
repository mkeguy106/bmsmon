package dev.joely.bmsmon.cloud

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.ByteArrayOutputStream
import java.security.PrivateKey
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val TAG = "TelemetryReporter"
private const val OUTBOX_MAX = 200_000
private const val IMPORT_PAGE = 500

/**
 * Batch accumulation (bandwidth): don't POST the moment the outbox is non-empty — that turned
 * [UPLOAD_BATCH]=200 into an effective ~2 rows across ~3,000 POSTs/hour, paying ~470 B of per-POST
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
 * the upload loop's own per-iteration check still bounds it while uploading. Every eviction is
 * counted and queued for a re-send from local history (DATA-19, see capOutbox).
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
    // Everything a coroutine launched from `init` touches is declared ABOVE `init`: Kotlin runs
    // initializers in declaration order, and the IO drain can start before a later one runs.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = uploadHttpClient(appUserAgent())
    private val conn = Connectivity(appContext)
    private val enqueueChannel = Channel<OutboxEntity>(Channel.UNLIMITED)
    @Volatile private var started = false
    @Volatile var lastUploadMs = 0L
    @Volatile private var importStarted = false
    private var uploaderJob: Job? = null
    private val uploadRate = UploadRate()
    var onImportProgress: ((Long) -> Unit)? = null

    private val _status = MutableStateFlow(UploadStatus())

    /** Live upload status for the UI: the queue, the auth and clock state, the holds, re-sync and the counters. */
    val status: StateFlow<UploadStatus> = _status.asStateFlow()

    /**
     * Legacy hook MonitorEngine mirrors into its state (retired with that mirror). Fed by the upload
     * loop only, as before, so its four values always arrive in the loop's order.
     */
    var onStatus: ((outboxCount: Long, lastUploadMs: Long, kbps: Double, authFailed: Boolean) -> Unit)? = null

    // The outbox cap and the re-sync windows, each behind its own lock (when both, always cap →
    // resync). capMutex also covers the upload loop's outbox deletes, so an eviction's span and its
    // delete always see the same oldest rows (DATA-19).
    private val capMutex = Mutex()
    private val resyncMutex = Mutex()
    private var resyncState: ResyncState? = null   // guarded by resyncMutex; loaded lazily

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

    // DATA-20: the clock correction on token iat/exp, learned from the app's own responses
    // (nextSigningOffsetMs). Shared by every stream; memory only. Never applied to a sample's time.
    @Volatile private var signingOffsetMs = 0L
    @Volatile private var lastAuthLog: String? = null
    // Vouches for this phone's clock before a correction may start (a phone running ahead must
    // never be "corrected" into uploading future-dated samples).
    private val independentClock = IndependentClock(appContext)

    /** Whose clock the last time-rejected sign-in blamed (nextClockBlame); null once uploads succeed. */
    @Volatile internal var clockBlame: ClockBlame? = null
        private set

    /** The skew shown for the last time-rejected sign-in (nextAuthSkewMs); null once uploads succeed. */
    @Volatile private var authSkewMs: Long? = null

    init {
        // Keep the settings snapshot fresh. DataStore emits the current value on collect, so the
        // gate is correct within milliseconds of construction (before that, report() drops — the
        // same conservative behavior as before, minus the multi-second first-loop-pass window).
        scope.launch {
            settings.persisted.collect { cachedSettings = it }
        }
        // The persisted all-time counters, so the status shows exactly what was counted.
        scope.launch {
            settings.outboxEvicted.collect { n -> publish { it.copy(outboxEvicted = n) } }
        }
        scope.launch {
            settings.serverFaultSkips.collect { n -> publish { it.copy(serverFaultSkips = n) } }
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
                        capOutbox()
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
                val outcome = postSigned(ingestUrl, p.deviceId, body)
                noteOutcome(outcome)
                val result = outcome.result
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

    private fun publish(update: (UploadStatus) -> UploadStatus): UploadStatus = _status.updateAndGet(update)

    /**
     * The upload loop's view of the queue and the ingest stream's state. Also the only feed of the
     * legacy [onStatus] hook, so the engine's mirror keeps receiving the loop's values in its order.
     */
    private fun publishQueue(depth: Int, s: IngestLoopState) {
        val st = publish {
            it.copy(
                outboxDepth = depth,
                lastUploadMs = lastUploadMs,
                kbps = uploadRate.kbps(System.currentTimeMillis()),
                authFailed = s.authFailed,
                keyMissing = s.keyMissing,
                hold = s.hold,
            )
        }
        onStatus?.invoke(st.outboxDepth.toLong(), st.lastUploadMs, st.kbps, st.authFailed)
    }

    /**
     * Transform the re-sync windows under their lock: one in-memory copy, loaded once (normalised by
     * [decodeResync]), persisted on every change, and its summary published.
     */
    private suspend fun mutateResync(transform: (ResyncState) -> ResyncState): ResyncState = resyncMutex.withLock {
        val now = System.currentTimeMillis()
        val cur = resyncState ?: decodeResync(settings.loadResyncJson(), now)
        val next = transform(cur)
        resyncState = next
        if (next != cur) settings.setResyncJson(encodeResync(next))
        publish { it.copy(resync = resyncSummary(next, now)) }
        next
    }

    private suspend fun readResync(): ResyncState = mutateResync { it }

    /**
     * Enforce [OUTBOX_MAX] (DATA-5), never silently (DATA-19): the sample-time span of the rows about
     * to go is queued for a re-send from local history BEFORE they are deleted (a kill between the two
     * costs a duplicate send, which the server dedups, never a lost re-send), and the rows actually
     * deleted are counted (persisted, shown in Cloud sync). A re-send that can't be recorded keeps
     * every row for now (no eviction without its record), and uploading carries on, which is what
     * drains the queue. Serialized with every other outbox delete by [capMutex], so the span and the
     * delete see the same oldest rows. The enqueue drain and the upload loop both call it. Returns the
     * depth afterwards.
     */
    private suspend fun capOutbox(): Int = capMutex.withLock {
        val depth = db.outbox().count()
        if (depth <= OUTBOX_MAX) return@withLock depth
        withContext(NonCancellable) {
            val n = depth - OUTBOX_MAX
            val span = db.outbox().oldestSpan(n)
            val from = span.fromMs ?: return@withContext depth
            val to = span.toMs ?: return@withContext depth
            try {
                mutateResync { addResyncWindow(it, ResyncWindow(from, to), System.currentTimeMillis()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "outbox: over the cap, but the re-send could not be recorded — evicting nothing this time", e)
                return@withContext depth
            }
            val dropped = db.outbox().dropOldest(n)
            if (dropped > 0) {
                settings.addOutboxEvicted(dropped.toLong())
                Log.w(
                    TAG,
                    "outbox: full — evicted $dropped oldest samples; they are re-sent from local history " +
                        "while usage logging keeps it",
                )
            }
            depth - dropped
        }
    }

    /**
     * Apply one [ingestStep]'s outbox effects in the order it emitted them (a re-send is recorded
     * before the delete it accompanies), as a unit with respect to stop(): a cancellation can't land
     * between a skip's delete and its count, or between a re-send record and its delete. A skip counts
     * the rows ACTUALLY deleted, so a head the cap evicted first counts nothing.
     */
    private suspend fun applyEffects(effects: List<OutboxEffect>) = withContext(NonCancellable) {
        for (e in effects) {
            when (e) {
                is OutboxEffect.Resync -> mutateResync { addResyncWindow(it, e.window, System.currentTimeMillis()) }
                is OutboxEffect.DeleteThrough -> capMutex.withLock { db.outbox().deleteUpTo(e.id) }
                is OutboxEffect.SkipOne -> {
                    val n = capMutex.withLock { db.outbox().deleteOne(e.id) }
                    if (n > 0) settings.addServerFaultSkips(n.toLong())
                }
            }
        }
    }

    /**
     * The one outcome observer for every stream (ingest, import, config): folds a response into the
     * shared signing correction ([nextSigningOffsetMs]), the clock blame ([nextClockBlame]) and the
     * skew shown to the user ([nextAuthSkewMs]), publishes them, and logs a rejected sign-in once per
     * distinct reason. The independent clock is read at most once, and only when a rule needs it.
     * Synchronized: the import and the upload loop both report here.
     */
    @Synchronized
    private fun noteOutcome(o: PostOutcome) {
        val phoneClockErr by lazy(LazyThreadSafetyMode.NONE) { independentClock.measurePhoneClockErrorMs() }
        val prevOffsetMs = signingOffsetMs
        signingOffsetMs = nextSigningOffsetMs(prevOffsetMs, o) { phoneClockErr }
        val blame = nextClockBlame(clockBlame, o) { phoneClockErr }
        clockBlame = blame
        val skew = nextAuthSkewMs(authSkewMs, o)
        authSkewMs = skew
        publish { it.copy(authSkewMs = skew, signingOffsetMs = signingOffsetMs, clockBlame = blame) }
        if (o.result == PostResult.AuthFailed) {
            val key = "${o.code}|${o.fromApi}|${o.authReason}|${o.detail}|$blame|${signingOffsetMs / 1000}"
            if (key != lastAuthLog) {
                lastAuthLog = key
                val blameText = when (blame) {
                    ClockBlame.SERVER -> "; an independent clock agrees with this phone: the server's clock is off"
                    ClockBlame.PHONE -> "; this phone's clock looks off against an independent clock: not correcting"
                    ClockBlame.UNKNOWN -> "; no independent clock to tell which is off: not correcting"
                    null -> ""
                }
                Log.w(
                    TAG,
                    "upload: HTTP ${o.code} from ${if (o.fromApi) "the api" else "an intermediary"}: " +
                        "${o.authReason ?: "-"} (${o.detail ?: "-"}); " +
                        "server clock minus phone clock ${o.skewMs?.let { "${it / 1000} s" } ?: "unknown"}" +
                        "$blameText; signing correction now ${signingOffsetMs / 1000} s",
                )
            }
        } else if (o.result == PostResult.Ok) {
            lastAuthLog = null
            if (prevOffsetMs != 0L && signingOffsetMs == 0L) {
                Log.i(TAG, "upload: the server's clock agrees with this phone again — signing correction cleared")
            }
        }
    }

    /**
     * Sign [body] with the device key and POST [wire] (its gzipped form) to [url]. Classified by
     * HTTP status, plus what the response said beyond it ([outcomeOf]). [wire] defaults to gzipping
     * here; the upload loop passes it precomputed so the same bytes feed both the request body and
     * the upload-rate indicator (gzip once). A Keystore with no device key sends nothing
     * ([PostResult.KeyMissing], DATA-17).
     */
    private fun postSigned(url: String, deviceId: String, body: ByteArray, wire: ByteArray = gzip(body)): PostOutcome {
        val key: PrivateKey? = try {
            DeviceKeys.privateKeyOrNull()
        } catch (e: Exception) {
            return PostOutcome(PostResult.Transient)   // a Keystore hiccup is not proof the key is gone
        }
        if (key == null) return PostOutcome(PostResult.KeyMissing)
        return try {
            // Sign the PLAINTEXT body (the server's body-hash is over the decompressed JSON), then
            // send the gzipped wire bytes as a transport layer (~85% saved on this repetitive JSON).
            // The token is dated on the server's clock when one has been learned (DATA-20); the skew
            // is still measured against this phone's own clock.
            val sentAt = System.currentTimeMillis()
            val token = Jwt.signEs256(key, deviceId, body, tokenTimeMs(sentAt, signingOffsetMs))
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("Content-Encoding", "gzip")
                .post(wire.toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { outcomeOf(it, sentAt, System.currentTimeMillis()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PostOutcome(PostResult.Transient)   // network/IO — retry with backoff
        }
    }

    /**
     * The upload loop: an interpreter of [ingestStep]. It peeks a batch, POSTs it, feeds the outcome
     * to the shared observer ([noteOutcome]) and to [ingestStep], applies the step's effects in the
     * emitted order ([applyEffects]), and waits the step's delay. Every decision that can delete a
     * sample is in [ingestStep], JVM-tested; the only other delete is the cap's eviction ([capOutbox]).
     */
    private suspend fun uploadLoop() {
        var seq = 0
        // True while a started flush is draining the queue to empty (see shouldFlush). Stays true
        // across transient/auth retries so a failed batch's remainder never re-waits FLUSH_AGE_MS.
        var draining = false
        // The ingest stream's whole decision state: its poison breaker (DATA-14), its server-fault
        // bisection and one-skip breaker (DATA-22), auth, missing key, backoff and hold — see
        // ingestStep. Memory only: a process restart starts over at a full batch with both breakers
        // re-armed (patience).
        var ingest = IngestLoopState()
        // The last ingestStep message logged: each is logged once per change (see heldLog).
        var lastLog: String? = null
        // The config push: its own poison breaker (a reject of the config body says nothing about
        // sample batches, and vice versa), and its own retry gate. A config the uploader keeps —
        // Transient, AuthFailed, or a Poison the breaker holds — is not re-sent on every loop pass
        // (that is a POST per drained batch, or per idle ~1.5 s pass); it follows the ingest backoff
        // schedule (1 s doubling to 60 s, Retry-After a floor) on its own clock, so a held config can
        // never throttle sample uploads.
        var configPoisonSkips = 0
        var heldConfig: String? = null   // the config the breaker is holding — logged once, not every pass
        var configBackoff = INITIAL_BACKOFF_MS
        var configRetryAt = 0L   // SystemClock.elapsedRealtime(): monotonic, a wall-clock step can't strand it
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
                        val outcome = postSigned(CloudConfig(base).configUrl, p.deviceId, cfg.toByteArray())
                        noteOutcome(outcome)
                        val result = outcome.result
                        val d = decideUpload(result, configPoisonSkips, ingest.authFailed)
                        configPoisonSkips = d.poisonSkipsSinceOk
                        when (d.step) {
                            BatchStep.DELETE_ACCEPTED -> {
                                settings.clearPendingTempConfig()
                                configBackoff = INITIAL_BACKOFF_MS
                            }
                            BatchStep.DELETE_POISON -> {
                                Log.w(TAG, "config: server permanently rejected the temp-config push — dropping it (re-enqueued on the next threshold change)")
                                settings.clearPendingTempConfig()
                                configBackoff = INITIAL_BACKOFF_MS
                            }
                            BatchStep.BACK_OFF, BatchStep.BACK_OFF_AUTH -> {
                                if (result == PostResult.Poison && heldConfig != cfg) {
                                    heldConfig = cfg
                                    Log.w(TAG, "config: rejected again with no 2xx since the last drop — keeping it pending (poison breaker open)")
                                }
                                configRetryAt = SystemClock.elapsedRealtime() + retryDelayMs(configBackoff, outcome.retryAfterMs)
                                configBackoff = nextBackoffMs(configBackoff)
                            }
                        }
                    }
                }
                // Cap the outbox: evict the oldest rows if over the limit, counted and re-queued.
                val depth = capOutbox()
                if (!conn.online.value || depth == 0) {
                    if (depth == 0) draining = false
                    publishQueue(depth, ingest)
                    delay(1500)
                    continue
                }
                // Batch accumulation: wait for MIN_BATCH rows or a FLUSH_AGE_MS-old head before
                // flushing (then drain to empty). Rows sit durably in the Room outbox meanwhile.
                val oldestAgeMs = db.outbox().oldestEnqueuedAt()
                    ?.let { System.currentTimeMillis() - it }
                if (!shouldFlush(depth, oldestAgeMs, draining)) {
                    publishQueue(depth, ingest)
                    delay(1500)
                    continue
                }
                draining = true
                val rows = db.outbox().peek(minOf(UPLOAD_BATCH, ingest.fault.limit))
                if (rows.isEmpty()) {
                    delay(1500)
                    continue
                }
                seq += 1
                // The SAME body bytes are used for both signing and POSTing; gzip ONCE so the
                // rate indicator records the actual wire bytes, not the plaintext size.
                val body = CloudJson.encodeBatch(seq, rows.map { it.payload })
                val wire = gzip(body)
                val outcome = postSigned(CloudConfig(base).ingestUrl, p.deviceId, body, wire)
                noteOutcome(outcome)
                val step = ingestStep(ingest, batchMeta(rows), outcome, SystemClock.elapsedRealtime(), System.currentTimeMillis())
                ingest = step.state
                val (memory, line) = heldLog(lastLog, step.log, outcome.result)
                lastLog = memory
                line?.let { Log.w(TAG, it + stepLogSuffix(seq, rows, step.effects)) }
                applyEffects(step.effects)
                if (outcome.result == PostResult.Ok) {
                    val now = System.currentTimeMillis()
                    lastUploadMs = now
                    uploadRate.record(now, wire.size)
                }
                val remaining = db.outbox().count()
                if (remaining == 0) draining = false
                publishQueue(remaining, ingest)
                if (step.delayMs > 0) delay(step.delayMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                delay(ingest.backoffMs)
                ingest = ingest.copy(backoffMs = nextBackoffMs(ingest.backoffMs))
            }
        }
    }
}

/**
 * [ingestStep] repeats a held reason on every held step; the loop logs each message once per change.
 * Given the last message logged ([lastLog]), this step's message ([log]) and its [result], returns the
 * memory to keep and the line to log now (null = nothing). A step without a message ends the episode
 * (a 2xx, an auth reject), except an outage blip ([PostResult.Transient]) or a fault short of a trip
 * ([PostResult.ServerFault]), which say nothing new about the hold. Event messages (a skip, a
 * narrowing) name their rows, so one event never repeats another's text.
 */
internal fun heldLog(lastLog: String?, log: String?, result: PostResult): Pair<String?, String?> = when {
    log == null -> (if (result == PostResult.Transient || result == PostResult.ServerFault) lastLog else null) to null
    log == lastLog -> lastLog to null
    else -> log to log
}

/**
 * What the loop appends to an [ingestStep] log line: the batch's seq, and for a server-fault skip the
 * skipped row's payload size too (DATA-22: its id is already in the line). Identity and size only,
 * never the payload.
 */
internal fun stepLogSuffix(seq: Int, rows: List<OutboxEntity>, effects: List<OutboxEffect>): String {
    val skipped = effects.firstNotNullOfOrNull { e -> (e as? OutboxEffect.SkipOne)?.let { s -> rows.firstOrNull { it.id == s.id } } }
    return if (skipped != null) " (seq=$seq, ${skipped.payload.toByteArray().size} bytes)" else " (seq=$seq)"
}
