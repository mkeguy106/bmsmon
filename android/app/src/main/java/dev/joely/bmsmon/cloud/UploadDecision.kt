package dev.joely.bmsmon.cloud

import okhttp3.OkHttpClient

/** What the uploader does with the batch (or import page, or config push) it just POSTed. */
internal enum class BatchStep {
    /** 2xx — the server has the rows: delete them, reset backoff. */
    DELETE_ACCEPTED,
    /** First app-level permanent reject since the last 2xx — delete (skip) the batch so it can't head-of-line block. */
    DELETE_POISON,
    /** Keep the rows and back off: network/3xx/5xx/edge errors, or a repeat app reject (breaker open). */
    BACK_OFF,
    /** 401/403 — keep the rows, back off, surface "auth failed". */
    BACK_OFF_AUTH,
}

internal data class UploadDecision(
    val step: BatchStep,
    /** Poison batches deleted since the last 2xx — the breaker state to carry into the next call. */
    val poisonSkipsSinceOk: Int,
    /** The uploader's "auth failed" badge after this response. */
    val authFailed: Boolean,
)

/**
 * The uploader's per-response decision (DATA-14, contract C2) — pure, so the rule that decides
 * whether user data is DELETED is JVM-tested instead of buried in the loop.
 *
 * Poison circuit breaker: genuine poison is a rare single batch, so only the FIRST Poison after a
 * 2xx is skipped. A second Poison with no 2xx in between means the rejection is systematic (a
 * server bug, a schema change) and deleting batch after batch is exactly what must not happen —
 * that response is held and backed off like a Transient. Transient/AuthFailed responses in between
 * do NOT re-arm the skip: they prove nothing about whether the server would accept the rows. The
 * state lives in the loop's memory, so a process restart re-arms one skip per stream.
 *
 * A [PostResult.ServerFault] is not a reject of the rows: it backs off exactly like a Transient,
 * neither spending nor re-arming the poison skip. Isolating a sample that crashes the server is
 * [stepHeadFault]'s job, on the ingest stream only.
 */
internal fun decideUpload(result: PostResult, poisonSkipsSinceOk: Int, authFailed: Boolean): UploadDecision =
    when (result) {
        PostResult.Ok -> UploadDecision(BatchStep.DELETE_ACCEPTED, poisonSkipsSinceOk = 0, authFailed = false)
        PostResult.Poison ->
            // A 4xx from the app means the JWT was accepted, so the auth badge clears either way.
            if (poisonSkipsSinceOk == 0) UploadDecision(BatchStep.DELETE_POISON, poisonSkipsSinceOk = 1, authFailed = false)
            else UploadDecision(BatchStep.BACK_OFF, poisonSkipsSinceOk, authFailed = false)
        PostResult.AuthFailed -> UploadDecision(BatchStep.BACK_OFF_AUTH, poisonSkipsSinceOk, authFailed = true)
        PostResult.Transient, PostResult.ServerFault -> UploadDecision(BatchStep.BACK_OFF, poisonSkipsSinceOk, authFailed)
    }

/** Marked server faults on the same head, at the current batch size, before the batch is narrowed (DATA-22). */
internal const val FAULT_STREAK = 4

/**
 * ...and the span, first to latest fault of that streak, they must ALSO cover. A burst of crashes is
 * not enough: time is required, so nothing short-lived can ever narrow a batch or skip a row.
 */
internal const val FAULT_MIN_SPAN_MS = 5 * 60_000L

internal enum class HeadFaultAction {
    NONE,
    /** The batch is down to one row and it still faults, first time since a 2xx: delete that one OUTBOX row. */
    SKIP_HEAD_ROW,
}

/**
 * The ingest stream's fault bisection (DATA-22). [headId] is the outbox id the current streak is
 * about; [limit] is the batch size the uploader peeks (never above BATCH); [streak] counts marked
 * faults on that head at the current size, the first of which came at [firstFaultAtMs].
 * [skipsSinceOk] is the one-skip breaker: rows skipped since the last 2xx (a head change keeps it).
 */
