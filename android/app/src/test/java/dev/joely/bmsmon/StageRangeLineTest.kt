package dev.joely.bmsmon

import dev.joely.bmsmon.model.PackRange
import dev.joely.bmsmon.model.StageItem
import dev.joely.bmsmon.model.Telemetry
import dev.joely.bmsmon.model.stageRangeLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StageRangeLineTest {
    // Positional through temp: name, soc, powerW, current, voltage, capacityAh(remaining), cellV, temp.
    private val tel = Telemetry("2024-B", 70f, 50f, -4f, 13.1f, 70f, 3.28f, 30f)
    private val range = PackRange(37.33f, 49.78f, 8.96f, 12.8f, 119.47f, 215.04f)

    @Test fun formatsMinAcrossConnectedPacks() {
        val worse = PackRange(30f, 45f, 8f, 12f, 110f, 200f)
        val line = stageRangeLine(listOf(
            StageItem(tel, regen = false, connected = true, range = range),
            StageItem(tel, regen = false, connected = true, range = worse),
        ))
        assertEquals("~30–45 mi · ~8–12h use · ~5–8 days", line)
    }

    @Test fun nullWhenAnyPackDisconnected() {
        assertNull(stageRangeLine(listOf(
            StageItem(tel, regen = false, connected = true, range = range),
            StageItem(tel, regen = false, connected = false, range = null),
        )))
    }

    @Test fun nullWhenAnyPackHasNoRange() {
        // e.g. one pack charging (its estimate is null) — the recharge ETA owns that slot.
        assertNull(stageRangeLine(listOf(
            StageItem(tel, regen = false, connected = true, range = range),
            StageItem(tel, regen = false, connected = true, range = null),
        )))
    }

    @Test fun nullWhenEmpty() {
        assertNull(stageRangeLine(emptyList()))
    }
}
