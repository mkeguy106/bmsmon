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
 * True when an independent clock vouches for this phone's: [phoneClockErrorMs] — this phone's wall clock
 * minus network time or a satellite fix's time ([IndependentClock]) — is within [SKEW_ATTRIBUTE_MS].
 * Null (no independent clock) vouches for nothing.
 */
internal fun phoneClockConfirmed(phoneClockErrorMs: Long?): Boolean =
    phoneClockErrorMs != null && phoneClockErrorMs in -SKEW_ATTRIBUTE_MS..SKEW_ATTRIBUTE_MS

/**
 * The correction added to the next token's iat/exp (DATA-20), and nothing else: samples keep this
 * phone's clock. Only the app's own responses steer it, and only ones that carry its clock:
 * - a clock reject ([isClockReject]) sets it to the measured skew, or clears it when the clocks agree
 *   within [SKEW_ATTRIBUTE_MS] (then the correction itself was what the server rejected);
 * - a 2xx re-anchors a correction already in force to the clock that 2xx reports (cleared once the
 *   clocks agree), so a single bad reading cannot pin it; a 2xx never starts one;
 * - anything else (an intermediary, another reject reason, any other result) keeps it.
 *
 * A server running behind and this phone running ahead measure the same skew, and correcting the
 * second would let this phone's future-dated samples into the cloud, where they read as live. So a
 * correction may START — or JUMP by more than the tolerance, which means a clock stepped — only when
 * an independent clock vouches for this phone's ([phoneClockConfirmed]); otherwise it is 0 and uploads
 * hold, as before the correction existed. [phoneClockErrorMs] is read only then. Clearing, and
 * tracking a correction already in force, need no such evidence.
 *
 * Always within ±[SIGNING_OFFSET_CAP_MS]. The server still enforces its own window; this only lets the
 * phone's tokens land inside it after a server clock step. Memory only: a restart costs one more 401.
 */
internal fun nextSigningOffsetMs(prevMs: Long, o: PostOutcome, phoneClockErrorMs: () -> Long?): Long {
    if (!o.fromApi) return prevMs
    val skew = o.skewMs ?: return prevMs
    val proposed = when {
        isClockReject(o) -> attributedSkewMs(skew)
        o.result == PostResult.Ok && prevMs != 0L -> attributedSkewMs(skew)
        else -> return prevMs
    }
    val tracking = prevMs != 0L &&
        abs(proposed - prevMs.coerceIn(-SIGNING_OFFSET_CAP_MS, SIGNING_OFFSET_CAP_MS)) <= SKEW_ATTRIBUTE_MS
    if (proposed == 0L || tracking) return proposed
    return if (phoneClockConfirmed(phoneClockErrorMs())) proposed else 0L
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

/** Which clock a sign-in rejected for its time blames (DATA-20), so the UI can say which one to fix. */
internal enum class ClockBlame {
    /** An independent clock agrees with this phone: the server's clock is off, and tokens are corrected for it. */
    SERVER,

    /** An independent clock disagrees with this phone: this phone's clock looks off. Nothing is corrected; uploads hold. */
    PHONE,

    /** No independent clock to judge by. Nothing is corrected; uploads hold. */
    UNKNOWN,
}

/**
 * The blame for the skew [nextAuthSkewMs] shows, set and cleared exactly when it is: a clock reject
 * outside the tolerance is blamed by the independent clock ([phoneClockErrorMs], read only then).
 */
internal fun nextClockBlame(prev: ClockBlame?, o: PostOutcome, phoneClockErrorMs: () -> Long?): ClockBlame? = when {
    o.result == PostResult.Ok -> null
    o.result == PostResult.AuthFailed && o.fromApi -> {
        val shown = if (isClockReject(o)) o.skewMs?.let(::attributedSkewMs)?.takeIf { it != 0L } else null
        if (shown == null) {
            null
        } else {
            val err = phoneClockErrorMs()
            when {
                err == null -> ClockBlame.UNKNOWN
                phoneClockConfirmed(err) -> ClockBlame.SERVER
                else -> ClockBlame.PHONE
            }
        }
    }
    else -> prev
}

/** The time a token is dated: the send time plus the correction, bounded again where it meets the timestamp. */
internal fun tokenTimeMs(sentAtMs: Long, signingOffsetMs: Long): Long =
    sentAtMs + signingOffsetMs.coerceIn(-SIGNING_OFFSET_CAP_MS, SIGNING_OFFSET_CAP_MS)
