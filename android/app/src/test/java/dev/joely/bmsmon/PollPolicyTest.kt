package dev.joely.bmsmon

import dev.joely.bmsmon.ble.POLL_RETRY_DELAY_MS
import dev.joely.bmsmon.ble.PollAction
import dev.joely.bmsmon.ble.PollOutcome
import dev.joely.bmsmon.ble.decodeOrNull
import dev.joely.bmsmon.ble.missDelayMs
import dev.joely.bmsmon.ble.pollAction
import dev.joely.bmsmon.ble.pollOutcome
import dev.joely.bmsmon.model.SLOW_POLL_MS
import dev.joely.bmsmon.model.STAGE_POLL_MS
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The retry-before-drop policy that stops a single missed status frame from tearing down a
 * stage pack's GATT link (the "occasional stage disconnect" seen in the field logs).
 */
class PollPolicyTest {

    @Test fun frameAlwaysDelivers() {
        assertEquals(PollAction.DELIVER, pollAction(PollOutcome.FRAME, 0, maxMisses = 3))
        assertEquals(PollAction.DELIVER, pollAction(PollOutcome.FRAME, 2, maxMisses = 3))
    }

    @Test fun errorDropsImmediately() {
        // A real link failure (STATE_DISCONNECTED) is dead now — don't waste retries on it.
        assertEquals(PollAction.DROP, pollAction(PollOutcome.ERROR, 0, maxMisses = 3))
    }

    @Test fun singleTimeoutRetriesInsteadOfDropping() {
        // The old behavior dropped here; now the first miss keeps the link and retries.
        assertEquals(PollAction.RETRY, pollAction(PollOutcome.TIMEOUT, 0, maxMisses = 3))
    }

    @Test fun timeoutsRetryUntilToleranceThenDrop() {
        // maxMisses = 3 → two retries, drop on the third consecutive miss.
        assertEquals(PollAction.RETRY, pollAction(PollOutcome.TIMEOUT, 0, maxMisses = 3)) // miss #1
        assertEquals(PollAction.RETRY, pollAction(PollOutcome.TIMEOUT, 1, maxMisses = 3)) // miss #2
        assertEquals(PollAction.DROP, pollAction(PollOutcome.TIMEOUT, 2, maxMisses = 3))  // miss #3
    }

    @Test fun toleranceOfOneDropsOnFirstMiss() {
        // maxMisses = 1 restores the old drop-on-first-miss behavior.
        assertEquals(PollAction.DROP, pollAction(PollOutcome.TIMEOUT, 0, maxMisses = 1))
    }

    // --- UI-16: a response that never decodes is a miss, not a delivery ---

    @Test fun pollOutcomeClassifiesDecodeFailureAsUndecodable() {
        assertEquals(PollOutcome.TIMEOUT, pollOutcome(gotFrame = false, decoded = false))
        assertEquals(PollOutcome.UNDECODABLE, pollOutcome(gotFrame = true, decoded = false))
        assertEquals(PollOutcome.FRAME, pollOutcome(gotFrame = true, decoded = true))
    }

    @Test fun undecodableFrameRetriesLikeAMiss() {
        assertEquals(PollAction.RETRY, pollAction(PollOutcome.UNDECODABLE, 0, maxMisses = 5))
    }

    @Test fun decodeFailOnlyPackEventuallyDrops() {
        // The old loop counted any complete buffer as FRAME and reset the streak, so a pack whose
        // every frame failed to parse stayed "connected" with a frozen SOC forever.
        assertEquals(PollAction.RETRY, pollAction(PollOutcome.UNDECODABLE, 3, maxMisses = 5))
        assertEquals(PollAction.DROP, pollAction(PollOutcome.UNDECODABLE, 4, maxMisses = 5))
    }

    @Test fun timeoutsAndDecodeFailuresShareOneStreak() {
        // e.g. 3 timeouts, then an undecodable frame (miss #4), then a timeout (miss #5) → drop.
        assertEquals(PollAction.RETRY, pollAction(PollOutcome.UNDECODABLE, 3, maxMisses = 5))
        assertEquals(PollAction.DROP, pollAction(PollOutcome.TIMEOUT, 4, maxMisses = 5))
    }

    @Test fun aGoodFrameStillDeliversAfterUndecodableOnes() {
        assertEquals(PollAction.DELIVER, pollAction(PollOutcome.FRAME, 4, maxMisses = 5))
    }

    // --- a parser throw is an undecodable frame, not a dead link ---
    // It used to land in the poll loop's catch-all as ERROR: an immediate drop and a reconnect
    // ~2 s later, so one frame shape the parser chokes on looped connect/drop forever with no
    // decode_fail evidence. Now it counts as a miss, keeps the cadence, and the frame is logged.

    @Test fun aParserThrowDecodesAsNullAndIsReported() {
        var reported: Exception? = null
        val tel = decodeOrNull({ reported = it }) { throw ArrayIndexOutOfBoundsException("offset 96") }
        assertNull(tel)
        assertTrue(reported is ArrayIndexOutOfBoundsException)
        // ...so it classifies as UNDECODABLE — a miss that retries — never ERROR's immediate drop.
        assertEquals(PollOutcome.UNDECODABLE, pollOutcome(gotFrame = true, decoded = tel != null))
        assertEquals(PollAction.RETRY, pollAction(PollOutcome.UNDECODABLE, 0, maxMisses = 5))
    }

    @Test fun aCleanParseIsReturnedAndNothingIsReported() {
        val parsed = Any()
        assertSame(parsed, decodeOrNull({ fail("nothing threw") }) { parsed })
        assertNull(decodeOrNull<Any>({ fail("a rejected frame is not a throw") }) { null })
    }

    @Test fun theDecodeGuardNeverSwallowsCancellation() {
        try {
            decodeOrNull<Any>({ fail("cancellation must not be reported as a bad frame") }) {
                throw CancellationException("stop")
            }
            fail("cancellation must propagate")
        } catch (e: CancellationException) {
            assertEquals("stop", e.message)
        }
    }

    // Same policy as isolateCallback: a per-frame guard lets Errors through (see its KDoc).
    @Test(expected = StackOverflowError::class)
    fun theDecodeGuardDoesNotSwallowErrors() {
        decodeOrNull<Any>({ fail("an Error is not a bad frame") }) { throw StackOverflowError() }
    }

    @Test fun undecodableNeverRetriesFasterThanTheNormalCadence() {
        for (cadence in listOf(100L, POLL_RETRY_DELAY_MS, STAGE_POLL_MS, SLOW_POLL_MS)) {
            assertTrue("cadence $cadence", missDelayMs(PollOutcome.UNDECODABLE, cadence) >= cadence)
        }
    }

    @Test fun aTimeoutRetriesAfterTheShortBreather() {
        // The timeout itself already waited out the whole response window.
        assertEquals(POLL_RETRY_DELAY_MS, missDelayMs(PollOutcome.TIMEOUT, STAGE_POLL_MS))
        assertEquals(POLL_RETRY_DELAY_MS, missDelayMs(PollOutcome.TIMEOUT, SLOW_POLL_MS))
    }
}
