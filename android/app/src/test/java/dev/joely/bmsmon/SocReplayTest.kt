package dev.joely.bmsmon

import dev.joely.bmsmon.data.db.EstRowColumns
import dev.joely.bmsmon.model.BatteryState
import dev.joely.bmsmon.model.EstPackState
import dev.joely.bmsmon.model.EstReading
import dev.joely.bmsmon.model.EstSource
import dev.joely.bmsmon.model.SocCorrection
import dev.joely.bmsmon.model.replayEstimate
import dev.joely.bmsmon.model.replayRow
import dev.joely.bmsmon.model.seedEstimator
import dev.joely.bmsmon.model.toReading
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Task 4: the one-time replay of local history through the pure estimator. */
class SocReplayTest {

    /** Prod `samples` for 2012-B (C8:47:80:15:62:1B) around its 2026-11-16 charge, which ended at
     *  2026-11-17 07:38:07.954Z (1794901087954) with the counter pinned at 120 Ah against a 105 Ah
     *  full charge and the charger taper at 13.90-13.97 V. 30 min before to 3 h after; every Charging
     *  row of the last 20 min kept, every 10th row otherwise. */
    private fun fixture(): List<EstReading> {
        val text = javaClass.getResourceAsStream("/soc/2012b-2026-11-16-charge-end.csv")!!.bufferedReader().readText()
        val lines = text.lines().filter { it.isNotBlank() }
        assertEquals("ts_ms,state,regen,current_a,voltage_v,remaining_ah,full_charge_ah,soc", lines.first())
        return lines.drop(1).mapIndexed { k, line ->
            val c = line.split(',')
            EstRowColumns(
                id = k.toLong(), tsMs = c[0].toLong(), state = c[1], regen = c[2] == "t",
                currentA = c[3].toFloat(), voltageV = c[4].toFloat(), remainingAh = c[5].toFloat(),
                fullChargeAh = c[6].toFloat(), soc = c[7].toFloat(),
            ).toReading()!!
        }
    }

    @Test fun realChargeEndYields15Ah() {
        val rows = fixture()
        assertTrue("fixture is the decimated slice", rows.size in 1_000..2_000)
        val s = replayEstimate(rows.asSequence())
        assertEquals(EstSource.COUNTER, s.correction.source)
        assertTrue("correction ${s.correction.correctionAh}", s.correction.correctionAh in 14.5f..15.5f)
        // Set at the charge end (the first non-Charging row after the taper), not later.
        assertTrue(s.correction.setAtMs in 1_794_901_087_954L..1_794_901_087_954L + 60_000L)
    }

    private fun cols(
        tsMs: Long = 1_000L, state: String? = "Idle", currentA: Float? = 0f, voltageV: Float? = 13.1f,
        remainingAh: Float? = 100f, fullChargeAh: Float? = 105f, soc: Float? = 95f, regen: Boolean = false,
    ) = EstRowColumns(1L, tsMs, state, regen, currentA, voltageV, remainingAh, fullChargeAh, soc)

    @Test fun nullColumnsSkipped() {
        assertNull(cols(state = null).toReading())
        assertNull(cols(state = "Bogus").toReading())
        assertNull(cols(currentA = null).toReading())
        assertNull(cols(voltageV = null).toReading())
        assertNull(cols(remainingAh = null).toReading())
        assertNull(cols(fullChargeAh = null).toReading())
        assertNull(cols(soc = null).toReading())
        assertEquals(
            EstReading(1_000L, BatteryState.Charging, true, 2f, 13.9f, 120f, 105f, 99f),
            cols(state = "Charging", regen = true, currentA = 2f, voltageV = 13.9f, remainingAh = 120f, soc = 99f).toReading(),
        )
        // A skipped row leaves the state exactly as it was — not even its timestamp is taken.
        val s = EstPackState(correction = SocCorrection(15f, EstSource.COUNTER, 5L), lastTsMs = 500L)
        assertEquals(s, s.replayRow(cols(voltageV = null)))
        // A whole replay of usable rows with one null row in the middle equals the replay without it.
        val good = (0 until 40).map { cols(tsMs = it * 60_000L, voltageV = 13.10f, remainingAh = 100f) }
        val withNull = good.take(20) + cols(tsMs = 20 * 60_000L - 1, currentA = null) + good.drop(20)
        assertEquals(
            replayEstimate(good.mapNotNull { it.toReading() }.asSequence()),
            withNull.fold(EstPackState()) { acc, r -> acc.replayRow(r) },
        )
    }

