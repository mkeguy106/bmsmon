package dev.joely.bmsmon.cloud

import dev.joely.bmsmon.data.db.OutboxDao
import dev.joely.bmsmon.data.db.OutboxEntity
import dev.joely.bmsmon.data.db.TsSpan
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private const val NOW = 1_700_000_000_000L

/** An outbox in memory, logging each delete into [events]; [failDeletes] upcoming deletes throw. */
private class FakeOutbox(private val events: MutableList<String>) : OutboxDao {
    val rows = mutableListOf<OutboxEntity>()
    private var nextId = 1L
    var failDeletes = 0
    var spanGate: CompletableDeferred<Unit>? = null

    fun fill(vararg ts: Long) = ts.forEach { rows += OutboxEntity(nextId++, """{"ts":$it}""", it) }
    fun ids() = rows.map { it.id }

    private fun maybeFail(what: String) {
        if (failDeletes > 0) {
            failDeletes--
            throw IllegalStateException("$what failed")
        }
    }

    override suspend fun insert(rows: List<OutboxEntity>) = fail("not used")
    override suspend fun peek(limit: Int) = rows.sortedBy { it.id }.take(limit)
    override suspend fun deleteUpTo(id: Long) {
        maybeFail("deleteUpTo")
        events += "deleteUpTo($id)"
        rows.removeAll { it.id <= id }
    }
    override suspend fun count() = rows.size
    override suspend fun oldestEnqueuedAt() = rows.minOfOrNull { it.enqueuedAt }
    override suspend fun dropOldest(n: Int): Int {
        val drop = rows.sortedBy { it.id }.take(n)
        rows.removeAll(drop)
        events += "dropOldest($n)"
        return drop.size
    }
    override suspend fun oldestSpan(n: Int): TsSpan {
        spanGate?.await()
        val r = rows.sortedBy { it.id }.take(n)
        return TsSpan(r.minOfOrNull { it.enqueuedAt }, r.maxOfOrNull { it.enqueuedAt })
    }
    override suspend fun deleteOne(id: Long): Int {
        maybeFail("deleteOne")
        events += "deleteOne($id)"
        return if (rows.removeAll { it.id == id }) 1 else 0
    }
}

/** The ledger's store in memory, with injectable read / write / counter failures. */
private class FakeStore(private val events: MutableList<String>) : LedgerStore {
    var blob: String? = null
    var readFailures = 0
    var writeFailures = 0
    var countFailures = 0
    var writes = 0
    var evicted = 0L
    var skips = 0L

    fun windows() = decodeResync(blob, NOW).windows

    override suspend fun loadResyncJson(): String? {
        if (readFailures > 0) { readFailures--; throw IOException("read failed") }
        return blob
    }
    override suspend fun setResyncJson(json: String) {
        writes++
        if (writeFailures > 0) { writeFailures--; throw IOException("write failed") }
        events += "write"
        blob = json
    }
    override suspend fun addOutboxEvicted(n: Long) {
        if (countFailures > 0) { countFailures--; throw IOException("count failed") }
        evicted += n
    }
    override suspend fun addServerFaultSkips(n: Long) {
        if (countFailures > 0) { countFailures--; throw IOException("count failed") }
        skips += n
    }
}

/** The outbox's deletes, the cap and the re-sync windows (DATA-19, DATA-22), against a failing store or DAO. */
class OutboxLedgerTest {
    private val events = mutableListOf<String>()
    private val outbox = FakeOutbox(events)
    private val store = FakeStore(events)
    private val logs = mutableListOf<String>()
    // evictChunk = 0: these tests pin the cap's recording and counting at the exact cap; hysteresis has its own.
    private val ledger = OutboxLedger(outbox, store, warn = { m, _ -> logs += m }, now = { NOW }, maxRows = 5, evictChunk = 0)

    private val poison = PostOutcome(PostResult.Poison, code = 422, fromApi = true)
    private val ok = PostOutcome(PostResult.Ok, code = 200, fromApi = true)
    private val fault = PostOutcome(PostResult.ServerFault, code = 500, fromApi = true)

    // --- the cap (DATA-19) ---

