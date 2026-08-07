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
data class MotionReading(val still: Boolean, val confidence: Int, val atMs: Long)

/** Minimum confidence before a STILL reading is trusted enough to pause GNSS. */
const val STILL_CONFIDENCE_MIN = 75

/** A motion reading older than this is treated as no reading at all — 5 missed 30 s polls. */
const val MOTION_STALE_MS = 150_000L

/**
 * Consecutive confident-STILL readings required before [foldMotion] closes the gate (pauses
 * GNSS). Measured on-device 2026-08-07: while stationary the phone reports STILL at confidence
 * 96–100 interleaved with UNKNOWN at 41–50, and never confident motion — a single-sample rule
 * (the deleted `confidentlyStill()`) passed 70% of readings but toggled the gate 5 times in 5
 * minutes, restarting GNSS repeatedly. Simulated against that trace: N=3 keeps the gate closed
 * 96% of the time with no flapping; chosen as the smallest value that still requires genuinely
 * sustained evidence rather than a single reading. Do not retune without a fresh trace.
 */
const val STILL_DEBOUNCE_N = 3

/**
 * Debounced motion-gate state, folded one reading at a time by [foldMotion].
 *
 * [stillRun] counts consecutive confident-STILL readings since the gate last opened (reset by
 * any fail-open, reopen, or hold-from-empty — only a run of confident-STILL readings grows it).
 * [still] is the gate's actual verdict: true only once [stillRun] reaches [STILL_DEBOUNCE_N].
 * The all-default `MotionGate()` — `stillRun = 0, still = false` — is the fail-open / reopened
 * state.
 */
data class MotionGate(val stillRun: Int = 0, val still: Boolean = false)

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
 *
 * [nowMs] is threaded through explicitly (this file stays pure — no clock access) and compared
 * against [MotionReading.atMs] using the same clock as the caller.
 */
fun foldMotion(prev: MotionGate, reading: MotionReading?, nowMs: Long): MotionGate = when {
    reading == null || nowMs - reading.atMs > MOTION_STALE_MS -> MotionGate()
    reading.confidence < STILL_CONFIDENCE_MIN -> prev
    reading.still -> (prev.stillRun + 1).let { run -> MotionGate(run, run >= STILL_DEBOUNCE_N) }
    else -> MotionGate()
}
