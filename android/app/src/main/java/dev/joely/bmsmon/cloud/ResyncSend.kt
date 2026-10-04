package dev.joely.bmsmon.cloud

import dev.joely.bmsmon.data.FailureLogThrottle
import dev.joely.bmsmon.data.db.SampleEntity
import dev.joely.bmsmon.model.Roster
import dev.joely.bmsmon.model.batteryAt
import kotlinx.coroutines.CancellationException

/*
 * The re-sender (DATA-19). Samples the upload loop skipped or evicted are recorded as re-sync windows
 * (Resync.kt, OutboxLedger); this sends them again from the phone's local Room history, GPS included,
 * one bounded keyset page per POST, the cursor persisted in the window between pages. The one-shot
 * history import is just another window ([importWindow]), so it yields to live data like any re-send.
 *
 * It never deletes anything, neither a Room sample nor an outbox row. Its only state changes are a
 * window's cursor advancing, a window completing, and a span parked for a later retry.
 */

/** Rows per re-sync POST (the old import page). */
internal const val RESYNC_PAGE = 500

/** Pause between accepted re-sync pages: below the live path, like the import always was. */
internal const val RESYNC_PACE_MS = 750L

/** How often an idle (or paused) re-sync loop looks for work. */
internal const val RESYNC_IDLE_MS = 5_000L

/** A GPS fix as Room stores it on every sample while it is cached. */
internal data class GpsKey(val lat: Double, val lon: Double, val accuracyM: Float?)

/**
 * Wire JSON for a page of local samples: the import's mapping, now with GPS. Alias, group and name come
 * from the current roster, as the import always did. A fix is sent only when it differs from the last
 * one sent for that pack: the live path sends each fix once per pack (isNewFixForPack, keyed on the
 * fix's time), Room keeps no fix time, and byte-identical full-precision coordinates + accuracy on a
 * pack's consecutive samples are the same cached fix. The coordinates are rounded by
 * [CloudJson.sampleJson], exactly as the live path's are. With [withGps] off (the user's "Send GPS
 * location"), no coordinates are sent, as live. Motion, cells and the charge ETA are not stored locally
 * and are omitted. Returns the payloads and the updated last-sent fixes; keep the latter only once the
 * page is accepted.
 */
internal fun resyncPayloads(
    page: List<SampleEntity>,
    roster: Roster,
    lastSent: Map<String, GpsKey>,
    withGps: Boolean = true,
): Pair<List<String>, Map<String, GpsKey>> {
    val sent = lastSent.toMutableMap()
    val rows = page.map { e ->
        val bat = roster.batteryAt(e.address)
        val lat = e.lat
        val lon = e.lon
        val fix = if (withGps && lat != null && lon != null) GpsKey(lat, lon, e.gpsAccuracyM) else null
        val sendFix = fix?.takeIf { sent[e.address] != it }
        if (sendFix != null) sent[e.address] = sendFix
        CloudJson.sampleJson(
            e.tsMs, e.address, bat?.advertisedName, bat?.alias, bat?.groupId,
            e.state, e.soc, e.currentA, e.powerW, e.voltageV, e.tempC, e.mosfetTempC,
            e.soh, e.fullChargeAh, e.remainingAh, e.cycles,
            e.cellMinV, e.cellMaxV, e.regen, e.linkEvent,
            lat = sendFix?.lat, lon = sendFix?.lon, gpsAccuracyM = sendFix?.accuracyM,
        )
    }
    return rows to sent
}

/**
 * The page just sent. [headPos] is its first row's position in the window's walk (0, 1, 2…), the
 * bisection's ordering key: Room ids are not time-ordered after a CSV backfill, positions are.
 */
internal data class PageMeta(
    val headPos: Long,
    val size: Int,
    val firstTsMs: Long,
    val firstId: Long,
    val lastTsMs: Long,
    val lastId: Long,
)

internal fun freshResyncFault(skipsSinceOk: Int = 0) =
    HeadFaultState(headId = null, limit = RESYNC_PAGE, streak = 0, firstFaultAtMs = null, skipsSinceOk = skipsSinceOk)

/** The re-send stream's own breakers and backoff, like the ingest stream's. Memory only: a restart re-arms them. */
internal data class ResyncStepState(
    val poisonSkips: Int = 0,
    val fault: HeadFaultState = freshResyncFault(),
    val backoffMs: Long = INITIAL_BACKOFF_MS,
)

internal sealed interface ResyncAction {
    /** Accepted: move the window's cursor past ([afterTs], [afterId]). */
    data class Advance(val afterTs: Long, val afterId: Long) : ResyncAction

    /** The server permanently rejected the page: step past it and park its span for a later retry ([parkResyncPage]). */
    data class ParkPage(val firstTsMs: Long, val lastTsMs: Long, val lastId: Long) : ResyncAction

    /** The server keeps crashing on this one sample: step past it and park it for a later retry ([parkResyncRow]). */
    data class ParkRow(val tsMs: Long, val id: Long) : ResyncAction

