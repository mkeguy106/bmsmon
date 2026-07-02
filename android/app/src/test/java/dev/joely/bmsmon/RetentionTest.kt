package dev.joely.bmsmon

import dev.joely.bmsmon.data.RAW_FRAME_MAX_BYTES
import dev.joely.bmsmon.data.cutoffMs
import dev.joely.bmsmon.data.rawFrameBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetentionTest {
    @Test fun cutoffSubtractsDays() {
        val now = 1_000_000_000_000L
        assertEquals(now - 14L * 86_400_000L, cutoffMs(now, 14))
        assertEquals(now - 7L * 86_400_000L, cutoffMs(now, 7))
    }

    // DATA-6: the raw-frame size cap compares against SUM(LENGTH(hex)) — hex CHARS, 2 per byte.
    // Without the /2 conversion, "20 MB" of cap really meant 10 MB of frames.
    @Test fun rawFrameBytesHalvesHexCharCount() {
        assertEquals(105L, rawFrameBytes(210))   // a 105-byte status frame stores as 210 hex chars
        assertEquals(0L, rawFrameBytes(0))
    }

    @Test fun capComparisonUsesRealBytes() {
        // Exactly 20 MB of frames = 40 M hex chars: at the cap, NOT over it.
        val hexCharsFor20Mb = RAW_FRAME_MAX_BYTES * 2
        assertTrue(rawFrameBytes(hexCharsFor20Mb) <= RAW_FRAME_MAX_BYTES)
        assertTrue(rawFrameBytes(hexCharsFor20Mb + 2) > RAW_FRAME_MAX_BYTES)
    }
}
