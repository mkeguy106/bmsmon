package dev.joely.bmsmon.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockTaskTest {

    @Test fun repinsOnlyWhenLockedAndNothingIsPinned() {
        assertTrue(shouldRepin(locked = true, lockTaskModeState = LOCK_TASK_MODE_NONE))
    }

    @Test fun neverPinsWhenLockedModeIsOff() {
        assertFalse(shouldRepin(locked = false, lockTaskModeState = LOCK_TASK_MODE_NONE))
    }

    // ActivityManager.LOCK_TASK_MODE_LOCKED = 1 (device-owner kiosk), LOCK_TASK_MODE_PINNED = 2.
    @Test fun aResumeWhileAlreadyPinnedNeverRePrompts() {
        assertFalse(shouldRepin(locked = true, lockTaskModeState = 1))
        assertFalse(shouldRepin(locked = true, lockTaskModeState = 2))
    }

    @Test fun noRepinDuringAgentSession() {
        assertFalse(shouldRepin(locked = true, lockTaskModeState = LOCK_TASK_MODE_NONE, agentActive = true))
    }

    @Test fun unpinsOnlyWhenPinnedAndActive() {
        assertTrue(shouldUnpinForAgent(lockTaskModeState = 2, agentActive = true))
        assertFalse(shouldUnpinForAgent(lockTaskModeState = LOCK_TASK_MODE_NONE, agentActive = true))
        assertFalse(shouldUnpinForAgent(lockTaskModeState = 2, agentActive = false))
    }

    /** The decision is pure; the part only a device can run is that App.kt asks on every
     *  resume. Pin that wiring textually so dropping it can't pass silently. */
    @Test fun theAppAsksOnEveryResume() {
        val src = listOf("src/main/java", "app/src/main/java")
            .map { File(it, "dev/joely/bmsmon/ui/App.kt") }
            .first { it.isFile }
            .readText()
            .replace(Regex("\\s+"), " ")
        assertTrue(src.contains("event != Lifecycle.Event.ON_RESUME"))
        assertTrue(src.contains("if (shouldRepin(lockedNow, mode, agentNow)) startLockTaskCompat(act)"))
        // The agent session lifts the pin and its timer ends the session (agent/AgentSession.kt).
        assertTrue(src.contains("shouldUnpinForAgent("))
        assertTrue(src.contains("AgentSession.expire("))
        assertTrue(src.contains("owner?.lifecycle?.addObserver(observer)"))
    }
}
