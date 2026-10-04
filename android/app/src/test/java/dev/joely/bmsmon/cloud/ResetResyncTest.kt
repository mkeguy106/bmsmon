package dev.joely.bmsmon.cloud

import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Re-enrolling (or forgetting the device) drops the pending re-sends and marks the history import due.
 * The flag goes first: a process death between the two then leaves the old windows and a due import,
 * a re-send the server dedups, never an enrolled phone with no windows and the import marked queued.
 */
class ResetResyncTest {
    @Test fun theImportIsMarkedDueBeforeAnyWindowIsCleared() = runBlocking {
        val calls = mutableListOf<String>()
        resetResyncInOrder(setImportDone = { calls += "importDone=$it" }, clearWindows = { calls += "clear" })
        assertEquals(listOf("importDone=false", "clear"), calls)
    }

    @Test fun aFailedFlagWriteClearsNothing() = runBlocking {
        var cleared = false
        val thrown = runCatching {
            resetResyncInOrder(setImportDone = { throw IOException("disk") }, clearWindows = { cleared = true })
        }.exceptionOrNull()
        assertTrue(thrown is IOException)
        assertFalse(cleared)
    }

    private fun source(path: String): String =
        listOf("src/main/java", "app/src/main/java")
            .map { File(it, path) }
            .first { it.isFile }
            .readText()

    // Under the import lock, so a re-sync pass can't queue the import between the two steps and see it wiped.
    @Test fun theReporterResetsUnderTheImportLock() {
        val src = source("dev/joely/bmsmon/cloud/TelemetryReporter.kt")
        val reset = src.substringAfter("fun resetResync(): Job").substringBefore("\n    }\n")
        assertTrue(reset.contains("importMutex.withLock {"))
        assertTrue(reset.contains("resetResyncInOrder("))
        assertFalse(src.contains("fun clearResync("))
    }

    @Test fun enrollAndForgetResetThroughTheReporter() {
        val vm = source("dev/joely/bmsmon/BatteryViewModel.kt")
        val enroll = vm.substringAfter("fun enroll(").substringBefore("fun forgetDevice(")
        assertTrue(enroll.contains("reporter.resetResync().join()"))
        assertFalse(enroll.contains("setImportDone("))
        val forget = vm.substringAfter("fun forgetDevice(").substringBefore("fun setLogging(")
        assertTrue(forget.contains("reporter.resetResync()"))
        assertFalse(forget.contains("setImportDone("))
    }
}
