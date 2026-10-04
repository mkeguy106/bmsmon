package dev.joely.bmsmon.ui.settings

import dev.joely.bmsmon.cloud.ClockBlame
import dev.joely.bmsmon.cloud.ResyncSummary
import dev.joely.bmsmon.cloud.SIGNING_OFFSET_CAP_MS
import dev.joely.bmsmon.cloud.UploadHold
import dev.joely.bmsmon.cloud.UploadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Cloud sync page's status lines (DATA-17, DATA-19, DATA-20): each one says what to do, or that nothing is lost. */
class CloudStatusLinesTest {
    @Test fun nothingIsShownWhenAllIsWell() {
        assertNull(authLine(UploadStatus()))
        assertNull(clockCorrectionLine(UploadStatus()))
        assertNull(holdLine(UploadStatus()))
        assertNull(evictedLine(0))
        assertNull(resyncLine(ResyncSummary(), { "T" }, nowMs = 0L))
    }

    // DATA-17: the phone restored or moved without its Keystore key can only be fixed by re-enrolling.
    @Test fun aMissingKeyAsksForReEnrollmentAndOutranksEverything() {
        val line = authLine(
            UploadStatus(keyMissing = true, authFailed = true, authSkewMs = 600_000, clockBlame = ClockBlame.SERVER),
        )!!
        assertTrue(line.startsWith("Re-enroll required"))
        assertTrue(line.contains("buffered"))
    }

    @Test fun aPlainAuthFailureBlamesARevokedDeviceNeverTheClock() {
        val line = authLine(UploadStatus(authFailed = true))!!
        assertTrue(line.contains("revoked"))
        assertFalse(line.contains("clock"))
    }

    // DATA-20: a reject the independent clock pins on the server names the server and its direction.
    @Test fun aServerClockIsNamedWithItsDirection() {
        val ahead = authLine(UploadStatus(authFailed = true, authSkewMs = 585_000, clockBlame = ClockBlame.SERVER))!!
        assertTrue(ahead, ahead.startsWith("Server clock off by 585 s (ahead of this phone) — compensating"))
        val behind = authLine(UploadStatus(authFailed = true, authSkewMs = -90_000, clockBlame = ClockBlame.SERVER))!!
        assertTrue(behind, behind.startsWith("Server clock off by 90 s (behind this phone) — compensating"))
    }

    @Test fun aPhoneClockTheNetworkDisagreesWithIsBlamedOnThePhone() {
        val line = authLine(UploadStatus(authFailed = true, authSkewMs = 585_000, clockBlame = ClockBlame.PHONE))!!
        assertTrue(line, line.startsWith("Phone clock looks off"))
        assertTrue(line, line.contains("585 s behind the server"))
        assertFalse(line, line.contains("compensating"))
    }

    @Test fun withNoIndependentClockNeitherClockIsBlamed() {
        for (blame in listOf(ClockBlame.UNKNOWN, null)) {
            val line = authLine(UploadStatus(authFailed = true, authSkewMs = -120_000, clockBlame = blame))!!
            assertTrue(line, line.contains("can't tell which clock is off"))
            assertTrue(line, line.contains("120 s apart"))
            assertFalse(line, line.contains("compensating"))
        }
    }

    // Task 3 re-review: a reject within 30 s of an active correction can read PHONE/UNKNOWN; the
    // correction in force is the truth, so the line follows it, with the correction's own amount.
    @Test fun whileACorrectionIsActiveTheLineFollowsTheCorrectionNotTheBlame() {
        for (blame in listOf(ClockBlame.PHONE, ClockBlame.UNKNOWN, null)) {
            val s = UploadStatus(authFailed = true, authSkewMs = 600_000, signingOffsetMs = 585_000, clockBlame = blame)
            val line = authLine(s)!!
            assertTrue(line, line.startsWith("Server clock off by 585 s (ahead of this phone) — compensating"))
            assertFalse(line, line.contains("looks off"))
            assertFalse(line, line.contains("can't tell"))
            // One line on the page, not the same words twice.
            assertNull(clockCorrectionLine(s))
        }
    }

