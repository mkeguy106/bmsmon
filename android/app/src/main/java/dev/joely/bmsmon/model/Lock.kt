package dev.joely.bmsmon.model

/** How long the lock/unlock icon must be held (ms) before the action commits. */
const val LOCK_HOLD_MS = 1500L

/** Progress (0f..1f) of a press-and-hold given how long it has been held. */
fun lockHoldFraction(elapsedMs: Long): Float =
    (elapsedMs.toFloat() / LOCK_HOLD_MS).coerceIn(0f, 1f)

/** True once the hold has lasted long enough to commit the lock/unlock. */
fun lockHoldComplete(elapsedMs: Long): Boolean = elapsedMs >= LOCK_HOLD_MS
