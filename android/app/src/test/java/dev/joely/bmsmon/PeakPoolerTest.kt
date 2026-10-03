package dev.joely.bmsmon

import dev.joely.bmsmon.data.PeakPooler
import dev.joely.bmsmon.data.peakPool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PeakPoolerTest {

    @Test fun streamingPoolMatchesTheReferenceOnRandomSessions() {
        for (seed in 1L..20L) {
            val s = randomSession(seed, n = 30 + (seed * 53).toInt())
            assertEquals("seed $seed", referencePeakPool(s), peakPool(s))
            assertEquals("seed $seed (8 buckets)", referencePeakPool(s, buckets = 8), peakPool(s, buckets = 8))
        }
    }

    // The session may still be OPEN (rows land after the span query, past endMs), or a stepped-back
    // clock puts a row before startMs. Both clamp into the edge buckets.
    @Test fun rowsOutsideTheSpanClampIntoTheEdgeBuckets() {
        val pool = PeakPooler(startMs = 1_000, endMs = 2_000, rowCount = 10, buckets = 4)
        pool.add(tsMs = 5_000, isLink = false, currentA = -20f, powerW = 260f, voltageV = 13.0f, soc = 50f)
        pool.add(tsMs = 0, isLink = true, currentA = null, powerW = null, voltageV = null, soc = null)
        val b = pool.buckets()
        assertEquals(2, b.size)
        assertTrue(b.first().link)
        assertEquals(260f, b.last().dischargeW, 0f)
    }
}
