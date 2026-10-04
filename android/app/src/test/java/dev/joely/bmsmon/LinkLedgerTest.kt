package dev.joely.bmsmon

import dev.joely.bmsmon.ble.ConnectVerdict
import dev.joely.bmsmon.ble.LinkLedger
import dev.joely.bmsmon.ble.RECONNECT_BACKOFF_MS
import dev.joely.bmsmon.ble.profile.BackoffSpec
import dev.joely.bmsmon.ble.reconnectDelayMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The BLE attempt model (T2.6, BLE-15). Sessions are plain strings here: BmsRepository's control
 * loop closes whatever session the ledger hands back and cancels jobs where it says so.
 */
class LinkLedgerTest {
    private val spec = BackoffSpec(baseMs = 5_000L, factor = 2, capMs = 120_000L)
    private val a = "C8:47:80:15:67:44"

    /** Connect [a] and hold it as [session]; returns the attempt id. */
    private fun hold(ledger: LinkLedger<String>, session: String = "s", now: Long = 0L): Long {
        val id = ledger.beginConnect(a)
        assertEquals(ConnectVerdict.HOLD, ledger.connectSucceeded(a, id, session, wanted = true, now = now))
        return id
    }

    @Test fun aSuccessfulCurrentAttemptIsHeld() {
        val ledger = LinkLedger<String>()
        val id = ledger.beginConnect(a)
        assertEquals(setOf(a), ledger.connectingAddrs)
        assertEquals(ConnectVerdict.HOLD, ledger.connectSucceeded(a, id, "s", wanted = true, now = 7L))
        assertEquals(mapOf(a to "s"), ledger.heldSessions())
        assertEquals(mapOf(a to 7L), ledger.heldSinceSnapshot())
        assertTrue(ledger.connectingAddrs.isEmpty())
    }

    // BLE-15, Review Focus 2: Disconnect then Reconnect while the first connect is still in flight,
    // and both workers succeed. The old loop held both and orphaned the first GATT link.
    @Test fun disconnectThenReconnectDuringASlowConnectHoldsExactlyOneLink() {
        val ledger = LinkLedger<String>()
        val first = ledger.beginConnect(a)
        assertNull(ledger.drop(a))                      // Disconnect: nothing held yet
        val second = ledger.beginConnect(a)             // Reconnect: a fresh attempt
        assertEquals(ConnectVerdict.STALE, ledger.connectSucceeded(a, first, "old", wanted = true, now = 1L))
        assertEquals(ConnectVerdict.HOLD, ledger.connectSucceeded(a, second, "new", wanted = true, now = 2L))
        assertEquals(mapOf(a to "new"), ledger.heldSessions())
    }

    @Test fun aStaleSuccessIsClosedEvenWhenItLandsLast() {
        val ledger = LinkLedger<String>()
        val first = ledger.beginConnect(a)
        ledger.drop(a)
        val second = ledger.beginConnect(a)
        assertEquals(ConnectVerdict.HOLD, ledger.connectSucceeded(a, second, "new", wanted = true, now = 1L))
        assertEquals(ConnectVerdict.STALE, ledger.connectSucceeded(a, first, "old", wanted = true, now = 2L))
        assertEquals(mapOf(a to "new"), ledger.heldSessions())
    }

    // The old loop cleared `connecting` on ANY worker's failure, which let the planner start a third.
    @Test fun aStaleFailureLeavesTheCurrentAttemptInFlight() {
        val ledger = LinkLedger<String>()
        val first = ledger.beginConnect(a)
        ledger.drop(a)
        ledger.beginConnect(a)
        assertNull(ledger.connectFailed(a, first, spec, failThreshold = 3, now = 10L))
        assertEquals(setOf(a), ledger.connectingAddrs)
        assertTrue(ledger.backoffSnapshot().isEmpty())
    }

