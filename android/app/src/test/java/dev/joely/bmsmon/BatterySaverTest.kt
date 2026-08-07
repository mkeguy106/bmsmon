package dev.joely.bmsmon

import dev.joely.bmsmon.model.BRIGHTNESS_RELEASE
import dev.joely.bmsmon.model.DEFAULT_DIM_LEVEL
import dev.joely.bmsmon.model.LOCK_REFRESH_HZ
import dev.joely.bmsmon.model.MIN_DIM_LEVEL
import dev.joely.bmsmon.model.MOTION_STALE_MS
import dev.joely.bmsmon.model.PARKED_HOLD_MS
import dev.joely.bmsmon.model.MotionGate
import dev.joely.bmsmon.model.MotionReading
import dev.joely.bmsmon.model.STILL_CONFIDENCE_MIN
import dev.joely.bmsmon.model.STILL_DEBOUNCE_N
import dev.joely.bmsmon.model.foldMotion
import dev.joely.bmsmon.model.gpsParked
import dev.joely.bmsmon.model.gpsShouldRun
import dev.joely.bmsmon.model.lockBrightness
import dev.joely.bmsmon.model.lockRefreshRate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatterySaverTest {

    // ── lockRefreshRate ──────────────────────────────────────────────────────
    // 60 Hz measured ~18 mA cheaper than 90 on a Pixel 6; 30 Hz measured no
    // further gain, so 60 is the floor we ask for. 0f means "system default".

    @Test fun refreshRateIsSixtyOnlyWhenLockedAndEnabled() {
        assertEquals(LOCK_REFRESH_HZ, lockRefreshRate(locked = true, enabled = true), 0f)
        assertEquals(60f, lockRefreshRate(locked = true, enabled = true), 0f)
    }

    @Test fun refreshRateIsSystemDefaultOtherwise() {
        assertEquals(0f, lockRefreshRate(locked = true, enabled = false), 0f)
        assertEquals(0f, lockRefreshRate(locked = false, enabled = true), 0f)
        assertEquals(0f, lockRefreshRate(locked = false, enabled = false), 0f)
    }

    // ── lockBrightness ───────────────────────────────────────────────────────

    @Test fun brightnessReleasesWhenOff() {
        assertEquals(BRIGHTNESS_RELEASE, lockBrightness(true, false, 0.3f), 0f)
        assertEquals(BRIGHTNESS_RELEASE, lockBrightness(false, true, 0.3f), 0f)
        assertEquals(BRIGHTNESS_RELEASE, lockBrightness(false, false, 0.3f), 0f)
    }

    @Test fun brightnessAppliesLevelWhenLockedAndEnabled() {
        assertEquals(0.30f, lockBrightness(true, true, DEFAULT_DIM_LEVEL), 0.0001f)
        assertEquals(0.75f, lockBrightness(true, true, 0.75f), 0.0001f)
    }

    // A slider dragged to zero must not produce a black screen: this display is
    // mounted on a power wheelchair and unreadable is a safety problem.
    @Test fun brightnessNeverGoesBelowTheFloor() {
        assertEquals(MIN_DIM_LEVEL, lockBrightness(true, true, 0f), 0.0001f)
        assertEquals(MIN_DIM_LEVEL, lockBrightness(true, true, -5f), 0.0001f)
    }

    @Test fun brightnessClampsAboveOne() {
        assertEquals(1f, lockBrightness(true, true, 2f), 0.0001f)
    }

    // ── gpsParked ────────────────────────────────────────────────────────────
    // The chair cannot move without discharging a pack, so "no base has
    // discharged for PARKED_HOLD_MS" means parked and GPS teaches nothing.

    @Test fun neverDischargedIsParked() {
        assertTrue(gpsParked(lastDischargeMs = null, nowMs = 1_000_000L))
    }

    @Test fun recentDischargeIsNotParked() {
        val now = 10_000_000L
        assertFalse(gpsParked(now - 1L, now))
        assertFalse(gpsParked(now - PARKED_HOLD_MS + 1L, now))
    }

    // Boundary: fires AT the threshold, matching the alert-ladder convention.
    @Test fun exactlyAtHoldIsParked() {
        val now = 10_000_000L
        assertTrue(gpsParked(now - PARKED_HOLD_MS, now))
    }

    @Test fun beyondHoldIsParked() {
        val now = 10_000_000L
        assertTrue(gpsParked(now - PARKED_HOLD_MS - 1L, now))
        assertTrue(gpsParked(now - 86_400_000L, now))
    }

    @Test fun holdIsFiveMinutes() {
        assertEquals(5 * 60_000L, PARKED_HOLD_MS)
    }

    // ── gpsShouldRun ─────────────────────────────────────────────────────────
    // The composed rule MonitorEngine.applyGpsGate delegates to.

    @Test fun gpsRunsWhenWantedAndMoving() {
        val now = 10_000_000L
        assertTrue(
            gpsShouldRun(
                wanted = true, pauseEnabled = true,
                lastDischargeMs = now - 1000L, nowMs = now, confidentlyStill = true,
            ),
        )
    }

    @Test fun gpsStopsWhenParked() {
        val now = 10_000_000L
        assertFalse(
            gpsShouldRun(
                true, pauseEnabled = true,
                lastDischargeMs = now - PARKED_HOLD_MS, nowMs = now, confidentlyStill = true,
            ),
        )
    }

    // With the toggle off, parking is irrelevant — this is the opt-out path.
    @Test fun gpsIgnoresParkedWhenPauseDisabled() {
        val now = 10_000_000L
        assertTrue(
            gpsShouldRun(
                true, pauseEnabled = false,
                lastDischargeMs = null, nowMs = now, confidentlyStill = true,
            ),
        )
    }

    // The parked gate can only ever SUBTRACT from what the cloud settings want.
    @Test fun gpsNeverRunsWhenNotWanted() {
        val now = 10_000_000L
        assertFalse(
            gpsShouldRun(
                false, pauseEnabled = false,
                lastDischargeMs = now, nowMs = now, confidentlyStill = true,
            ),
        )
        assertFalse(
            gpsShouldRun(
                false, pauseEnabled = true,
                lastDischargeMs = now, nowMs = now, confidentlyStill = true,
            ),
        )
    }

    // ── gpsShouldRun with the motion gate ────────────────────────────────────
    // Pausing now needs BOTH conditions: chair not discharging AND phone still.

    @Test fun pausesOnlyWhenParkedAndStill() {
        val now = 10_000_000L
        assertFalse(
            gpsShouldRun(
                wanted = true, pauseEnabled = true,
                lastDischargeMs = now - PARKED_HOLD_MS, nowMs = now, confidentlyStill = true,
            ),
        )
    }

    // THE TRANSIT CASE: chair drew nothing for an hour (it is in a van), but the phone is
    // moving, so GPS must stay on. This is the entire point of the feature.
    @Test fun parkedButMovingKeepsGpsOn() {
        val now = 10_000_000L
        assertTrue(
            gpsShouldRun(
                wanted = true, pauseEnabled = true,
                lastDischargeMs = now - 3_600_000L, nowMs = now, confidentlyStill = false,
            ),
        )
    }

    @Test fun recentDischargeKeepsGpsOnRegardlessOfStillness() {
        val now = 10_000_000L
        for (still in booleanArrayOf(true, false)) {
            assertTrue(
                gpsShouldRun(
                    wanted = true, pauseEnabled = true,
                    lastDischargeMs = now - 1_000L, nowMs = now, confidentlyStill = still,
                ),
            )
        }
    }

    @Test fun pauseDisabledIgnoresBothConditions() {
        val now = 10_000_000L
        assertTrue(
            gpsShouldRun(
                wanted = true, pauseEnabled = false,
                lastDischargeMs = null, nowMs = now, confidentlyStill = true,
            ),
        )
    }

    // The gate can still only ever SUBTRACT from what the cloud settings want.
    @Test fun neverRunsWhenNotWantedWhateverTheMotionState() {
        val now = 10_000_000L
        for (still in booleanArrayOf(true, false)) {
            for (pause in booleanArrayOf(true, false)) {
                assertFalse(
                    gpsShouldRun(
                        wanted = false, pauseEnabled = pause,
                        lastDischargeMs = now, nowMs = now, confidentlyStill = still,
                    ),
                )
            }
        }
    }

    // ── foldMotion ───────────────────────────────────────────────────────────
    // Asymmetric hysteresis (2026-08-07 amendment): closing the gate (pausing GNSS) needs
    // STILL_DEBOUNCE_N consecutive confident-STILL readings; reopening needs only one confident
    // non-STILL reading; uncertainty (confidence < STILL_CONFIDENCE_MIN, including UNKNOWN at any
    // confidence) HOLDS the previous state rather than resetting it — measured on-device as
    // STILL@96-100 interleaved with UNKNOWN@41-50, never confident motion, so treating UNKNOWN as
    // "moving" was the whole bug. null/stale readings still fail open immediately, same as the
    // deleted confidentlyStill()'s contract, because false/MotionGate() means GPS STAYS ON.

    private fun reading(still: Boolean, conf: Int, age: Long, now: Long = 10_000_000L) =
        MotionReading(still = still, confidence = conf, atMs = now - age)

    @Test fun noReadingFailsOpen() {
        val prev = MotionGate(stillRun = 2, still = false)
        assertEquals(MotionGate(), foldMotion(prev, null, 10_000_000L))
    }

    @Test fun staleReadingFailsOpen() {
        val now = 10_000_000L
        val prev = MotionGate(stillRun = 2, still = false)
        val stale = reading(still = true, conf = 99, age = MOTION_STALE_MS + 1, now = now)
        assertEquals(MotionGate(), foldMotion(prev, stale, now))
    }

    // Boundary is inclusive, matching the alert-ladder convention: exactly at the staleness
    // bound is still fresh, so it counts toward the run rather than resetting it.
    @Test fun exactlyAtStalenessBoundIsFreshAndCounts() {
        val now = 10_000_000L
        val fresh = reading(still = true, conf = 99, age = MOTION_STALE_MS, now = now)
        assertEquals(MotionGate(stillRun = 1, still = false), foldMotion(MotionGate(), fresh, now))
    }

    @Test fun oneConfidentStillIsNotYetStill() {
        val now = 10_000_000L
        val gate = foldMotion(MotionGate(), reading(still = true, conf = 99, age = 0, now = now), now)
        assertEquals(1, gate.stillRun)
        assertFalse(gate.still)
    }

    @Test fun debounceConsecutiveConfidentStillClosesTheGate() {
        val now = 10_000_000L
        var gate = MotionGate()
        repeat(STILL_DEBOUNCE_N - 1) {
            gate = foldMotion(gate, reading(still = true, conf = 99, age = 0, now = now), now)
            assertFalse(gate.still)
        }
        gate = foldMotion(gate, reading(still = true, conf = 99, age = 0, now = now), now)
        assertTrue(gate.still)
        assertEquals(STILL_DEBOUNCE_N, gate.stillRun)
    }

    // UNKNOWN@41 mid-run: absence of evidence, not evidence of motion. The run must hold, not
    // reset — this is the exact defect confidentlyStill() had.
    @Test fun uncertainSampleMidRunHoldsRatherThanResets() {
        val now = 10_000_000L
        var gate = foldMotion(MotionGate(), reading(still = true, conf = 99, age = 0, now = now), now)
        assertEquals(1, gate.stillRun)
        val held = foldMotion(gate, reading(still = false, conf = 41, age = 0, now = now), now)
        assertEquals(gate, held)
        // The hold contributed nothing: the run still only needs STILL_DEBOUNCE_N - 1 more
        // confident-STILL readings to close, not a fresh count from zero.
        repeat(STILL_DEBOUNCE_N - 1) {
            gate = foldMotion(gate, reading(still = true, conf = 99, age = 0, now = now), now)
        }
        assertTrue(gate.still)
    }

    @Test fun confidentNonStillReopensImmediatelyFromClosedGate() {
        val now = 10_000_000L
        val closed = MotionGate(stillRun = STILL_DEBOUNCE_N, still = true)
        val moving = reading(still = false, conf = 99, age = 0, now = now)
        assertEquals(MotionGate(), foldMotion(closed, moving, now))
    }

    @Test fun uncertainSampleWhileClosedKeepsItClosed() {
        val now = 10_000_000L
        val closed = MotionGate(stillRun = STILL_DEBOUNCE_N, still = true)
        val unknown = reading(still = false, conf = 50, age = 0, now = now)
        assertEquals(closed, foldMotion(closed, unknown, now))
    }

    // STILL@38 from the measured trace: still=true but below STILL_CONFIDENCE_MIN, so it is
    // uncertain too and must hold rather than count toward the run.
    @Test fun lowConfidenceStillHoldsRatherThanCounting() {
        val now = 10_000_000L
        val gate = MotionGate(stillRun = 1, still = false)
        val weak = reading(still = true, conf = 38, age = 0, now = now)
        assertEquals(gate, foldMotion(gate, weak, now))
    }

    @Test fun motionThresholdsAreSeventyFiveAndTwoAndAHalfMinutesAndThreeInARow() {
        assertEquals(75, STILL_CONFIDENCE_MIN)
        assertEquals(150_000L, MOTION_STALE_MS)
        assertEquals(3, STILL_DEBOUNCE_N)
    }

    // The gate can only ever SUBTRACT from `wanted`: a fully closed motion gate (still=true)
    // must not make GPS run when the cloud settings don't want it at all.
    @Test fun closedGateCannotMakeGpsRunWhenNotWanted() {
        val now = 10_000_000L
        val closed = MotionGate(stillRun = STILL_DEBOUNCE_N, still = true)
        assertFalse(
            gpsShouldRun(
                wanted = false, pauseEnabled = true,
                lastDischargeMs = now - PARKED_HOLD_MS, nowMs = now,
                confidentlyStill = closed.still,
            ),
        )
    }
}
