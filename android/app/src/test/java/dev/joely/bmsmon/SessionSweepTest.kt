package dev.joely.bmsmon

import dev.joely.bmsmon.data.OrphanedSessionAction
import dev.joely.bmsmon.data.orphanedSessionAction
import dev.joely.bmsmon.data.db.SampleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Startup finalize-sweep decision (DATA-2): a `sampleCount = 0` session stub left by process
 * death is either finalized with real rollups (it has telemetry samples) or deleted (it never
 * got any). The DB plumbing around this is a trivial loop in TelemetryRepository's init; the
 * decision itself is pure and tested here (no Room test infra in this project by design).
 */
class SessionSweepTest {

    private fun telemetry(ts: Long, soc: Float, cur: Float, pw: Float, v: Float) =
        SampleEntity(
            address = "A", tsMs = ts, sessionId = 42, state = if (cur < 0) "Discharging" else "Idle",
            soc = soc, currentA = cur, powerW = pw, voltageV = v, tempC = 25f, mosfetTempC = 26,
            soh = 99, fullChargeAh = 98.5f, remainingAh = 50f, cycles = 12,
            cellMinV = v / 4f, cellMaxV = v / 4f, regen = false, linkEvent = null,
        )

    private fun linkEvent(ts: Long, event: String) =
        SampleEntity(
            address = "A", tsMs = ts, sessionId = 42, state = null, soc = null,
            currentA = null, powerW = null, voltageV = null, tempC = null, mosfetTempC = null,
            soh = null, fullChargeAh = null, remainingAh = null, cycles = null,
            cellMinV = null, cellMaxV = null, regen = false, linkEvent = event,
        )

    @Test
    fun stubWithTelemetrySamplesIsFinalizedWithRollups() {
        val samples = listOf(
            telemetry(0, 90f, -10f, 100f, 13.2f),
            telemetry(1000, 89f, -30f, 300f, 13.0f),
        )
        val action = orphanedSessionAction("A", 42, samples)
        assertTrue(action is OrphanedSessionAction.Finalize)
        val rollup = (action as OrphanedSessionAction.Finalize).rollup
        assertEquals(42, rollup.id)                    // updates the existing stub row, not a new one
        assertEquals("A", rollup.address)
        assertEquals(2, rollup.sampleCount)            // > 0: the run becomes visible to history DAOs
        assertEquals(0L, rollup.startMs)
        assertEquals(1000L, rollup.endMs)
        assertEquals(90f, rollup.socStart, 0.01f)
        assertEquals(89f, rollup.socEnd, 0.01f)
        assertEquals(300f, rollup.peakPowerW, 0.01f)
    }

    /** Link-event rows are not telemetry; a stub holding only those carries no run → delete. */
    @Test
    fun stubWithOnlyLinkEventsIsDeleted() {
        val samples = listOf(linkEvent(0, "Connected"), linkEvent(500, "Disconnected"))
        assertEquals(OrphanedSessionAction.Delete, orphanedSessionAction("A", 42, samples))
    }

    /** A stub whose samples were retention-pruned (or that never got one) is pure noise → delete. */
    @Test
    fun stubWithNoSamplesIsDeleted() {
        assertEquals(OrphanedSessionAction.Delete, orphanedSessionAction("A", 42, emptyList()))
    }

    /** Mixed link + telemetry: finalized, and the rollup counts only telemetry rows. */
    @Test
    fun mixedLinkAndTelemetryFinalizesCountingTelemetryOnly() {
        val samples = listOf(
            linkEvent(0, "Connected"),
            telemetry(100, 80f, -5f, 60f, 13.1f),
            linkEvent(200, "Disconnected"),
        )
        val action = orphanedSessionAction("A", 42, samples)
        assertTrue(action is OrphanedSessionAction.Finalize)
        assertEquals(1, (action as OrphanedSessionAction.Finalize).rollup.sampleCount)
    }
}
