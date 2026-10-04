package dev.joely.bmsmon

import dev.joely.bmsmon.data.FailureLogThrottle
import dev.joely.bmsmon.data.FailureLogThrottle.Action.Full
import dev.joely.bmsmon.data.FailureLogThrottle.Action.Summary
import dev.joely.bmsmon.data.FailureLogThrottle.Action.Suppress
import dev.joely.bmsmon.data.warnThrottled
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The telemetry writer's failure-log throttle: a persistent DB fault fails every op (~100/min), and
 * a full stack trace each time would evict everything else from logcat. First failure of a burst in
 * full, then at most one counted summary line per interval; nothing is ever dropped from the count.
 */
class FailureLogThrottleTest {

    private val interval = 60_000L

    @Test fun theFirstFailureIsLoggedInFull() {
        assertEquals(Full(unreported = 0), FailureLogThrottle(interval).onFailure(5_000))
    }

    @Test fun aPersistentFaultLogsOneSummaryLinePerInterval() {
        val t = FailureLogThrottle(interval)
        assertEquals(Full(0), t.onFailure(0))
        for (k in 1..99) assertEquals("failure at ${k * 600} ms", Suppress, t.onFailure(k * 600L))
        assertEquals(Summary(count = 100), t.onFailure(60_000))   // 99 suppressed + this one; boundary inclusive
        for (k in 1..99) assertEquals(Suppress, t.onFailure(60_000 + k * 600L))
        assertEquals(Summary(count = 100), t.onFailure(120_000))
    }

    @Test fun aQuietIntervalEndsTheBurstAndTheNextFailureIsLoggedInFull() {
        val t = FailureLogThrottle(interval)
        assertEquals(Full(0), t.onFailure(0))
        assertEquals(Suppress, t.onFailure(1_000))
        assertEquals(Suppress, t.onFailure(2_000))
        // 60 s with no failure (inclusive): a new burst, carrying the two the last line never counted.
        assertEquals(Full(unreported = 2), t.onFailure(62_000))
        assertEquals(Suppress, t.onFailure(62_500))
    }

    @Test fun failuresJustInsideTheIntervalStayOneBurst() {
        val t = FailureLogThrottle(interval)
        assertEquals(Full(0), t.onFailure(0))
        assertEquals(Suppress, t.onFailure(59_999))
        assertEquals(Summary(count = 2), t.onFailure(119_998))
    }

    /** Over arbitrary failure times: lines are ≥ one interval apart, and every failure is counted once. */
    @Test fun linesAreSpacedByTheIntervalAndTheCountIsExact() {
        for (seed in 1L..20L) {
            val rnd = java.util.Random(seed)
            val t = FailureLogThrottle(interval)
            var now = 0L
            var failures = 0
            var counted = 0
            var lastLineAt: Long? = null
            repeat(2_000) {
                now += rnd.nextInt(if (rnd.nextInt(20) == 0) 150_000 else 3_000).toLong()
                failures++
                when (val a = t.onFailure(now)) {
                    is Full -> counted += 1 + a.unreported
                    is Summary -> counted += a.count
                    Suppress -> return@repeat
                }
                lastLineAt?.let { assertTrue("seed $seed: lines ${now - it} ms apart", now - it >= interval) }
                lastLineAt = now
            }
            // A failure after a long quiet spell reports whatever the last summary never did.
            val flush = t.onFailure(now + 10 * interval) as Full
            assertEquals("seed $seed", failures, counted + flush.unreported)
        }
    }

    // The shared helper the cloud loops log through (the ledger's cap, the re-sender, the reporter's loops).
    @Test fun warnThrottledLogsTheFirstWithItsCauseThenOneCountedLineAMinute() {
        val t = FailureLogThrottle(interval)
        val lines = mutableListOf<Pair<String, Throwable?>>()
        val boom = IllegalStateException("boom")
        for (k in 0..100) warnThrottled(t, k * 1_500L, { m, e -> lines += m to e }, "re-sync: pass failed", boom)
        assertEquals(listOf("re-sync: pass failed" to boom), lines.take(1))
        assertEquals(
            listOf("re-sync: pass failed (40 times since the last report; latest: java.lang.IllegalStateException: boom)" to null),
            lines.drop(1).take(1),
        )
        assertEquals(3, lines.size)                         // at 0 s, 60 s and 120 s over 150 s of 1.5 s failures
        // After a quiet minute, the next is in full again and carries what the last line never reported.
        warnThrottled(t, 300_000L, { m, e -> lines += m to e }, "re-sync: pass failed", boom)
        assertEquals("re-sync: pass failed (20 earlier times went unreported)" to boom, lines.last())
    }
}
