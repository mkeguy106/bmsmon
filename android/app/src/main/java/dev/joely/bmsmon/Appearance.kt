package dev.joely.bmsmon

import kotlin.math.log10
import kotlin.math.pow

/** How the user chose the theme. [Auto] is driven by the ambient light sensor. */
enum class Appearance { Dark, Light, System, Auto }

/** Multiplicative hysteresis deadband around the user's lux threshold (prevents flicker). */
const val AUTO_BAND = 1.3f

/** A candidate flip must persist this long before it is applied. */
const val AUTO_DEBOUNCE_MS = 2500L

/** Default auto-switch threshold (lux) and the adjustable slider range. */
const val DEFAULT_AUTO_LUX = 300f
const val AUTO_LUX_MIN = 20f
const val AUTO_LUX_MAX = 2000f

/**
 * Hysteresis resolver: go [Mode.Light] above `threshold * AUTO_BAND`, [Mode.Dark] below
 * `threshold / AUTO_BAND`, and hold [current] inside the deadband (no flip).
 */
fun resolveAutoMode(lux: Float, thresholdLux: Float, current: Mode): Mode {
    val high = thresholdLux * AUTO_BAND
    val low = thresholdLux / AUTO_BAND
    return when {
        lux >= high -> Mode.Light
        lux <= low -> Mode.Dark
        else -> current
    }
}

/**
 * Returns the mode to commit now: [candidate] only once it has been stable since
 * [candidateSince] for at least [debounceMs]; otherwise [current].
 */
fun debouncedMode(
    current: Mode,
    candidate: Mode,
    candidateSince: Long,
    now: Long,
    debounceMs: Long = AUTO_DEBOUNCE_MS,
): Mode = when {
    candidate == current -> current
    now - candidateSince >= debounceMs -> candidate
    else -> current
}

/** Map legacy persisted booleans to the new [Appearance]. */
fun legacyAppearance(manualMode: Boolean, darkMode: Boolean): Appearance = when {
    !manualMode -> Appearance.System
    darkMode -> Appearance.Dark
    else -> Appearance.Light
}

/** Log-scaled slider position [0,1] for a lux value, clamped to [AUTO_LUX_MIN, AUTO_LUX_MAX]. */
fun luxToFraction(lux: Float): Float {
    val clamped = lux.coerceIn(AUTO_LUX_MIN, AUTO_LUX_MAX)
    return (log10(clamped / AUTO_LUX_MIN) / log10(AUTO_LUX_MAX / AUTO_LUX_MIN)).toFloat()
}

/** Inverse of [luxToFraction]: slider position [0,1] → lux (log scale). */
fun fractionToLux(fraction: Float): Float {
    val f = fraction.coerceIn(0f, 1f)
    return AUTO_LUX_MIN * (AUTO_LUX_MAX / AUTO_LUX_MIN).toDouble().pow(f.toDouble()).toFloat()
}
