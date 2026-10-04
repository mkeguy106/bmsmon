package dev.joely.bmsmon.cloud

import dev.joely.bmsmon.data.db.OutboxEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The upload loop's per-response decisions, wired end to end (DATA-22 follow-up: the skip/hold
 * wiring used to live untested inside the loop). Every path that can delete a sample is here.
 */
class IngestStepTest {
    private val meta = BatchMeta(firstId = 11, lastId = 210, size = 200, headTsMs = 5_000, minTsMs = 5_000, maxTsMs = 9_000)
    private val one = BatchMeta(firstId = 42, lastId = 42, size = 1, headTsMs = 7_000, minTsMs = 7_000, maxTsMs = 7_000)
    private val wall = 1_000_000L
    private val sec = 1_000L
    private val minute = 60_000L

    private fun out(r: PostResult, fromApi: Boolean = true, retryAfterMs: Long? = null) =
        PostOutcome(r, code = null, fromApi = fromApi, retryAfterMs = retryAfterMs)

    /** An HTTP response as the loop sees it: classified by the real [classifyPost]. */
    private fun http(code: Int, fromApi: Boolean, retryAfterMs: Long? = null) =
        PostOutcome(classifyPost(code, fromApi), code = code, fromApi = fromApi, retryAfterMs = retryAfterMs)

    private fun step(s: IngestLoopState, o: PostOutcome, b: BatchMeta = meta, at: Long = 0L) =
        ingestStep(s, b, o, at, wall)

    private fun deletes(effects: List<OutboxEffect>) =
        effects.filter { it is OutboxEffect.DeleteThrough || it is OutboxEffect.SkipOne }

    @Test fun anAcceptedBatchDeletesThroughItsLastRowAndResetsEverything() {
        val r = step(IngestLoopState(backoffMs = 8_000, hold = UploadHold.SERVER_REJECTING, authFailed = true), out(PostResult.Ok))
        assertEquals(listOf(OutboxEffect.DeleteThrough(210)), r.effects)
        assertEquals(0L, r.delayMs)
        assertEquals(INITIAL_BACKOFF_MS, r.state.backoffMs)
        assertEquals(UploadHold.NONE, r.state.hold)
        assertFalse(r.state.authFailed)
    }

    @Test fun aPoisonSkipDeletesTheBatchAndParksItsSpanForAResend() {
        val r = step(IngestLoopState(), out(PostResult.Poison))
        assertEquals(
            listOf(   // the re-send record first: a kill between the two stores re-sends, never loses
                OutboxEffect.Resync(ResyncWindow(5_000, 9_000, notBeforeMs = wall + RESYNC_PARK_MS)),
                OutboxEffect.DeleteThrough(210),
            ),
            r.effects,
        )
        assertNotNull(r.log)
    }

    @Test fun aSecondPoisonHoldsWithNoEffectsAndShowsTheHold() {
        val r = step(IngestLoopState(poisonSkips = 1), out(PostResult.Poison))
        assertTrue(r.effects.isEmpty())
        assertEquals(UploadHold.SERVER_REJECTING, r.state.hold)
        assertEquals(INITIAL_BACKOFF_MS, r.delayMs)
    }

    @Test fun aFaultStreakAtOneRowSkipsExactlyTheHeadRowAndParksItsResend() {
        var s = IngestLoopState(fault = HeadFaultState(headId = null, limit = 1, streak = 0, firstFaultAtMs = null))
        val effects = mutableListOf<OutboxEffect>()
        for (at in listOf(0L, 2 * minute, 4 * minute, 5 * minute)) {
            val r = step(s, out(PostResult.ServerFault), one, at)
            effects += r.effects
            s = r.state
        }
        assertEquals(
            listOf(   // the re-send record first, as for a poison skip
                OutboxEffect.Resync(ResyncWindow(7_000, 7_000, notBeforeMs = wall + RESYNC_PARK_MS)),
                OutboxEffect.SkipOne(42),
            ),
            effects,
        )
        assertEquals(UploadHold.SERVER_FAULTING, s.hold)
    }

