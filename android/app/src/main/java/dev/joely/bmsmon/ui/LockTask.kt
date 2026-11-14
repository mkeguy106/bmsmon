package dev.joely.bmsmon.ui

/** Mirrors android.app.ActivityManager.LOCK_TASK_MODE_NONE, kept local so [shouldRepin] stays JVM-testable. */
const val LOCK_TASK_MODE_NONE = 0

/**
 * Whether the app should ask to be pinned again as it comes back to the foreground. Locked mode
 * means "pinned", but pinning can end without the app's `locked` setting changing: unpinning for
 * an install drops this phone to the lock screen, and the system's swipe-up-and-hold gesture
 * unpins it outright. An activity that merely resumes after that would otherwise stay unpinned
 * until it was recreated or locked mode was toggled. Only when nothing is pinned, so a resume
 * while already pinned (or pinned as a device-owner kiosk) never re-prompts. Never while an agent
 * session is running: the agent lifted the pin on purpose (see agent/AgentSession.kt).
 */
fun shouldRepin(locked: Boolean, lockTaskModeState: Int, agentActive: Boolean = false): Boolean =
    locked && lockTaskModeState == LOCK_TASK_MODE_NONE && !agentActive

/**
 * Whether a running agent session should lift the pin now: only a pin that locked mode set, and
 * only while something is pinned. A pin set by hand with locked mode off is left alone, because
 * [shouldRepin] would never restore it.
 */
fun shouldUnpinForAgent(locked: Boolean, lockTaskModeState: Int, agentActive: Boolean): Boolean =
    locked && agentActive && lockTaskModeState != LOCK_TASK_MODE_NONE
