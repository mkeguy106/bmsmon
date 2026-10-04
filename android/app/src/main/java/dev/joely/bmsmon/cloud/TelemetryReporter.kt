package dev.joely.bmsmon.cloud

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.ByteArrayOutputStream
import java.security.PrivateKey
import java.util.zip.GZIPOutputStream
import dev.joely.bmsmon.data.FailureLogThrottle
import dev.joely.bmsmon.data.Persisted
import dev.joely.bmsmon.data.SettingsStore
import dev.joely.bmsmon.data.db.BmsDatabase
import dev.joely.bmsmon.data.db.OutboxEntity
import dev.joely.bmsmon.model.DEFAULT_ROSTER
import dev.joely.bmsmon.model.Roster
import dev.joely.bmsmon.model.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val TAG = "TelemetryReporter"

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
    private var uploaderJob: Job? = null
    private var resyncJob: Job? = null
    private val uploadRate = UploadRate()
    // The upload loop's last measured outbox depth gates the re-sender; until it has measured once, wait.
    @Volatile private var depthKnown = false
    // Serialises queueing the history import, so concurrent callers add its window once.
    private val importMutex = Mutex()
    // queueImport's failure log, rate-limited (the re-sync loop retries it every pass while it fails).
    private val importFailures = FailureLogThrottle()

    private val _status = MutableStateFlow(UploadStatus())

    /** Live upload status for the UI: the queue, the auth and clock state, the holds, re-sync and the counters. */
    val status: StateFlow<UploadStatus> = _status.asStateFlow()

    /**
     * Legacy hook MonitorEngine mirrors into its state (retired with that mirror). Fed by the upload
     * loop only, as before, so its four values always arrive in the loop's order.
     */
    var onStatus: ((outboxCount: Long, lastUploadMs: Long, kbps: Double, authFailed: Boolean) -> Unit)? = null

    // Every outbox delete, the cap and the re-sync windows (DATA-19, DATA-22): see OutboxLedger.
    private val ledger = OutboxLedger(
        outbox = db.outbox(),
        store = object : LedgerStore {
            override suspend fun loadResyncJson() = settings.loadResyncJson()
            override suspend fun setResyncJson(json: String) = settings.setResyncJson(json)
            override suspend fun addOutboxEvicted(n: Long) = settings.addOutboxEvicted(n)
            override suspend fun addServerFaultSkips(n: Long) = settings.addServerFaultSkips(n)
        },
        warn = { msg, e -> Log.w(TAG, msg, e) },
        onResync = { summary -> publish { it.copy(resync = summary) } },
        elapsed = SystemClock::elapsedRealtime,
    )

    // Re-sends skipped and evicted samples, and the history import, from local Room history (DATA-19).
    private val resync = ResyncSender(
        readWindows = { ledger.readResync() },
        mutateWindows = { transform -> ledger.mutateResync(transform) },
        readPage = { toMs, afterTs, afterId, limit -> db.samples().resyncPage(toMs, afterTs, afterId, limit) },
        warn = { msg, e -> Log.w(TAG, msg, e) },
        elapsedNow = SystemClock::elapsedRealtime,
    )

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

    /** Start the uploader and the re-sync loop, and queue the history import if it is due. Idempotent. */
    fun start() {
        if (started) return
        started = true
        uploaderJob = scope.launch { uploadLoop() }
        resyncJob = scope.launch { resyncLoop() }
        queueImport()
    }

    /** Cancel both loops (the drain continues so enqueued rows persist). */
    fun stop() {
        uploaderJob?.cancel()
        resyncJob?.cancel()
        started = false
    }

    /**
     * The one-shot history import, now a re-sync window (DATA-19): everything in local history up to
     * now, GPS included, sent under the same fault handling as any re-send, and yielding to live data
     * like one (it no longer holds the live upload behind it). A phone upgraded mid-import re-sends from
     * the start; the server dedups what it already has. Safe to call repeatedly; a store failure is
     * logged and retried by the re-sync loop.
     */
    fun queueImport() {
        scope.launch {
            try {
                queueImportIfDue()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logImportFailure(e)
            }
        }
    }

    /** Older entry point (MonitorEngine init, enroll), retired in Task 15; the re-sender reads the persisted roster itself. */
    fun startImportIfNeeded(@Suppress("UNUSED_PARAMETER") roster: Roster) = queueImport()

    /** Forget every pending re-send (the device was forgotten; a new enrollment queues a fresh import). */
    fun clearResync() {
        scope.launch {
            try {
                ledger.mutateResync { ResyncState() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "re-sync: could not clear the pending re-sends", e)
            }
        }
    }

    /** Add the import window once, if enrolled and not yet queued. Throws on a store error (nothing is marked done). */
    private suspend fun queueImportIfDue() = importMutex.withLock {
        val p = settings.load()
        if (!p.enrolled || p.importDone) return@withLock
        val now = System.currentTimeMillis()
        ledger.mutateResync { addResyncWindow(it, importWindow(now), now) }
        settings.setImportDone(true)
    }

    private fun logImportFailure(e: Exception) = synchronized(importFailures) {
        warnThrottled(
            importFailures, SystemClock.elapsedRealtime(), { msg, t -> Log.w(TAG, msg, t) },
            "re-sync: could not queue the history import — retrying", e,
        )
    }

    private fun publish(update: (UploadStatus) -> UploadStatus): UploadStatus = _status.updateAndGet(update)

    /**
     * The upload loop's view of the queue and the ingest stream's state. Also the only feed of the
     * legacy [onStatus] hook, so the engine's mirror keeps receiving the loop's values in its order.
     */
    private fun publishQueue(depth: Int, s: IngestLoopState) {
        depthKnown = true
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

    /** Enforce the outbox cap through the ledger: every eviction is recorded first, then counted. Returns the depth. */
    private suspend fun capOutbox(): Int = ledger.capOutbox()

    /**
     * The one outcome observer for every stream (ingest, re-sync, config): folds a response into the
     * shared signing correction ([nextSigningOffsetMs]), the clock blame ([nextClockBlame]) and the
     * skew shown to the user ([nextAuthSkewMs]), publishes them, and logs a rejected sign-in once per
     * distinct reason. The independent clock is read at most once, and only when a rule needs it.
     * Synchronized: the re-sync and the upload loop both report here.
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
     * to the shared observer ([noteOutcome]), then has the ledger decide and apply the step
     * ([OutboxLedger.applyStep]: effects in emitted order, the state committed only once they took),
     * and waits the step's delay. Every decision that can delete a sample is in [ingestStep], and every
     * delete (the cap's eviction, [capOutbox], included) is in [OutboxLedger], both JVM-tested.
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
        // The last ingestStep message logged: each is logged once per change (see heldLog). It moves
        // with the state: a rolled-back step logs its failure, not its message.
        var lastLog: String? = null
        // The config push: its own poison breaker (a reject of the config body says nothing about
        // sample batches, and vice versa), and its own retry gate. A config the uploader keeps —
        // Transient, AuthFailed, a Poison the breaker holds, or a failed clear — is not re-sent on
        // every loop pass (that is a POST per drained batch, or per idle ~1.5 s pass); it follows the
        // ingest backoff schedule (1 s doubling to 60 s, Retry-After a floor) on its own clock, so a
        // held config can never throttle sample uploads. See configPushStep.
        var config = ConfigPushState()
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
                if (conn.online.value && SystemClock.elapsedRealtime() >= config.retryAtMs) {
                    p.pendingTempConfig?.let { cfg ->
                        val outcome = postSigned(CloudConfig(base).configUrl, p.deviceId, cfg.toByteArray())
                        noteOutcome(outcome)
                        config = configPushStep(
                            config, cfg, outcome, ingest.authFailed, SystemClock.elapsedRealtime(),
                            clear = { settings.clearPendingTempConfig() },
                            warn = { msg, e -> Log.w(TAG, msg, e) },
                        )
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
                // Decide (ingestStep), apply the effects, and only then commit the state: a step whose
                // outbox update failed before its delete is retried from the state before it.
                val run = ledger.applyStep(
                    ingest, lastLog, rows, outcome, seq, SystemClock.elapsedRealtime(), System.currentTimeMillis(),
                )
                ingest = run.state
                lastLog = run.lastLog
                if (run.took && outcome.result == PostResult.Ok) {
                    val now = System.currentTimeMillis()
                    lastUploadMs = now
                    uploadRate.record(now, wire.size)
                }
                val remaining = db.outbox().count()
                if (remaining == 0) draining = false
                publishQueue(remaining, ingest)
                if (run.delayMs > 0) delay(run.delayMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                delay(ingest.backoffMs)
                ingest = ingest.copy(backoffMs = nextBackoffMs(ingest.backoffMs))
            }
        }
    }

    /**
     * The re-sync loop (DATA-19): the [ResyncSender] sends one page of local history per pass, as a
     * signed `batch_seq = -1` ingest batch, while the live queue is caught up ([shouldResync]: fresh data
     * first). Every response goes through the shared outcome observer ([noteOutcome]). It never deletes
     * a Room sample or an outbox row. While the history import is not yet queued (a store failure when it
     * was asked for), each pass retries it.
     */
    private suspend fun resyncLoop() {
        while (true) {
            try {
                val p = cachedSettings
                val base = p?.apiBaseUrl
                val deviceId = p?.deviceId
                if (p == null || !p.cloudEnabled || !p.enrolled || deviceId == null || base == null) {
                    delay(RESYNC_IDLE_MS)
                    continue
                }
                if (!p.importDone) {
                    try {
                        queueImportIfDue()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logImportFailure(e)
                    }
                }
                val depth = if (depthKnown) _status.value.outboxDepth else Int.MAX_VALUE
                val wait = resync.pass(
                    roster = p.roster ?: DEFAULT_ROSTER,
                    withGps = p.gpsEnabled ?: p.cloudEnabled,
                    liveDepth = depth,
                    online = conn.online.value,
                ) { body ->
                    val outcome = postSigned(CloudConfig(base).ingestUrl, deviceId, body)
                    noteOutcome(outcome)
                    outcome
                }
                if (wait > 0) delay(wait)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                delay(RESYNC_IDLE_MS)   // the sender handles its own failures; this is a last guard
            }
        }
    }
}
