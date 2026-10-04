package dev.joely.bmsmon.cloud

import kotlin.math.abs

/** A skew under this is noise (latency, `Date`'s whole seconds) — never blamed on the clocks. */
internal const val SKEW_ATTRIBUTE_MS = 30_000L

/** The largest clock correction ever applied to a token, and the largest skew ever reported. */
internal const val SIGNING_OFFSET_CAP_MS = 60 * 60_000L

/** The [AUTH_REASON_HEADER] token for a sign-in rejected because the token's time is outside the server's window. */
internal const val AUTH_REASON_CLOCK_SKEW = "clock_skew"

/**
 * [skewMs] bounded to ±[SIGNING_OFFSET_CAP_MS], or 0 when it is within [SKEW_ATTRIBUTE_MS] (the clocks
 * agree). Bounded before `abs`, which is negative for `Long.MIN_VALUE`.
 */
private fun attributedSkewMs(skewMs: Long): Long {
    val bounded = skewMs.coerceIn(-SIGNING_OFFSET_CAP_MS, SIGNING_OFFSET_CAP_MS)
    return if (abs(bounded) > SKEW_ATTRIBUTE_MS) bounded else 0L
}

/**
 * An APP-marked 401/403 that blames the token's time: the server's [AUTH_REASON_CLOCK_SKEW], or no
 * reason at all (an older server, or a 403), where the skew itself is the evidence. An intermediary's
 * response never counts, whatever it says.
 */
private fun isClockReject(o: PostOutcome): Boolean =
    o.result == PostResult.AuthFailed && o.fromApi && (o.authReason == null || o.authReason == AUTH_REASON_CLOCK_SKEW)

/**
 * The correction added to the next token's iat/exp (DATA-20), and nothing else: samples keep this
 * phone's clock. Only the app's own responses steer it, and only ones that carry its clock:
 * - a clock reject ([isClockReject]) sets it to the measured skew, or clears it when the clocks agree
 *   within [SKEW_ATTRIBUTE_MS] (then the correction itself was what the server rejected);
 * - a 2xx re-anchors a correction already in force to the clock that 2xx reports (cleared once the
 *   clocks agree), so a single bad reading cannot pin it; a 2xx never starts one;
 * - anything else (an intermediary, another reject reason, any other result) keeps it.
 * Always within ±[SIGNING_OFFSET_CAP_MS]. The server still enforces its own window; this only lets the
 * phone's tokens land inside it after a server clock step. Memory only: a restart costs one more 401.
 */
internal fun nextSigningOffsetMs(prevMs: Long, o: PostOutcome): Long {
    if (!o.fromApi) return prevMs
    val skew = o.skewMs ?: return prevMs
    return when {
        isClockReject(o) -> attributedSkewMs(skew)
        o.result == PostResult.Ok && prevMs != 0L -> attributedSkewMs(skew)
        else -> prevMs
    }
}

/**
 * The skew to show the user (bounded like the correction): set by a clock reject outside the
 * tolerance, cleared by a 2xx or by an app reject that names another cause, kept otherwise.
 */
internal fun nextAuthSkewMs(prevMs: Long?, o: PostOutcome): Long? = when {
    o.result == PostResult.Ok -> null
    o.result == PostResult.AuthFailed && o.fromApi ->
        if (isClockReject(o)) o.skewMs?.let(::attributedSkewMs)?.takeIf { it != 0L } else null
    else -> prevMs
}

/** The time a token is dated: the send time plus the correction, bounded again where it meets the timestamp. */
internal fun tokenTimeMs(sentAtMs: Long, signingOffsetMs: Long): Long =
    sentAtMs + signingOffsetMs.coerceIn(-SIGNING_OFFSET_CAP_MS, SIGNING_OFFSET_CAP_MS)
