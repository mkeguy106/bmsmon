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
 * How long one confident-STILL reading must stand uncontradicted — by confident motion, never by
 * mere silence — before GNSS pauses. 10 min (user-chosen over 5) so a long vehicle standstill
 * (train at a station) rarely closes the gate mid-trip; when one does, departure vibration wakes
 * AR and the first confident non-STILL reopens it. Deliberately separate from [PARKED_HOLD_MS]:
 * that defines "chair parked", this defines "phone still long enough that a mid-trip standstill
 * is implausible".
 */
const val STILL_CLOSE_HOLD_MS = 10 * 60_000L

/**
 * Silence-as-stillness gate state, folded one reading at a time by [foldMotion]
 * (2026-08-09 rework — docs/superpowers/specs/2026-08-09-silence-as-stillness-motion-gate-design.md).
 *
 * [stillSinceMs] is the start of the current uncontradicted confident-STILL run (the starting
 * reading's own [MotionReading.atMs]), or null when no run is live. [still] is the verdict:
 * closed once the run is [STILL_CLOSE_HOLD_MS] old. [lastConfidentAtMs] keeps its dedup role
 * unchanged: the atMs of the last confident reading folded, so re-evaluations never re-fold one
 * reading. The all-default `MotionGate()` is the fail-open state.
 */
data class MotionGate(
    val stillSinceMs: Long? = null,
    val still: Boolean = false,
    val lastConfidentAtMs: Long = 0L,
)

/** Re-derive the verdict from the clock: closed iff the run is HOLD old (inclusive). */
private fun MotionGate.withClock(nowMs: Long): MotionGate {
    val closed = stillSinceMs != null && nowMs - stillSinceMs >= STILL_CLOSE_HOLD_MS
    return if (closed == still) this else copy(still = closed)
}

/**
 * Fold one motion [reading] into [prev] — the second condition for pausing GNSS.
 *
 * WHY the 2026-08-09 inversion: AR delivery is motion-triggered at the sensor level — rich while
 * the device moves (~5.7 s cadence in vehicles), essentially silent while it is genuinely still
 * (measured: 4 readings in ~18 h parked). The old rule (STILL_DEBOUNCE_N fresh readings inside a
 * MOTION_STALE_MS window) therefore demanded evidence that never arrives, and the shipped gate
 * never closed in the recorded telemetry era: silence after a confident STILL is stillness
 * evidence, not signal loss.
 *
 * Rules, branch order load-bearing:
 * - **null reading** → fail open (no permission, AR unavailable, no reading yet, source stopped).
 * - **already folded** (atMs == lastConfidentAtMs) → hold the run, re-derive the verdict from
 *   the clock. This is the branch silence closes through: evaluations keep arriving (per BLE
 *   frame + the 5-min range tick) while readings do not.
 * - **uncertain** (confidence < [STILL_CONFIDENCE_MIN], which is how UNKNOWN always arrives) →
 *   same: neither starts, breaks, nor ends a run, and there is no staleness deadline to postpone.
 * - **confident STILL** → start the run if none (at the reading's own time), else keep its
 *   start; verdict from the clock.
 * - **confident non-STILL** → reopen on the single reading, run cleared — getting into a vehicle
 *   resumes GNSS at the first solid reading.
 *
 * The hold replaces the debounce's anti-flap job: a spurious stoplight STILL must survive
 * [STILL_CLOSE_HOLD_MS] uncontradicted, and in a real drive AR's rich in-vehicle delivery
 * contradicts it long before that. A silently-dead subscription can now hold the gate closed
 * while parked (accepted by explicit user decision) — bounded by the discharge clause in
 * [gpsShouldRun] and MotionSource's periodic re-subscribe.
 */
fun foldMotion(prev: MotionGate, reading: MotionReading?, nowMs: Long): MotionGate = when {
    reading == null -> MotionGate()
    reading.atMs == prev.lastConfidentAtMs -> prev.withClock(nowMs)
    reading.confidence < STILL_CONFIDENCE_MIN -> prev.withClock(nowMs)
    reading.still -> MotionGate(
        stillSinceMs = prev.stillSinceMs ?: reading.atMs,
        lastConfidentAtMs = reading.atMs,
    ).withClock(nowMs)
    else -> MotionGate(lastConfidentAtMs = reading.atMs)
}
