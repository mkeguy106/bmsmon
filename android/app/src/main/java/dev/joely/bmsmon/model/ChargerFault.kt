package dev.joely.bmsmon.model

/** How far back the fold looks for the reading it compares against (15 min). */
const val CHARGER_FAULT_WINDOW_MS = 15 * 60_000L

/**
 * Charge lost over [CHARGER_FAULT_WINDOW_MS] that counts as a fault: 25 mAh in 15 min is a net
 * discharge of at least 100 mA. Measured failures run 170-180 mA; a healthy pass-through hold is
 * flat, so there is a wide gap on both sides.
 */
const val CHARGER_FAULT_DROP_MAH = 25

/** Rise from the lowest counter seen since the fault began that proves charging has resumed. */
const val CHARGER_CLEAR_RISE_MAH = 6

/** How much history the fold keeps (20 min: the window plus slack for sparse readings). */
const val CHARGER_BUFFER_MS = 20 * 60_000L

/**
 * Minimum gap between buffered readings. Battery broadcasts can arrive every second or so, and the
 * count cap would then evict the 15-min anchor; spacing keeps ~40 entries across the buffer.
 */
const val CHARGER_BUFFER_SPACING_MS = 30_000L

/** Hard cap on buffered readings, so a fast reading rate can never grow the state without bound. */
const val CHARGER_BUFFER_MAX = 256

/** One look at the phone's power: when, whether a source is connected, and the charge counter. */
data class ChargeReading(
    val atElapsedMs: Long,
    val onExternal: Boolean,
    /** Real charge in mAh, or null when the device gives no usable counter. */
    val chargeMah: Int?,
)

/**
 * Fold state for [foldChargerFault]. [readings] is `(elapsedMs, mAh)`, oldest first, and is only
 * filled while not in fault.
 */
data class ChargerFaultState(
    val readings: List<Pair<Long, Int>> = emptyList(),
    val fault: Boolean = false,
    val faultSinceElapsedMs: Long? = null,
    val minMahSinceFault: Int? = null,
)

/**
 * Detect a charger that reports "connected" while the battery's real charge keeps falling.
 *
 * **Why the charge counter and not `EXTRA_STATUS`.** A healthy pad holding at a charge limit or
 * FULL reads NOT_CHARGING too, so status cannot tell a dead pad from a content one. The counter
 * can: a held battery is flat, a dead pad leaves it falling at the phone's whole load (~180 mA
 * measured on 2026-10-04, over a night, while the displayed level sat frozen).
 *
 * Rules, in order:
 * 1. Unplugged, or no counter reading, resets to a clean state. Fail safe: no reading never
 *    faults, so behaviour matches the app before this existed. A reading whose clock goes
 *    backwards resets the same way.
 * 2. In fault, the minimum counter seen is tracked, and a rise of [CHARGER_CLEAR_RISE_MAH] above
 *    it clears the fault (fresh buffer from that reading). Falling at a lower drain, as when the
 *    screen is released, does not clear it: the counter still falls on a dead pad.
 * 3. Otherwise the reading is buffered (at most one per [CHARGER_BUFFER_SPACING_MS]) and compared
 *    with the newest reading at least [CHARGER_FAULT_WINDOW_MS] old; a loss of [CHARGER_FAULT_DROP_MAH] or more is a fault.
 *
 * Pure and total: no clock, no Android types.
 */
fun foldChargerFault(prev: ChargerFaultState, r: ChargeReading): ChargerFaultState {
    val mah = r.chargeMah
    if (!r.onExternal || mah == null) return ChargerFaultState()
    val at = r.atElapsedMs
    val last = prev.readings.lastOrNull()?.first
    val prevTime = last ?: prev.faultSinceElapsedMs
    if (prevTime != null && at < prevTime) return ChargerFaultState()

    if (prev.fault) {
        val lowest = minOf(prev.minMahSinceFault ?: mah, mah)
        if (mah >= lowest + CHARGER_CLEAR_RISE_MAH) {
            return ChargerFaultState(readings = listOf(at to mah))
        }
        return prev.copy(minMahSinceFault = lowest)
    }

    // A reading less than CHARGER_BUFFER_SPACING_MS after the newest buffered one still runs the
    // drop check below, but is not itself kept.
    val spaced = last == null || at - last >= CHARGER_BUFFER_SPACING_MS
    val buffered = (if (spaced) prev.readings + (at to mah) else prev.readings)
        .filter { it.first >= at - CHARGER_BUFFER_MS }
        .takeLast(CHARGER_BUFFER_MAX)
    val anchor = buffered.lastOrNull { it.first <= at - CHARGER_FAULT_WINDOW_MS }
    if (anchor != null && anchor.second - mah >= CHARGER_FAULT_DROP_MAH) {
        return ChargerFaultState(
            readings = emptyList(),
            fault = true,
            faultSinceElapsedMs = at,
            minMahSinceFault = mah,
        )
    }
    return ChargerFaultState(readings = buffered)
}
