package dev.joely.bmsmon

import dev.joely.bmsmon.data.SESSION_GAP_MS
import dev.joely.bmsmon.data.isNewSession
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
}
