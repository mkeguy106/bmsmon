package dev.joely.bmsmon.model

/**
 * Pure policy for the in-app battery saver (Settings › Battery saver).
 *
 * Every constant here was set from on-device measurement on the Pixel 6, not intuition — see
 * docs/superpowers/specs/2026-08-03-battery-saver-settings-design.md for the numbers. Pure and
 * total: no clock, no Android types, same inputs always yield the same answer.
 */

/**
 * Refresh rate (Hz) requested while locked. Measured: capping 90 → 60 saves ~18 mA (the raw net
 * delta was 28.7 mA, but 11 mA of that was the charging pad recovering as the phone cooled).
 * Going 60 → 30 measured NO further gain — Android's idle frame-rate override was already
 * dropping the render rate, because the stage only redraws every 1.5 s. Do not lower this
 * without a measurement showing otherwise.
 */
const val LOCK_REFRESH_HZ = 60f

/** [lockRefreshRate] value meaning "no preference, use the system default". */
const val SYSTEM_REFRESH_RATE = 0f

/** No base discharging for this long means the chair is parked. */
const val PARKED_HOLD_MS = 5 * 60_000L

/**
 * Floor for the dim slider. A slider dragged to zero would black out a display mounted on a
 * power wheelchair, so the floor is a safety limit, not a preference.
 */
const val MIN_DIM_LEVEL = 0.05f

/** Dim level a fresh install starts at, if the user ever enables dimming (it defaults off). */
const val DEFAULT_DIM_LEVEL = 0.30f

/** `WindowManager.LayoutParams.screenBrightness` value that releases to the user's own setting. */
const val BRIGHTNESS_RELEASE = -1f

/**
 * Preferred display refresh rate for the app window.
 *
 * Not gated on the plug-aware screen policy: that latch exists to stop the screen being *held on*,
 * whereas a lower refresh rate is a saving in every power state, so gating it could only ever
 * cost battery.
 */
fun lockRefreshRate(locked: Boolean, enabled: Boolean): Float =
    if (locked && enabled) LOCK_REFRESH_HZ else SYSTEM_REFRESH_RATE

/**
 * Window brightness override while locked, or [BRIGHTNESS_RELEASE] to hand control back to the
 * user's system brightness. Clamped into [MIN_DIM_LEVEL]..1f — see [MIN_DIM_LEVEL].
 */
fun lockBrightness(locked: Boolean, enabled: Boolean, level: Float): Float =
    if (locked && enabled) level.coerceIn(MIN_DIM_LEVEL, 1f) else BRIGHTNESS_RELEASE

/**
 * True when no base has discharged within [holdMs] — the chair is parked, so GNSS is spending
 * ~22 mA to produce fixes the range learner's discharge gate discards anyway.
 *
 * [lastDischargeMs] is the newest entry of `MonitorState.lastDischargeAt`, which the engine
 * already maintains. Boundary is inclusive (`>=`), matching the alert-ladder convention that a
 * threshold fires *at* its value.
 */
fun gpsParked(lastDischargeMs: Long?, nowMs: Long, holdMs: Long = PARKED_HOLD_MS): Boolean =
    lastDischargeMs == null || nowMs - lastDischargeMs >= holdMs

/**
 * Whether GPS capture should actually run: what the cloud settings want, minus the parked gate.
 *
 * [wanted] is `monitoring && gpsEnabled && enrolled && cloudEnabled`, decided elsewhere. The gate
 * can only ever SUBTRACT from it — it never turns GPS on.
 *
 * Pausing needs **both** conditions: the chair has not discharged recently **and** the phone is
 * confidently still. The chair draws nothing in a van or on a train, so discharge alone reads
 * transit as "parked" and switches GNSS off for the whole journey — measured 2026-08-06 as three
 * outings that were entirely invisible on the map, destinations included.
 */