    /** Keep the cursor and send the same page again after the delay. */
    object Hold : ResyncAction
}

/** One re-send response, decided. [log]: one WARN line (never a payload), logged once per change ([heldLog]). */
internal data class ResyncStep(val state: ResyncStepState, val action: ResyncAction, val delayMs: Long, val log: String? = null)

/**
 * One re-sync response, decided with the ingest stream's own rules: [decideUpload]'s poison breaker and
 * [stepHeadFault]'s bisection, so a deterministic server fault can no longer hold every later page
 * forever (it did on the old import). A marked 2xx advances. The first permanent reject since a 2xx
 * parks the page, as the ingest stream parks its rejected batch; another before any 2xx is held. A row
 * the bisection isolates is parked. Everything else (a network error, an unmarked or 503 answer, a
 * 401/403, a missing key, a fault short of a trip) holds and backs off, Retry-After a floor.
 */
internal fun resyncStep(s: ResyncStepState, page: PageMeta, o: PostOutcome, nowElapsedMs: Long): ResyncStep {
    val r = o.result
    val (fault, faultAction) = stepHeadFault(
        s.fault, page.headPos, page.size, r, nowElapsedMs, RESYNC_PAGE,
        tailId = page.headPos + page.size - 1,
    )
    val d = decideUpload(r, s.poisonSkips, authFailed = false)
    val next = s.copy(poisonSkips = d.poisonSkipsSinceOk, fault = fault)
    val parkHours = RESYNC_PARK_MS / 3_600_000L
    return when (d.step) {
        BatchStep.DELETE_ACCEPTED -> ResyncStep(
            next.copy(backoffMs = INITIAL_BACKOFF_MS),
            ResyncAction.Advance(page.lastTsMs, page.lastId),
            RESYNC_PACE_MS,
        )
        BatchStep.DELETE_POISON -> ResyncStep(
            next.copy(backoffMs = INITIAL_BACKOFF_MS),
            ResyncAction.ParkPage(page.firstTsMs, page.lastTsMs, page.lastId),
            RESYNC_PACE_MS,
            log = "re-sync: server permanently rejected ${page.size} local samples (from Room id ${page.firstId}) — " +
                "parked for a retry in $parkHours h; another reject before any 2xx is held",
        )
        BatchStep.BACK_OFF, BatchStep.BACK_OFF_AUTH -> if (faultAction == HeadFaultAction.SKIP_HEAD_ROW) {
            ResyncStep(
                next.copy(backoffMs = INITIAL_BACKOFF_MS),
                ResyncAction.ParkRow(page.firstTsMs, page.firstId),
                RESYNC_PACE_MS,
                log = "re-sync: server keeps faulting on one local sample (Room id ${page.firstId}) — " +
                    "parked for a retry in $parkHours h",
            )
        } else {
            // A ServerFault always extends the streak unless the streak tripped, which clears it.
            val log = when {
                r == PostResult.Poison ->
                    "re-sync: page rejected again with no 2xx since the last park — holding it (poison breaker open)"
                r == PostResult.ServerFault && fault.streak == 0 && fault.limit < page.size ->
                    "re-sync: server faults on Room id ${page.firstId} for $FAULT_STREAK+ tries over " +
                        "${FAULT_MIN_SPAN_MS / 60_000}+ min — narrowing the page to ${fault.limit} rows"
                r == PostResult.ServerFault && fault.streak == 0 ->
                    "re-sync: server still faulting on Room id ${page.firstId} with a sample already parked " +
                        "since the last 2xx — holding it (fault breaker open)"
                else -> null
            }
            ResyncStep(next.copy(backoffMs = nextBackoffMs(s.backoffMs)), ResyncAction.Hold, retryDelayMs(s.backoffMs, o.retryAfterMs), log)
        }
    }
}

/** Re-sync runs only online, with a window due, while the live queue is caught up: fresh data first. */
internal fun shouldResync(outboxDepth: Int, online: Boolean, hasDueWindow: Boolean): Boolean =
    online && hasDueWindow && outboxDepth < MIN_BATCH

/**
 * The re-send loop's body, one page per [pass], against the re-sync windows ([readWindows] /
 * [mutateWindows]: the OutboxLedger's, which persist before they assign and throw on a store error) and
 * local history ([readPage]: one bounded keyset page, see RESYNC_PAGE_SQL). Pure of Android, so the
 * loop's rules are JVM-tested:
 * - a failing store or Room read pauses the re-sender (logged at most once a minute), never throws;
 * - the step's state is committed only once its window change persisted: a page whose advance or park
 *   didn't save is sent again from the previous state (the server dedups), and a breaker never spends a
 *   park that didn't happen;
 * - a window that changed underneath (a merge moved its cursor or its span) starts a fresh walk.
 *
 * One consumer (the reporter's re-sync loop); not thread-safe.
 */
