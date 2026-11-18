package dev.joely.bmsmon

import dev.joely.bmsmon.model.BatteryState
import dev.joely.bmsmon.model.EstPackState
import dev.joely.bmsmon.model.EstReading
import dev.joely.bmsmon.model.EstSource
import dev.joely.bmsmon.model.SocCorrection
import dev.joely.bmsmon.model.SocEstimate
import dev.joely.bmsmon.model.Telemetry
import dev.joely.bmsmon.model.rawCapacityAh
import dev.joely.bmsmon.model.rawSoc
import dev.joely.bmsmon.model.withEstimate
import dev.joely.bmsmon.model.estimateOf
import dev.joely.bmsmon.model.stepEstimate
import dev.joely.bmsmon.model.voltageSoc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SocEstimateTest {

    private val min = 60_000L

    private fun rd(
        tsMs: Long,
        state: BatteryState = BatteryState.Idle,
        regen: Boolean = false,
        i: Float = 0f,
        v: Float = 13.3f,
        rem: Float,
        full: Float = 105f,
        soc: Float = 99f,
    ) = EstReading(tsMs, state, regen, i, v, rem, full, soc)

    private fun feed(start: EstPackState, vararg rs: EstReading): EstPackState =
        rs.fold(start) { s, r -> stepEstimate(s, r) }

    /** A charge ending on the taper (13.969 V, 2 A) one minute before [endTs]. */
    private fun taperedCharge(endTs: Long, endRem: Float, start: EstPackState = EstPackState()): EstPackState =
        feed(
            start,
            rd(endTs - 3 * min, BatteryState.Charging, i = 8f, v = 13.6f, rem = 110f),
            rd(endTs - 2 * min, BatteryState.Charging, i = 5f, v = 13.9f, rem = 115f),
            rd(endTs - 1 * min, BatteryState.Charging, i = 2f, v = 13.969f, rem = 120f),
            rd(endTs, BatteryState.Idle, rem = endRem),
        )

    private fun restedFor(
        start: EstPackState, from: Long, minutes: Int, stepMs: Long = min,
        v: Float, rem: Float, full: Float, soc: Float,
    ): EstPackState {
        var s = start
        var t = from
        val end = from + minutes * min
        while (t <= end) {
            s = stepEstimate(s, rd(t, v = v, rem = rem, full = full, soc = soc))
            t += stepMs
        }
        return s
    }

    @Test fun missedFullDetectSetsCounterCorrection() {
        val s = taperedCharge(10 * min, endRem = 120.0f)
        assertEquals(15.0f, s.correction.correctionAh, 1e-4f)
        assertEquals(EstSource.COUNTER, s.correction.source)
        assertEquals(10 * min, s.correction.setAtMs)
        val e = estimateOf(s, rd(11 * min, rem = 111.84f, soc = 99f))
        assertEquals(92f, e.soc, 0f)
        assertEquals(96.84f, e.remainingAh, 1e-3f)
        assertEquals(EstSource.COUNTER, e.source)
    }

    @Test fun fullDetectClampClears() {
        val start = EstPackState(correction = SocCorrection(15f, EstSource.COUNTER, 0))
        val s = taperedCharge(10 * min, endRem = 105.0f, start = start)
        assertEquals(0f, s.correction.correctionAh, 0f)
        assertNull(s.correction.source)
    }

    @Test fun partialChargeKeepsVoltageCorrection() {
        val start = EstPackState(correction = SocCorrection(39f, EstSource.VOLTAGE, 0))
        val s = feed(
            start,
            rd(0, BatteryState.Charging, i = 8f, v = 13.5f, rem = 100.4f),
            rd(min, BatteryState.Charging, i = 8f, v = 13.6f, rem = 104.4f),
            rd(2 * min, BatteryState.Charging, i = 8f, v = 13.7f, rem = 108.4f),
            rd(3 * min, BatteryState.Idle, v = 13.5f, rem = 108.4f),
        )
        assertEquals(39f, s.correction.correctionAh, 0f)
        assertEquals(EstSource.VOLTAGE, s.correction.source)
        val e = estimateOf(s, rd(3 * min, rem = 108.4f, soc = 99f))
        assertEquals(69.4f, e.remainingAh, 1e-3f)
    }

    @Test fun partialChargeRaisesLowerBound() {
        val s = feed(
            EstPackState(),
            rd(0, BatteryState.Charging, i = 8f, v = 13.5f, rem = 100f),
            rd(min, BatteryState.Charging, i = 8f, v = 13.6f, rem = 106f),
            rd(2 * min, BatteryState.Idle, v = 13.5f, rem = 108f),
        )
        assertEquals(3.0f, s.correction.correctionAh, 1e-4f)
        assertEquals(EstSource.COUNTER, s.correction.source)
    }

    @Test fun chargeEndAcrossGapWithStaleTaperIsPartial() {
        // A held 30 Ah correction must survive a charge whose taper is 16 min stale at its end
        // (pack reconnected after a gap): partial, lower bound only (120 - 105 = 15 < 30).
        val held = EstPackState(correction = SocCorrection(30f, EstSource.VOLTAGE, 0))
        fun endingAfter(gapMin: Int) = feed(
            held,
            rd(0, BatteryState.Charging, i = 8f, v = 13.6f, rem = 110f),
            rd(min, BatteryState.Charging, i = 2f, v = 13.969f, rem = 120f),
            rd(min + gapMin * min, BatteryState.Idle, rem = 120f),
        )
        val stale = endingAfter(16)
        assertEquals(30f, stale.correction.correctionAh, 0f)
        assertEquals(EstSource.VOLTAGE, stale.correction.source)
        // Exactly 15 min is still within the window: a full charge replaces the correction.
        val fresh = endingAfter(15)
        assertEquals(15f, fresh.correction.correctionAh, 1e-4f)
        assertEquals(EstSource.COUNTER, fresh.correction.source)
    }

    @Test fun regenChargingIsNotACharge() {
        val start = EstPackState(correction = SocCorrection(15f, EstSource.COUNTER, 0))
        var s = start
        val seq = listOf(
            rd(0, BatteryState.Discharging, i = -8f, v = 13.0f, rem = 100f),
            rd(min, BatteryState.Charging, regen = true, i = 12f, v = 14.0f, rem = 100.2f),
            rd(2 * min, BatteryState.Discharging, i = -8f, v = 13.0f, rem = 100f),
        )
        for (r in seq) {
            s = stepEstimate(s, r)
            assertFalse(s.wasCharging)
        }
        assertEquals(15f, s.correction.correctionAh, 0f)
        assertEquals(EstSource.COUNTER, s.correction.source)
    }

    @Test fun unseenChargeClampClears() {
        val s = feed(
            EstPackState(correction = SocCorrection(39f, EstSource.VOLTAGE, 0), lastRawAh = 100.4f),
            rd(5 * min, rem = 105.0f, full = 105f),
        )
        assertEquals(0f, s.correction.correctionAh, 0f)
        assertNull(s.correction.source)
    }

    @Test fun lowerBoundAfterDriving() {
        val s = feed(EstPackState(), rd(0, rem = 111.84f, full = 105f))
        assertEquals(6.84f, s.correction.correctionAh, 1e-3f)
        assertEquals(EstSource.COUNTER, s.correction.source)
    }

    @Test fun spareRestVoltageCorrects() {
        val s = restedFor(EstPackState(), 0, 30, stepMs = 90_000L, v = 13.099f, rem = 100.4f, full = 100.44f, soc = 99f)
        assertEquals(EstSource.VOLTAGE, s.correction.source)
        val e = estimateOf(s, rd(31 * min, v = 13.099f, rem = 100.4f, full = 100.44f, soc = 99f))
        assertEquals(58f, e.soc, 1f)
        assertEquals(EstSource.VOLTAGE, e.source)
    }

    @Test fun notRestedBefore30Min() {
        val s = restedFor(EstPackState(), 0, 29, v = 13.099f, rem = 100.4f, full = 100.44f, soc = 99f)
        assertEquals(0f, s.correction.correctionAh, 0f)
    }

    @Test fun gapRestartsRest() {
        var s = restedFor(EstPackState(), 0, 20, v = 13.099f, rem = 100.4f, full = 100.44f, soc = 99f)
        s = restedFor(s, 31 * min, 15, v = 13.099f, rem = 100.4f, full = 100.44f, soc = 99f)
        assertEquals(0f, s.correction.correctionAh, 0f)
    }

    @Test fun dailyDriverPlateauIgnored() {
        val s = restedFor(EstPackState(), 0, 60, v = 13.325f, rem = 96.6f, full = 105f, soc = 92f)
        assertEquals(0f, s.correction.correctionAh, 0f)
    }

    @Test fun withinMarginIgnored() {
        val s = restedFor(EstPackState(), 0, 45, v = 13.18f, rem = 84f, full = 105f, soc = 80f)
        assertEquals(0f, s.correction.correctionAh, 0f)
    }

    @Test fun voltageFollowsDown() {
        // VOLTAGE correction of 40 Ah on a 100 Ah pack reading 100 -> estimate 60 %.
        val start = EstPackState(correction = SocCorrection(40f, EstSource.VOLTAGE, 0))
        val a = restedFor(start, 0, 40, v = 13.10f, rem = 100f, full = 100f, soc = 99f)
        assertEquals(40f, a.correction.correctionAh, 0f)
        val b = restedFor(a, 41 * min, 5, v = 13.08f, rem = 100f, full = 100f, soc = 99f)
        assertEquals(40f, b.correction.correctionAh, 0f)
        val c = restedFor(b, 47 * min, 2, v = 13.00f, rem = 100f, full = 100f, soc = 99f)
        assertEquals(49f, c.correction.correctionAh, 1e-3f)
        assertEquals(EstSource.VOLTAGE, c.correction.source)
        assertEquals(51f, estimateOf(c, rd(50 * min, v = 13.0f, rem = 100f, full = 100f, soc = 99f)).soc, 0f)
    }

    @Test fun swapHoldsThroughDischarge() {
        var s = EstPackState(correction = SocCorrection(39f, EstSource.VOLTAGE, 0))
        val expected = listOf(100.4f to 61.4f, 95.4f to 56.4f, 90.4f to 51.4f)
        expected.forEachIndexed { n, (raw, ah) ->
            val r = rd(n * min, BatteryState.Discharging, i = -8f, v = 13.0f, rem = raw, full = 100.44f, soc = 99f)
            s = stepEstimate(s, r)
            assertEquals(39f, s.correction.correctionAh, 0f)
            assertEquals(ah, estimateOf(s, r).remainingAh, 1e-3f)
        }
    }

    @Test fun zeroFullChargeLeavesStateAlone() {
        val start = EstPackState(
            correction = SocCorrection(15f, EstSource.COUNTER, 5),
            wasCharging = true, taperAtMs = 3L, restSinceMs = 4L, lastTsMs = 10L, lastRawAh = 99f,
        )
        val r = rd(1000, BatteryState.Idle, rem = 120f, full = 0f)
        assertEquals(start.copy(lastTsMs = 1000), stepEstimate(start, r))
        assertEquals(start.copy(lastTsMs = 7), stepEstimate(start, rd(7, rem = 120f, full = -5f)))
        assertEquals(start.copy(lastTsMs = 8), stepEstimate(start, rd(8, rem = Float.NaN)))
        val e = estimateOf(start, r)
        assertEquals(r.soc, e.soc, 0f)
        assertEquals(r.remainingAh, e.remainingAh, 0f)
    }

    @Test fun onlyLowersProperty() {
        val rnd = Random(7)
        var s = EstPackState()
        var t = 0L
        repeat(2000) {
            t += (rnd.nextInt(1, 200) * 1000L) + if (rnd.nextInt(20) == 0) 20 * min else 0L
            val state = BatteryState.values()[rnd.nextInt(3)]
            val current = when (rnd.nextInt(3)) {
                0 -> 0f
                1 -> rnd.nextFloat() * 20f
                else -> -rnd.nextFloat() * 20f
            }
            val r = EstReading(
                tsMs = t, state = state, regen = rnd.nextInt(5) == 0, currentA = current,
                voltageV = 12.5f + rnd.nextFloat() * 1.6f,
                remainingAh = rnd.nextFloat() * 125f,
                fullChargeAh = 95f + rnd.nextFloat() * 15f,
                soc = (rnd.nextInt(101)).toFloat(),
            )
            s = stepEstimate(s, r)
            val e = estimateOf(s, r)
            assertTrue("soc ${e.soc} > ${r.soc}", e.soc <= r.soc)
            assertTrue("ah ${e.remainingAh} > ${r.remainingAh}", e.remainingAh <= r.remainingAh)
        }
    }

    @Test fun voltageTable() {
        assertEquals(28f, voltageSoc(12.90f)!!, 0f)
        assertEquals(58f, voltageSoc(13.10f)!!, 0f)
        assertEquals(62f, voltageSoc(13.12f)!!, 0f)
        assertEquals(28f, voltageSoc(12.5f)!!, 0f)
        assertNull(voltageSoc(13.30f))
        assertNull(voltageSoc(13.325f))
        assertNull(voltageSoc(Float.NaN))
    }

    @Test fun withEstimateKeepsRaw() {
        val raw = Telemetry(
            name = "p", soc = 99f, powerW = 0f, current = 0f, voltage = 13.2f,
            capacityAh = 111.84f, cellV = 3.3f, temp = 20f,
        )
        val t = raw.withEstimate(SocEstimate(92f, 96.84f, EstSource.COUNTER))
        assertEquals(92f, t.soc, 0f)
        assertEquals(96.84f, t.capacityAh, 0f)
        assertEquals(99f, t.bmsSoc!!, 0f)
        assertEquals(111.84f, t.bmsCapacityAh!!, 0f)
        assertEquals(EstSource.COUNTER, t.estSource)
        assertEquals(99f, t.rawSoc, 0f)
        assertEquals(111.84f, t.rawCapacityAh, 0f)
        assertEquals(99f, raw.rawSoc, 0f)
        assertEquals(111.84f, raw.rawCapacityAh, 0f)
    }
}
