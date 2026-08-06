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
 * [wanted] is `monitoring && gpsEnabled && enrolled && cloudEnabled`, decided elsewhere. The
 * parked gate can only ever SUBTRACT from it — it never turns GPS on. `MonitorEngine.applyGpsGate`
 * is a thin wrapper around this, so the rule is testable without a device.
 */
fun gpsShouldRun(
    wanted: Boolean,
    pauseEnabled: Boolean,
    lastDischargeMs: Long?,
    nowMs: Long,
    holdMs: Long = PARKED_HOLD_MS,
): Boolean = wanted && !(pauseEnabled && gpsParked(lastDischargeMs, nowMs, holdMs))

/**
 * A single phone-motion sample, as produced by `motion/MotionSource`.
 *
 * [still] is true when the most probable detected activity is STILL; [confidence] is that entry's
 * 0–100 confidence; [atMs] is wall-clock (`System.currentTimeMillis()`), the same clock
 * [confidentlyStill] compares against.
 */
data class MotionReading(val still: Boolean, val confidence: Int, val atMs: Long)

/** Minimum confidence before a STILL reading is trusted enough to pause GNSS. */
const val STILL_CONFIDENCE_MIN = 75

/** A motion reading older than this is treated as no reading at all — 5 missed 30 s polls. */
const val MOTION_STALE_MS = 150_000L

/**
 * Whether the phone is confidently stationary — the second condition for pausing GNSS.
 *
 * **Every "no usable signal" path returns false, and false means GPS STAYS ON**: permission denied,
 * activity recognition unavailable on the device, subscription lapsed, process restarted with no
 * reading yet, or updates gone stale. That is a deliberate user decision (2026-08-06): losing an
 * outing is worse than losing the battery saving, because a paused GNSS makes a real trip
 * indistinguishable from a nap at home.
 *
 * Kept as one expression on purpose, so the fail-open property cannot drift as callers are added.
 */
fun confidentlyStill(reading: MotionReading?, nowMs: Long): Boolean =
    reading != null &&
        reading.still &&
        reading.confidence >= STILL_CONFIDENCE_MIN &&
        nowMs - reading.atMs <= MOTION_STALE_MS