    @Test fun theCapPersistsTheEvictedSpanBeforeDeletingAndCountsWhatItDropped() = runBlocking {
        outbox.fill(NOW - 5_000, NOW - 9_000, NOW - 7_000, NOW - 1_000, NOW - 2_000, NOW - 3_000, NOW - 4_000, NOW - 500)
        assertEquals(5, ledger.capOutbox())
        assertEquals(listOf(4L, 5L, 6L, 7L, 8L), outbox.ids())
        assertEquals(listOf(ResyncWindow(NOW - 9_000, NOW - 5_000)), store.windows())   // exactly the three dropped
        assertEquals(3L, store.evicted)
        assertEquals(listOf("write", "dropOldest(3)"), events)                          // recorded first
    }

    @Test fun aStoreReadErrorEvictsNothingAndNeverOverwritesTheSavedWindows() = runBlocking {
        val saved = ResyncWindow(NOW - 3 * 3_600_000L, NOW - 3 * 3_600_000L + 1_000)
        store.blob = encodeResync(ResyncState(listOf(saved)))
        store.readFailures = 1
        outbox.fill(NOW - 8_000, NOW - 7_000, NOW - 6_000, NOW - 5_000, NOW - 4_000, NOW - 3_000)
        assertEquals(6, ledger.capOutbox())
        assertEquals(6, outbox.rows.size)
        assertEquals(0, store.writes)                       // abandoned: "unreadable" is never "no windows"
        assertEquals(listOf(saved), store.windows())
        // The next pass reads, records, and only then evicts — keeping the saved window.
        assertEquals(5, ledger.capOutbox())
        assertEquals(listOf(saved, ResyncWindow(NOW - 8_000, NOW - 8_000)), store.windows())
    }

    @Test fun theCapNeverEvictsUnderAWindowThatExistsOnlyInMemory() = runBlocking {
        store.writeFailures = 2
        outbox.fill(NOW - 8_000, NOW - 7_000, NOW - 6_000, NOW - 5_000, NOW - 4_000, NOW - 3_000, NOW - 2_000)
        assertEquals(7, ledger.capOutbox())                 // the write failed: nothing evicted
        // The same span again. Had memory taken the window before the write, this merge would be a
        // no-op (no write) and the eviction would go ahead with the window held only in memory.
        assertEquals(7, ledger.capOutbox())
        assertEquals(2, store.writes)                       // the identical merge wrote again (and failed)
        assertEquals(7, outbox.rows.size)
        assertEquals(5, ledger.capOutbox())                 // the write lands: now it evicts
        assertEquals(listOf(ResyncWindow(NOW - 8_000, NOW - 7_000)), store.windows())
        assertEquals(2L, store.evicted)
    }

    @Test fun aFailedWriteIsTriedAgainByTheSameMerge() = runBlocking {
        val w = ResyncWindow(NOW - 10_000, NOW - 9_000)
        store.writeFailures = 1
        try {
            ledger.mutateResync { addResyncWindow(it, w, NOW) }
            fail("the failed write must surface")
        } catch (_: IOException) {
        }
        ledger.mutateResync { addResyncWindow(it, w, NOW) }
        assertEquals(2, store.writes)
        assertEquals(listOf(w), store.windows())
    }

    @Test fun aLostEvictionCountNeverUndoesTheEviction() = runBlocking {
        store.countFailures = 1
        outbox.fill(NOW - 3_000, NOW - 2_000, NOW - 1_000, NOW - 900, NOW - 800, NOW - 700)
        assertEquals(5, ledger.capOutbox())
        assertEquals(listOf(ResyncWindow(NOW - 3_000, NOW - 3_000)), store.windows())
        assertTrue(logs.any { "count is short" in it })
    }

    // Eviction hysteresis: past the cap the outbox is cut back a whole chunk, so each eviction, and its
    // DataStore write of the re-send windows, waits for a chunk of new rows instead of happening every pass.
    @Test fun rowsPastTheCapCostOneWindowWritePerChunkNotOnePerRow() = runBlocking {
        val max = 50
        val chunk = 10
        val l = OutboxLedger(outbox, store, warn = { m, _ -> logs += m }, now = { NOW }, maxRows = max, evictChunk = chunk)
        var ts = NOW - 1_000_000
        outbox.fill(*LongArray(max) { ts++ })
        assertEquals(max, l.capOutbox())
        val n = 100
        repeat(n) {                                         // one enqueue, then one upload-loop pass, each
            outbox.fill(ts++)
            assertTrue(l.capOutbox() <= max)
        }
        // The first row past the cap evicts down to max − chunk; then every chunk + 1 rows evict again:
        // ⌈n / (chunk + 1)⌉ = ⌈100 / 11⌉ = 10 = ⌈n / chunk⌉ writes, not n.
        assertEquals(10, store.writes)
        assertEquals(10, events.count { it.startsWith("dropOldest") })
        assertEquals(max + n - outbox.rows.size, store.evicted.toInt())   // every evicted row is counted
        // and the windows cover every row that left, oldest first, contiguously.
        assertEquals(listOf(ResyncWindow(NOW - 1_000_000, outbox.rows.minOf { it.enqueuedAt } - 1)), store.windows())
    }