fun gpsShouldRun(
    wanted: Boolean,
    pauseEnabled: Boolean,
    lastDischargeMs: Long?,
    nowMs: Long,
    confidentlyStill: Boolean,
    holdMs: Long = PARKED_HOLD_MS,
): Boolean = wanted && !(pauseEnabled && gpsParked(lastDischargeMs, nowMs, holdMs) && confidentlyStill)

/**
 * A single phone-motion sample, as produced by `motion/MotionSource`.
 *
 * [still] is true when the most probable detected activity is STILL; [confidence] is that entry's
 * 0–100 confidence; [atMs] is wall-clock (`System.currentTimeMillis()`), the same clock
 * [foldMotion] compares against.
 */
data class MotionReading(
    val still: Boolean,
    val confidence: Int,
    val atMs: Long,
    /**
     * Most probable detected activity, as a readable name (`STILL`, `IN_VEHICLE`, `UNKNOWN`, …).
     *
     * Carried purely so it can be uploaded — `foldMotion` never reads it. It exists because
     * `still = false` collapses `UNKNOWN@41` and `IN_VEHICLE@90` into the same value, and telling
     * those apart from the server is the entire reason this field was added.
     */
    val activity: String,
)

/** Minimum confidence before a STILL reading is trusted enough to pause GNSS. */
const val STILL_CONFIDENCE_MIN = 75

/**
 * A motion signal that has produced no confident reading for this long is treated as no signal at
 * all — 5 missed 30 s polls. Measured from the last *confident* reading, not the last reading of
 * any kind: see [foldMotion]'s uncertainty branch.
 */
const val MOTION_STALE_MS = 150_000L

/**
 * Distinct confident-STILL **readings** required before [foldMotion] closes the gate (pauses
 * GNSS). Measured on-device 2026-08-07: while stationary the phone reports STILL at confidence
 * 96–100 interleaved with UNKNOWN at 41–50, and never confident motion — a single-sample rule
 * (the deleted `confidentlyStill()`) passed 70% of readings but toggled the gate 5 times in 5
 * minutes, restarting GNSS repeatedly. Simulated against that trace: N=3 keeps the gate closed
 * 96% of the time with no flapping; chosen as the smallest value that still requires genuinely
 * sustained evidence rather than a single reading. Do not retune without a fresh trace.
 *
 * "Readings", emphatically, not gate evaluations — the engine evaluates the gate on every BLE
 * frame (~80–115×/min across the fleet) while Activity Recognition broadcasts arrive ~10×/min, so
 * without [MotionGate.lastConfidentAtMs]'s identity dedup a single reading would be folded ~11
 * times and N would collapse to an effective 1.
 */
const val STILL_DEBOUNCE_N = 3

/**
 * Debounced motion-gate state, folded one reading at a time by [foldMotion].
 *
 * [stillRun] counts consecutive confident-STILL readings since the gate last opened (reset by
 * any fail-open, reopen, or hold-from-empty — only a run of confident-STILL readings grows it).
 * [still] is the gate's actual verdict: true only once [stillRun] reaches [STILL_DEBOUNCE_N].
 * The all-default `MotionGate()` — `stillRun = 0, still = false, lastConfidentAtMs = 0` — is the
 * fail-open / reopened state.
 *
 * [lastConfidentAtMs] is the [MotionReading.atMs] of the last reading that actually moved the
 * gate, i.e. the last *confident* one, and does double duty **on purpose — one field, two jobs**:
 * - **Identity dedup.** A reading whose `atMs` already equals it has been folded already, so
 *   folding it again is a no-op. One field covers this because only confident readings ever
 *   change the gate: re-folding an uncertain reading is already idempotent (it returns [prev]
 *   unchanged, or fails open on the deadline below, and both are stable under repetition), so
 *   uncertain readings need no dedup key of their own.
 * - **Fail-open deadline.** Staleness is measured from here rather than from the newest reading
 *   of any kind, so a stream of uncertain readings may hold the verdict but cannot postpone
 *   failing open.
 *
 * `0L` means "no confident reading folded yet", which reads as infinitely stale — fail open, the
 * correct default. Being part of the gate is also what makes `shutdownGps()`'s existing
 * `motionGate = MotionGate()` reset the dedup key and the deadline along with the verdict.
 */
