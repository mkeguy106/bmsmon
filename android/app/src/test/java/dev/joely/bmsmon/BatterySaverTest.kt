package dev.joely.bmsmon

import dev.joely.bmsmon.model.BRIGHTNESS_RELEASE
import dev.joely.bmsmon.model.DEFAULT_DIM_LEVEL
import dev.joely.bmsmon.model.LOCK_REFRESH_HZ
import dev.joely.bmsmon.model.MIN_DIM_LEVEL
import dev.joely.bmsmon.model.MOTION_STALE_MS
import dev.joely.bmsmon.model.PARKED_HOLD_MS
import dev.joely.bmsmon.model.MotionReading
import dev.joely.bmsmon.model.STILL_CONFIDENCE_MIN
import dev.joely.bmsmon.model.confidentlyStill
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
        assertTrue(gpsShouldRun(wanted = true, pauseEnabled = true, lastDischargeMs = now - 1000L, nowMs = now))
    }

    @Test fun gpsStopsWhenParked() {
        val now = 10_000_000L
        assertFalse(gpsShouldRun(true, pauseEnabled = true, lastDischargeMs = now - PARKED_HOLD_MS, nowMs = now))
    }

    // With the toggle off, parking is irrelevant — this is the opt-out path.
    @Test fun gpsIgnoresParkedWhenPauseDisabled() {
        val now = 10_000_000L
        assertTrue(gpsShouldRun(true, pauseEnabled = false, lastDischargeMs = null, nowMs = now))
    }

    // The parked gate can only ever SUBTRACT from what the cloud settings want.
    @Test fun gpsNeverRunsWhenNotWanted() {
        val now = 10_000_000L
        assertFalse(gpsShouldRun(false, pauseEnabled = false, lastDischargeMs = now, nowMs = now))
        assertFalse(gpsShouldRun(false, pauseEnabled = true, lastDischargeMs = now, nowMs = now))
    }

    // ── confidentlyStill ─────────────────────────────────────────────────────
    // Every "no usable signal" path must return false, because false means GPS
    // STAYS ON. That is the explicit fail-open decision: losing an outing is
    // worse than losing the battery saving.

    private fun reading(still: Boolean, conf: Int, age: Long, now: Long = 10_000_000L) =
        MotionReading(still = still, confidence = conf, atMs = now - age)

    @Test fun noReadingIsNotConfidentlyStill() {
        assertFalse(confidentlyStill(null, 10_000_000L))
    }

    @Test fun movingIsNotConfidentlyStill() {
        assertFalse(confidentlyStill(reading(still = false, conf = 99, age = 0), 10_000_000L))
    }

    @Test fun lowConfidenceStillIsNotConfidentlyStill() {
        assertFalse(confidentlyStill(reading(still = true, conf = 74, age = 0), 10_000_000L))
    }

    // Threshold fires AT its value, matching the alert-ladder convention.
    @Test fun exactlyAtConfidenceThresholdIsStill() {
        assertTrue(confidentlyStill(reading(still = true, conf = STILL_CONFIDENCE_MIN, age = 0), 10_000_000L))
    }

    @Test fun staleReadingIsNotConfidentlyStill() {
        assertFalse(confidentlyStill(reading(still = true, conf = 99, age = MOTION_STALE_MS + 1), 10_000_000L))
    }

    // Boundary is inclusive: exactly at the staleness bound still counts.
    @Test fun exactlyAtStalenessBoundIsStill() {
        assertTrue(confidentlyStill(reading(still = true, conf = 99, age = MOTION_STALE_MS), 10_000_000L))
    }

    @Test fun freshConfidentStillIsStill() {
        assertTrue(confidentlyStill(reading(still = true, conf = 99, age = 1_000), 10_000_000L))
    }

    @Test fun motionThresholdsAreSeventyFiveAndTwoAndAHalfMinutes() {
        assertEquals(75, STILL_CONFIDENCE_MIN)
        assertEquals(150_000L, MOTION_STALE_MS)
    }
}
