package dev.joely.bmsmon

import dev.joely.bmsmon.model.Band
import dev.joely.bmsmon.model.BatteryState
import dev.joely.bmsmon.model.PackRange
import dev.joely.bmsmon.model.RangeParams
import dev.joely.bmsmon.model.SEED_RANGE_PARAMS
import dev.joely.bmsmon.model.TodayUsage
import dev.joely.bmsmon.model.estimatePackRange
import dev.joely.bmsmon.model.formatRangeLine
import dev.joely.bmsmon.model.minRange
import dev.joely.bmsmon.model.tiltedBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** SHARED VECTORS: web/src/range.test.ts asserts the same numbers — keep in sync. */
class RangeEstimateTest {
    // 70 Ah remaining × 12.8 V = 896 Wh; bands: whPerDay 100–180, activeW 70–100, whPerMile 18–24.
    private val params = RangeParams(
        whPerDay = Band(100f, 180f), activeW = Band(70f, 100f), whPerMile = Band(18f, 24f),
        learnedDays = 10, updatedMs = 0L,
    )

    @Test fun computesAllThreeBands() {
        val r = estimatePackRange(BatteryState.Discharging, 70f, params, today = null)!!
        assertEquals(896f / 24f, r.milesLo, 0.01f)     // 37.33
        assertEquals(896f / 18f, r.milesHi, 0.01f)     // 49.78
        assertEquals(896f / 100f, r.activeHLo, 0.01f)  // 8.96
        assertEquals(896f / 70f, r.activeHHi, 0.01f)   // 12.8
        assertEquals(896f / 180f * 24f, r.wallHLo, 0.01f)  // 119.47
        assertEquals(896f / 100f * 24f, r.wallHHi, 0.01f)  // 215.04
    }

    @Test fun nullWhenCharging() {
        assertNull(estimatePackRange(BatteryState.Charging, 70f, params, null))
    }

    @Test fun nullWhenRemainingUnknown() {
        assertNull(estimatePackRange(BatteryState.Idle, 0f, params, null))
    }

    // SHARED VECTOR: web/src/range.test.ts asserts the same null result for a zero whPerDay band.
    @Test fun nullWhenWhPerDayBandIsZero() {
        val zeroDay = params.copy(whPerDay = Band(0f, 0f))
        assertNull(estimatePackRange(BatteryState.Discharging, 70f, zeroDay, today = null))
    }

    @Test fun idleStillEstimates() {
        val r = estimatePackRange(BatteryState.Idle, 70f, params, null)
        assertEquals(896f / 24f, r!!.milesLo, 0.01f)
    }

    @Test fun tiltShiftsCenterKeepsWidth() {
        // band 100–180 (mid 140, half-width 40); today: 90 Wh over 3 dis-hours, 12 h into the day.
        // w = min(0.5, 3/4 × 0.5) = 0.375; todayWhPerDay = 90 × 24/12 = 180.
        // mid' = 140×0.625 + 180×0.375 = 155 → band 115–195.
        val t = tiltedBand(Band(100f, 180f), todayRate = 180f, w = 0.375f)
        assertEquals(115f, t.lo, 0.01f)
        assertEquals(195f, t.hi, 0.01f)
    }

    @Test fun tiltAppliedThroughEstimate() {
        // Same tilt as above lowers wall-clock time: hi = 896/115×24 = 186.99, lo = 896/195×24 = 110.28.
        val today = TodayUsage(disWh = 90f, disHours = 3f, hoursSinceMidnight = 12f)
        val r = estimatePackRange(BatteryState.Discharging, 70f, params, today)!!
        assertEquals(896f / 195f * 24f, r.wallHLo, 0.05f)
        assertEquals(896f / 115f * 24f, r.wallHHi, 0.05f)
        // whPerMile is never tilted.
        assertEquals(896f / 24f, r.milesLo, 0.01f)
    }

    @Test fun noTiltUnderQuarterHourDischarge() {
        val today = TodayUsage(disWh = 5f, disHours = 0.1f, hoursSinceMidnight = 2f)
        val r = estimatePackRange(BatteryState.Discharging, 70f, params, today)!!
        assertEquals(896f / 180f * 24f, r.wallHLo, 0.01f)  // untouched history band
    }

    @Test fun minAcrossPacksTakesWorstPerFigure() {
        val a = PackRange(37f, 50f, 9f, 13f, 119f, 215f)
        val b = PackRange(40f, 45f, 8f, 14f, 125f, 200f)
        val m = minRange(listOf(a, b))
        assertEquals(37f, m.milesLo, 0f); assertEquals(45f, m.milesHi, 0f)
        assertEquals(8f, m.activeHLo, 0f); assertEquals(13f, m.activeHHi, 0f)
        assertEquals(119f, m.wallHLo, 0f); assertEquals(200f, m.wallHHi, 0f)
    }

    @Test fun formatsWholeMilesHoursAndDays() {
        assertEquals("~37–50 mi · ~9–13h use · ~5–9 days",
            formatRangeLine(PackRange(37.33f, 49.78f, 8.96f, 12.8f, 119.47f, 215.04f)))
    }

    @Test fun formatsDecimalMilesWhenLow() {
        assertEquals("~1.5–2.4 mi · ~0–1h use · ~34–42h",
            formatRangeLine(PackRange(1.5f, 2.4f, 0.4f, 0.6f, 34.2f, 42.1f)))
    }

    @Test fun seedParamsMatchSpec() {
        assertEquals(78f, SEED_RANGE_PARAMS.whPerDay.lo, 0f)
        assertEquals(182f, SEED_RANGE_PARAMS.whPerDay.hi, 0f)
        assertEquals(52.5f, SEED_RANGE_PARAMS.activeW.lo, 0f)
        assertEquals(97.5f, SEED_RANGE_PARAMS.activeW.hi, 0f)
        assertEquals(51f, SEED_RANGE_PARAMS.whPerMile.lo, 0f)
        assertEquals(85f, SEED_RANGE_PARAMS.whPerMile.hi, 0f)
        assertEquals(0, SEED_RANGE_PARAMS.learnedDays)
    }
}