internal class ResyncSender(
    private val readWindows: suspend () -> ResyncState,
    private val mutateWindows: suspend ((ResyncState) -> ResyncState) -> ResyncState,
    private val readPage: suspend (toMs: Long, afterTs: Long, afterId: Long, limit: Int) -> List<SampleEntity>,
    private val warn: (String, Throwable?) -> Unit,
    private val wallNow: () -> Long = System::currentTimeMillis,
    /** Monotonic ms: the fault span and the log throttle. */
    private val elapsedNow: () -> Long,
) {
    /**
     * Where the current window's walk stands: the window (cursor stripped), the cursor this sender
     * last left it at, the stream position there, and the fixes sent so far.
     */
    private data class Walk(
        val window: ResyncWindow,
        val cursorTs: Long,
        val afterId: Long,
        val pos: Long,
        val lastSent: Map<String, GpsKey>,
    )

    private var state = ResyncStepState()
    private var walk: Walk? = null
    private var lastLog: String? = null
    private val failures = FailureLogThrottle()

    /**
     * Send at most one page of the first due window, if the live queue ([liveDepth]) is caught up and
     * the phone is [online]. [send] signs and POSTs an ingest body. Returns the delay before the next
     * pass (0 = go straight on).
     */
    suspend fun pass(
        roster: Roster,
        withGps: Boolean,
        liveDepth: Int,
        online: Boolean,
        send: suspend (ByteArray) -> PostOutcome,
    ): Long {
        val due: ResyncWindow
        val w: Walk
        val limit: Int
        val page: List<SampleEntity>
        try {
            val found = nextEligibleWindow(readWindows(), wallNow())
            if (!shouldResync(liveDepth, online, hasDueWindow = found != null) || found == null) return RESYNC_IDLE_MS
            due = found
            w = walkOf(due)
            limit = minOf(RESYNC_PAGE, state.fault.limit)
            page = readPage(due.toMs, due.cursorTs, due.afterId, limit)
            if (page.isEmpty()) {
                mutateWindows { completeResync(it, due) }
                walk = null
                return 0L
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warnThrottled(failures, elapsedNow(), warn, "re-sync: the re-send windows or local history failed — paused, retrying", e)
            return RESYNC_IDLE_MS
        }
        val (rows, sentNext) = resyncPayloads(page, roster, w.lastSent, withGps)
        // seq = -1: stored by the server, never pushed to live dashboards (the import contract).
        val outcome = send(CloudJson.encodeBatch(seq = -1, rows = rows))
        val meta = PageMeta(w.pos, page.size, page.first().tsMs, page.first().id, page.last().tsMs, page.last().id)
        val step = resyncStep(state, meta, outcome, elapsedNow())
        val exhausted = page.size < limit
        val nextWalk = try {
            when (val a = step.action) {
                is ResyncAction.Advance -> {
                    mutateWindows { advanceResync(it, due, a.afterTs, a.afterId, exhausted) }
                    w.copy(cursorTs = a.afterTs, afterId = a.afterId, pos = w.pos + page.size, lastSent = sentNext)
                }
                is ResyncAction.ParkPage -> {
                    // The page's fixes never landed: the next page sends them again.
                    mutateWindows { parkResyncPage(it, due, a.firstTsMs, a.lastTsMs, a.lastId, exhausted, wallNow()) }
                    w.copy(cursorTs = a.lastTsMs, afterId = a.lastId, pos = w.pos + page.size)
                }
                is ResyncAction.ParkRow -> {
                    mutateWindows { parkResyncRow(it, due, a.tsMs, a.id, wallNow()) }
                    w.copy(cursorTs = a.tsMs, afterId = a.id, pos = w.pos + 1)
                }
                ResyncAction.Hold -> w
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warnThrottled(
                failures, elapsedNow(), warn,
                "re-sync: the server answered, but the re-send window could not be updated — the page is sent again", e,
            )
            val delay = retryDelayMs(state.backoffMs, outcome.retryAfterMs)
            state = state.copy(backoffMs = nextBackoffMs(state.backoffMs))
            return delay
        }
        state = step.state
        walk = nextWalk
        val (memory, line) = heldLog(lastLog, step.log, outcome.result)
        lastLog = memory
        line?.let { warn(it, null) }
        return step.delayMs
    }

    /**
     * The walk serving [due]: the current one if [due] is the window it walks and its cursor is where
     * this sender left it; otherwise a fresh one (position 0, no fixes sent, a fresh bisection). The
     * skip breaker carries over, so a server failing on everything still costs one row, then holds.
     */
    private fun walkOf(due: ResyncWindow): Walk {
        val window = due.copy(afterTs = null, afterId = -1L)
        walk?.let { if (it.window == window && it.cursorTs == due.cursorTs && it.afterId == due.afterId) return it }
        state = state.copy(fault = freshResyncFault(state.fault.skipsSinceOk))
        return Walk(window, due.cursorTs, due.afterId, pos = 0L, lastSent = emptyMap()).also { walk = it }
    }
}
