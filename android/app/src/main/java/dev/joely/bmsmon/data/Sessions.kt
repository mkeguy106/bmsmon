package dev.joely.bmsmon.data

/** A gap larger than this (or a disconnect) between samples for one pack starts a new session. */
const val SESSION_GAP_MS = 10 * 60 * 1000L

/**
 * True when the incoming sample at [nowMs] should open a NEW session for a pack, given the pack's
 * previous sample time ([prevSampleTsMs], null if none) and whether the pack disconnected since
 * that sample ([prevWasDisconnect]). A gap strictly greater than [gapMs], a disconnect, or no
 * prior sample all start a new session.
 */
fun isNewSession(
    prevSampleTsMs: Long?,
    prevWasDisconnect: Boolean,
    nowMs: Long,
    gapMs: Long = SESSION_GAP_MS,
): Boolean {
    if (prevSampleTsMs == null) return true
    if (prevWasDisconnect) return true
    return (nowMs - prevSampleTsMs) > gapMs
}
