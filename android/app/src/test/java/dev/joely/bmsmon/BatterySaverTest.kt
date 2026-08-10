package dev.joely.bmsmon

import dev.joely.bmsmon.model.BRIGHTNESS_RELEASE
import dev.joely.bmsmon.model.DEFAULT_DIM_LEVEL
import dev.joely.bmsmon.model.LOCK_REFRESH_HZ
import dev.joely.bmsmon.model.MIN_DIM_LEVEL
import dev.joely.bmsmon.model.PARKED_HOLD_MS
import dev.joely.bmsmon.model.MotionGate
import dev.joely.bmsmon.model.MotionReading
import dev.joely.bmsmon.model.STILL_CLOSE_HOLD_MS
import dev.joely.bmsmon.model.STILL_CONFIDENCE_MIN
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
    // Silence-as-stillness semantics (2026-08-09 rework): AR delivery is motion-triggered at the
    // sensor level — measured 4 readings in ~18 h while genuinely parked — so the old rule
    // (N fresh confident readings inside a staleness window) demanded evidence that never
    // arrives, and the gate never closed in the recorded telemetry era. Closing now needs ONE
    // confident STILL reading left uncontradicted for STILL_CLOSE_HOLD_MS; silence extends the
    // run instead of failing it open. Reopening is unchanged: a single confident non-STILL.
    // Null readings (no signal at all) still fail open, because MotionGate() means GPS STAYS ON.

    private fun reading(
        still: Boolean,
        conf: Int,
        age: Long,
        now: Long = 10_000_000L,
        activity: String = if (still) "STILL" else "UNKNOWN",
    ) = MotionReading(still = still, confidence = conf, atMs = now - age, activity = activity)

    /** A closed gate as production reaches it: run started HOLD ago, last confident at [atMs]. */
    private fun closedGate(atMs: Long) = MotionGate(
        stillSinceMs = atMs - STILL_CLOSE_HOLD_MS, still = true, lastConfidentAtMs = atMs,
    )

    @Test fun noReadingFailsOpen() {
        val prev = MotionGate(stillSinceMs = 9_000_000L, still = true, lastConfidentAtMs = 9_999_000L)
        assertEquals(MotionGate(), foldMotion(prev, null, 10_000_000L))
    }

    @Test fun confidentStillStartsARunButDoesNotCloseYet() {
        val now = 10_000_000L
        val r = reading(still = true, conf = 99, age = 0, now = now)
        val gate = foldMotion(MotionGate(), r, now)
        assertEquals(r.atMs, gate.stillSinceMs)
        assertFalse(gate.still)
        assertEquals(r.atMs, gate.lastConfidentAtMs)
    }

    // The core inversion: no new readings arrive (AR is silent because nothing moves) and the
    // SAME cached reading refolds while the clock advances. At the hold boundary — inclusive,
    // the alert-ladder convention — the gate closes on silence alone.
    @Test fun silenceClosesTheGateAtTheHoldBoundary() {
        val t0 = 10_000_000L
        val r = reading(still = true, conf = 100, age = 0, now = t0)
        var gate = foldMotion(MotionGate(), r, t0)
        gate = foldMotion(gate, r, t0 + STILL_CLOSE_HOLD_MS - 1)
        assertFalse(gate.still)
        gate = foldMotion(gate, r, t0 + STILL_CLOSE_HOLD_MS)
        assertTrue(gate.still)
        assertEquals(r.atMs, gate.stillSinceMs)
    }

    // Restart self-heal: a fresh gate (process restart) + the subscription's one burst reading
    // + time = closed. Under the old rules a parked restart never re-closed.
    @Test fun restartSelfHealsFromOneBurstReading() {
        val t0 = 10_000_000L
        val burst = reading(still = true, conf = 100, age = 0, now = t0)
        var gate = foldMotion(MotionGate(), burst, t0)
        gate = foldMotion(gate, burst, t0 + STILL_CLOSE_HOLD_MS)
        assertTrue(gate.still)
    }

    // A later confident STILL keeps the ORIGINAL run start — the run is one continuous stretch
    // of stillness, not restarted per reading (else the close would chase the newest reading).
    @Test fun subsequentStillKeepsTheOriginalRunStart() {
        val t0 = 10_000_000L
        var gate = foldMotion(MotionGate(), reading(still = true, conf = 99, age = 0, now = t0), t0)
        val t1 = t0 + 60_000L
        val second = MotionReading(still = true, confidence = 100, atMs = t1, activity = "STILL")
        gate = foldMotion(gate, second, t1)
        assertEquals(t0, gate.stillSinceMs)
        assertEquals(t1, gate.lastConfidentAtMs)
        // Closes HOLD after the FIRST reading, not the second.
        gate = foldMotion(gate, second, t0 + STILL_CLOSE_HOLD_MS)
        assertTrue(gate.still)
    }

    // UNKNOWN@41 mid-run: absence of evidence, not evidence of motion — holds the run, and the
    // clock keeps counting through it, so an uncertain fold can itself close the gate.
    @Test fun uncertaintyHoldsTheRunAndTheClockStillCloses() {
        val t0 = 10_000_000L
        var gate = foldMotion(MotionGate(), reading(still = true, conf = 99, age = 0, now = t0), t0)
        val u1 = t0 + 1_000L
        gate = foldMotion(gate, MotionReading(still = false, confidence = 41, atMs = u1, activity = "UNKNOWN"), u1)
        assertEquals(t0, gate.stillSinceMs)
        assertFalse(gate.still)
        val u2 = t0 + STILL_CLOSE_HOLD_MS
        gate = foldMotion(gate, MotionReading(still = false, confidence = 41, atMs = u2, activity = "UNKNOWN"), u2)
        assertTrue(gate.still)
    }

    // Deliberate inversion of the old "uncertainty cannot postpone fail-open": there is no
    // staleness deadline anymore. A closed gate holds through unbounded silence/uncertainty —
    // that is what a parked night actually looks like (4 readings in ~18 h, all STILL).
    @Test fun closedGateHoldsThroughHoursOfUncertainty() {
        val t0 = 10_000_000L
        val later = t0 + 11 * 3_600_000L
        val gate = foldMotion(
            closedGate(t0),
            MotionReading(still = false, confidence = 41, atMs = later, activity = "UNKNOWN"),
            later,
        )
        assertTrue(gate.still)
    }

    @Test fun confidentNonStillReopensImmediatelyFromClosed() {
        val now = 10_000_000L
        val gate = foldMotion(closedGate(now - 6_000L), reading(still = false, conf = 99, age = 0, now = now), now)
        assertFalse(gate.still)
        assertEquals(null, gate.stillSinceMs)
    }

    // The stoplight case, handled by the hold instead of the deleted N=3 debounce: a spurious
    // STILL mid-drive starts a run, but in-vehicle delivery is rich (~5.7 s cadence measured),
    // so a single confident IN_VEHICLE clears it long before the 10-minute hold.
    @Test fun stoplightStillIsCancelledByInVehicleBeforeTheHold() {
        val t0 = 10_000_000L
        var gate = foldMotion(MotionGate(), reading(still = true, conf = 96, age = 0, now = t0), t0)
        assertEquals(t0, gate.stillSinceMs)
        val t1 = t0 + 120_000L
        gate = foldMotion(gate, MotionReading(still = false, confidence = 90, atMs = t1, activity = "IN_VEHICLE"), t1)
        assertEquals(null, gate.stillSinceMs)
        assertFalse(gate.still)
    }

    // Dedup branch: refolding the same reading must not corrupt the run — and must re-derive
    // the verdict from the clock (this is the branch silence actually closes through).
    @Test fun refoldingOneReadingHoldsTheRunAndAdvancesOnlyTheClock() {
        val t0 = 10_000_000L
        val r = reading(still = true, conf = 99, age = 0, now = t0)
        var gate = foldMotion(MotionGate(), r, t0)
        repeat(12) { i ->
            gate = foldMotion(gate, r, t0 + (i + 1) * 100L)
            assertEquals(t0, gate.stillSinceMs)
            assertFalse(gate.still)
        }
    }

    // STILL below the confidence floor is uncertainty, not evidence: it neither starts a run…
    @Test fun lowConfidenceStillDoesNotStartARun() {
        val now = 10_000_000L
        val gate = foldMotion(MotionGate(), reading(still = true, conf = 38, age = 0, now = now), now)
        assertEquals(MotionGate(), gate)
    }

    // …nor advances one (it holds, exactly like UNKNOWN).
    @Test fun lowConfidenceStillHoldsAnOpenRun() {
        val t0 = 10_000_000L
        val gate = foldMotion(MotionGate(), reading(still = true, conf = 99, age = 0, now = t0), t0)
        val weak = MotionReading(still = true, confidence = 38, atMs = t0 + 1_000L, activity = "STILL")
        val held = foldMotion(gate, weak, t0 + 1_000L)
        assertEquals(gate.stillSinceMs, held.stillSinceMs)
        assertEquals(gate.lastConfidentAtMs, held.lastConfidentAtMs)
    }

    @Test fun motionThresholdsAreSeventyFiveAndTenMinutes() {
        assertEquals(75, STILL_CONFIDENCE_MIN)
        assertEquals(600_000L, STILL_CLOSE_HOLD_MS)
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
        val t0 = 10_000_000L
        val still = MotionReading(true, 99, t0, "STILL")
        val vehicle = MotionReading(true, 99, t0, "IN_VEHICLE")
        var gStill = foldMotion(MotionGate(), still, t0)
        var gVehicle = foldMotion(MotionGate(), vehicle, t0)
        gStill = foldMotion(gStill, still, t0 + STILL_CLOSE_HOLD_MS)
        gVehicle = foldMotion(gVehicle, vehicle, t0 + STILL_CLOSE_HOLD_MS)
        assertTrue(gStill.still)
        assertEquals(gStill, gVehicle)
    }
}
