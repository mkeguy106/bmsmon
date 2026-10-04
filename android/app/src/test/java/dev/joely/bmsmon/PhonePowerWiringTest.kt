package dev.joely.bmsmon

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source guard for the phone-charger snapshot upload. The provider defaults to `{ null }`, so if
 * either end of the wiring were dropped the block would silently stop riding live batches while
 * every encoding test stayed green — and the server could no longer page about a charger that
 * isn't charging. The reporter and the Application can't be unit-tested without Android, so pin
 * the two lines textually. Gradle runs unit tests from the module dir.
 */
class PhonePowerWiringTest {

    private fun source(path: String): String =
        listOf("src/main/java", "app/src/main/java")
            .map { File(it, path) }
            .first { it.isFile }
            .readText()
            .replace(Regex("\\s+"), " ")

    @Test fun theApplicationHandsTheEngineSnapshotToTheReporter() {
        assertTrue(source("dev/joely/bmsmon/BmsApp.kt").contains("phonePower = { engine.phonePowerJson() }"))
    }

    @Test fun theLiveIngestBatchCarriesTheSnapshot() {
        val reporter = source("dev/joely/bmsmon/cloud/TelemetryReporter.kt")
        assertTrue(reporter.contains("val phone = runCatching(phonePower).getOrNull()"))
        assertTrue(reporter.contains("CloudJson.encodeBatch(seq, rows.map { it.payload }, phone)"))
    }
}
