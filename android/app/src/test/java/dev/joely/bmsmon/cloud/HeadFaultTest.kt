package dev.joely.bmsmon.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DATA-22: a server bug that crashes (marked 500) on one specific sample must not block the ingest
 * queue forever, and an outage must never be mistaken for one. [stepHeadFault] narrows the head
 * batch only after [FAULT_STREAK] marked faults that also span [FAULT_MIN_SPAN_MS], and skips a
 * single outbox row only once the batch is down to that one row — and at most one row since the
 * last 2xx, so a server that faults on everything costs one sample, then holds.
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
        assertEquals(HeadFaultState(headId = 1, limit = 100, streak = 0, firstFaultAtMs = null, searchEndId = 200), next)
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
        // A fresh streak, the breaker spent, and the next head goes alone until a 2xx.
        assertEquals(HeadFaultState(headId = 7, limit = 1, streak = 0, firstFaultAtMs = null, skipsSinceOk = 1), s)
    }

    @Test fun noSkipAtOneRowBeforeTheSpan() {
        // Even at batch size 1, a fast burst of faults deletes nothing.
        val s = fresh(limit = 1).faults(head = 1, sent = 1, times = (0L..20L).map { it * 14 * sec })
        assertEquals(21, s.streak)
        val (_, action) = s.step(head = 1, sent = 1, r = PostResult.ServerFault, at = FAULT_MIN_SPAN_MS)
        assertEquals(HeadFaultAction.SKIP_HEAD_ROW, action)
    }

    /** A state that has just skipped head 1 (four faults over 5 min at one row, breaker armed). */
    private fun justSkipped(): HeadFaultState {
        val s = fresh(limit = 1).faults(head = 1, sent = 1, times = listOf(0, 2 * min, 4 * min))
        val (afterSkip, action) = s.step(head = 1, sent = 1, r = PostResult.ServerFault, at = 5 * min)
        assertEquals(HeadFaultAction.SKIP_HEAD_ROW, action)
        return afterSkip
    }

    @Test fun afterASkipTheNextHeadStartsAFreshStreakAtLimitOne() {
        val (next, action) = justSkipped().step(head = 2, sent = 1, r = PostResult.ServerFault, at = 6 * min)
        assertEquals(HeadFaultAction.NONE, action)
        assertEquals(
            HeadFaultState(headId = 2, limit = 1, streak = 1, firstFaultAtMs = 6 * min, skipsSinceOk = 1),
            next,
        )
    }

    // Controller test 3: after a skip the next head is sent alone, and 2xxs double it back to 200.
    @Test fun afterASkipTheNextHeadIsSentAloneAndOksDoubleItBackTo200() {
        var s = justSkipped()
        assertEquals(1, s.limit)
        val limits = mutableListOf<Int>()
        for (head in 2L..10L) {
            val (next, action) = s.step(head = head, sent = s.limit, r = PostResult.Ok, at = 6 * min + head * sec)
            assertEquals(HeadFaultAction.NONE, action)
            assertEquals(0, next.skipsSinceOk)
            limits += next.limit
            s = next
        }
        assertEquals(listOf(2, 4, 8, 16, 32, 64, 128, 200, 200), limits)
    }

    // --- the one-skip breaker ---

    // Controller test 1: a server that faults on EVERY request (a bug unrelated to the rows) costs
    // exactly one sample, then holds — through as many further full trip spans as it takes.
    @Test fun aServerThatFaultsOnEverythingCostsOneSampleThenHolds() {
        var s = fresh()
        var head = 1L
        var t = 0L
        var skips = 0
        var holds = 0
        repeat(24 * 60) {   // a day, one POST a minute
            val (next, action) = s.step(head = head, sent = minOf(max, s.limit), r = PostResult.ServerFault, at = t)
            if (action == HeadFaultAction.SKIP_HEAD_ROW) {
                skips++
                head++   // the uploader deleted the head row
            } else if (skips > 0 && next.streak == 0) {
                holds++  // a full trip span at one row, with the breaker spent
                assertEquals(1, next.limit)
            }
            s = next
            t += min
        }
        assertEquals(1, skips)
        assertTrue("held through many further trip spans, saw $holds", holds >= 10)
        assertEquals(2L, s.headId)
        assertEquals(1, s.skipsSinceOk)
    }

    @Test fun aHeldTripRequiresAFreshFullSpanBeforeTheNextEvaluation() {
        // Breaker spent: the trip on head 2 holds and clears the streak, so the next three faults
        // and the next 5 min both have to accrue again — the hold can't re-fire on every response.
        val armed = justSkipped().faults(head = 2, sent = 1, times = listOf(6 * min, 7 * min, 8 * min))
        val (held, action) = armed.step(head = 2, sent = 1, r = PostResult.ServerFault, at = 11 * min)
        assertEquals(HeadFaultAction.NONE, action)
        assertEquals(
            HeadFaultState(headId = 2, limit = 1, streak = 0, firstFaultAtMs = null, skipsSinceOk = 1),
            held,
        )
    }

    @Test fun aHeadChangeDoesNotReArmTheSkip() {
        val (moved, _) = justSkipped().step(head = 9, sent = 1, r = PostResult.Transient, at = 6 * min)
        assertEquals(1, moved.skipsSinceOk)
    }

    // Controller test 2: a 2xx re-arms the skip, so a later poison row can still be isolated.
    @Test fun anOkReArmsTheSkipForALaterPoisonRow() {
        val (ok, _) = justSkipped().step(head = 2, sent = 1, r = PostResult.Ok, at = 6 * min)
        assertEquals(HeadFaultState(headId = 2, limit = 2, streak = 0, firstFaultAtMs = null, skipsSinceOk = 0), ok)
        // A later poison head: bisect 2 -> 1, then skip it.
        val a = ok.faults(head = 3, sent = 2, times = listOf(10 * min, 12 * min, 14 * min))
        val (halved, a1) = a.step(head = 3, sent = 2, r = PostResult.ServerFault, at = 15 * min)
        assertEquals(HeadFaultAction.NONE, a1)
        assertEquals(1, halved.limit)
        val b = halved.faults(head = 3, sent = 1, times = listOf(16 * min, 18 * min, 20 * min))
        val (_, a2) = b.step(head = 3, sent = 1, r = PostResult.ServerFault, at = 21 * min)
        assertEquals(HeadFaultAction.SKIP_HEAD_ROW, a2)
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

    private class Run(val accepted: Set<Long>, val skipped: List<Long>, val queue: List<Long>)

    /**
     * Drive the machine over a simulated outbox the way the upload loop does: peek
     * min(BATCH, limit) rows, a batch containing any bad id gets a marked 500, a clean one is
     * accepted; one POST a minute, until the queue drains or [maxPosts] POSTs.
     */
    private fun simulate(total: Int, bad: Set<Long>, maxPosts: Int): Run {
        val queue = ArrayDeque((1L..total.toLong()).toList())
        val accepted = mutableSetOf<Long>()
        val skipped = mutableListOf<Long>()
        var s = fresh()
        var t = 0L
        var posts = 0
        while (queue.isNotEmpty() && posts++ < maxPosts) {
            val sent = queue.take(minOf(max, s.limit))
            val r = if (sent.any { it in bad }) PostResult.ServerFault else PostResult.Ok
            val (next, action) = stepHeadFault(s, sent.first(), sent.size, r, t, max)
            when {
                r == PostResult.Ok -> repeat(sent.size) { accepted += queue.removeFirst() }
                action == HeadFaultAction.SKIP_HEAD_ROW -> skipped += queue.removeFirst()
            }
            s = next
            t += min
        }
        return Run(accepted, skipped, queue.toList())
    }

    /** [simulate] to an empty queue; asserts every good row was accepted. Returns the skipped ids. */
    private fun drain(total: Int, bad: Set<Long>): List<Long> {
        val run = simulate(total, bad, maxPosts = 10_000)
        assertTrue("drained", run.queue.isEmpty())
        assertEquals((1L..total.toLong()).toSet() - bad, run.accepted)
        return run.skipped
    }

    @Test fun onlyTheFaultingRowIsSkipped() {
        for (badId in listOf(1L, 2L, 99L, 150L, 200L, 201L, 777L, 1000L)) {
            assertEquals("bad id $badId", listOf(badId), drain(total = 1000, bad = setOf(badId)))
        }
    }

    @Test fun twoSeparatedFaultingRowsAreEachSkippedAndNothingElse() {
        // A clean row between them is sent alone after the first skip; its 2xx re-arms the breaker.
        assertEquals(listOf(3L, 180L), drain(total = 500, bad = setOf(3L, 180L)))
        assertEquals(listOf(40L, 42L), drain(total = 500, bad = setOf(40L, 42L)))
    }

    @Test fun adjacentFaultingRowsSkipTheFirstAndHoldTheSecond() {
        // No 2xx between them, so the breaker holds the second — exactly as a server faulting on
        // everything would look — until the server is fixed. Nothing behind it is lost.
        val run = simulate(total = 500, bad = setOf(40L, 41L), maxPosts = 3 * 24 * 60)
        assertEquals(listOf(40L), run.skipped)
        assertEquals((1L..39L).toSet(), run.accepted)
        assertEquals(41L, run.queue.first())
        assertEquals(500 - 40, run.queue.size)
    }

    // --- DATA-22 minor: a 2xx inside a narrowing search must not undo the halving ---

    /** Drain a queue with one bad row the way the upload loop does; count trips until it is skipped. */
    private fun tripsToIsolate(total: Int, bad: Long): Int {
        val queue = ArrayDeque((1L..total.toLong()).toList())
        var s = fresh()
        var t = 0L
        var trips = 0
        var posts = 0
        while (queue.isNotEmpty() && posts++ < 10_000) {
            val sent = queue.take(minOf(max, s.limit))
            val r = if (bad in sent) PostResult.ServerFault else PostResult.Ok
            val (next, action) = stepHeadFault(s, sent.first(), sent.size, r, t, max, tailId = sent.last())
            if (r == PostResult.ServerFault && next.streak == 0) trips++
            if (r == PostResult.Ok) repeat(sent.size) { queue.removeFirst() }
            if (action == HeadFaultAction.SKIP_HEAD_ROW) return trips
            s = next
            t += min
        }
        error("bad row $bad was never isolated")
    }

    @Test fun isolationTakesOneTripPerHalvingWhereverTheBadRowSits() {
        for (bad in listOf(1L, 2L, 99L, 100L, 101L, 150L, 199L, 200L, 333L, 777L, 1000L)) {
            val trips = tripsToIsolate(total = 1000, bad = bad)
            assertTrue("bad row $bad took $trips trips", trips <= 9)
        }
    }

    @Test fun aHalvingRecordsTheSuspectRangeAndKeepsTheNarrowerEnd() {
        val s = fresh().faults(head = 1, sent = 200, times = listOf(0, 2 * min, 4 * min))
        val (halved, _) = s.step(head = 1, sent = 200, r = PostResult.ServerFault, at = 5 * min)
        assertEquals(200L, halved.searchEndId)
        val s2 = halved.faults(head = 150, sent = 100, times = listOf(6 * min, 8 * min, 10 * min))
        val (again, _) = s2.step(head = 150, sent = 100, r = PostResult.ServerFault, at = 11 * min)
        assertEquals(50, again.limit)
        assertEquals(200L, again.searchEndId)
    }

    @Test fun anOkBeforeTheSuspectRangeEndsHoldsTheLimit() {
        val searching = HeadFaultState(headId = 1, limit = 100, streak = 0, firstFaultAtMs = null, searchEndId = 200)
        val (next, _) = stepHeadFault(searching, 1, 100, PostResult.Ok, 0L, max, tailId = 100)
        assertEquals(HeadFaultState(headId = 1, limit = 100, streak = 0, firstFaultAtMs = null, searchEndId = 200), next)
    }

    @Test fun anOkThatCoversTheWholeSuspectRangeEndsTheSearchAndDoubles() {
        val searching = HeadFaultState(headId = 101, limit = 100, streak = 0, firstFaultAtMs = null, searchEndId = 200)
        val (next, _) = stepHeadFault(searching, 101, 100, PostResult.Ok, 0L, max, tailId = 200)
        assertEquals(HeadFaultState(headId = 101, limit = 200, streak = 0, firstFaultAtMs = null, searchEndId = null), next)
    }

    @Test fun aTripAtOneRowConcludesTheSearch() {
        val s = HeadFaultState(headId = 7, limit = 1, streak = 0, firstFaultAtMs = null, searchEndId = 9)
            .faults(head = 7, sent = 1, times = listOf(0, 2 * min, 4 * min))
        val (after, action) = s.step(head = 7, sent = 1, r = PostResult.ServerFault, at = 5 * min)
        assertEquals(HeadFaultAction.SKIP_HEAD_ROW, action)
        assertNull(after.searchEndId)
    }

    @Test fun aMissingKeyNeitherResetsNorAdvancesTheStreak() {
        val two = fresh().faults(head = 1, sent = 200, times = listOf(0, 1 * min))
        val (next, action) = two.step(head = 1, sent = 200, r = PostResult.KeyMissing, at = 2 * min)
        assertEquals(HeadFaultAction.NONE, action)
        assertEquals(two, next)
    }
}