data class MotionGate(
    val stillRun: Int = 0,
    val still: Boolean = false,
    val lastConfidentAtMs: Long = 0L,
)

/**
 * Fold one motion [reading] into [prev] to produce the next [MotionGate] — the second condition
 * for pausing GNSS, replacing the single-sample `confidentlyStill()` this shipped with originally
 * (see [STILL_DEBOUNCE_N] for why: it toggled 5 times in 5 minutes against the measured trace).
 *
 * Asymmetric hysteresis, on purpose — this is what preserves the fail-open property:
 * - **Closing** (pausing GNSS) needs [STILL_DEBOUNCE_N] consecutive confident-STILL readings.
 * - **Reopening** needs only one confident non-STILL reading, so getting into a vehicle resumes
 *   GNSS at the first solid reading, not after N of them.
 * - **Uncertainty changes nothing.** A `null` or stale reading (older than [MOTION_STALE_MS])
 *   fails open immediately — same contract the old `confidentlyStill()` had. A present-but-low-
 *   confidence reading (confidence `< STILL_CONFIDENCE_MIN`, which is how `UNKNOWN` always
 *   arrives, and occasionally `STILL` too) **holds [prev] unchanged** rather than resetting it,
 *   because it is absence of evidence, not evidence of motion — treating it as movement was the
 *   entire defect.
 * - **…but uncertainty cannot postpone fail-open.** The hold above only lasts while *confident*
 *   evidence is still fresh: once nothing confident has arrived for [MOTION_STALE_MS] the gate
 *   fails open even though uncertain readings keep landing. Without that, a closed gate plus a
 *   run of `UNKNOWN@41` (28% of the measured trace, and AR is least certain in the first minute
 *   of a trip) would keep GNSS off bounded by nothing.
 * - **Folding is idempotent per reading.** Callers re-evaluate the gate far more often than
 *   readings arrive (see [STILL_DEBOUNCE_N]), so a reading already folded — identified by its
 *   [MotionReading.atMs] matching [MotionGate.lastConfidentAtMs] — returns [prev] untouched.
 *   Two broadcasts landing in the same millisecond would collapse into one fold; readings arrive
 *   seconds apart, and the source only ever caches the newest anyway.
 *
 * **Branch order is load-bearing:** staleness of the reading itself is checked *first*, before
 * both the dedup and the uncertainty hold, so neither can wedge the gate shut on a dead signal.
 *
 * [nowMs] is threaded through explicitly (this file stays pure — no clock access) and compared
 * against [MotionReading.atMs] using the same clock as the caller.
 */
fun foldMotion(prev: MotionGate, reading: MotionReading?, nowMs: Long): MotionGate = when {
    // No signal at all, or the freshest reading is itself older than the staleness bound.
    reading == null || nowMs - reading.atMs > MOTION_STALE_MS -> MotionGate()
    // Already folded this exact reading — the gate must not advance on a re-evaluation.
    reading.atMs == prev.lastConfidentAtMs -> prev
    // Uncertain: hold the last confident verdict, but only until the fail-open deadline.
    reading.confidence < STILL_CONFIDENCE_MIN ->
        if (nowMs - prev.lastConfidentAtMs > MOTION_STALE_MS) MotionGate() else prev
    // Confident STILL: grow the run, close the gate once it reaches N.
    reading.still -> (prev.stillRun + 1).let { run ->
        MotionGate(run, run >= STILL_DEBOUNCE_N, reading.atMs)
    }
    // Confident motion: reopen immediately, keeping the reading's identity as the new deadline.
    else -> MotionGate(lastConfidentAtMs = reading.atMs)
}
