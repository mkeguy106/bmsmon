package dev.joely.bmsmon.cloud

import dev.joely.bmsmon.data.db.OutboxEntity

/** Rows per ingest POST. */
internal const val UPLOAD_BATCH = 200

/** Why uploads are being held for a server-side problem (the Cloud sync page + the stage badge). */
enum class UploadHold {
    NONE,
    /** The poison breaker is open: the app keeps rejecting the head batch (marked 400/413/422). */
    SERVER_REJECTING,
    /** The fault bisection is engaged: the app keeps crashing (marked 5xx) on the head of the queue. */
    SERVER_FAULTING,
}

/** Outbox facts about the batch just POSTed. A row's `enqueuedAt` IS its sample's ts_ms. */
internal data class BatchMeta(
    val firstId: Long,
    val lastId: Long,
    val size: Int,
    val headTsMs: Long,
    val minTsMs: Long,
    val maxTsMs: Long,
)

internal fun batchMeta(rows: List<OutboxEntity>): BatchMeta {
    require(rows.isNotEmpty()) { "an empty batch is never POSTed" }
    return BatchMeta(
        firstId = rows.first().id,
        lastId = rows.last().id,
        size = rows.size,
        headTsMs = rows.first().enqueuedAt,
        minTsMs = rows.minOf { it.enqueuedAt },
        maxTsMs = rows.maxOf { it.enqueuedAt },
    )
}

/** What the loop must do to the outbox after a response. Applied together and in order, never split by a stop. */
internal sealed interface OutboxEffect {
    /** Delete every outbox row with id <= [id]: the batch was accepted, or skipped as poison. */
    data class DeleteThrough(val id: Long) : OutboxEffect
    /** Delete exactly this row (DATA-22 skip); count the rows actually deleted. */
    data class SkipOne(val id: Long) : OutboxEffect
    /** Re-send these samples later from local history, while usage logging keeps it. */
    data class Resync(val window: ResyncWindow) : OutboxEffect
}

/**
 * The ingest stream's whole decision state. Memory only: a restart re-arms both breakers. The clock
 * state (signing correction, shown skew, blame) is not here: the reporter's outcome observer keeps it
 * for every stream.
 */
internal data class IngestLoopState(
    val poisonSkips: Int = 0,
    val authFailed: Boolean = false,
    /** The last signing attempt found no device key and no request has been answered since: re-enroll. */
    val keyMissing: Boolean = false,
    val fault: HeadFaultState = HeadFaultState(headId = null, limit = UPLOAD_BATCH, streak = 0, firstFaultAtMs = null),
    val backoffMs: Long = INITIAL_BACKOFF_MS,
    val hold: UploadHold = UploadHold.NONE,
)

internal data class IngestStepResult(
    val state: IngestLoopState,
    /**
     * Applied in this order. A [OutboxEffect.Resync] comes before the delete it accompanies, so a kill
     * between the two stores fails toward a duplicate send (the server dedups), never a lost re-send.
     */
    val effects: List<OutboxEffect>,
    /** 0 = go straight on to the next batch. */
    val delayMs: Long,
    /** One WARN line (never a payload or token); the loop logs it once per change. */
    val log: String?,
)

/**
 * One ingest response, decided in full: the poison breaker ([decideUpload]), the fault bisection
 * ([stepHeadFault]), the auth / missing-key state, the backoff (Retry-After a floor), the hold shown
 * to the user, and the re-send of anything skipped. Pure, so every path that can delete a sample is
 * JVM-tested; the loop only peeks, POSTs, applies [IngestStepResult.effects] and waits.
 *
 * A response removes rows on exactly three paths: a marked 2xx (the batch is accepted), the poison breaker's
 * one skip since the last 2xx, and [stepHeadFault]'s one-row skip. Nothing an outage can produce — no
 * response, an unmarked response, a marked 503, a 401/403, a missing key, a fault streak short of its
 * span — reaches any of them. Each skip parks its samples for a re-send from local history (while
 * usage logging keeps it). The outbox cap's eviction of the oldest rows is the loop's, not a response's.
 *
 * [nowElapsedMs] (monotonic) drives the fault span; [nowWallMs] dates a parked re-send.
 */
