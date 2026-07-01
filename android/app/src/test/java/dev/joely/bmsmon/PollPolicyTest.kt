package dev.joely.bmsmon

import dev.joely.bmsmon.ble.PollAction
import dev.joely.bmsmon.ble.PollOutcome
import dev.joely.bmsmon.ble.pollAction
import org.junit.Assert.assertEquals
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
}