    @Test fun anActiveCorrectionWithWorkingUploadsIsShownAsInformation() {
        assertEquals(
            "Server clock off by 585 s (ahead of this phone) — compensating. Uploads are signed for the server's time.",
            clockCorrectionLine(UploadStatus(signingOffsetMs = 585_000)),
        )
        assertTrue(clockCorrectionLine(UploadStatus(signingOffsetMs = -30_500))!!.contains("30 s (behind this phone)"))
        // A revoked device under a correction is still a revoked device; the correction stays visible.
        val revoked = UploadStatus(authFailed = true, signingOffsetMs = 585_000)
        assertTrue(authLine(revoked)!!.contains("revoked"))
        assertTrue(clockCorrectionLine(revoked)!!.contains("585 s"))
    }

    // Task 3: the skew and the correction are clamped to exactly an hour, which means "an hour or more".
    @Test fun aSkewAtTheClampReadsOverAnHourNeverThirtySixHundredSeconds() {
        val cap = SIGNING_OFFSET_CAP_MS
        val lines = listOf(
            authLine(UploadStatus(authFailed = true, authSkewMs = cap, clockBlame = ClockBlame.SERVER))!!,
            authLine(UploadStatus(authFailed = true, authSkewMs = -cap, clockBlame = ClockBlame.PHONE))!!,
            authLine(UploadStatus(authFailed = true, authSkewMs = cap, clockBlame = ClockBlame.UNKNOWN))!!,
            authLine(UploadStatus(authFailed = true, authSkewMs = cap, signingOffsetMs = -cap))!!,
            clockCorrectionLine(UploadStatus(signingOffsetMs = cap))!!,
        )
        for (line in lines) {
            assertTrue(line, line.contains("over an hour"))
            assertFalse(line, line.contains("3600"))
        }
        // Just under the clamp is still a number of seconds (floored, so it can never print 3600).
        assertTrue(clockCorrectionLine(UploadStatus(signingOffsetMs = cap - 1))!!.contains("3599 s"))
    }

    @Test fun eachHoldSaysThatSamplesAreBuffered() {
        assertTrue(holdLine(UploadStatus(hold = UploadHold.SERVER_REJECTING))!!.contains("buffered"))
        assertTrue(holdLine(UploadStatus(hold = UploadHold.SERVER_FAULTING))!!.contains("buffered"))
    }

    // Task 5 review: the hold is display-only and can lag; a sign-in problem is the current truth.
    @Test fun aSignInProblemOutranksTheHold() {
        assertNull(holdLine(UploadStatus(hold = UploadHold.SERVER_FAULTING, authFailed = true)))
        assertNull(holdLine(UploadStatus(hold = UploadHold.SERVER_REJECTING, keyMissing = true)))
    }

    @Test fun evictionsAreCountedAndPromiseTheResend() {
        assertEquals(
            "1 sample was dropped from the full upload queue (all time); dropped samples are re-sent from " +
                "this phone's 14-day history.",
            evictedLine(1),
        )
        assertTrue(evictedLine(1_200)!!.startsWith("1200 samples were dropped"))
    }

    // Task 7: "import done" now only means "queued"; the history line comes from the re-sync summary.
    @Test fun reSyncShowsWhereItResumesButNeverAnAncientDate() {
        val now = 20L * 86_400_000L
        assertEquals("Re-sending local history from T…", resyncLine(ResyncSummary(pending = 1, fromMs = now - 3_600_000L), { "T" }, now))
        // The import window starts at ts 0: no "1970".
        assertEquals("Re-sending local history…", resyncLine(ResyncSummary(pending = 1, fromMs = 0L), { "T" }, now))
        assertEquals("Re-sending local history…", resyncLine(ResyncSummary(pending = 1, fromMs = null), { "T" }, now))
        assertTrue(resyncLine(ResyncSummary(parked = 2), { "T" }, now)!!.contains("every 6 h"))
        assertEquals(
            "Re-sending local history from T… Some samples the server could not store are retried every 6 h.",
            resyncLine(ResyncSummary(pending = 1, parked = 1, fromMs = now), { "T" }, now),
        )
    }
}
