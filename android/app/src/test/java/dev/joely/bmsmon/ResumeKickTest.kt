package dev.joely.bmsmon

import dev.joely.bmsmon.ble.RESUME_KICK_MIN_INTERVAL_MS
import dev.joely.bmsmon.ble.resumeKickDue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** BLE-16: an app resume resets every backoff at most once per five minutes. */
class ResumeKickTest {

    @Test fun theFirstResumeKicks() = assertTrue(resumeKickDue(lastAt = null, now = 0L))

    @Test fun aResumeWithinFiveMinutesDoesNot() {
        assertFalse(resumeKickDue(lastAt = 1_000L, now = 1_000L))
        assertFalse(resumeKickDue(lastAt = 1_000L, now = 1_000L + RESUME_KICK_MIN_INTERVAL_MS - 1))
    }

    @Test fun fiveMinutesLaterItKicksAgain() =
        assertTrue(resumeKickDue(lastAt = 1_000L, now = 1_000L + RESUME_KICK_MIN_INTERVAL_MS))
}