    // Review Focus 2: an orphaned poll loop's frames and drop must not touch the new link.
    @Test fun aStaleDropNeverClosesTheNewLink() {
        val ledger = LinkLedger<String>()
        val first = hold(ledger, "old")
        assertEquals("old", ledger.drop(a))             // a planner drop hands back the session to close
        val second = ledger.beginConnect(a)
        ledger.connectSucceeded(a, second, "new", wanted = true, now = 5L)
        assertFalse(ledger.pollFrame(a, first, decoded = true))
        assertNull(ledger.pollDropped(a, first, spec, now = 6L))
        assertEquals(mapOf(a to "new"), ledger.heldSessions())
        assertTrue(ledger.pollFrame(a, second, decoded = true))
    }

    @Test fun aSuccessForAPackNoLongerWantedIsClosed() {
        val ledger = LinkLedger<String>()
        val id = ledger.beginConnect(a)
        assertEquals(ConnectVerdict.UNWANTED, ledger.connectSucceeded(a, id, "s", wanted = false, now = 1L))
        assertTrue(ledger.heldSessions().isEmpty())
        assertTrue(ledger.connectingAddrs.isEmpty())
    }

    @Test fun beginConnectSupersedesAnAttemptStillInFlight() {
        val ledger = LinkLedger<String>()
        val first = ledger.beginConnect(a)
        val second = ledger.beginConnect(a)
        assertEquals(ConnectVerdict.STALE, ledger.connectSucceeded(a, first, "old", wanted = true, now = 1L))
        assertEquals(ConnectVerdict.HOLD, ledger.connectSucceeded(a, second, "new", wanted = true, now = 2L))
    }

    @Test fun failuresClimbTheBackoffLadderAndReportUnreachableAtTheThreshold() {
        val ledger = LinkLedger<String>()
        assertEquals(false, ledger.connectFailed(a, ledger.beginConnect(a), spec, failThreshold = 3, now = 0L))
        assertEquals(5_000L, ledger.backoffSnapshot()[a])
        assertEquals(false, ledger.connectFailed(a, ledger.beginConnect(a), spec, failThreshold = 3, now = 100L))
        assertEquals(10_100L, ledger.backoffSnapshot()[a])
        assertEquals(true, ledger.connectFailed(a, ledger.beginConnect(a), spec, failThreshold = 3, now = 200L))
        assertEquals(20_200L, ledger.backoffSnapshot()[a])
    }

    @Test fun aSuccessResetsTheFailureCount() {
        val ledger = LinkLedger<String>()
        repeat(2) { ledger.connectFailed(a, ledger.beginConnect(a), spec, failThreshold = 3, now = 0L) }
        val id = hold(ledger)
        assertTrue(ledger.backoffSnapshot().isEmpty())
        ledger.pollDropped(a, id, spec, now = 0L)
        // Third failure overall, but the first since the success: not yet unreachable.
        assertEquals(false, ledger.connectFailed(a, ledger.beginConnect(a), spec, failThreshold = 3, now = 0L))
        assertEquals(5_000L, ledger.backoffSnapshot()[a])
    }

    @Test fun anOrdinaryDropReconnectsQuickly() {
        val ledger = LinkLedger<String>()
        val id = hold(ledger)
        assertTrue(ledger.pollFrame(a, id, decoded = true))
        assertEquals("s", ledger.pollDropped(a, id, spec, now = 1_000L))
        assertEquals(1_000L + RECONNECT_BACKOFF_MS, ledger.backoffSnapshot()[a])
        assertTrue(ledger.heldSessions().isEmpty())
    }

    // Tier-1 follow-up: a pack that connects but never sends a decodable frame used to cycle
    // connect → 5 misses → drop → 2 s, every ~8–10 s on the stage, indefinitely.
    @Test fun aPackThatNeverDecodesBacksOffFurtherEachDrop() {
        val ledger = LinkLedger<String>()
        var now = 0L
        for (wait in listOf(5_000L, 10_000L, 20_000L, 40_000L, 80_000L, 120_000L, 120_000L)) {
            val id = hold(ledger, now = now)
            repeat(5) { assertTrue(ledger.pollFrame(a, id, decoded = false)) }
            assertEquals("s", ledger.pollDropped(a, id, spec, now = now))
            assertEquals(now + wait, ledger.backoffSnapshot()[a])
            now += wait
        }
    }

