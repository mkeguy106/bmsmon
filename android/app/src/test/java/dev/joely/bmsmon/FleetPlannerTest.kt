package dev.joely.bmsmon

import dev.joely.bmsmon.ble.delayFor
import dev.joely.bmsmon.ble.profile.BackoffSpec
import org.junit.Assert.assertEquals
import org.junit.Test

class FleetPlannerTest {
    private val spec = BackoffSpec(baseMs = 5_000L, factor = 2, capMs = 120_000L)

    @Test fun backoffGrowsThenCaps() {
        assertEquals(0L, spec.delayFor(0))         // never failed → eligible immediately
        assertEquals(5_000L, spec.delayFor(1))     // 5s
        assertEquals(10_000L, spec.delayFor(2))    // 10s
        assertEquals(20_000L, spec.delayFor(3))    // 20s
        assertEquals(40_000L, spec.delayFor(4))    // 40s
        assertEquals(80_000L, spec.delayFor(5))    // 80s
        assertEquals(120_000L, spec.delayFor(6))   // 160s → capped at 120s
        assertEquals(120_000L, spec.delayFor(20))  // stays capped
    }
}
