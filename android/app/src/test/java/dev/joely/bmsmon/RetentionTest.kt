package dev.joely.bmsmon

import dev.joely.bmsmon.data.cutoffMs
import org.junit.Assert.assertEquals
import org.junit.Test

class RetentionTest {
    @Test fun cutoffSubtractsDays() {
        val now = 1_000_000_000_000L
        assertEquals(now - 14L * 86_400_000L, cutoffMs(now, 14))
        assertEquals(now - 7L * 86_400_000L, cutoffMs(now, 7))
    }
}