    @Test fun oneDecodedFrameResetsTheStreak() {
        val ledger = LinkLedger<String>()
        repeat(2) {
            val id = hold(ledger)
            ledger.pollFrame(a, id, decoded = false)
            ledger.pollDropped(a, id, spec, now = 0L)
        }
        val id = hold(ledger)
        ledger.pollFrame(a, id, decoded = false)
        ledger.pollFrame(a, id, decoded = true)
        ledger.pollDropped(a, id, spec, now = 0L)
        assertEquals(RECONNECT_BACKOFF_MS, ledger.backoffSnapshot()[a])
    }

    // A silent session (connected, then no answer at all) is the RF case, where the official app's
    // patient fast retry is the proven-good behaviour: it neither escalates nor resets the streak.
    @Test fun aSilentDropNeitherRaisesNorResetsTheStreak() {
        val ledger = LinkLedger<String>()
        val garbage = hold(ledger)
        ledger.pollFrame(a, garbage, decoded = false)
        ledger.pollDropped(a, garbage, spec, now = 0L)
        assertEquals(5_000L, ledger.backoffSnapshot()[a])
        val silent = hold(ledger)
        ledger.pollDropped(a, silent, spec, now = 0L)
        assertEquals(5_000L, ledger.backoffSnapshot()[a])        // streak still 1
        val again = hold(ledger)
        ledger.pollFrame(a, again, decoded = false)
        ledger.pollDropped(a, again, spec, now = 0L)
        assertEquals(10_000L, ledger.backoffSnapshot()[a])       // streak 2
    }

    @Test fun kickClearsBackoffFailuresAndTheGarbageStreak() {
        val ledger = LinkLedger<String>()
        val id = hold(ledger)
        ledger.pollFrame(a, id, decoded = false)
        ledger.pollDropped(a, id, spec, now = 0L)
        repeat(2) { ledger.connectFailed(a, ledger.beginConnect(a), spec, failThreshold = 3, now = 0L) }
        ledger.kick()
        assertTrue(ledger.backoffSnapshot().isEmpty())
        assertEquals(false, ledger.connectFailed(a, ledger.beginConnect(a), spec, failThreshold = 3, now = 0L))
        val next = hold(ledger)
        ledger.pollDropped(a, next, spec, now = 0L)
        assertEquals(RECONNECT_BACKOFF_MS, ledger.backoffSnapshot()[a])   // streak cleared too
    }

    // BLE-16: an app resume inside the rate limit still retries the stage packs, as kick() would,
    // and leaves every other pack's backoff, failure count and garbage streak alone.
    @Test fun kickingSomePacksLeavesTheOthersBackingOff() {
        val ledger = LinkLedger<String>()
        val spare = "C8:47:80:15:DB:13"
        val id = hold(ledger)
        ledger.pollFrame(a, id, decoded = false)
        ledger.pollDropped(a, id, spec, now = 0L)
        repeat(2) { ledger.connectFailed(a, ledger.beginConnect(a), spec, failThreshold = 3, now = 0L) }
        repeat(2) { ledger.connectFailed(spare, ledger.beginConnect(spare), spec, failThreshold = 3, now = 0L) }
        ledger.kick(setOf(a))
        assertEquals(mapOf(spare to 10_000L), ledger.backoffSnapshot())
        // a starts over: its next failure is its first, not its third …
        assertEquals(false, ledger.connectFailed(a, ledger.beginConnect(a), spec, failThreshold = 3, now = 0L))
        val next = hold(ledger)
        ledger.pollDropped(a, next, spec, now = 0L)
        assertEquals(RECONNECT_BACKOFF_MS, ledger.backoffSnapshot()[a])   // … and its streak is gone
        // The spare kept its count: its next failure is its third.
        assertEquals(true, ledger.connectFailed(spare, ledger.beginConnect(spare), spec, failThreshold = 3, now = 0L))
    }