    // Offline at the cap, every ~1.5 s pass evicts a few rows: one line per minute, not one per pass
    // (logcat also holds the motion instrumentation). Every evicted row is still counted.
    @Test fun aPhoneOfflineAtTheCapLogsItsEvictionsAtMostOncePerMinute() = runBlocking {
        var clock = 0L
        val l = OutboxLedger(outbox, store, warn = { m, _ -> logs += m }, now = { NOW }, maxRows = 5, evictChunk = 0, elapsed = { clock })
        var ts = NOW - 100_000
        outbox.fill(*LongArray(5) { ts++ })
        repeat(80) {                                        // two minutes of passes, each two rows over the cap
            outbox.fill(ts++, ts++)
            l.capOutbox()
            clock += 1_500
        }
        assertEquals(160L, store.evicted)
        val lines = logs.filter { "evicted" in it }
        assertEquals(2, lines.size)                         // the first pass, then one summary at 60 s
        assertTrue(lines[0], lines[0].startsWith("outbox: full — evicted 2 oldest samples"))
        assertTrue(lines[1], lines[1].startsWith("outbox: full — evicted 80 oldest samples") && "40 passes" in lines[1])
    }

    @Test fun aStoreThatCannotRecordTheReSendLogsAtMostOncePerMinute() = runBlocking {
        var clock = 0L
        val l = OutboxLedger(outbox, store, warn = { m, _ -> logs += m }, now = { NOW }, maxRows = 5, evictChunk = 0, elapsed = { clock })
        store.readFailures = Int.MAX_VALUE
        outbox.fill(NOW - 7_000, NOW - 6_000, NOW - 5_000, NOW - 4_000, NOW - 3_000, NOW - 2_000)
        repeat(80) {
            assertEquals(6, l.capOutbox())
            clock += 1_500
        }
        assertEquals(2, logs.size)
        assertTrue(logs.all { "could not be recorded" in it })
    }

    // The loop's deletes take the cap's lock: an accepted batch deleted between the cap's span and its
    // drop would make the drop take newer rows the recorded window doesn't cover.
    @Test fun theCapAndAnAcceptedDeleteNeverInterleave() = runBlocking {
        outbox.fill(NOW - 8_000, NOW - 7_000, NOW - 6_000, NOW - 5_000, NOW - 4_000, NOW - 3_000, NOW - 2_000, NOW - 1_000)
        val gate = CompletableDeferred<Unit>()
        outbox.spanGate = gate
        val cap = launch { ledger.capOutbox() }
        yield()                                             // the cap holds its lock, waiting on the span
        val delete = launch { ledger.applyEffects(listOf(OutboxEffect.DeleteThrough(2))) }
        yield()
        val deletedMidCap = events.any { it.startsWith("deleteUpTo") }
        gate.complete(Unit)                                 // before any assert: a failure must not hang the cap
        joinAll(cap, delete)
        assertFalse(deletedMidCap)
        assertEquals(listOf(4L, 5L, 6L, 7L, 8L), outbox.ids())
        assertEquals(listOf(ResyncWindow(NOW - 8_000, NOW - 6_000)), store.windows())
    }

    // --- one ingest step: effects first, then the state (and the log) ---

