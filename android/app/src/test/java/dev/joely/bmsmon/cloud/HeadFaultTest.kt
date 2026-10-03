package dev.joely.bmsmon.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DATA-22: a server bug that crashes (marked 500) on one specific sample must not block the ingest
 * queue forever, and an outage must never be mistaken for one. [stepHeadFault] narrows the head
 * batch only after [FAULT_STREAK] marked faults that also span [FAULT_MIN_SPAN_MS], and skips a
 * single outbox row only once the batch is down to that one row.
 */
class HeadFaultTest {

    private val max = 200
    private val sec = 1_000L
    private val min = 60_000L

    private fun fresh(limit: Int = max) = HeadFaultState(headId = null, limit = limit, streak = 0, firstFaultAtMs = null)

    /** One step, the way the upload loop calls it. */
    private fun HeadFaultState.step(head: Long, sent: Int, r: PostResult, at: Long) =
        stepHeadFault(this, head, sent, r, at, max)

    /** Marked faults on [head] at each of [times], sending [sent] rows; returns the state after the last. */
    private fun HeadFaultState.faults(head: Long, sent: Int, times: List<Long>): HeadFaultState =
        times.fold(this) { s, t ->
            val (next, action) = s.step(head, sent, PostResult.ServerFault, t)
            assertEquals("no skip at t=$t", HeadFaultAction.NONE, action)
            next
        }

    // --- time is required, not just a count ---

    @Test fun fourMarkedFaultsWithinOneMinuteDoNotBisect() {
        val s = fresh().faults(head = 1, sent = 200, times = listOf(0, 10 * sec, 30 * sec, 59 * sec))
        assertEquals(HeadFaultState(headId = 1, limit = 200, streak = 4, firstFaultAtMs = 0), s)
    }

    @Test fun manyFaultsJustShortOfTheSpanStillDoNotBisect() {
        val times = (0..9).map { it * 33 * sec } + listOf(FAULT_MIN_SPAN_MS - 1)
        val s = fresh().faults(head = 1, sent = 200, times = times)
        assertEquals(200, s.limit)
        assertEquals(11, s.streak)
    }

    @Test fun fourFaultsSpanningFiveMinutesHalveTheLimit() {
        val s = fresh().faults(head = 1, sent = 200, times = listOf(0, 100 * sec, 200 * sec))
        val (next, action) = s.step(head = 1, sent = 200, r = PostResult.ServerFault, at = 5 * min)
        assertEquals(HeadFaultAction.NONE, action)
        assertEquals(HeadFaultState(headId = 1, limit = 100, streak = 0, firstFaultAtMs = null), next)
    }

    @Test fun aLongSpanNeedsTheCountToo() {
        val s = fresh().faults(head = 1, sent = 200, times = listOf(0, 10 * min, 20 * min))
        assertEquals(200, s.limit)
        assertEquals(3, s.streak)
    }

    @Test fun halvingUsesTheSentSizeRoundedUp() {
        for ((sent, expected) in listOf(200 to 100, 37 to 19, 7 to 4, 3 to 2, 2 to 1)) {
            val s = fresh().faults(head = 1, sent = sent, times = listOf(0, 2 * min, 4 * min))
            val (next, _) = s.step(head = 1, sent = sent, r = PostResult.ServerFault, at = 5 * min)
            assertEquals("sent $sent", expected, next.limit)
        }
    }

    // --- bisection to one row, then the skip ---

    @Test fun repeatedHalvingFrom200ReachesOneThenSkipsTheHeadRow() {
        var s = fresh()
        var t = 0L
        val sizes = mutableListOf<Int>()
        var action = HeadFaultAction.NONE
        while (action == HeadFaultAction.NONE) {
            val sent = minOf(max, s.limit)   // a deep outbox: always a full batch available
            if (sizes.lastOrNull() != sent) sizes += sent
            val (next, a) = s.step(head = 7, sent = sent, r = PostResult.ServerFault, at = t)
            if (a == HeadFaultAction.SKIP_HEAD_ROW) assertEquals("skips only a single row", 1, sent)
            s = next
            action = a
            t += min   // the Transient backoff caps at 60 s
            assertTrue("bounded", t < 24 * 60 * min)
        }
        assertEquals(listOf(200, 100, 50, 25, 13, 7, 4, 2, 1), sizes)
        // The skip resets everything: full batch, fresh streak.
        assertEquals(max, s.limit)
        assertEquals(0, s.streak)
        assertNull(s.firstFaultAtMs)
    }

    @Test fun noSkipAtOneRowBeforeTheSpan() {
        // Even at batch size 1, a fast burst of faults deletes nothing.
        val s = fresh(limit = 1).faults(head = 1, sent = 1, times = (0L..20L).map { it * 14 * sec })
        assertEquals(21, s.streak)
        val (_, action) = s.step(head = 1, sent = 1, r = PostResult.ServerFault, at = FAULT_MIN_SPAN_MS)
        assertEquals(HeadFaultAction.SKIP_HEAD_ROW, action)
    }

    @Test fun afterASkipTheNextHeadStartsAFreshStreakAtFullBatch() {
        val s = fresh(limit = 1).faults(head = 1, sent = 1, times = listOf(0, 2 * min, 4 * min))
        val (afterSkip, action) = s.step(head = 1, sent = 1, r = PostResult.ServerFault, at = 5 * min)
        assertEquals(HeadFaultAction.SKIP_HEAD_ROW, action)
        val (next, a2) = afterSkip.step(head = 2, sent = 200, r = PostResult.ServerFault, at = 6 * min)
        assertEquals(HeadFaultAction.NONE, a2)
        assertEquals(HeadFaultState(headId = 2, limit = 200, streak = 1, firstFaultAtMs = 6 * min), next)
    }

