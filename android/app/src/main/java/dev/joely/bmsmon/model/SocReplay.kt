package dev.joely.bmsmon.model

import dev.joely.bmsmon.data.db.EstRowColumns

// One-time seeding of the SOC estimator from the phone's local history (Task 4, 2026-11-18). The
// moments that set today's corrections (a charge end, a long rest) have already passed when this
// version is installed, so on its first start the engine replays each pack's Room rows through the
// same pure stepEstimate the live path uses. No Android imports: the engine supplies the I/O.

/** This row as an estimator reading, or null when a column the estimator needs is missing or the
 *  state is not a [BatteryState] name. Such rows are skipped outright. */
fun EstRowColumns.toReading(): EstReading? {
    val st = state?.let { name -> BatteryState.entries.firstOrNull { it.name == name } } ?: return null
    return EstReading(
        tsMs = tsMs,
        state = st,
        regen = regen,
        currentA = currentA ?: return null,
        voltageV = voltageV ?: return null,
        remainingAh = remainingAh ?: return null,
        fullChargeAh = fullChargeAh ?: return null,
        soc = soc ?: return null,
    )
}

/** Folds one stored row into [this] state; a row [toReading] rejects leaves it untouched. */
fun EstPackState.replayRow(row: EstRowColumns): EstPackState =
    row.toReading()?.let { stepEstimate(this, it) } ?: this

/** Replays [rows] (time order) through the estimator from [start]. */
fun replayEstimate(rows: Sequence<EstReading>, start: EstPackState = EstPackState()): EstPackState =
    rows.fold(start, ::stepEstimate)

/**
 * Seeds the estimator: [replay]s every address, then [install]s all results at once, then
 * [markSeeded]. All or nothing: any Throwable (a failed read, a cancelled replay, a dying process's
 * cancellation) is passed to [onFailure] and nothing is installed or marked, so the next start
 * replays again. [install] returns false to refuse (the engine's session ended); then nothing is
 * marked either. Returns true only when every step succeeded.
 */
suspend fun seedEstimator(
    addresses: Collection<String>,
    replay: suspend (String) -> EstPackState,
    install: (Map<String, EstPackState>) -> Boolean,
    markSeeded: suspend () -> Unit,
    onFailure: (Throwable) -> Unit = {},
): Boolean {
    val result = LinkedHashMap<String, EstPackState>()
    try {
        for (a in addresses) result[a] = replay(a)
    } catch (e: Throwable) {
        onFailure(e)
        return false
    }
    if (!install(result)) return false
    return try {
        markSeeded()
        true
    } catch (e: Throwable) {
        onFailure(e)
        false
    }
}
