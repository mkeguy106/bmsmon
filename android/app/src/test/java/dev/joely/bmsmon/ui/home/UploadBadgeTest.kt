package dev.joely.bmsmon.ui.home

import dev.joely.bmsmon.cloud.ClockBlame
import dev.joely.bmsmon.cloud.ResyncSummary
import dev.joely.bmsmon.cloud.UploadHold
import dev.joely.bmsmon.cloud.UploadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The glanceable badge beside the stage label, most urgent state first; the words carry the state, not only the color. */
class UploadBadgeTest {
    @Test fun aMissingKeyBeatsEverything() {
        assertEquals(
            "↑ re-enroll" to BadgeTone.CRITICAL,
            uploadBadge(
                UploadStatus(
                    keyMissing = true, authFailed = true, authSkewMs = 600_000, hold = UploadHold.SERVER_REJECTING,
                    outboxDepth = 9, kbps = 3.0, lastUploadMs = 5L,
                ),
            ),
        )
    }

    @Test fun authFailuresSayWhichKind() {
        assertEquals(
            "↑ clock skew" to BadgeTone.CRITICAL,
            uploadBadge(UploadStatus(authFailed = true, authSkewMs = 585_000, clockBlame = ClockBlame.SERVER)),
        )
        assertEquals("↑ auth failed" to BadgeTone.CRITICAL, uploadBadge(UploadStatus(authFailed = true)))
    }

    // Task 5 review: the hold can lag; a sign-in failure is what is holding uploads now.
    @Test fun aSignInFailureOutranksAHold() {
        assertEquals(
            "↑ auth failed" to BadgeTone.CRITICAL,
            uploadBadge(UploadStatus(authFailed = true, hold = UploadHold.SERVER_FAULTING, outboxDepth = 120)),
        )
    }

    // The DATA-22 follow-up: a breaker holding used to read as an ordinary "N queued".
    @Test fun aHeldQueueSaysHeld() {
        assertEquals("↑ held · 120" to BadgeTone.WARN, uploadBadge(UploadStatus(hold = UploadHold.SERVER_FAULTING, outboxDepth = 120)))
        assertEquals("↑ held · 3" to BadgeTone.WARN, uploadBadge(UploadStatus(hold = UploadHold.SERVER_REJECTING, outboxDepth = 3, kbps = 1.0)))
    }

    @Test fun normalStates() {
        val (text, tone) = uploadBadge(UploadStatus(kbps = 2.5, outboxDepth = 40))
        assertTrue(text, text.startsWith("↑ ") && text.endsWith(" KB/s"))
        assertEquals(BadgeTone.GOOD, tone)
        assertEquals("↑ 37 queued" to BadgeTone.WARN, uploadBadge(UploadStatus(outboxDepth = 37)))
        assertEquals("↑ synced" to BadgeTone.MUTED, uploadBadge(UploadStatus(lastUploadMs = 5L)))
        assertEquals("↑ idle" to BadgeTone.MUTED, uploadBadge(UploadStatus()))
    }

    // DATA-19: an empty outbox is not "synced" while evicted or skipped samples still wait for their re-send.
    @Test fun samplesStillToReSendAreNeverSynced() {
        assertEquals(
            "↑ re-sending" to BadgeTone.WARN,
            uploadBadge(UploadStatus(lastUploadMs = 5L, resync = ResyncSummary(pending = 1, fromMs = 0L))),
        )
        assertEquals(
            "↑ retry later" to BadgeTone.WARN,
            uploadBadge(UploadStatus(lastUploadMs = 5L, resync = ResyncSummary(parked = 2))),
        )
        // The live queue is the more immediate fact.
        assertEquals(
            "↑ 4 queued" to BadgeTone.WARN,
            uploadBadge(UploadStatus(outboxDepth = 4, lastUploadMs = 5L, resync = ResyncSummary(pending = 1))),
        )
    }

    @Test fun nothingThatHoldsOrLosesSamplesEverReadsSynced() {
        val notSynced = listOf(
            UploadStatus(lastUploadMs = 5L, keyMissing = true),
            UploadStatus(lastUploadMs = 5L, authFailed = true),
            UploadStatus(lastUploadMs = 5L, authFailed = true, authSkewMs = 60_000),
            UploadStatus(lastUploadMs = 5L, hold = UploadHold.SERVER_REJECTING),
            UploadStatus(lastUploadMs = 5L, hold = UploadHold.SERVER_FAULTING),
            UploadStatus(lastUploadMs = 5L, resync = ResyncSummary(pending = 1)),
            UploadStatus(lastUploadMs = 5L, resync = ResyncSummary(parked = 1)),
        )
        for (s in notSynced) {
            val (text, tone) = uploadBadge(s)
            assertNotEquals(s.toString(), "↑ synced", text)
            assertNotEquals(s.toString(), BadgeTone.MUTED, tone)
        }
    }
}