    // --- recovery ---

    @Test fun anOkDoublesTheLimitBackTo200() {
        var s = HeadFaultState(headId = 1, limit = 13, streak = 2, firstFaultAtMs = 0)
        val limits = mutableListOf<Int>()
        for (head in 1L..5L) {
            val (next, action) = s.step(head = head, sent = s.limit, r = PostResult.Ok, at = head * sec)
            assertEquals(HeadFaultAction.NONE, action)
            assertEquals(0, next.streak)
            assertNull(next.firstFaultAtMs)
            limits += next.limit
            s = next
        }
        assertEquals(listOf(26, 52, 104, 200, 200), limits)
    }

    @Test fun aHeadChangeResetsTheStreakButKeepsTheLimit() {
        val s = HeadFaultState(headId = 1, limit = 50, streak = 3, firstFaultAtMs = 0)
        // Whatever the response (here: the head moved under an edge error — eviction, a poison skip).
        val (moved, _) = s.step(head = 2, sent = 50, r = PostResult.Transient, at = 9 * min)
        assertEquals(HeadFaultState(headId = 2, limit = 50, streak = 0, firstFaultAtMs = null), moved)
        val (faulted, _) = s.step(head = 2, sent = 50, r = PostResult.ServerFault, at = 9 * min)
        assertEquals(HeadFaultState(headId = 2, limit = 50, streak = 1, firstFaultAtMs = 9 * min), faulted)
    }

    // --- outages neither reset nor advance ---

    @Test fun transientAndAuthFailedBetweenFaultsNeitherResetNorAdvance() {
        val twoFaults = fresh().faults(head = 1, sent = 200, times = listOf(0, 1 * min))
        var s = twoFaults
        for ((i, r) in listOf(PostResult.Transient, PostResult.AuthFailed, PostResult.Poison, PostResult.Transient).withIndex()) {
            val (next, action) = s.step(head = 1, sent = 200, r = r, at = (2 + i) * min)
            assertEquals(HeadFaultAction.NONE, action)
            assertEquals("$r leaves the state untouched", twoFaults, next)
            s = next
        }
        // The streak resumes where it was: faults 3 and 4, the 4th 5 min after the 1st, trip it.
        val s3 = s.faults(head = 1, sent = 200, times = listOf(4 * min))
        val (tripped, _) = s3.step(head = 1, sent = 200, r = PostResult.ServerFault, at = 5 * min)
        assertEquals(100, tripped.limit)
    }

    // Review focus 1 + 2: a DB restart (marked 503) and a proxy outage (unmarked 5xx, no response)
    // must never advance the streak — hours of them leave the state exactly as it was, and delete
    // nothing through either the fault bisection or the poison breaker.
    @Test fun anOutageNeverAdvancesTheStreakOrDeletesAnything() {
        val outage = listOf(
            classifyPost(503, fromApi = true),
            classifyPost(500, fromApi = false),
            classifyPost(502, fromApi = false),
            classifyPost(503, fromApi = false),
            classifyPost(504, fromApi = false),
            classifyPost(404, fromApi = false),
            classifyPost(null, fromApi = false),
        )
        val start = HeadFaultState(headId = 1, limit = 1, streak = 0, firstFaultAtMs = null)
        var s = start
        var skips = 0
        for (i in 0 until 600) {   // ten hours, one POST a minute
            val r = outage[i % outage.size]
            val (next, action) = s.step(head = 1, sent = 1, r = r, at = i * min)
            assertEquals(HeadFaultAction.NONE, action)
            val d = decideUpload(r, skips, authFailed = false)
            assertEquals(BatchStep.BACK_OFF, d.step)
            skips = d.poisonSkipsSinceOk
            s = next
        }
        assertEquals(start, s)
    }

    // --- end to end over a queue: only the bad row is ever skipped ---

    /**
     * Drive the machine over a simulated outbox the way the upload loop does: peek
     * min(BATCH, limit) rows, a batch containing any bad id gets a marked 500, a clean one is
     * accepted; one POST a minute. Returns the skipped ids, after asserting every good row was
     * accepted and the queue drained.
     */
    private fun drain(total: Int, bad: Set<Long>): List<Long> {
        val queue = ArrayDeque((1L..total.toLong()).toList())
        val accepted = mutableSetOf<Long>()
        val skipped = mutableListOf<Long>()
        var s = fresh()
        var t = 0L
        var posts = 0
        while (queue.isNotEmpty()) {
            val sent = queue.take(minOf(max, s.limit))
            val r = if (sent.any { it in bad }) PostResult.ServerFault else PostResult.Ok
            val (next, action) = stepHeadFault(s, sent.first(), sent.size, r, t, max)
            when {
                r == PostResult.Ok -> repeat(sent.size) { accepted += queue.removeFirst() }
                action == HeadFaultAction.SKIP_HEAD_ROW -> skipped += queue.removeFirst()
            }
            s = next
            t += min
            assertTrue("bounded", ++posts < 10_000)
        }
        assertEquals((1L..total.toLong()).toSet() - bad, accepted)
        return skipped
    }

    @Test fun onlyTheFaultingRowIsSkipped() {
        for (badId in listOf(1L, 2L, 99L, 150L, 200L, 201L, 777L, 1000L)) {
            assertEquals("bad id $badId", listOf(badId), drain(total = 1000, bad = setOf(badId)))
        }
    }

    @Test fun twoFaultingRowsAreEachSkippedAndNothingElse() {
        assertEquals(listOf(40L, 41L), drain(total = 500, bad = setOf(40L, 41L)))
        assertEquals(listOf(3L, 180L), drain(total = 500, bad = setOf(3L, 180L)))
    }
}
