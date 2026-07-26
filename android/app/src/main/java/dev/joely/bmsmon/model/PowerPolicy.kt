package dev.joely.bmsmon.model

/** Battery level (%) at or below which the phone is treated as in trouble. */
const val LOW_ENTER_PCT = 5

/** Battery level (%) at which it is considered recovered. */
const val LOW_EXIT_PCT = 15

/**
 * What the phone's power situation means for the app, derived by [powerDecision].
 *
 * [lowPower] is the hysteretic latch and must be fed back in as `wasLowPower` on the next call —
 * it is both an input and an output, which is what makes the band between [LOW_ENTER_PCT] and
 * [LOW_EXIT_PCT] stable instead of flapping.
 */
data class PowerDecision(
    val holdScreen: Boolean,
    val gpsBalanced: Boolean,
    val lowPower: Boolean,
)

/**
 * Fold a power reading into the app's screen/GPS policy.
 *
 * The screen is the phone's dominant drain (measured ~136 mAh/h against ~22 for GNSS and ~1.6 for
 * BLE), so it is held only while on external power AND out of the low-battery latch. The latch
 * exists because holding the screen at very low charge can out-draw the charger, which is how the
 * phone ends up in a shutdown/reboot loop at 0%; it must bank real capacity before the display
 * load returns. The same latch drops GPS to balanced power — that emergency window only, so the
 * still-converging Wh/mile band never learns from coarse fixes.
 *
 * Pure and total: the same inputs always yield the same decision. No clock, no Android types.
 */
fun powerDecision(onExternal: Boolean, levelPct: Int, wasLowPower: Boolean): PowerDecision {
    val level = levelPct.coerceIn(0, 100)
    val lowPower = when {
        level < LOW_ENTER_PCT -> true
        level >= LOW_EXIT_PCT -> false
        else -> wasLowPower  // inside the band: hold, so the latch cannot flap
    }
    return PowerDecision(
        holdScreen = onExternal && !lowPower,
        gpsBalanced = lowPower,
        lowPower = lowPower,
    )
}
