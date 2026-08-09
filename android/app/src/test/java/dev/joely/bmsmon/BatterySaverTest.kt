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
    //
    // Two properties added after the whole-branch review, both about MotionGate.lastConfidentAtMs:
    // folds are deduped by reading identity (the engine evaluates the gate ~10x more often than
    // readings arrive, which had collapsed N to an effective 1), and the fail-open deadline is
    // measured from the last CONFIDENT reading, so uncertainty can hold the verdict but cannot
    // postpone failing open.

    private fun reading(
        still: Boolean,
        conf: Int,
        age: Long,
        now: Long = 10_000_000L,
        activity: String = if (still) "STILL" else "UNKNOWN",
    ) = MotionReading(still = still, confidence = conf, atMs = now - age, activity = activity)

    /** A closed gate as production reaches it: the last confident reading is [atMs]. */
    private fun closedGate(atMs: Long) =
        MotionGate(stillRun = STILL_DEBOUNCE_N, still = true, lastConfidentAtMs = atMs)

    @Test fun noReadingFailsOpen() {
        val prev = MotionGate(stillRun = 2, still = false, lastConfidentAtMs = 9_999_000L)
        assertEquals(MotionGate(), foldMotion(prev, null, 10_000_000L))
    }

    @Test fun staleReadingFailsOpen() {
        val now = 10_000_000L
        val prev = MotionGate(stillRun = 2, still = false, lastConfidentAtMs = 9_999_000L)
        val stale = reading(still = true, conf = 99, age = MOTION_STALE_MS + 1, now = now)
        assertEquals(MotionGate(), foldMotion(prev, stale, now))
    }

    // The review's missing case: stale AND uncertain, from a closed gate. Branch order decides it
    // — staleness is evaluated before the uncertainty hold, so a dead signal cannot be held shut
    // by the very ambiguity that made it look alive.
    @Test fun staleAndUncertainReadingFailsOpenRatherThanHolding() {
        val now = 10_000_000L
        val closed = closedGate(now - MOTION_STALE_MS - 1)
        val staleUnknown = reading(still = false, conf = 41, age = MOTION_STALE_MS + 1, now = now)
        assertEquals(MotionGate(), foldMotion(closed, staleUnknown, now))
    }

    // Boundary is inclusive, matching the alert-ladder convention: exactly at the staleness
    // bound is still fresh, so it counts toward the run rather than resetting it.
    @Test fun exactlyAtStalenessBoundIsFreshAndCounts() {
        val now = 10_000_000L
        val fresh = reading(still = true, conf = 99, age = MOTION_STALE_MS, now = now)
        val gate = foldMotion(MotionGate(), fresh, now)
        assertEquals(1, gate.stillRun)
        assertFalse(gate.still)
        assertEquals(fresh.atMs, gate.lastConfidentAtMs)
    }

    @Test fun oneConfidentStillIsNotYetStill() {
        val now = 10_000_000L
        val gate = foldMotion(MotionGate(), reading(still = true, conf = 99, age = 0, now = now), now)
        assertEquals(1, gate.stillRun)
        assertFalse(gate.still)
    }

    // THE regression test for the review's finding 1. The engine folds on every gate evaluation
    // (~80-115x/min across the fleet) while MotionSource.current() keeps returning the SAME cached
    // reading until a new broadcast lands (~10/min). Folding one reading must advance the run
    // exactly once, or STILL_DEBOUNCE_N is an effective 1 and a single spurious STILL at a stop
    // light closes the gate ~1.5 s later.
    @Test fun refoldingOneReadingCountsItOnce() {
        val t0 = 10_000_000L
        val once = reading(still = true, conf = 99, age = 0, now = t0)
        var gate = MotionGate()
        // 12 evaluations spread over the ~11 the engine really makes between two readings.
        repeat(12) { i ->
            gate = foldMotion(gate, once, t0 + i * 100L)
            assertEquals(1, gate.stillRun)
            assertFalse(gate.still)
        }
    }

    // The same thing from the engine's side: many evaluations per reading, three distinct
    // readings. The gate must close on the third READING, not the third evaluation.
    @Test fun debounceCountsDistinctReadingsNotEvaluations() {
        val t0 = 10_000_000L
        var gate = MotionGate()
        repeat(STILL_DEBOUNCE_N) { n ->
            val r = MotionReading(still = true, confidence = 99, atMs = t0 + n * 6_000L, activity = "STILL")
            repeat(11) { i ->      // ~11 gate evaluations per reading
                gate = foldMotion(gate, r, r.atMs + i * 500L)
                assertEquals(n + 1, gate.stillRun)
            }
        }
        assertTrue(gate.still)
        assertEquals(STILL_DEBOUNCE_N, gate.stillRun)
    }

    // UNKNOWN@41 mid-run: absence of evidence, not evidence of motion. The run must hold, not
    // reset — this is the exact defect confidentlyStill() had.
    @Test fun uncertainSampleMidRunHoldsRatherThanResets() {
        val t0 = 10_000_000L
        var gate = foldMotion(MotionGate(), reading(still = true, conf = 99, age = 0, now = t0), t0)
        assertEquals(1, gate.stillRun)
        val t1 = t0 + 1_000L
        val held = foldMotion(gate, MotionReading(still = false, confidence = 41, atMs = t1, activity = "UNKNOWN"), t1)
        assertEquals(gate, held)
        // The hold contributed nothing: the run still only needs STILL_DEBOUNCE_N - 1 more
        // confident-STILL readings to close, not a fresh count from zero.
        repeat(STILL_DEBOUNCE_N - 1) { n ->
            val at = t0 + 6_000L * (n + 1)
            gate = foldMotion(gate, MotionReading(still = true, confidence = 99, atMs = at, activity = "STILL"), at)
        }
        assertTrue(gate.still)
    }

    @Test fun confidentNonStillReopensImmediatelyFromClosedGate() {
        val now = 10_000_000L
        val closed = closedGate(now - 6_000L)
        val moving = reading(still = false, conf = 99, age = 0, now = now)
        val gate = foldMotion(closed, moving, now)
        assertEquals(0, gate.stillRun)
        assertFalse(gate.still)
    }

    @Test fun uncertainSampleWhileClosedKeepsItClosed() {
        val now = 10_000_000L
        val closed = closedGate(now - 6_000L)
        val unknown = reading(still = false, conf = 50, age = 0, now = now)
        assertEquals(closed, foldMotion(closed, unknown, now))
    }

    // Review finding 3: uncertainty may hold the verdict, but it must not postpone fail-open. AR
    // is least certain in the first 30-60 s of a trip (28% of the measured trace is sub-75), so a
    // gate closed at the kerb could otherwise stay closed for the whole ride, bounded by nothing.
    @Test fun uncertainReadingsCannotPostponeFailOpen() {
        val closedAt = 10_000_000L
        var gate = closedGate(closedAt)
        // Uncertain readings keep landing, each one fresh in its own right.
        var t = closedAt + 10_000L
        while (t <= closedAt + MOTION_STALE_MS) {
            gate = foldMotion(gate, MotionReading(still = false, confidence = 41, atMs = t, activity = "UNKNOWN"), t)
            assertTrue("gate must hold while confident evidence is still fresh at t=$t", gate.still)
            t += 10_000L
        }
        // One tick past MOTION_STALE_MS since the last CONFIDENT reading — even though the newest
        // reading of any kind is only 10 s old — the gate fails open and GNSS resumes.
        val past = closedAt + MOTION_STALE_MS + 1
        gate = foldMotion(gate, MotionReading(still = false, confidence = 41, atMs = past, activity = "UNKNOWN"), past)
        assertEquals(MotionGate(), gate)
    }

    // A confident reading refreshes the deadline; only confident ones do.
    @Test fun confidentStillRefreshesTheFailOpenDeadline() {
        val t0 = 10_000_000L
        var gate = closedGate(t0)
        val at = t0 + MOTION_STALE_MS - 1_000L
        gate = foldMotion(gate, MotionReading(still = true, confidence = 99, atMs = at, activity = "STILL"), at)
        assertTrue(gate.still)
        // Past the ORIGINAL deadline but inside the refreshed one: still closed.
        val later = t0 + MOTION_STALE_MS + 1_000L
        gate = foldMotion(gate, MotionReading(still = false, confidence = 41, atMs = later, activity = "UNKNOWN"), later)
        assertTrue(gate.still)
    }

    // STILL@38 from the measured trace: still=true but below STILL_CONFIDENCE_MIN, so it is
    // uncertain too and must hold rather than count toward the run.
    @Test fun lowConfidenceStillHoldsRatherThanCounting() {
        val now = 10_000_000L
        val gate = MotionGate(stillRun = 1, still = false, lastConfidentAtMs = now - 6_000L)
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
        val closed = closedGate(now)
        assertFalse(
            gpsShouldRun(
                wanted = false, pauseEnabled = true,
                lastDischargeMs = now - PARKED_HOLD_MS, nowMs = now,
                confidentlyStill = closed.still,
            ),
        )
    }

    // The reading must carry the activity NAME, not just the still/not-still collapse — the whole
    // point of uploading it is telling "UNKNOWN@41" apart from "IN_VEHICLE@90", which both map to
    // still=false and are indistinguishable without it.
    @Test fun motionReadingCarriesTheActivityName() {
        val r = MotionReading(still = false, confidence = 90, atMs = 1_000L, activity = "IN_VEHICLE")
        assertEquals("IN_VEHICLE", r.activity)
        assertFalse(r.still)
    }

    // Adding the field must not disturb the gate: foldMotion ignores it entirely. Discriminating
    // test — fold the SAME reading sequence twice, varying only the activity string, and assert
    // the resulting gates are equal. (A version that never varies the field, like an earlier
    // draft of this test, cannot tell "ignored" from "always happens to match".)
    @Test fun activityNameDoesNotAffectTheGateVerdict() {
        val now = 10_000_000L
        var gStill = MotionGate()
        var gVehicle = MotionGate()
        repeat(STILL_DEBOUNCE_N) { i ->
            val atMs = now - (STILL_DEBOUNCE_N - i) * 1_000L
            gStill = foldMotion(gStill, MotionReading(true, 99, atMs, "STILL"), now)
            gVehicle = foldMotion(gVehicle, MotionReading(true, 99, atMs, "IN_VEHICLE"), now)
        }
        assertTrue(gStill.still)
        assertEquals(gStill, gVehicle)
    }
}