    // A pack removed from the roster must not leave per-address state behind: re-added, it starts
    // from scratch. drop() alone keeps the counters (a disabled pack's backoff still applies).
    @Test fun aForgottenPackStartsFromScratch() {
        val ledger = LinkLedger<String>()
        val id = hold(ledger)
        ledger.pollFrame(a, id, decoded = false)
        ledger.pollDropped(a, id, spec, now = 0L)                       // garbage streak 1
        repeat(2) { ledger.connectFailed(a, ledger.beginConnect(a), spec, failThreshold = 3, now = 0L) }
        val inFlight = ledger.beginConnect(a)
        assertEquals(setOf(a), ledger.connectingAddrs)
        assertTrue(ledger.backoffSnapshot().isNotEmpty())

        assertNull(ledger.forget(a))                                    // nothing held to close
        assertTrue(ledger.backoffSnapshot().isEmpty())
        assertTrue(ledger.connectingAddrs.isEmpty())
        // The attempt that was in flight is stale now, like after a drop.
        assertEquals(ConnectVerdict.STALE, ledger.connectSucceeded(a, inFlight, "late", wanted = true, now = 1L))

        // Re-added: its next failure is the first, not the third (which would report it unreachable)…
        assertEquals(false, ledger.connectFailed(a, ledger.beginConnect(a), spec, failThreshold = 3, now = 0L))
        assertEquals(5_000L, ledger.backoffSnapshot()[a])
        // …and its garbage streak is gone: a silent drop gets the short delay, not the escalated one.
        val next = hold(ledger)
        ledger.pollDropped(a, next, spec, now = 0L)
        assertEquals(RECONNECT_BACKOFF_MS, ledger.backoffSnapshot()[a])
    }

    @Test fun forgettingAHeldPackHandsBackItsSessionAndStalesItsEvents() {
        val ledger = LinkLedger<String>()
        val id = hold(ledger, "s")
        assertEquals("s", ledger.forget(a))
        assertTrue(ledger.heldSessions().isEmpty())
        assertFalse(ledger.pollFrame(a, id, decoded = true))
        assertNull(ledger.pollDropped(a, id, spec, now = 0L))
        assertTrue(ledger.backoffSnapshot().isEmpty())                 // a stale drop writes no backoff
    }

    // Final wave: a pack disabled while its attempt was queued is skipped, never connected. That ends
    // the attempt without a failure or backoff, so Reconnect retries it at the very next plan.
    @Test fun aSkippedAttemptEndsWithoutAFailureOrBackoff() {
        val ledger = LinkLedger<String>()
        val id = ledger.beginConnect(a)
        assertTrue(ledger.connectSkipped(a, id))
        assertTrue(ledger.connectingAddrs.isEmpty())
        assertTrue(ledger.backoffSnapshot().isEmpty())
        // Three skips in a row still count as no failure: the next real failure is the first.
        repeat(2) { ledger.connectSkipped(a, ledger.beginConnect(a)) }
        assertEquals(false, ledger.connectFailed(a, ledger.beginConnect(a), spec, failThreshold = 2, now = 0L))
    }

    @Test fun aStaleSkipLeavesTheCurrentAttemptInFlight() {
        val ledger = LinkLedger<String>()
        val first = ledger.beginConnect(a)
        ledger.drop(a)
        ledger.beginConnect(a)
        assertFalse(ledger.connectSkipped(a, first))
        assertEquals(setOf(a), ledger.connectingAddrs)
    }

    @Test fun reconnectDelayIsShortUntilAGarbageDrop() {
        assertEquals(RECONNECT_BACKOFF_MS, reconnectDelayMs(0, spec))
        assertEquals(5_000L, reconnectDelayMs(1, spec))
        assertEquals(120_000L, reconnectDelayMs(9, spec))
    }
}
