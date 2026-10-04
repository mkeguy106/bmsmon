package dev.joely.bmsmon

import dev.joely.bmsmon.monitor.PhonePowerSnapshot
import dev.joely.bmsmon.monitor.toJson
import org.junit.Assert.assertEquals
import org.junit.Test

class PhonePowerSnapshotTest {
    private fun snap(level: Int) = PhonePowerSnapshot(level, 4, 1000, false, null, 1L)

    @Test fun uploadedLevelIsClampedToZeroThroughHundred() {
        assertEquals(100, snap(130).toJson().level)
        assertEquals(0, snap(-5).toJson().level)
        assertEquals(42, snap(42).toJson().level)
    }
}
