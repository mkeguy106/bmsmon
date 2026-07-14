package dev.joely.bmsmon.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlushPolicyTest {

    @Test fun emptyOutboxNeverFlushes() {
        assertFalse(shouldFlush(depth = 0, oldestAgeMs = null, draining = false))
        assertFalse(shouldFlush(depth = 0, oldestAgeMs = 20_000L, draining = true))
    }

    @Test fun shallowFreshQueueWaits() {
        // The old behavior — POST the moment anything is queued — is exactly what's being fixed.
        assertFalse(shouldFlush(depth = 1, oldestAgeMs = 0L, draining = false))
        assertFalse(shouldFlush(depth = 2, oldestAgeMs = 1_500L, draining = false))
        assertFalse(shouldFlush(depth = MIN_BATCH - 1, oldestAgeMs = FLUSH_AGE_MS - 1, draining = false))
    }

    @Test fun depthAtMinBatchFlushes() {
        assertTrue(shouldFlush(depth = MIN_BATCH, oldestAgeMs = 0L, draining = false))
        assertTrue(shouldFlush(depth = MIN_BATCH + 180, oldestAgeMs = null, draining = false))
    }

    @Test fun oldHeadFlushesRegardlessOfDepth() {
        // A single row must never wait longer than FLUSH_AGE_MS (fires AT the threshold).
        assertTrue(shouldFlush(depth = 1, oldestAgeMs = FLUSH_AGE_MS, draining = false))
        assertTrue(shouldFlush(depth = 2, oldestAgeMs = FLUSH_AGE_MS + 1, draining = false))
    }

    @Test fun unknownAgeAloneDoesNotFlush() {
        assertFalse(shouldFlush(depth = 5, oldestAgeMs = null, draining = false))
    }

    @Test fun drainingFlushesRemainderWithoutRewaiting() {
        // Depth 250: first pass posts 200 (>= MIN_BATCH), the remaining 50 must drain
        // immediately — not sit out another FLUSH_AGE_MS.
        assertTrue(shouldFlush(depth = 50, oldestAgeMs = 0L, draining = true))
        assertTrue(shouldFlush(depth = 1, oldestAgeMs = null, draining = true))
    }

    @Test fun negativeAgeFromClockSkewWaits() {
        // enqueuedAt in the future (clock skew) must not trip the age trigger.
        assertFalse(shouldFlush(depth = 1, oldestAgeMs = -5_000L, draining = false))
    }
}
