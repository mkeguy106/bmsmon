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
import dev.joely.bmsmon.model.DrainModel
import dev.joely.bmsmon.model.SEED_DRAIN_AH_PER_DAY
import dev.joely.bmsmon.model.DRAIN_EMA_ALPHA
import dev.joely.bmsmon.model.estPersistWorthy
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
        pending: Float = 0f,
    ) = EstReading(tsMs, state, regen, i, v, rem, full, soc, pending)

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
        // Read at the charge's own end: a full charge also anchors the standby drain there.
        val e = estimateOf(s, rd(10 * min, rem = 111.84f, soc = 99f))
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

    // What makes the engine write the per-pack state: a changed correction, or a charge end (the
    // raw counter has just risen, so the persisted last raw would otherwise be stale-low and a
    // restart could read a later clamp as an unseen charge). Not every frame: lastRawAh moves on
    // every frame and is written along with those.
    @Test fun persistOnCorrectionChangeOrChargeEnd() {
        val base = EstPackState(correction = SocCorrection(15f, EstSource.COUNTER, 0), lastRawAh = 60f)
        assertFalse(estPersistWorthy(base, base.copy(lastRawAh = 59f, lastTsMs = 5L)))
        assertTrue(estPersistWorthy(base, base.copy(correction = SocCorrection(16f, EstSource.COUNTER, 5L))))
        assertTrue(estPersistWorthy(base.copy(wasCharging = true), base.copy(lastRawAh = 90f)))
        assertFalse(estPersistWorthy(base, base.copy(wasCharging = true)))
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
        assertEquals(38f, e.soc, 1f)
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
        // 13.19 V reads 64 %: inside the 10-point first-correction margin of a 72 % estimate.
        val s = restedFor(EstPackState(), 0, 45, v = 13.19f, rem = 75.6f, full = 105f, soc = 72f)
        assertEquals(0f, s.correction.correctionAh, 0f)
    }

    @Test fun voltageFollowsDown() {
        // VOLTAGE correction of 40 Ah on a 100 Ah pack reading 100 -> estimate 60 %.
        val start = EstPackState(correction = SocCorrection(40f, EstSource.VOLTAGE, 0))
        // 13.18 V reads 60.5 %, 13.17 V reads 57 %: neither is more than 3 below 60, so it holds.
        val a = restedFor(start, 0, 40, v = 13.18f, rem = 100f, full = 100f, soc = 99f)
        assertEquals(40f, a.correction.correctionAh, 0f)
        val b = restedFor(a, 41 * min, 5, v = 13.17f, rem = 100f, full = 100f, soc = 99f)
        assertEquals(40f, b.correction.correctionAh, 0f)
        // 13.13 V reads 46 %: follows down.
        val c = restedFor(b, 47 * min, 2, v = 13.13f, rem = 100f, full = 100f, soc = 99f)
        assertEquals(54f, c.correction.correctionAh, 1e-3f)
        assertEquals(EstSource.VOLTAGE, c.correction.source)
        assertEquals(46f, estimateOf(c, rd(50 * min, v = 13.13f, rem = 100f, full = 100f, soc = 99f)).soc, 0f)
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
                pendingAh = if (rnd.nextInt(4) == 0) (rnd.nextFloat() - 0.7f) * 80f else 0f,
            )
            s = stepEstimate(s, r)
            val e = estimateOf(s, r)
            assertTrue("soc ${e.soc} > ${r.soc}", e.soc <= r.soc)
            assertTrue("ah ${e.remainingAh} > ${r.remainingAh}", e.remainingAh <= r.remainingAh)
        }
    }

    // --- The BMS re-anchoring its own counter (pendingAh < 0). Values: prod, 2026-11-25. ---

    @Test fun bmsRecalibrationShowsItsTargetAndDropsOurCorrection() {
        // 2012-B: our held 15 Ah counter correction, BMS lowering its counter toward 25 Ah.
        val held = EstPackState(correction = SocCorrection(15f, EstSource.COUNTER, 0))
        val r = rd(10 * min, v = 13.015f, rem = 53.23f, full = 105f, soc = 50f, pending = -28.25f)
        val s = stepEstimate(held, r)
        assertEquals(0f, s.correction.correctionAh, 0f)
        assertNull(s.correction.source)
        val e = estimateOf(s, r)
        assertEquals(24.98f, e.remainingAh, 1e-3f)
        assertEquals(24f, e.soc, 0f)
        assertEquals(EstSource.VOLTAGE, e.source)
    }

    @Test fun bmsRecalibrationOnAPackWithNoCorrection() {
        val r = rd(10 * min, v = 13.032f, rem = 44.61f, full = 105f, soc = 42f, pending = -19.64f)
        val e = estimateOf(stepEstimate(EstPackState(), r), r)
        assertEquals(24.97f, e.remainingAh, 1e-3f)
        assertEquals(24f, e.soc, 0f)
        assertEquals(EstSource.VOLTAGE, e.source)
    }

    @Test fun pendingAndHeldCorrectionAreNeverSubtractedTogether() {
        // An un-stepped state still holding 15 Ah: the lower of the two, not their sum.
        val held = EstPackState(correction = SocCorrection(15f, EstSource.COUNTER, 0))
        val e = estimateOf(held, rd(0, rem = 53.23f, full = 105f, soc = 50f, pending = -28.25f))
        assertEquals(24.98f, e.remainingAh, 1e-3f)
    }

    @Test fun estimateIsRawAgainOnceTheBmsHasFinished() {
        val held = EstPackState(correction = SocCorrection(15f, EstSource.COUNTER, 0))
        var s = stepEstimate(held, rd(0, rem = 53.23f, full = 105f, soc = 50f, pending = -28.25f, v = 13.02f))
        val done = rd(min, rem = 25.2f, full = 105f, soc = 24f, pending = 0f, v = 13.02f)
        s = stepEstimate(s, done)
        val e = estimateOf(s, done)
        assertEquals(25.2f, e.remainingAh, 0f)
        assertEquals(24f, e.soc, 0f)
        assertNull(e.source)
    }

    @Test fun noVoltageCorrectionWhileTheBmsIsRecalibrating() {
        // Rested an hour at a voltage the table reads far below the counter: the BMS is already
        // correcting, so we hold nothing of our own.
        var s = EstPackState()
        var t = 0L
        while (t <= 60 * min) {
            s = stepEstimate(s, rd(t, v = 12.95f, rem = 70f, full = 105f, soc = 67f, pending = -40f))
            t += min
        }
        assertEquals(0f, s.correction.correctionAh, 0f)
    }

    @Test fun positivePendingChangesNothing() {
        // Never seen in the field. A BMS raising its counter must not raise or clear our estimate.
        val held = EstPackState(correction = SocCorrection(15f, EstSource.COUNTER, 0))
        val r = rd(0, rem = 53.23f, full = 105f, soc = 50f, pending = 5f)
        val s = stepEstimate(held, r)
        assertEquals(15f, s.correction.correctionAh, 0f)
        val e = estimateOf(s, r)
        assertEquals(38.23f, e.remainingAh, 1e-3f)
        assertEquals(EstSource.COUNTER, e.source)
    }

    @Test fun nonFinitePendingLeavesStateAlone() {
        val held = EstPackState(correction = SocCorrection(15f, EstSource.COUNTER, 0))
        val r = rd(0, rem = 53.23f, full = 105f, soc = 50f, pending = Float.NaN)
        assertEquals(held.correction, stepEstimate(held, r).correction)
        assertEquals(50f, estimateOf(held, r).soc, 0f)
    }

    // --- Standby drain: the counter is right at a full charge, then misses the load under the
    // BMS's deadband (~3 Ah/day on the chair). Figures: 2012-A, prod, 2026-11-11 -> 11-17. ---

    private val day = 24 * 60 * min

    /** Anchored by a tapered full charge that the BMS clamped to full, ending at [endTs]. */
    private fun anchoredAt(endTs: Long, ratePerDay: Float? = null): EstPackState {
        val s = taperedCharge(endTs, endRem = 105f, start = EstPackState(drain = DrainModel(ahPerDay = ratePerDay)))
        assertEquals(endTs, s.drain.anchorAtMs)
        return s
    }

    /** Drives the counter down to [rem] at [ts] (one discharging frame, then one idle a minute later). */
    private fun drivenTo(s: EstPackState, ts: Long, rem: Float): EstPackState = feed(
        s,
        rd(ts - min, BatteryState.Discharging, i = -10f, v = 13.0f, rem = rem + 0.2f, soc = 80f),
        rd(ts, rem = rem, v = 13.2f, soc = 80f),
    )

    /** A steady charge from the reading at [startTs], then a taper and a clamp to full. Returns
     *  the state after the clamp and the Ah the frames delivered. */
    private fun fullChargeFrom(
        s0: EstPackState, startTs: Long, startRem: Float, amps: Float = 8f, minutes: Int,
        stepMs: Long = min, skipMinutes: IntRange? = null, endRem: Float = 105f, taper: Boolean = true,
    ): Pair<EstPackState, Float> {
        var s = s0
        var ah = 0f
        var last = startTs
        var rem = startRem
        for (m in 1..minutes) {
            if (skipMinutes != null && m in skipMinutes) continue
            val t = startTs + m * stepMs
            val d = amps * (t - last) / 3_600_000f
            ah += d
            rem = kotlin.math.min(120f, rem + d)
            s = stepEstimate(s, rd(t, BatteryState.Charging, i = amps, v = 13.6f, rem = rem, soc = 99f))
            last = t
        }
        val t = last + stepMs
        if (!taper) return stepEstimate(s, rd(t, rem = endRem, v = 13.4f, soc = 90f)) to ah
        ah += 2f * stepMs / 3_600_000f
        s = stepEstimate(s, rd(t, BatteryState.Charging, i = 2f, v = 13.97f, rem = rem, soc = 99f))
        s = stepEstimate(s, rd(t + stepMs, rem = endRem, soc = 100f))
        return s to ah
    }

    @Test fun noDrainWithoutAFullChargeSeen() {
        val r = rd(10 * day, rem = 80f, soc = 76f)
        val e = estimateOf(stepEstimate(EstPackState(), r), r)
        assertEquals(80f, e.remainingAh, 0f)
        assertNull(e.source)
    }

    @Test fun drainAccruesFromTheFullChargeAtTheSeedRate() {
        val s = drivenTo(anchoredAt(0), 4 * day, rem = 80f)
        val e = estimateOf(s, rd(4 * day, rem = 80f, soc = 76f))
        assertEquals(80f - SEED_DRAIN_AH_PER_DAY * 4f, e.remainingAh, 1e-3f)
        assertEquals(67f, e.soc, 0f)
        assertEquals(EstSource.COUNTER, e.source)
    }

    @Test fun drainStopsGrowingAfterFourteenDays() {
        val s = anchoredAt(0, ratePerDay = 2f)
        assertEquals(100f - 28f, estimateOf(s, rd(40 * day, rem = 100f, soc = 95f)).remainingAh, 1e-3f)
    }

    @Test fun learnsTheRateFromWhatTheChargerDelivers() {
        // Counter says 83.7 Ah after 5.78 days; the charger then puts in ~34.9 Ah to reach full,
        // so the pack really held ~70.1 Ah: 13.6 Ah of drain, 2.35 Ah/day.
        val start = (5.78f * day).toLong()
        val s = drivenTo(anchoredAt(0), start, rem = 83.7f)
        val (done, ah) = fullChargeFrom(s, start, startRem = 83.7f, minutes = 261)
        val rate = (ah - (105f - 83.7f)) / 5.78f
        assertEquals(2.35f, rate, 0.05f)
        assertEquals(rate, done.drain.ahPerDay!!, 0.01f)
        assertEquals(start + 263 * min, done.drain.anchorAtMs)
        // Full again: the estimate is the BMS reading, and the drain starts over.
        assertEquals(105f, estimateOf(done, rd(start + 263 * min, rem = 105f, soc = 100f)).remainingAh, 1e-3f)
    }

    @Test fun aNewRateIsBlendedWithTheOldOne() {
        val start = 5 * day
        val s = drivenTo(anchoredAt(0, ratePerDay = 4f), start, rem = 85f)
        val (done, ah) = fullChargeFrom(s, start, startRem = 85f, minutes = 225)
        val measured = (85f - (105f - ah)) / 5f
        assertEquals(4f + DRAIN_EMA_ALPHA * (measured - 4f), done.drain.ahPerDay!!, 0.01f)
    }

    @Test fun aChargeWithAGapInItReAnchorsButDoesNotLearn() {
        val start = 5 * day
        val s = drivenTo(anchoredAt(0, ratePerDay = 4f), start, rem = 85f)
        // Frames at minutes 100-102 never arrive: three unseen minutes of charging.
        val (done, _) = fullChargeFrom(s, start, startRem = 85f, minutes = 225, skipMinutes = 100..102)
        assertEquals(4f, done.drain.ahPerDay!!, 0f)
        assertEquals(start + 227 * min, done.drain.anchorAtMs)
    }

    @Test fun aChargeFirstSeenPartWayThroughDoesNotLearn() {
        // After a restart the first frame is already charging: the Ah before it were never seen.
        val start = 5 * day
        val restarted = EstPackState(drain = DrainModel(anchorAtMs = 0, ahPerDay = 4f, anchorRawAh = 105f))
        val (done, _) = fullChargeFrom(restarted, start, startRem = 95f, minutes = 60)
        assertEquals(4f, done.drain.ahPerDay!!, 0f)
        assertEquals(start + 62 * min, done.drain.anchorAtMs)
    }

    @Test fun anIntervalUnderADayDoesNotLearn() {
        val start = 17 * 60 * min
        val s = drivenTo(anchoredAt(0, ratePerDay = 3f), start, rem = 92f)
        val (done, _) = fullChargeFrom(s, start, startRem = 92f, minutes = 120)
        assertEquals(3f, done.drain.ahPerDay!!, 0f)
    }

    @Test fun anImplausibleRateIsIgnored() {
        // The charger delivered less than the counter said was missing: a negative drain.
        val start = 5 * day
        val s = drivenTo(anchoredAt(0, ratePerDay = 3f), start, rem = 60f)
        val (done, _) = fullChargeFrom(s, start, startRem = 60f, minutes = 60)
        assertEquals(3f, done.drain.ahPerDay!!, 0f)
    }

    @Test fun aPackThatMissesFullDetectLearnsFromItsOwnCounterDrop() {
        // 2012-B: the counter ends each charge pinned at 120, not clamped to 105. The rate comes
        // from how far the counter fell against what the charger put back, so the offset cancels.
        val start = (5.78f * day).toLong()
        val anchored = taperedCharge(0, endRem = 120f)
        assertEquals(120f, anchored.drain.anchorRawAh!!, 0f)
        val s = drivenTo(anchored, start, rem = 98.7f)
        val (done, ah) = fullChargeFrom(s, start, startRem = 98.7f, minutes = 261, endRem = 120f)
        assertEquals((ah - (120f - 98.7f)) / 5.78f, done.drain.ahPerDay!!, 0.01f)
        assertEquals(2.35f, done.drain.ahPerDay!!, 0.05f)
    }

    @Test fun aCounterLeftBelowFullByTheBmsStillLearnsTheRightRate() {
        // After a BMS re-anchor a full charge can end with the counter under full and no clamp.
        val start = (5.78f * day).toLong()
        val anchored = taperedCharge(0, endRem = 100f)
        assertEquals(0f, anchored.correction.correctionAh, 0f)
        val s = drivenTo(anchored, start, rem = 78.7f)
        val (done, ah) = fullChargeFrom(s, start, startRem = 78.7f, minutes = 261, endRem = 100f)
        assertEquals((ah - (100f - 78.7f)) / 5.78f, done.drain.ahPerDay!!, 0.01f)
    }

    @Test fun aHeldCorrectionDoesNotChangeTheLearnedRate() {
        val start = 5 * day
        var s = drivenTo(anchoredAt(0), start, rem = 85f)
        s = s.copy(correction = SocCorrection(5f, EstSource.VOLTAGE, start))
        val (done, ah) = fullChargeFrom(s, start, startRem = 85f, minutes = 225)
        assertEquals((ah - (105f - 85f)) / 5f, done.drain.ahPerDay!!, 0.01f)
    }

    @Test fun anUnseenChargeThatDidNotReachFullStopsTheNextLearn() {
        // The counter rose 10 Ah between sightings: the fall since the anchor is no longer known.
        var s = drivenTo(anchoredAt(0, ratePerDay = 3f), 2 * day, rem = 70f)
        s = stepEstimate(s, rd(3 * day, rem = 80f, v = 13.3f, soc = 76f))
        assertNull(s.drain.anchorRawAh)
        assertEquals(0L, s.drain.anchorAtMs)
        val start = 5 * day
        s = drivenTo(s, start, rem = 75f)
        val (done, _) = fullChargeFrom(s, start, startRem = 75f, minutes = 240)
        assertEquals(3f, done.drain.ahPerDay!!, 0f)
        assertEquals(105f, done.drain.anchorRawAh!!, 0f)
    }

    @Test fun aPauseInsideTheChargeKeepsCounting() {
        val start = 5 * day
        val s = drivenTo(anchoredAt(0), start, rem = 85f)
        // An hour of charging that stops without a taper, five idle minutes, then on to full.
        var (mid, ah1) = fullChargeFrom(s, start, startRem = 85f, minutes = 60, endRem = 93f, taper = false)
        assertEquals(0L, mid.drain.anchorAtMs)
        var t = start + 61 * min
        repeat(5) { t += min; mid = stepEstimate(mid, rd(t, rem = 93f, v = 13.4f, soc = 88f)) }
        val (done, ah2) = fullChargeFrom(mid, t, startRem = 93f, minutes = 150)
        val rate = (85f - (105f - (ah1 + ah2))) / 5f
        assertEquals(rate, done.drain.ahPerDay!!, 0.02f)
    }

    @Test fun drivingBetweenTwoChargesCountsOnlyTheLastOne() {
        val start = 5 * day
        var s = drivenTo(anchoredAt(0), start, rem = 70f)
        // An hour's top-up with no taper (70 -> 78 Ah), then driving down to 75.
        var t = start
        repeat(60) { t += min; s = stepEstimate(s, rd(t, BatteryState.Charging, i = 8f, v = 13.5f, rem = 70f + 8f * (it + 1) / 60f)) }
        s = drivenTo(s, t + 3 * 60 * min, rem = 75f)
        val second = t + 3 * 60 * min
        val (done, ah) = fullChargeFrom(s, second, startRem = 75f, minutes = 300)
        val days = second.toFloat() / day
        assertEquals((75f - (105f - ah)) / days, done.drain.ahPerDay!!, 0.02f)
    }

    @Test fun theBmsReAnchorDropsOurAnchor() {
        val s = drivenTo(anchoredAt(0, ratePerDay = 3f), 4 * day, rem = 57.72f)
        val r = rd(4 * day + min, v = 13.04f, rem = 57.72f, soc = 54f, pending = -32.72f)
        val after = stepEstimate(s, r)
        assertNull(after.drain.anchorAtMs)
        assertEquals(3f, after.drain.ahPerDay!!, 0f)
        assertEquals(25f, estimateOf(after, r).remainingAh, 1e-3f)
        // Once the BMS has finished there is no drain term until the next full charge.
        val done = rd(6 * day, v = 13.04f, rem = 25.1f, soc = 24f)
        assertEquals(25.1f, estimateOf(stepEstimate(after, done), done).remainingAh, 0f)
    }

    @Test fun theVoltageRuleDoesNotCountTheDrainTwice() {
        // 100 Ah pack, 4 days at 2.5 Ah/day: estimate 90 %. Rested at 13.13 V (46 %): the estimate
        // becomes 46 %, not 46 % minus the drain again.
        var s = taperedCharge(0, endRem = 100f, start = EstPackState()).let {
            feed(it, rd(min, rem = 100f, full = 100f, soc = 99f))
        }
        s = s.copy(correction = SocCorrection(), drain = DrainModel(anchorAtMs = 0, ahPerDay = 2.5f))
        s = restedFor(s, 4 * day, 40, v = 13.13f, rem = 100f, full = 100f, soc = 99f)
        assertEquals(EstSource.VOLTAGE, s.correction.source)
        val e = estimateOf(s, rd(4 * day + 40 * min, v = 13.13f, rem = 100f, full = 100f, soc = 99f))
        assertEquals(46f, e.soc, 1f)
    }

    @Test fun aChangedAnchorOrRateIsWorthPersisting() {
        val a = EstPackState(drain = DrainModel(anchorAtMs = 5, ahPerDay = 3f, anchorRawAh = 105f))
        assertTrue(estPersistWorthy(a, a.copy(drain = a.drain.copy(anchorRawAh = null))))
        assertTrue(estPersistWorthy(a, a.copy(drain = a.drain.copy(anchorAtMs = null))))
        assertTrue(estPersistWorthy(a, a.copy(drain = a.drain.copy(ahPerDay = 2f))))
        assertFalse(estPersistWorthy(a, a.copy(lastTsMs = 9)))
    }

    @Test fun voltageTable() {
        // Refit 2026-11-25 against charger-delivered Ah (the BMS counter the first table was
        // fitted to runs ~3 Ah/day high): 13.01 V rested is ~34 %, not ~52 %.
        assertEquals(21f, voltageSoc(12.90f)!!, 0f)
        assertEquals(21f, voltageSoc(12.5f)!!, 0f)
        assertEquals(30f, voltageSoc(12.97f)!!, 0f)
        assertEquals(34f, voltageSoc(13.01f)!!, 0f)
        assertEquals(38f, voltageSoc(13.11f)!!, 0f)
        assertEquals(42f, voltageSoc(13.12f)!!, 0f)
        assertEquals(64f, voltageSoc(13.19f)!!, 0f)
        assertEquals(71f, voltageSoc(13.295f)!!, 0f)
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
