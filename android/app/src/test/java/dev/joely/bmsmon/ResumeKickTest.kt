package dev.joely.bmsmon

import dev.joely.bmsmon.ble.LinkLedger
import dev.joely.bmsmon.ble.RESUME_KICK_MIN_INTERVAL_MS
import dev.joely.bmsmon.ble.profile.BackoffSpec
import dev.joely.bmsmon.ble.resumeKickDue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BLE-16: an app resume retries every pack at most once per five minutes. The stage packs, the
 * ones the user is watching, are not rate-limited: every resume retries them.
 */
class ResumeKickTest {

    @Test fun theFirstResumeKicks() = assertTrue(resumeKickDue(lastAt = null, now = 0L))

    @Test fun aResumeWithinFiveMinutesDoesNot() {
        assertFalse(resumeKickDue(lastAt = 1_000L, now = 1_000L))
        assertFalse(resumeKickDue(lastAt = 1_000L, now = 1_000L + RESUME_KICK_MIN_INTERVAL_MS - 1))
    }

    @Test fun fiveMinutesLaterItKicksAgain() =
        assertTrue(resumeKickDue(lastAt = 1_000L, now = 1_000L + RESUME_KICK_MIN_INTERVAL_MS))

    // The stage is never rate-limited. [resume] mirrors BmsRepository.kickOnResume's two branches
    // (pinned by AttemptWiringTest.everyResumeRetriesTheStagePacks): a due resume kicks every pack,
    // any other kicks only the stage packs.
    @Test fun aSecondResumeWithinFiveMinutesStillRetriesTheStageButNotTheSpare() {
        val spec = BackoffSpec(baseMs = 5_000L, factor = 2, capMs = 120_000L)
        val stage = "C8:47:80:15:67:44"
        val spare = "C8:47:80:15:DB:13"
        val ledger = LinkLedger<String>()
        var lastAt: Long? = null
        fun resume(now: Long) {
            if (resumeKickDue(lastAt, now)) {
                lastAt = now
                ledger.kick()
            } else {
                ledger.kick(setOf(stage))
            }
        }
        fun bothFail(now: Long) = listOf(stage, spare).forEach {
            ledger.connectFailed(it, ledger.beginConnect(it), spec, failThreshold = 3, now = now)
        }

        bothFail(now = 0L)
        resume(now = 1_000L)                          // the first resume retries every pack
        assertTrue(ledger.backoffSnapshot().isEmpty())
        repeat(4) { bothFail(now = 2_000L) }          // both climb the ladder again: 40 s each
        resume(now = 32_000L)                         // half a minute later: inside the five minutes
        assertNull("the stage pack is retried at once", ledger.backoffSnapshot()[stage])
        assertEquals("the spare still waits out its backoff", 42_000L, ledger.backoffSnapshot()[spare])
    }
}
