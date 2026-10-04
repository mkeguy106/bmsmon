package dev.joely.bmsmon.ui.home

import dev.joely.bmsmon.cloud.UploadHold
import dev.joely.bmsmon.cloud.UploadStatus

/** How urgent the stage's cloud badge is; the composable maps it to a readable color (UI-21). */
enum class BadgeTone { CRITICAL, WARN, GOOD, MUTED }

/**
 * The stage's cloud badge, most urgent state first. The words carry the state on their own, color
 * only reinforces it. "Synced" is claimed only when nothing is held, rejected, queued or waiting for
 * a re-send from local history (DATA-19): a sign-in problem outranks a hold, which is display-only
 * and can lag (DATA-17, DATA-20, DATA-22).
 */
internal fun uploadBadge(s: UploadStatus): Pair<String, BadgeTone> = when {
    s.keyMissing -> "↑ re-enroll" to BadgeTone.CRITICAL
    s.authFailed && s.authSkewMs != null -> "↑ clock skew" to BadgeTone.CRITICAL
    s.authFailed -> "↑ auth failed" to BadgeTone.CRITICAL
    s.hold != UploadHold.NONE -> "↑ held · ${s.outboxDepth}" to BadgeTone.WARN
    s.kbps > 0.05 -> "↑ %.1f KB/s".format(s.kbps) to BadgeTone.GOOD
    s.outboxDepth > 0 -> "↑ ${s.outboxDepth} queued" to BadgeTone.WARN
    s.resync.pending > 0 -> "↑ re-sending" to BadgeTone.WARN
    s.resync.parked > 0 -> "↑ retry later" to BadgeTone.WARN
    s.lastUploadMs > 0L -> "↑ synced" to BadgeTone.MUTED
    else -> "↑ idle" to BadgeTone.MUTED
}