    @Test fun aSpentFaultBreakerHoldsTheNextRowWithNoEffects() {
        val next = BatchMeta(43, 43, 1, 8_000, 8_000, 8_000)
        var s = IngestLoopState(fault = HeadFaultState(headId = 42, limit = 1, streak = 0, firstFaultAtMs = null, skipsSinceOk = 1))
        val effects = mutableListOf<OutboxEffect>()
        // One trip at minute 5 (held: the breaker is spent), then a second streak, too short to trip by minute 10.
        for (at in (0L..10L).map { it * minute }) {
            val r = step(s, out(PostResult.ServerFault), next, at)
            effects += r.effects
            s = r.state
        }
        assertTrue(effects.isEmpty())
        assertEquals(UploadHold.SERVER_FAULTING, s.hold)
    }

    @Test fun aTrippedStreakOnAFullBatchNarrowsItAndDeletesNothing() {
        var s = IngestLoopState()
        var last: IngestStepResult? = null
        for (at in listOf(0L, 2 * minute, 4 * minute, 5 * minute)) {
            last = step(s, out(PostResult.ServerFault), meta, at)
            assertTrue("no effect at $at", last.effects.isEmpty())
            s = last.state
        }
        assertEquals(100, s.fault.limit)
        assertEquals(UploadHold.SERVER_FAULTING, s.hold)
        assertNotNull(last!!.log)
    }

    @Test fun aMarked503WaitsForItsRetryAfterAndDeletesNothing() {
        val r = step(IngestLoopState(backoffMs = 2_000), out(PostResult.Transient, retryAfterMs = 30_000))
        assertTrue(r.effects.isEmpty())
        assertEquals(30_000L, r.delayMs)
        assertEquals(4_000L, r.state.backoffMs)
    }

    @Test fun retryAfterNeverShortensTheBackoff() {
        assertEquals(60_000L, step(IngestLoopState(backoffMs = 60_000), out(PostResult.Transient, retryAfterMs = 1_000)).delayMs)
    }

    @Test fun aMissingKeyHoldsEverythingSaysSoAndClearsOnA2xx() {
        val r = step(IngestLoopState(backoffMs = 2_000), out(PostResult.KeyMissing, fromApi = false))
        assertTrue(r.effects.isEmpty())
        assertTrue(r.state.keyMissing)
        assertEquals(2_000L, r.delayMs)
        assertEquals(4_000L, r.state.backoffMs)
        assertNotNull(r.log)
        assertFalse(step(r.state, out(PostResult.Ok)).state.keyMissing)
    }

    @Test fun anyResponseProvesAKeyAgainButANetworkErrorProvesNothing() {
        val missing = step(IngestLoopState(), PostOutcome(PostResult.KeyMissing)).state
        // No response: a network error, or a Keystore hiccup, which says nothing about the key.
        assertTrue(step(missing, PostOutcome(PostResult.Transient)).state.keyMissing)
        // A response means a request was signed, so a key exists again (the phone was re-enrolled).
        assertFalse(step(missing, http(502, fromApi = false)).state.keyMissing)
        assertFalse(step(missing, http(401, fromApi = true)).state.keyMissing)
    }

    @Test fun aMarked401HoldsTheBatchAndSaysAuthFailed() {
        val r = step(IngestLoopState(), out(PostResult.AuthFailed))
        assertTrue(r.effects.isEmpty())
        assertTrue(r.state.authFailed)
        assertEquals(INITIAL_BACKOFF_MS, r.delayMs)
        assertNull(r.log)
    }

    @Test fun anUnmarked2xxDeletesNothing() {
        val r = step(IngestLoopState(), http(200, fromApi = false))
        assertEquals(PostResult.Transient, classifyPost(200, fromApi = false))
        assertTrue(r.effects.isEmpty())
        assertTrue(r.delayMs > 0L)
    }