internal data class HeadFaultState(
    val headId: Long?,
    val limit: Int,
    val streak: Int,
    val firstFaultAtMs: Long?,
    val skipsSinceOk: Int = 0,
)

/**
 * One ingest response through the fault bisection — pure, so the rule that can delete a sample's
 * outbox row is JVM-tested. A server bug that 500s on one specific sample used to retry that head
 * batch forever while every later sample waited behind it.
 *
 * - A changed [headId] (the last batch was accepted, skipped or evicted) starts a fresh streak but
 *   KEEPS the limit and the breaker, so the bisection carries on past the half that was accepted.
 * - [PostResult.Ok] clears the streak, re-arms the breaker and doubles the limit toward [maxBatch].
 * - [PostResult.ServerFault] extends the streak. Once it holds [FAULT_STREAK] faults spanning
 *   [FAULT_MIN_SPAN_MS] the streak trips, and every trip clears the streak, so the next one needs a
 *   fresh full span. A batch of [sentSize] > 1 is halved (rounded up). A batch of one row is skipped
 *   ([HeadFaultAction.SKIP_HEAD_ROW]) only if no row was skipped since the last 2xx; otherwise it is
 *   held and backed off. Either way the limit stays at 1: the next head goes alone, and only 2xxs
 *   double it back. This is the poison breaker's rule (only the FIRST skip after a 2xx): a genuine
 *   bad row is still isolated, since bisection's clean halves are 2xxs that re-arm the breaker,
 *   while a server that faults on everything costs one sample, then holds until it is fixed. The
 *   state lives in the loop's memory, so a process restart re-arms one skip.
 * - Anything else (Transient, AuthFailed, Poison) leaves the state alone: an outage neither resets
 *   nor advances a streak. [classifyPost] guarantees outages never arrive here as a ServerFault.
 *
 * [nowMs] must be monotonic (elapsedRealtime), so a wall-clock step can't fake the span.
 */
internal fun stepHeadFault(
    s: HeadFaultState,
    headId: Long,
    sentSize: Int,
    result: PostResult,
    nowMs: Long,
    maxBatch: Int,
): Pair<HeadFaultState, HeadFaultAction> {
    val cur = if (headId != s.headId) s.copy(headId = headId, streak = 0, firstFaultAtMs = null) else s
    return when (result) {
        PostResult.Ok -> cur.copy(
            limit = minOf(maxBatch, cur.limit * 2), streak = 0, firstFaultAtMs = null, skipsSinceOk = 0,
        ) to HeadFaultAction.NONE
        PostResult.ServerFault -> {
            val streak = cur.streak + 1
            val first = cur.firstFaultAtMs ?: nowMs
            if (streak < FAULT_STREAK || nowMs - first < FAULT_MIN_SPAN_MS) {
                return cur.copy(streak = streak, firstFaultAtMs = first) to HeadFaultAction.NONE
            }
            val tripped = cur.copy(streak = 0, firstFaultAtMs = null)
            when {
                sentSize > 1 -> tripped.copy(limit = (sentSize + 1) / 2) to HeadFaultAction.NONE
                cur.skipsSinceOk == 0 -> tripped.copy(limit = 1, skipsSinceOk = 1) to HeadFaultAction.SKIP_HEAD_ROW
                else -> tripped.copy(limit = 1) to HeadFaultAction.NONE   // breaker open: hold this row
            }
        }
        PostResult.Transient, PostResult.AuthFailed, PostResult.Poison -> cur to HeadFaultAction.NONE
    }
}

/**
 * HTTP client for every signed upload (ingest, import, config). Redirects are NEVER followed: a 3xx
 * must reach [classifyPost] as Transient. Followed, the POST would become a GET to wherever the 3xx
 * points (a login page, a captive portal), and that 2xx would read as "accepted" and delete the batch.
 */
internal fun uploadHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .followRedirects(false)
    .followSslRedirects(false)
    .build()