    // Review focus 5: a restart (or any failure) mid-replay must not leave a half-seeded state
    // marked done. Nothing is installed and the seeded flag is not written, so it retries next start.
    @Test fun replayFailureLeavesUnseeded() = runBlocking {
        var installed: Map<String, EstPackState>? = null
        var marked = false
        var failure: Throwable? = null
        val ok = seedEstimator(
            addresses = listOf("A", "B", "C"),
            replay = { a -> if (a == "B") throw IllegalStateException("process dying") else EstPackState() },
            install = { installed = it; true },
            markSeeded = { marked = true },
            onFailure = { failure = it },
        )
        assertFalse(ok)
        assertNull("nothing installed", installed)
        assertFalse("seeded flag not written", marked)
        assertTrue(failure is IllegalStateException)
    }

    @Test fun aReplayFinishingAfterStopChangesNothing() = runBlocking {
        var marked = false
        val ok = seedEstimator(
            addresses = listOf("A"),
            replay = { EstPackState(correction = SocCorrection(15f, EstSource.COUNTER, 1L)) },
            install = { false },   // the session ended: the engine refuses the install
            markSeeded = { marked = true },
        )
        assertFalse(ok)
        assertFalse(marked)
    }

    @Test fun aFailedSeededWriteReportsFailure() = runBlocking {
        var failure: Throwable? = null
        val ok = seedEstimator(
            addresses = listOf("A"),
            replay = { EstPackState() },
            install = { true },
            markSeeded = { throw java.io.IOException("disk full") },
            onFailure = { failure = it },
        )
        assertFalse(ok)
        assertTrue(failure is java.io.IOException)
    }

    @Test fun aSuccessfulReplayInstallsEveryPackThenMarksSeeded() = runBlocking {
        val order = ArrayList<String>()
        var installed: Map<String, EstPackState>? = null
        val ok = seedEstimator(
            addresses = listOf("A", "B"),
            replay = { a -> order += "replay $a"; EstPackState(lastTsMs = a.length.toLong()) },
            install = { order += "install"; installed = it; true },
            markSeeded = { order += "mark" },
        )
        assertTrue(ok)
        assertEquals(listOf("replay A", "replay B", "install", "mark"), order)
        assertEquals(setOf("A", "B"), installed!!.keys)
    }

    // Engine wiring: the replay runs through seedEstimator, only for an unseeded store, inside the
    // session-checked load, with live stepping held off until it is done.
    @Test fun engineSeedsOnlyWhenUnseededAndSessionChecked() {
        val src = listOf("src/main/java", "app/src/main/java")
            .map { File(it, "dev/joely/bmsmon/monitor/MonitorEngine.kt") }
            .first { it.isFile }.readText().replace(Regex("\\s+"), " ")
        val start = src.substringAfter("fun start(roster: Roster,").substringBefore("suspend fun restoreFromPersisted(")
        assertTrue(start.contains("seeding = !saved.socEstimatorSeeded"))
        val seed = src.substringAfter("private fun startSocReplay(").substringBefore("private fun ")
        assertTrue(seed.contains("seedEstimator("))
        assertTrue("install is session-checked", seed.contains("if (session != currentSession) false"))
        assertTrue("corrections are written before the seeded flag",
            seed.indexOf("settings.setSocCorrections(") in 0 until seed.indexOf("settings.setSocEstimatorSeeded(true)"))
        assertTrue("seeding always ends", seed.contains("finally"))
    }
}
