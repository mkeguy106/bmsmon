package dev.joely.bmsmon

import dev.joely.bmsmon.location.isFixTooStale
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `lastLocation` can hand back an arbitrarily old fix — before the parked-GPS gate this was read
 * once per monitoring session, but now [dev.joely.bmsmon.location.LocationSource.start] runs at
 * every park→drive transition. [isFixTooStale] guards the cache seed so a pre-park fix is never
 * mistaken for the current position (see MAX_CACHED_FIX_AGE_MS in LocationSource.kt).
 */
class LocationFixStalenessTest {

    @Test fun freshFixIsNotStale() {
        assertFalse(isFixTooStale(fixTimeMs = 9_000L, nowMs = 10_000L, maxAgeMs = 120_000L))
    }

    @Test fun fixExactlyAtTheAgeLimitIsNotStale() {
        // Boundary is exclusive (`>`), matching a fix landing exactly on the limit still counting.
        assertFalse(isFixTooStale(fixTimeMs = 0L, nowMs = 120_000L, maxAgeMs = 120_000L))
    }

    @Test fun fixOlderThanTheLimitIsStale() {
        assertTrue(isFixTooStale(fixTimeMs = 0L, nowMs = 120_001L, maxAgeMs = 120_000L))
    }

    @Test fun realWorldExample_preParkFixIsRejected() {
        // A fix cached 5 minutes before a park->drive transition (well past the 120 s window).
        val fixTimeMs = 0L
        val nowMs = 5 * 60_000L
        assertTrue(isFixTooStale(fixTimeMs, nowMs))
    }

    @Test fun realWorldExample_justAcquiredFixIsAccepted() {
        // A fix from the same GNSS refresh cadence (2-20 s) as "now" is trusted.
        val fixTimeMs = 100_000L
        val nowMs = 105_000L
        assertFalse(isFixTooStale(fixTimeMs, nowMs))
    }
}
