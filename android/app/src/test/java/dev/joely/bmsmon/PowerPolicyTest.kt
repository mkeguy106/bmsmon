package dev.joely.bmsmon

import dev.joely.bmsmon.model.LOW_ENTER_PCT
import dev.joely.bmsmon.model.LOW_EXIT_PCT
import dev.joely.bmsmon.model.powerDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerPolicyTest {

    @Test fun thresholdsAreFiveAndFifteen() {
        assertEquals(5, LOW_ENTER_PCT)
        assertEquals(15, LOW_EXIT_PCT)
    }

    @Test fun unpluggedNeverHoldsScreen() {
        for (level in intArrayOf(0, 4, 5, 14, 15, 50, 100)) {
            assertFalse(powerDecision(onExternal = false, levelPct = level, wasLowPower = false).holdScreen)
        }
    }

    @Test fun pluggedAndHealthyHoldsScreen() {
        val d = powerDecision(onExternal = true, levelPct = 50, wasLowPower = false)
        assertTrue(d.holdScreen)
        assertFalse(d.gpsBalanced)
        assertFalse(d.lowPower)
    }

    // Walking down from 20%, the latch must set at 4 and NOT before.
    @Test fun latchSetsBelowFiveAndNotAbove() {
        var low = false
        for (level in intArrayOf(20, 16, 15, 14, 9, 5)) {
            low = powerDecision(onExternal = true, levelPct = level, wasLowPower = low).lowPower
            assertFalse("latch set early at $level", low)
        }
        val d = powerDecision(onExternal = true, levelPct = 4, wasLowPower = low)
        assertTrue(d.lowPower)
        assertFalse(d.holdScreen)
        assertTrue(d.gpsBalanced)
    }

    // Once set, climbing through the 5..14 band must NOT clear it.
    @Test fun latchHoldsThroughHysteresisBand() {
        var low = true
        for (level in intArrayOf(4, 5, 8, 10, 14)) {
            low = powerDecision(onExternal = true, levelPct = level, wasLowPower = low).lowPower
            assertTrue("latch cleared early at $level", low)
        }
    }

    @Test fun latchClearsAtFifteen() {
        val d = powerDecision(onExternal = true, levelPct = 15, wasLowPower = true)
        assertFalse(d.lowPower)
        assertTrue(d.holdScreen)
        assertFalse(d.gpsBalanced)
    }

    // Oscillating inside the band produces zero transitions in either starting state.
    @Test fun noFlappingInsideBand() {
        var low = true
        for (level in intArrayOf(5, 14, 5, 14, 6, 13)) {
            low = powerDecision(onExternal = true, levelPct = level, wasLowPower = low).lowPower
            assertTrue(low)
        }
        var high = false
        for (level in intArrayOf(14, 5, 14, 5, 13, 6)) {
            high = powerDecision(onExternal = true, levelPct = level, wasLowPower = high).lowPower
            assertFalse(high)
        }
    }

    @Test fun gpsBalancedTracksLatchExactly() {
        assertTrue(powerDecision(onExternal = true, levelPct = 4, wasLowPower = false).gpsBalanced)
        assertTrue(powerDecision(onExternal = false, levelPct = 10, wasLowPower = true).gpsBalanced)
        assertFalse(powerDecision(onExternal = false, levelPct = 10, wasLowPower = false).gpsBalanced)
        assertFalse(powerDecision(onExternal = true, levelPct = 15, wasLowPower = true).gpsBalanced)
    }

    // Level is clamped, so a malformed reading can never fabricate a low-power state.
    @Test fun outOfRangeLevelsAreClamped() {
        assertFalse(powerDecision(onExternal = true, levelPct = 200, wasLowPower = true).lowPower)
        assertTrue(powerDecision(onExternal = true, levelPct = -3, wasLowPower = false).lowPower)
    }
}
