package dev.joely.bmsmon.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.retryWhen

/*
 * DataStore failure containment (DATA-21). Without these, a damaged bms_settings.preferences_pb
 * throws CorruptionException out of `data`, nothing catches it, and the process dies as soon as the
 * reporter is constructed — on EVERY start: a crash loop that takes the foreground service and BLE
 * monitoring with it, recoverable only by clearing app data. Pure (no Android imports), JVM-tested
 * against a real file-backed DataStore in DataStoreSafetyTest.
 */

/**
 * Replace an unparseable settings file with defaults instead of crashing. Every setting reverts to
 * its documented default — monitoring off, not enrolled, default roster and alert ladder — which is
 * strictly better than the crash loop (whose only exit, "clear app data", lands in the same place).
 * [onCorrupt] is for logging.
 */
internal fun settingsCorruptionHandler(
    onCorrupt: (CorruptionException) -> Unit = {},
): ReplaceFileCorruptionHandler<Preferences> =
    ReplaceFileCorruptionHandler { e ->
        onCorrupt(e)
        emptyPreferences()
    }

/** One-shot reads (`load()`): an I/O failure degrades to defaults instead of throwing into the caller. */
internal fun Flow<Preferences>.orEmptyOnIoError(onError: (IOException) -> Unit = {}): Flow<Preferences> =
    catch { e ->
        if (e is IOException) {
            onError(e)
            emit(emptyPreferences())
        } else {
            throw e
        }
    }

/** Capped exponential backoff for [retryOnIoError]: base·2^attempt, at most [maxDelayMs]. */
internal fun ioRetryDelayMs(attempt: Long, baseDelayMs: Long, maxDelayMs: Long): Long =
    minOf(maxDelayMs, baseDelayMs shl attempt.coerceIn(0L, 5L).toInt())

/**
 * Long-lived collectors (the reporter's settings snapshot): an I/O failure re-reads with capped
 * backoff. Emitting defaults instead (the usual `.catch { emit(emptyPreferences()) }`) would
 * COMPLETE the flow and freeze the snapshot at "cloud off" for the rest of the process — so the
 * collector keeps its last good value and tries again. Non-I/O errors still throw.
 */
internal fun <T> Flow<T>.retryOnIoError(
    baseDelayMs: Long = 1_000L,
    maxDelayMs: Long = 30_000L,
    onError: (IOException) -> Unit = {},
): Flow<T> = retryWhen { cause, attempt ->
    if (cause !is IOException) return@retryWhen false
    onError(cause)
    delay(ioRetryDelayMs(attempt, baseDelayMs, maxDelayMs))
    true
}
