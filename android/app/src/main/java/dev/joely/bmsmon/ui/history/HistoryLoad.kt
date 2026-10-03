package dev.joely.bmsmon.ui.history

import kotlinx.coroutines.CancellationException

/** State of a History / Review / Timeline load, so a failure renders instead of crashing (UI-17). */
sealed interface HistoryLoad<out T> {
    object Loading : HistoryLoad<Nothing>
    data class Ready<T>(val value: T) : HistoryLoad<T>
    data class Failed(val error: Throwable) : HistoryLoad<Nothing>
}

/**
 * Run a history loader inside `produceState` so ANY failure becomes [HistoryLoad.Failed] instead of
 * an exception escaping the composition's coroutine — which would crash the process, and the
 * foreground service and BLE monitoring share that process. Catches Throwable on purpose: an OOM or
 * SQLite error while opening an analytics screen must never take monitoring down. Cancellation (the
 * user navigating away) still propagates.
 */
suspend fun <T> loadHistory(block: suspend () -> T): HistoryLoad<T> =
    try {
        HistoryLoad.Ready(block())
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        HistoryLoad.Failed(t)
    }
