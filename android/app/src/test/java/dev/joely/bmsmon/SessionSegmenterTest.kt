package dev.joely.bmsmon

import dev.joely.bmsmon.data.MAX_SESSION_MS
import dev.joely.bmsmon.data.SESSION_GAP_MS
import dev.joely.bmsmon.data.isNewSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSegmenterTest {
    @Test fun firstSampleEverStartsSession() {
        assertTrue(isNewSession(prevSampleTsMs = null, prevWasDisconnect = false, nowMs = 1_000))
    }

    @Test fun backToBackSamplesStayInSession() {
        assertFalse(isNewSession(prevSampleTsMs = 1_000, prevWasDisconnect = false, nowMs = 3_000))
    }

    @Test fun gapBeyondThresholdStartsNewSession() {
        val now = 1_000 + SESSION_GAP_MS + 1
        assertTrue(isNewSession(prevSampleTsMs = 1_000, prevWasDisconnect = false, nowMs = now))
    }

    @Test fun gapExactlyAtThresholdStaysInSession() {
        val now = 1_000 + SESSION_GAP_MS
        assertFalse(isNewSession(prevSampleTsMs = 1_000, prevWasDisconnect = false, nowMs = now))
    }

    @Test fun disconnectSinceLastSampleStartsNewSession() {
        assertTrue(isNewSession(prevSampleTsMs = 1_000, prevWasDisconnect = true, nowMs = 2_000))
    }

    // DATA-16: a session ends only on a disconnect or a > 10 min gap, so a stable link used to keep
    // one open for days (every finalize then loaded ~800k rows). Capped at 24 h, inclusive.

    @Test fun capIsTwentyFourHours() {
        assertEquals(24 * 60 * 60 * 1000L, MAX_SESSION_MS)
    }

    @Test fun sessionReachingTheCapStartsNewSession() {
        val start = 1_000L
        assertTrue(
            isNewSession(
                prevSampleTsMs = start + MAX_SESSION_MS - 1_500, prevWasDisconnect = false,
                nowMs = start + MAX_SESSION_MS, sessionStartMs = start,
            ),
        )
    }

    @Test fun sessionJustUnderTheCapContinues() {
        val start = 1_000L
        assertFalse(
            isNewSession(
                prevSampleTsMs = start + MAX_SESSION_MS - 1_500, prevWasDisconnect = false,
                nowMs = start + MAX_SESSION_MS - 1, sessionStartMs = start,
            ),
        )
    }

    @Test fun unknownSessionStartNeverSplitsOnLength() {
        val now = 10 * MAX_SESSION_MS
        assertFalse(isNewSession(prevSampleTsMs = now - 1_500, prevWasDisconnect = false, nowMs = now))
    }

    @Test fun aClockSteppingBackDoesNotSplit() {
        val start = 10 * MAX_SESSION_MS
        assertFalse(
            isNewSession(prevSampleTsMs = start + 5_000, prevWasDisconnect = false, nowMs = start - 2_000, sessionStartMs = start),
        )
    }
}