    @Test fun aPoisonStepWhoseRecordFailsIsRetriedWithItsSkipStillArmed() = runBlocking {
        outbox.fill(NOW - 3_000, NOW - 2_000, NOW - 1_000)
        store.writeFailures = 1
        val s0 = IngestLoopState()
        val first = ledger.applyStep(s0, null, outbox.peek(UPLOAD_BATCH), poison, seq = 1, nowElapsedMs = 0L, nowWallMs = NOW)
        assertFalse(first.took)
        assertEquals(s0.copy(backoffMs = nextBackoffMs(s0.backoffMs)), first.state)   // the previous state: skip still armed
        assertEquals(3, outbox.rows.size)
        assertFalse(logs.any { "permanently rejected" in it })                        // no skip that didn't happen
        assertTrue(logs.single().contains("nothing was deleted"))
        // The retry gets the same decision — the one skip — and this time it lands: the queue moves.
        val second = ledger.applyStep(first.state, first.lastLog, outbox.peek(UPLOAD_BATCH), poison, seq = 2, nowElapsedMs = 0L, nowWallMs = NOW)
        assertTrue(second.took)
        assertEquals(1, second.state.poisonSkips)
        assertEquals(0, outbox.rows.size)
        assertEquals(listOf("write", "deleteUpTo(3)"), events)                        // the re-send recorded first
        assertTrue(logs.last().contains("permanently rejected") && logs.last().endsWith("(seq=2)"))
    }

    @Test fun anAcceptedBatchWhoseDeleteFailsIsRetriedFromThePreviousState() = runBlocking {
        outbox.fill(NOW - 2_000, NOW - 1_000)
        outbox.failDeletes = 1
        val s0 = IngestLoopState(backoffMs = 4_000L, authFailed = true)
        val first = ledger.applyStep(s0, null, outbox.peek(UPLOAD_BATCH), ok, seq = 1, nowElapsedMs = 0L, nowWallMs = NOW)
        assertFalse(first.took)
        assertEquals(s0.copy(backoffMs = 8_000L), first.state)
        assertEquals(4_000L, first.delayMs)
        assertEquals(2, outbox.rows.size)
        val second = ledger.applyStep(first.state, first.lastLog, outbox.peek(UPLOAD_BATCH), ok, seq = 2, nowElapsedMs = 0L, nowWallMs = NOW)
        assertTrue(second.took)
        assertEquals(0, outbox.rows.size)
        assertFalse(second.state.authFailed)
    }

    // The delete is the commit point: once the row is gone the skip is spent, even if its count isn't saved.
    @Test fun aSkipIsSpentOnceItsRowIsGoneEvenIfItsCountIsNotSaved() = runBlocking {
        outbox.fill(NOW - 2_000, NOW - 1_000)
        store.countFailures = 1
        val tripping = IngestLoopState(
            fault = HeadFaultState(headId = 1L, limit = 1, streak = FAULT_STREAK - 1, firstFaultAtMs = 0L),
        )
        val run = ledger.applyStep(tripping, null, outbox.peek(1), fault, seq = 9, nowElapsedMs = FAULT_MIN_SPAN_MS, nowWallMs = NOW)
        assertTrue(run.took)
        assertEquals(1, run.state.fault.skipsSinceOk)                                 // spent: a second skip needs a 2xx
        assertEquals(listOf(2L), outbox.ids())
        assertEquals(0L, store.skips)
        assertEquals(listOf("write", "deleteOne(1)"), events)
        val bytes = """{"ts":${NOW - 2_000}}""".toByteArray().size
        assertTrue(logs.any { "skipping outbox id=1" in it && it.endsWith("(seq=9, $bytes bytes)") })
        assertTrue(logs.last().contains("count is short"))
    }

    @Test fun aSkipOfARowTheCapAlreadyEvictedCountsNothing() = runBlocking {
        outbox.fill(NOW - 1_000)
        val r = ledger.applyEffects(listOf(OutboxEffect.SkipOne(99L)))
        assertTrue(r.took)
        assertEquals(0L, store.skips)
        assertEquals(1, outbox.rows.size)
    }

    @Test fun aStoreReadErrorRollsBackAStepThatWouldRecordAReSend() = runBlocking {
        outbox.fill(NOW - 1_000)
        store.readFailures = 1
        val run = ledger.applyStep(IngestLoopState(), null, outbox.peek(UPLOAD_BATCH), poison, seq = 1, nowElapsedMs = 0L, nowWallMs = NOW)
        assertFalse(run.took)
        assertEquals(0, run.state.poisonSkips)
        assertEquals(1, outbox.rows.size)
        assertEquals(0, store.writes)
    }
}
