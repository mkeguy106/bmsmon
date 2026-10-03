package dev.joely.bmsmon

import dev.joely.bmsmon.location.FixCache
import dev.joely.bmsmon.location.GpsFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** BLE-23: an old fix must never be stamped onto a new sample. */
class FixCacheTest {

    private fun fix(t: Long) = GpsFix(43.0, -87.9, 5f, t)

    @Test fun aFixOlderThanTheLimitIsDroppedAtRead() {
        val c = FixCache(maxAgeMs = 120_000L)
        c.begin()
        c.offerLive(fix(0L))
        assertEquals("exactly at the limit still counts", fix(0L), c.read(nowMs = 120_000L))
        assertNull(c.read(nowMs = 120_001L))
    }

    @Test fun aLateLastLocationFromAnEarlierStartIsIgnored() {
        val c = FixCache()
        val first = c.begin()
        c.end()
        c.begin()                                       // start → stop → start inside the Task's latency
        c.offerSeed(first, fix(1_000L), nowMs = 2_000L)
        assertNull(c.read(2_000L))
    }

    @Test fun aLateLastLocationAfterStopIsIgnored() {
        val c = FixCache()
        val gen = c.begin()
        c.end()
        c.offerSeed(gen, fix(1_000L), nowMs = 2_000L)
        assertNull(c.read(2_000L))
        assertFalse(c.requesting)
    }

    @Test fun aSeedNeverReplacesANewerLiveFix() {
        val c = FixCache()
        val gen = c.begin()
        c.offerLive(fix(10_000L))
        c.offerSeed(gen, fix(5_000L), nowMs = 11_000L)
        assertEquals(fix(10_000L), c.read(11_000L))
    }

    @Test fun aStaleSeedIsRejected() {
        val c = FixCache(maxAgeMs = 120_000L)
        val gen = c.begin()
        c.offerSeed(gen, fix(0L), nowMs = 300_000L)
        assertNull(c.read(300_000L))
    }

    @Test fun aLiveResultAfterStopIsDropped() {
        val c = FixCache()
        c.begin()
        c.end()
        c.offerLive(fix(1_000L))
        assertNull(c.read(1_000L))
    }
}