internal fun ingestStep(
    s: IngestLoopState,
    b: BatchMeta,
    o: PostOutcome,
    nowElapsedMs: Long,
    nowWallMs: Long,
): IngestStepResult {
    val r = o.result
    val (fault, faultAction) = stepHeadFault(s.fault, b.firstId, b.size, r, nowElapsedMs, UPLOAD_BATCH, tailId = b.lastId)
    val d = decideUpload(r, s.poisonSkips, s.authFailed)
    val next = s.copy(
        poisonSkips = d.poisonSkipsSinceOk,
        authFailed = d.authFailed,
        keyMissing = when {
            r == PostResult.KeyMissing -> true
            // Any response means a request was signed, so a key exists again (a re-enroll). No
            // response (a network error, or a Keystore hiccup) says nothing about the key.
            r == PostResult.Ok || o.code != null -> false
            else -> s.keyMissing
        },
        fault = fault,
    )
    val parkUntil = nowWallMs + RESYNC_PARK_MS
    return when (d.step) {
        BatchStep.DELETE_ACCEPTED -> IngestStepResult(
            next.copy(backoffMs = INITIAL_BACKOFF_MS, hold = UploadHold.NONE),
            listOf(OutboxEffect.DeleteThrough(b.lastId)),
            delayMs = 0L,
            log = null,
        )
        BatchStep.DELETE_POISON -> IngestStepResult(
            next.copy(backoffMs = INITIAL_BACKOFF_MS, hold = UploadHold.NONE),
            listOf(
                OutboxEffect.Resync(ResyncWindow(b.minTsMs, b.maxTsMs, notBeforeMs = parkUntil)),
                OutboxEffect.DeleteThrough(b.lastId),
            ),
            delayMs = 0L,
            log = "upload: server permanently rejected ${b.size} rows (outbox ids ${b.firstId}..${b.lastId}) — " +
                "skipping past them; they are re-sent later from local history, while usage logging keeps it, " +
                "and another reject before any 2xx is held",
        )
        BatchStep.BACK_OFF_AUTH -> IngestStepResult(
            next.copy(backoffMs = nextBackoffMs(s.backoffMs)),
            emptyList(),
            delayMs = retryDelayMs(s.backoffMs, o.retryAfterMs),
            log = if (r == PostResult.KeyMissing) {
                "upload: this phone has no device key — holding every sample until it is re-enrolled"
            } else {
                null
            },
        )
        BatchStep.BACK_OFF -> if (faultAction == HeadFaultAction.SKIP_HEAD_ROW) {
            IngestStepResult(
                next.copy(backoffMs = INITIAL_BACKOFF_MS, hold = UploadHold.SERVER_FAULTING),
                listOf(
                    OutboxEffect.Resync(ResyncWindow(b.headTsMs, b.headTsMs, notBeforeMs = parkUntil)),
                    OutboxEffect.SkipOne(b.firstId),
                ),
                delayMs = 0L,
                log = "upload: server keeps faulting on one sample — skipping outbox id=${b.firstId}; " +
                    "it is re-sent later from local history, while usage logging keeps it",
            )
        } else {
            val hold = when {
                r == PostResult.Poison -> UploadHold.SERVER_REJECTING
                r == PostResult.ServerFault && (fault.limit < UPLOAD_BATCH || fault.skipsSinceOk > 0) ->
                    UploadHold.SERVER_FAULTING
                else -> s.hold
            }
            // A ServerFault always extends the streak unless the streak tripped, which clears it.
            val log = when {
                r == PostResult.Poison ->
                    "upload: batch rejected again with no 2xx since the last skip — holding it (poison breaker open)"
                r == PostResult.ServerFault && fault.streak == 0 && fault.limit < b.size ->
                    "upload: server faults on outbox id ${b.firstId} for $FAULT_STREAK+ tries over " +
                        "${FAULT_MIN_SPAN_MS / 60_000}+ min — narrowing the batch to ${fault.limit} rows"
                r == PostResult.ServerFault && fault.streak == 0 ->
                    "upload: server still faulting on outbox id=${b.firstId} with a sample already skipped " +
                        "since the last 2xx — holding it (fault breaker open)"
                else -> null
            }
            IngestStepResult(
                next.copy(backoffMs = nextBackoffMs(s.backoffMs), hold = hold),
                emptyList(),
                delayMs = retryDelayMs(s.backoffMs, o.retryAfterMs),
                log = log,
            )
        }
    }
}
