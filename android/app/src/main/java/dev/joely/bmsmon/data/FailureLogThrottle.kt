package dev.joely.bmsmon.data

/**
 * Rate limit for a failure log that can repeat as fast as a persistent fault fails: a broken
 * database fails every telemetry op (~100/min), and a full stack trace each time evicts everything
 * else — the motion instrumentation included — from logcat. The first failure of a burst is logged
 * in full; later ones are counted and reported in at most one summary line per [intervalMs]. A
 * burst ends once [intervalMs] passes with no failure, so the next one is logged in full again,
 * carrying the count of any failures the last summary never reported. Pure; [onFailure] takes a
 * monotonic clock reading from the caller. Not thread-safe: one consumer (the writer loop) owns it.
 */
class FailureLogThrottle(private val intervalMs: Long = 60_000L) {

    sealed interface Action {
        /** Log this failure with its stack trace; [unreported] earlier failures never made a line. */
        data class Full(val unreported: Int) : Action

        /** Log one line, no stack: [count] failures, this one included, since the last line. */
        data class Summary(val count: Int) : Action

        /** Log nothing; this failure is counted toward the next line. */
        object Suppress : Action
    }

    private var lastFailureAt: Long? = null
    private var lastLineAt = 0L
    private var unreported = 0

    /** What to log for a failure at [nowMs]. */
    fun onFailure(nowMs: Long): Action {
        val previous = lastFailureAt
        lastFailureAt = nowMs
        if (previous == null || nowMs - previous >= intervalMs) {
            val carried = unreported
            unreported = 0
            lastLineAt = nowMs
            return Action.Full(carried)
        }
        unreported++
        if (nowMs - lastLineAt < intervalMs) return Action.Suppress
        val count = unreported
        unreported = 0
        lastLineAt = nowMs
        return Action.Summary(count)
    }
}

/**
 * A WARN that can repeat as fast as its loop runs, through [throttle] ([FailureLogThrottle]): the first of
 * a burst with its [cause], then at most one counted line per interval carrying the latest cause's text.
 */
internal fun warnThrottled(
    throttle: FailureLogThrottle,
    nowElapsedMs: Long,
    warn: (String, Throwable?) -> Unit,
    what: String,
    cause: Throwable?,
) {
    when (val a = throttle.onFailure(nowElapsedMs)) {
        is FailureLogThrottle.Action.Full ->
            warn(if (a.unreported == 0) what else "$what (${a.unreported} earlier times went unreported)", cause)
        is FailureLogThrottle.Action.Summary ->
            warn("$what (${a.count} times since the last report${cause?.let { "; latest: $it" } ?: ""})", null)
        FailureLogThrottle.Action.Suppress -> Unit
    }
}
