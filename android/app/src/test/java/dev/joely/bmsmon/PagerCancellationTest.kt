package dev.joely.bmsmon

import dev.joely.bmsmon.data.db.IvPoint
import dev.joely.bmsmon.data.db.SessionSpan
import dev.joely.bmsmon.data.db.TimelineRow
import dev.joely.bmsmon.data.poolTimeline
import dev.joely.bmsmon.data.stridePoints
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The timeline pager and the V–I stride walk are plain blocking loops, so the ONLY cancellation
 * points are their `fetch` lambdas. TelemetryRepository.timeline/healthInputs open each lambda with
 * `ensureActive()`; Room is not reachable from a JVM test, so these tests run the pure seams with a
 * fetch of that same shape and pin the contract it relies on: a cancelled caller stops the walk at
 * the next fetch (no further page/step is read) and the CancellationException propagates out
 * through the pager instead of being swallowed.
 */
class PagerCancellationTest {

    @Test fun timelinePagingStopsAndPropagatesWhenTheCallerIsCancelled() = runBlocking {
        var fetches = 0
        var nextId = 0L
        var thrown: CancellationException? = null
        val job = launch(Dispatchers.IO) {
            try {
                poolTimeline(SessionSpan(n = 10_000, startMs = 0, endMs = 10_000), pageSize = 2) { _, limit ->
                    ensureActive()                       // the line TelemetryRepository.timeline starts its lambda with
                    fetches++
                    if (fetches == 2) cancel()           // the user leaves the screen mid-walk
                    // Bounded so a missing cancellation check fails the asserts below instead of paging forever.
                    List(if (fetches > 50) 0 else limit) { TimelineRow(++nextId, nextId, false, -5f, 60f, 13f, 50f) }
                }
            } catch (e: CancellationException) {
                thrown = e
                throw e
            }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertNotNull("CancellationException must propagate out of the pager", thrown)
        assertEquals("page 3 must never be fetched", 2, fetches)
    }

    @Test fun strideWalkStopsAndPropagatesWhenTheCallerIsCancelled() = runBlocking {
        var fetches = 0
        var thrown: CancellationException? = null
        val job = launch(Dispatchers.IO) {
            try {
                stridePoints(step = 3, cap = 1_000) { _, _, _ ->
                    ensureActive()                       // the line TelemetryRepository.healthInputs starts its lambda with
                    fetches++
                    if (fetches == 2) cancel()
                    IvPoint(id = fetches.toLong(), tsMs = fetches.toLong(), currentA = -5f, voltageV = 13f)
                }
            } catch (e: CancellationException) {
                thrown = e
                throw e
            }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertNotNull("CancellationException must propagate out of the stride walk", thrown)
        assertEquals("step 3 must never be fetched", 2, fetches)
    }
}