    @Test fun anOutageNeverDeletesAnything() {
        val outage = listOf(
            PostOutcome(PostResult.Transient, code = 404, fromApi = false),
            PostOutcome(PostResult.Transient, code = 503, fromApi = true, retryAfterMs = 30_000),
            PostOutcome(PostResult.Transient),
            PostOutcome(PostResult.AuthFailed, code = 401, fromApi = true),
            PostOutcome(PostResult.KeyMissing),
        )
        var s = IngestLoopState()
        repeat(600) { i ->   // ten hours, one POST a minute
            val r = step(s, outage[i % outage.size], at = i * minute)
            assertTrue("no effect at $i", r.effects.isEmpty())
            s = r.state
        }
    }

    /**
     * Every outage class, alone, for ten hours, from the two states closest to a delete: both breakers
     * armed, and a one-row fault streak one fault short of tripping with its span already covered.
     * None may delete a row; the responses go through the real [classifyPost].
     */
    @Test fun noOutageClassEverDeletesARow() {
        val cases = listOf(
            "network error (no response)" to PostOutcome(PostResult.Transient),
            "missing device key" to PostOutcome(PostResult.KeyMissing),
            "unmarked 200 (captive portal, misrouted proxy)" to http(200, fromApi = false),
            "unmarked 204" to http(204, fromApi = false),
            "unmarked 404 (proxy, api down)" to http(404, fromApi = false),
            "unmarked 400" to http(400, fromApi = false),
            "unmarked 422" to http(422, fromApi = false),
            "unmarked 500" to http(500, fromApi = false),
            "unmarked 502" to http(502, fromApi = false),
            "unmarked 503" to http(503, fromApi = false),
            "unmarked 504" to http(504, fromApi = false),
            "marked 503 + Retry-After (database unavailable)" to http(503, fromApi = true, retryAfterMs = 30_000),
            "marked 408" to http(408, fromApi = true),
            "marked 429" to http(429, fromApi = true),
            "302 redirect" to http(302, fromApi = false),
            "marked 307 redirect" to http(307, fromApi = true),
            "unmarked 401" to http(401, fromApi = false),
            "marked 401" to http(401, fromApi = true),
            "unmarked 403" to http(403, fromApi = false),
            "marked 403" to http(403, fromApi = true),
        )
        val armed = IngestLoopState()
        val primed = IngestLoopState(fault = HeadFaultState(headId = 42, limit = 1, streak = 3, firstFaultAtMs = 0L))
        for ((name, o) in cases) {
            for ((startName, start, batch) in listOf(Triple("armed", armed, meta), Triple("primed", primed, one))) {
                var s = start
                repeat(600) { i ->   // ten hours, one POST a minute, starting past the primed span
                    val r = step(s, o, batch, at = 10 * minute + i * minute)
                    assertEquals("$name from $startName, try $i", emptyList<OutboxEffect>(), deletes(r.effects))
                    assertTrue("$name from $startName, try $i: no re-send without a delete", r.effects.isEmpty())
                    s = r.state
                }
            }
        }
    }

    @Test fun aFaultStreakShorterThanTheSpanNeverDeletes() {
        // A burst of marked 500s on one row: hundreds of tries, all inside the span.
        var s = IngestLoopState(fault = HeadFaultState(headId = null, limit = 1, streak = 0, firstFaultAtMs = null))
        var at = 0L
        while (at < FAULT_MIN_SPAN_MS) {
            val r = step(s, http(500, fromApi = true), one, at)
            assertTrue("no effect at $at", r.effects.isEmpty())
            s = r.state
            at += sec
        }
        assertEquals(1, s.fault.limit)
        assertEquals(0, s.fault.skipsSinceOk)
    }

    @Test fun batchMetaReadsIdsAndSampleTimes() {
        val rows = listOf(OutboxEntity(5, "{}", 300), OutboxEntity(6, "{}", 100), OutboxEntity(9, "{}", 200))
        assertEquals(BatchMeta(5, 9, 3, 300, 100, 300), batchMeta(rows))
    }
}
