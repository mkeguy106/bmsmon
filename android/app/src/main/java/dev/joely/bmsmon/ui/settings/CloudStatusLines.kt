package dev.joely.bmsmon.ui.settings

import dev.joely.bmsmon.cloud.ClockBlame
import dev.joely.bmsmon.cloud.RESYNC_PARK_MS
import dev.joely.bmsmon.cloud.ResyncSummary
import dev.joely.bmsmon.cloud.SIGNING_OFFSET_CAP_MS
import dev.joely.bmsmon.cloud.UploadHold
import dev.joely.bmsmon.cloud.UploadStatus
import dev.joely.bmsmon.data.SAMPLE_RETENTION_DAYS
import dev.joely.bmsmon.data.cutoffMs
import kotlin.math.abs

/*
 * The Cloud sync page's status lines (DATA-17, DATA-19, DATA-20). Each is null while there is
 * nothing to say, so a healthy phone shows no line at all. Pure, so the wording is JVM-tested.
 */

/**
 * A clock difference as the user reads it. The skew and the correction are clamped to exactly
 * [SIGNING_OFFSET_CAP_MS], which means "an hour or more", so the clamp never prints as "3600 s";
 * below it, whole seconds, floored (so nothing under the clamp can round up to it either).
 */
private fun clockGap(ms: Long): String {
    val a = abs(ms.coerceIn(-SIGNING_OFFSET_CAP_MS, SIGNING_OFFSET_CAP_MS))   // bounded before abs (Long.MIN_VALUE)
    return if (a >= SIGNING_OFFSET_CAP_MS) "over an hour" else "${a / 1000} s"
}

/** The correction in force, in the words the status uses for it whether uploads currently pass or not. */
private fun serverClockOff(offsetMs: Long): String =
    "Server clock off by ${clockGap(offsetMs)} (${if (offsetMs > 0) "ahead of" else "behind"} this phone) — compensating."

/** True when [authLine] is already wording a clock correction, so [clockCorrectionLine] stays silent. */
private fun authLineCoversCorrection(s: UploadStatus): Boolean =
    !s.keyMissing && s.authFailed && s.authSkewMs != null && s.signingOffsetMs != 0L

/**
 * Sign-in / key state — null while uploads authenticate fine. Most actionable first: a missing key
 * outranks everything, because nothing else can succeed until the phone is re-enrolled.
 *
 * A sign-in rejected for its time is worded from the correction while one is in force (a reject
 * within the tolerance of an active correction can read PHONE or UNKNOWN, but the correction is
 * what is happening); otherwise from [UploadStatus.clockBlame]. A rejection with no skew attached
 * never mentions a clock.
 */
internal fun authLine(s: UploadStatus): String? {
    val skew = s.authSkewMs
    return when {
        s.keyMissing ->
            "Re-enroll required: this phone has no upload key (it was restored or moved from another phone). " +
                "Samples stay buffered on the phone."
        !s.authFailed -> null
        skew == null ->
            "Upload sign-in rejected: this device may have been revoked on the server. Samples stay buffered on the phone."
        s.signingOffsetMs != 0L ->
            "${serverClockOff(s.signingOffsetMs)} Retrying; samples stay buffered on the phone."
        else -> when (s.clockBlame) {
            ClockBlame.SERVER ->
                "${serverClockOff(skew)} Retrying; samples stay buffered on the phone."
            ClockBlame.PHONE ->
                "Phone clock looks off: it is ${clockGap(skew)} ${if (skew > 0) "behind" else "ahead of"} the server, " +
                    "and network time disagrees with it too. Turn on automatic date and time. " +
                    "Samples stay buffered on the phone."
            ClockBlame.UNKNOWN, null ->
                "Upload sign-in rejected: the server's and this phone's clocks are ${clockGap(skew)} apart, and with " +
                    "no network time to check against the app can't tell which clock is off. " +
                    "Samples stay buffered on the phone."
        }
    }
}

/**
 * The clock correction currently applied to upload tokens, while uploads pass with it — null when
 * there is none, or when [authLine] is already saying the same thing about a rejected sign-in.
 */
internal fun clockCorrectionLine(s: UploadStatus): String? =
    if (s.signingOffsetMs == 0L || authLineCoversCorrection(s)) null
    else "${serverClockOff(s.signingOffsetMs)} Uploads are signed for the server's time."

/**
 * A breaker holding the queue for a server-side problem — null when there is none, and while a
 * sign-in problem is shown instead: the hold is display-only and can lag, the sign-in state cannot.
 */
internal fun holdLine(s: UploadStatus): String? = when {
    s.keyMissing || s.authFailed -> null
    else -> when (s.hold) {
        UploadHold.NONE -> null
        UploadHold.SERVER_REJECTING ->
            "Uploads held: the server keeps rejecting the next batch. Samples stay buffered and send once it accepts them."
        UploadHold.SERVER_FAULTING ->
            "Uploads slowed: the server keeps failing on a sample. Narrowing it down; samples stay buffered."
    }
}

/** All-time outbox evictions at the cap (DATA-19); each evicted span is queued for a re-send from local history. */
internal fun evictedLine(evicted: Long): String? = when {
    evicted <= 0L -> null
    evicted == 1L ->
        "1 sample was dropped from the full upload queue (all time); dropped samples are re-sent from " +
            "this phone's $SAMPLE_RETENTION_DAYS-day history."
    else ->
        "$evicted samples were dropped from the full upload queue (all time); dropped samples are re-sent from " +
            "this phone's $SAMPLE_RETENTION_DAYS-day history."
}

/**
 * Where the re-send of local history stands — the history import included, which is a re-send window
 * like any other (DATA-19). A resume point older than local history (the import starts at 0) shows
 * no date, never "1970".
 */
internal fun resyncLine(r: ResyncSummary, formatTime: (Long) -> String, nowMs: Long): String? {
    val parts = buildList {
        if (r.pending > 0) {
            val from = r.fromMs?.takeIf { it >= cutoffMs(nowMs, SAMPLE_RETENTION_DAYS) }
            add(if (from != null) "Re-sending local history from ${formatTime(from)}…" else "Re-sending local history…")
        }
        if (r.parked > 0) add("Some samples the server could not store are retried every ${RESYNC_PARK_MS / 3_600_000L} h.")
    }
    return parts.joinToString(" ").ifEmpty { null }
}
