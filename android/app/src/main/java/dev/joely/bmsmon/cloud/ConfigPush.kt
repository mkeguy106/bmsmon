package dev.joely.bmsmon.cloud

import kotlinx.coroutines.CancellationException

/** The config push's own breaker and retry gate (memory only: a restart re-arms the breaker). */
internal data class ConfigPushState(
    /** Permanent rejects dropped since the last 2xx: the config stream's own poison breaker ([decideUpload]). */
    val poisonSkips: Int = 0,
    val backoffMs: Long = INITIAL_BACKOFF_MS,
    /** SystemClock.elapsedRealtime() before which the push waits: monotonic, so a wall-clock step can't strand it. */
    val retryAtMs: Long = 0L,
    /** The config the breaker is holding, logged once rather than on every pass. */
    val held: String? = null,
)

/**
 * One config-push response, applied. A 2xx, or the breaker's one permanent-reject drop (WEB-6b), clears
 * the pending config through [clear]. The breaker's count is committed only once that clear succeeded,
 * so a failed clear never spends the drop: the config stays pending and is retried on the push's own
 * clock. Anything else keeps the config and backs off on that clock (1 s doubling to 60 s, Retry-After a
 * floor), so a held config never throttles sample uploads. [authFailed] is the ingest stream's badge,
 * carried through [decideUpload] unchanged. Log lines come after the clear they describe.
 */
internal suspend fun configPushStep(
    s: ConfigPushState,
    cfg: String,
    o: PostOutcome,
    authFailed: Boolean,
    nowElapsedMs: Long,
    clear: suspend () -> Unit,
    warn: (String, Throwable?) -> Unit,
): ConfigPushState {
    val d = decideUpload(o.result, s.poisonSkips, authFailed)
    val backedOff = s.copy(
        retryAtMs = nowElapsedMs + retryDelayMs(s.backoffMs, o.retryAfterMs),
        backoffMs = nextBackoffMs(s.backoffMs),
    )
    return when (d.step) {
        BatchStep.DELETE_ACCEPTED, BatchStep.DELETE_POISON -> {
            try {
                clear()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                warn("config: could not clear the sent temp-config push — keeping it pending and retrying", e)
                return backedOff
            }
            if (d.step == BatchStep.DELETE_POISON) {
                warn("config: server permanently rejected the temp-config push — dropped it (re-enqueued on the next threshold change)", null)
            }
            s.copy(poisonSkips = d.poisonSkipsSinceOk, backoffMs = INITIAL_BACKOFF_MS)
        }
        BatchStep.BACK_OFF, BatchStep.BACK_OFF_AUTH -> {
            val held = if (o.result == PostResult.Poison && s.held != cfg) {
                warn("config: rejected again with no 2xx since the last drop — keeping it pending (poison breaker open)", null)
                cfg
            } else {
                s.held
            }
            backedOff.copy(poisonSkips = d.poisonSkipsSinceOk, held = held)
        }
    }
}
