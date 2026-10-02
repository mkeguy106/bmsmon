package dev.joely.bmsmon

import androidx.datastore.core.CorruptionException
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import dev.joely.bmsmon.data.ioRetryDelayMs
import dev.joely.bmsmon.data.orEmptyOnIoError
import dev.joely.bmsmon.data.retryOnIoError
import dev.joely.bmsmon.data.settingsCorruptionHandler
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * DATA-21: a damaged bms_settings.preferences_pb used to throw CorruptionException out of `data`
 * on every process start (crash loop: monitoring dead until app data is cleared). These run a REAL
 * file-backed DataStore on the JVM (datastore-core / -preferences-core are plain JVM jars).
 */
class DataStoreSafetyTest {

    private val flag = booleanPreferencesKey("flag")

    /** A settings file whose bytes are not a protobuf (first byte 0x6E = an invalid wire type). */
    private fun corruptFile(): File = File.createTempFile("bms_settings", ".preferences_pb").apply {
        writeBytes("not a protobuf".toByteArray())
        deleteOnExit()
    }

    private fun withScope(block: suspend (CoroutineScope) -> Unit) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            runBlocking { block(scope) }
        } finally {
            scope.cancel()
        }
    }

    /** Pins the premise: without a handler the corrupt file escapes as an exception. */
    @Test fun withoutAHandlerACorruptFileThrowsOutOfData() {
        withScope { scope ->
            val file = corruptFile()
            val ds = PreferenceDataStoreFactory.create(scope = scope) { file }
            try {
                ds.data.first()
                fail("expected CorruptionException")
            } catch (_: CorruptionException) {
            }
        }
    }

    @Test fun corruptFileIsReplacedWithDefaultsAndStaysWritable() {
        withScope { scope ->
            val file = corruptFile()
            var seen: CorruptionException? = null
            val ds = PreferenceDataStoreFactory.create(
                corruptionHandler = settingsCorruptionHandler { seen = it },
                scope = scope,
            ) { file }
            assertEquals(emptyPreferences(), ds.data.first())
            assertNotNull("the handler must observe the corruption (production logs it)", seen)
            ds.edit { it[flag] = true }
            assertEquals(true, ds.data.first()[flag])
        }
    }

    @Test fun oneShotReadDegradesToDefaultsOnIoError() {
        runBlocking {
            var logged = 0
            val p = flow<Preferences> { throw IOException("disk") }.orEmptyOnIoError { logged++ }.first()
            assertEquals(emptyPreferences(), p)
            assertEquals(1, logged)
        }
    }

    @Test fun oneShotReadStillThrowsNonIoErrors() {
        runBlocking {
            try {
                flow<Preferences> { throw IllegalStateException("bug") }.orEmptyOnIoError().first()
                fail("non-I/O errors must propagate")
            } catch (e: IllegalStateException) {
                assertEquals("bug", e.message)
            }
        }
    }

    // Review Focus 5: the reporter's snapshot is a process-lifetime collector. Emitting defaults on
    // an I/O error would COMPLETE the flow and freeze "cloud off" until the next process start; it
    // must re-read and keep going instead.
    @Test fun longLivedCollectorRetriesIoErrorsAndKeepsCollecting() {
        runBlocking {
            var attempts = 0
            var logged = 0
            val upstream = flow {
                attempts++
                if (attempts <= 2) throw IOException("transient")
                emit("ok")
            }
            assertEquals("ok", upstream.retryOnIoError(baseDelayMs = 1L, onError = { logged++ }).first())
            assertEquals(3, attempts)
            assertEquals(2, logged)
        }
    }

    @Test fun longLivedCollectorStillThrowsNonIoErrors() {
        runBlocking {
            try {
                flow<String> { throw IllegalStateException("bug") }.retryOnIoError(baseDelayMs = 1L).first()
                fail("non-I/O errors must propagate")
            } catch (e: IllegalStateException) {
                assertEquals("bug", e.message)
            }
        }
    }

    @Test fun retryBackoffDoublesAndCaps() {
        assertEquals(1_000L, ioRetryDelayMs(0, 1_000L, 30_000L))
        assertEquals(2_000L, ioRetryDelayMs(1, 1_000L, 30_000L))
        assertEquals(16_000L, ioRetryDelayMs(4, 1_000L, 30_000L))
        assertEquals(30_000L, ioRetryDelayMs(5, 1_000L, 30_000L))     // 32 s, capped
        assertEquals(30_000L, ioRetryDelayMs(500, 1_000L, 30_000L))   // no shift overflow
    }
}
