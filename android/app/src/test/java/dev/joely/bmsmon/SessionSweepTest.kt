package dev.joely.bmsmon

import dev.joely.bmsmon.data.OrphanedSessionAction
import dev.joely.bmsmon.data.RollupAccumulator
import dev.joely.bmsmon.data.orphanedSessionAction
import dev.joely.bmsmon.data.rollupAccumulatorOf
import dev.joely.bmsmon.data.sweepOrphanedStubs
import dev.joely.bmsmon.data.db.SampleEntity
import dev.joely.bmsmon.data.db.SessionEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Startup finalize-sweep (DATA-2/DATA-16): a `sampleCount = 0` session stub left by process
 * death is either finalized with real rollups (it has telemetry samples) or deleted (it never
 * got any). Both the per-stub decision and the guarded per-stub loop ([sweepOrphanedStubs]) are
 * pure, with the DAO calls injected, and tested here (no Room test infra in this project by
 * design). TelemetryRepository's init only wires the DAOs and the logger into the loop.
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

    // --- the guarded per-stub loop -------------------------------------------------------------

    private fun stub(id: Long) = SessionEntity(
        id = id, address = "A", startMs = 0, endMs = 0, sampleCount = 0,
        peakPowerW = 0f, p95PowerW = 0f, meanPowerW = 0f, peakCurrentA = 0f, peakRegenW = 0f,
        energyWh = 0f, socStart = 0f, socEnd = 0f, minSoc = 0f, maxSoc = 0f,
        minVoltageUnderLoad = 0f, estInternalResistanceMohm = null, irConfidence = 0f,
        sohEnd = 0, fullChargeAhEnd = 0f, cyclesEnd = 0, maxTempC = 0f,
    )

    /** A stub with one telemetry row folds to a Finalize; an empty fold is a Delete. */
    private fun withRun(id: Long) = rollupAccumulatorOf("A", id, listOf(telemetry(0, 90f, -10f, 100f, 13.2f)))
    private fun noRun(id: Long) = RollupAccumulator("A", id)

    /** Every injected call in order, plus each onError's (message, throwable). */
    private class Recorder {
        val events = mutableListOf<String>()
        val errors = mutableListOf<Pair<String, Throwable>>()
    }

    /** Runs the sweep over stubs 1..[count]; [update] and [delete] succeed unless told otherwise. */
    private fun sweep(
        count: Int,
        accumulate: (Long) -> RollupAccumulator,
        update: (SessionEntity) -> Unit = {},
        delete: (Long) -> Unit = {},
    ): Recorder {
        val r = Recorder()
        runBlocking {
            sweepOrphanedStubs(
                stubs = (1L..count).map(::stub),
                accumulate = { _, id -> r.events += "accumulate $id"; accumulate(id) },
                update = { s -> r.events += "update ${s.id} n=${s.sampleCount}"; update(s) },
                delete = { id -> r.events += "delete $id"; delete(id) },
                onError = { msg, t -> r.events += "error"; r.errors += msg to t },
            )
        }
        return r
    }

    @Test fun sweepFinalizesStubsWithARunAndDeletesEmptyOnes() {
        val r = sweep(2, accumulate = { id -> if (id == 1L) withRun(id) else noRun(id) })
        assertEquals(listOf("accumulate 1", "update 1 n=1", "accumulate 2", "delete 2"), r.events)
        assertTrue(r.errors.isEmpty())
    }

    /** Anything a stub throws — an Error included — is reported, the stub deleted, and the next swept. */
    @Test fun aThrowingStubIsReportedDeletedAndTheSweepCarriesOn() {
        val boom = OutOfMemoryError("page 3")
        val r = sweep(3, accumulate = { id -> if (id == 2L) throw boom else withRun(id) })
        assertEquals(
            listOf(
                "accumulate 1", "update 1 n=1",
                "accumulate 2", "error", "delete 2",
                "accumulate 3", "update 3 n=1",
            ),
            r.events,
        )
        assertEquals(1, r.errors.size)
        assertSame(boom, r.errors[0].second)
        assertTrue(r.errors[0].first, r.errors[0].first.contains("id=2"))
    }

    /** A failing finalize write is handled like a failing fold: report, delete, carry on. */
    @Test fun aFailingFinalizeWriteDeletesTheStub() {
        val r = sweep(2, accumulate = ::withRun, update = { s -> if (s.id == 1L) error("disk I/O") })
        assertEquals(
            listOf("accumulate 1", "update 1 n=1", "error", "delete 1", "accumulate 2", "update 2 n=1"),
            r.events,
        )
    }

    /** Cancellation is never swallowed: it propagates, nothing is reported, no later stub is touched. */
    @Test fun cancellationPropagatesAndStopsTheSweep() {
        for (where in listOf("accumulate", "recovery delete")) {
            val events = mutableListOf<String>()
            try {
                runBlocking {
                    sweepOrphanedStubs(
                        stubs = listOf(stub(1), stub(2)),
                        accumulate = { _, id ->
                            events += "accumulate $id"
                            if (where == "accumulate") throw CancellationException("left")
                            error("corrupt page")
                        },
                        update = { s -> events += "update ${s.id}" },
                        delete = { id ->
                            events += "delete $id"
                            throw CancellationException("left")
                        },
                        onError = { _, _ -> events += "error" },
                    )
                }
                fail("$where: CancellationException must propagate")
            } catch (_: CancellationException) {
            }
            val expected = if (where == "accumulate") listOf("accumulate 1")
                else listOf("accumulate 1", "error", "delete 1")
            assertEquals(where, expected, events)
        }
    }

    /** A stub whose recovery delete ALSO fails is reported twice, skipped, and the sweep carries on. */
    @Test fun aStubWhoseDeleteAlsoFailsIsSkipped() {
        val deleteFailure = IllegalStateException("database is locked")
        val r = sweep(
            2,
            accumulate = { id -> if (id == 1L) error("corrupt page") else withRun(id) },
            delete = { id -> if (id == 1L) throw deleteFailure },
        )
        assertEquals(
            listOf("accumulate 1", "error", "delete 1", "error", "accumulate 2", "update 2 n=1"),
            r.events,
        )
        assertEquals(2, r.errors.size)
        assertTrue(r.errors.all { it.first.contains("id=1") })
        assertSame(deleteFailure, r.errors[1].second)
    }
}
