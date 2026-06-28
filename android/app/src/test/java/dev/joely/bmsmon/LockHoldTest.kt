package dev.joely.bmsmon

import dev.joely.bmsmon.model.LOCK_HOLD_MS
import dev.joely.bmsmon.model.lockHoldComplete
import dev.joely.bmsmon.model.lockHoldFraction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockHoldTest {

    @Test fun fractionIsZeroAtStart() {
        assertEquals(0f, lockHoldFraction(0L), 0.0001f)
    }

    @Test fun fractionIsHalfwayAtHalfTime() {
        assertEquals(0.5f, lockHoldFraction(LOCK_HOLD_MS / 2), 0.0001f)
    }

    @Test fun fractionClampsToOne() {
        assertEquals(1f, lockHoldFraction(LOCK_HOLD_MS), 0.0001f)
        assertEquals(1f, lockHoldFraction(LOCK_HOLD_MS * 5), 0.0001f)
    }

    @Test fun fractionClampsAtZeroForNegative() {
        assertEquals(0f, lockHoldFraction(-100L), 0.0001f)
    }

    @Test fun completeOnlyOnceHeldLongEnough() {
        assertFalse(lockHoldComplete(0L))
        assertFalse(lockHoldComplete(LOCK_HOLD_MS - 1))
        assertTrue(lockHoldComplete(LOCK_HOLD_MS))
        assertTrue(lockHoldComplete(LOCK_HOLD_MS + 1))
    }
}
