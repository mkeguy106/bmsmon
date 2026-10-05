package dev.joely.bmsmon.ui

/** Mirrors android.app.ActivityManager.LOCK_TASK_MODE_NONE, kept local so [shouldRepin] stays JVM-testable. */
const val LOCK_TASK_MODE_NONE = 0

/**
 * Whether the app should ask to be pinned again as it comes back to the foreground. Locked mode
 * means "pinned", but pinning can end without the app's `locked` setting changing: unpinning for
 * an install drops this phone to the lock screen, and the system's swipe-up-and-hold gesture
 * unpins it outright. An activity that merely resumes after that would otherwise stay unpinned
 * until it was recreated or locked mode was toggled. Only when nothing is pinned, so a resume
 * while already pinned (or pinned as a device-owner kiosk) never re-prompts.
 */
fun shouldRepin(locked: Boolean, lockTaskModeState: Int): Boolean =
    locked && lockTaskModeState == LOCK_TASK_MODE_NONE
