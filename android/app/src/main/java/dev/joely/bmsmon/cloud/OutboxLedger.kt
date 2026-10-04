package dev.joely.bmsmon.cloud

import dev.joely.bmsmon.data.db.OutboxDao
import dev.joely.bmsmon.data.db.OutboxEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The most rows the outbox keeps; past it the oldest are evicted, counted and queued for a re-send (DATA-5, DATA-19). */
internal const val OUTBOX_MAX = 200_000

/** What [OutboxLedger] persists: the re-sync windows blob and two all-time counters. SettingsStore in the app. */
internal interface LedgerStore {
    /** The stored blob, or null when none is stored. Throws when it can't be read: unreadable never means "none". */
    suspend fun loadResyncJson(): String?
    suspend fun setResyncJson(json: String)
    suspend fun addOutboxEvicted(n: Long)
    suspend fun addServerFaultSkips(n: Long)
}

/**
 * How one step's effects went. [took]: the step is spent, because its delete ran (the commit point) or it
 * had none to run. [failure]: what failed, if anything; when [took] is false, nothing was deleted.
 */
internal data class EffectsResult(val took: Boolean, val failure: Exception? = null)

/** One ingest step as the upload loop carries it forward. */
internal data class IngestRun(
    /** The state to carry on with: the step's own once its effects took, else the previous one with its backoff advanced. */
    val state: IngestLoopState,
    /** The last ingestStep message logged ([heldLog]). */
    val lastLog: String?,
    val delayMs: Long,
    /** False = rolled back: nothing was deleted, and the batch is retried from the previous state. */
    val took: Boolean,
)

/**
 * Every delete of the upload outbox, and the re-sync windows that make those deletes recoverable
 * (DATA-19, DATA-22). The deletes are [applyEffects]' (what [ingestStep] decided) and the cap's
 * eviction ([capOutbox]); nothing else removes an outbox row. Every write that can fail commits its
 * in-memory state only after it succeeded, so memory never runs ahead of the store:
 * - the windows are assigned only after they are persisted, and a store read error abandons the
 *   mutation instead of reading as "no windows" ([mutateResync]);
 * - the cap evicts only rows whose window is persisted ([capOutbox]);
 * - the loop keeps a step's state only once its delete ran ([applyStep]).
 *
 * The reporter owns the only instance (a process singleton), so its two locks cover every caller. Pure
 * of Android: [OutboxDao] and [LedgerStore] are faked in JVM tests.
 */
internal class OutboxLedger(
    private val outbox: OutboxDao,
    private val store: LedgerStore,
    /** WARN logging (android.util.Log in the app). */
    private val warn: (String, Throwable?) -> Unit,
    /** Each persisted change to the windows, summarised for the status. */
    private val onResync: (ResyncSummary) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    private val maxRows: Int = OUTBOX_MAX,
) {
    // capMutex covers the cap's eviction AND every other outbox delete, so an eviction's span and its
    // delete always see the same oldest rows. resyncMutex covers the windows. When both, always cap → resync.
    private val capMutex = Mutex()
    private val resyncMutex = Mutex()
    private var resyncState: ResyncState? = null   // guarded by resyncMutex; when set, exactly what the store holds

    /**
     * Transform the re-sync windows under their lock: loaded once (normalised by [decodeResync]), then
     * held in memory. A change is persisted FIRST and assigned only once that write succeeded, so a failed
     * write leaves memory equal to the store and the next identical change writes again. A store read
     * error throws: the mutation is abandoned with no write, so a saved state is never overwritten by an
     * empty one, and the caller retries on its next pass.
     */
    suspend fun mutateResync(transform: (ResyncState) -> ResyncState): ResyncState = resyncMutex.withLock {
        val nowMs = now()
        val cur = resyncState ?: decodeResync(store.loadResyncJson(), nowMs)
        val next = transform(cur)
        if (next != cur) store.setResyncJson(encodeResync(next))
        resyncState = next
        onResync(resyncSummary(next, nowMs))
        next
    }

    suspend fun readResync(): ResyncState = mutateResync { it }

    /**
     * Enforce the cap ([maxRows], DATA-5), never silently (DATA-19). The sample-time span of the rows
     * about to go is persisted as a re-send window BEFORE they are deleted: a kill between the two costs a
     * duplicate send, which the server dedups, never a lost re-send. The rows actually deleted are then
     * counted (best-effort: a failed count is logged, the eviction stands). A window that can't be
     * persisted (store read or write error) evicts nothing this time, and uploading carries on, which is
     * what drains the queue. Returns the depth afterwards.
     */
    suspend fun capOutbox(): Int = capMutex.withLock {
        val depth = outbox.count()
        if (depth <= maxRows) return@withLock depth
        withContext(NonCancellable) {
            val n = depth - maxRows
            val span = outbox.oldestSpan(n)
            val from = span.fromMs ?: return@withContext depth
            val to = span.toMs ?: return@withContext depth
            try {
                mutateResync { addResyncWindow(it, ResyncWindow(from, to), now()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                warn("outbox: over the cap, but the re-send could not be recorded — evicting nothing this time", e)
                return@withContext depth
            }
            val dropped = outbox.dropOldest(n)
            if (dropped > 0) {
                warn(
                    "outbox: full — evicted $dropped oldest samples; they are re-sent from local history " +
                        "while usage logging keeps it",
                    null,
                )
                try {
                    store.addOutboxEvicted(dropped.toLong())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    warn("outbox: evicted $dropped samples, but saving the eviction count failed — the count is short", e)
                }
            }
            depth - dropped
        }
    }

    /**
     * Apply one [ingestStep]'s outbox effects in the order it emitted them (a re-send is recorded before
     * the delete it accompanies), as a unit with respect to a stop: a cancellation can't land between a
     * re-send record and its delete, or between a skip's delete and its count. The delete is the commit
     * point: a failure before it deleted nothing ([EffectsResult.took] false, the step is retried), a
     * failure after it (a counter) leaves the step spent. A skip counts the rows ACTUALLY deleted, so a
     * head the cap evicted first counts nothing.
     */
    suspend fun applyEffects(effects: List<OutboxEffect>): EffectsResult = withContext(NonCancellable) {
        var deleted = false
        try {
            for (e in effects) {
                when (e) {
                    is OutboxEffect.Resync -> mutateResync { addResyncWindow(it, e.window, now()) }
                    is OutboxEffect.DeleteThrough -> {
                        capMutex.withLock { outbox.deleteUpTo(e.id) }
                        deleted = true
                    }
                    is OutboxEffect.SkipOne -> {
                        val n = capMutex.withLock { outbox.deleteOne(e.id) }
                        deleted = true
                        if (n > 0) store.addServerFaultSkips(n.toLong())
                    }
                }
            }
            EffectsResult(took = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            EffectsResult(took = deleted, failure = e)
        }
    }

    /**
     * One ingest response, decided ([ingestStep]) and applied ([applyEffects]). The step's state is
     * committed only once its effects took: a step whose outbox update failed before its delete keeps the
     * PREVIOUS state (backoff advanced), so a breaker never spends a skip that didn't happen and the batch
     * is simply retried. The step's log line is emitted only after its effects applied, so a rolled-back
     * step logs its failure, never a skip that didn't happen.
     */
    suspend fun applyStep(
        s: IngestLoopState,
        lastLog: String?,
        rows: List<OutboxEntity>,
        o: PostOutcome,
        seq: Int,
        nowElapsedMs: Long,
        nowWallMs: Long,
    ): IngestRun {
        val step = ingestStep(s, batchMeta(rows), o, nowElapsedMs, nowWallMs)
        val applied = applyEffects(step.effects)
        if (!applied.took) {
            warn("upload: could not update the outbox after seq=$seq — nothing was deleted; the batch is retried", applied.failure)
            return IngestRun(
                state = s.copy(backoffMs = nextBackoffMs(s.backoffMs)),
                lastLog = lastLog,
                delayMs = retryDelayMs(s.backoffMs, o.retryAfterMs),
                took = false,
            )
        }
        val (memory, line) = heldLog(lastLog, step.log, o.result)
        line?.let { warn(it + stepLogSuffix(seq, rows, step.effects), null) }
        applied.failure?.let { warn("upload: seq=$seq updated the outbox, but saving its skip count failed — the count is short", it) }
        return IngestRun(step.state, memory, step.delayMs, took = true)
    }
}

/**
 * [ingestStep] repeats a held reason on every held step; the loop logs each message once per change.
 * Given the last message logged ([lastLog]), this step's message ([log]) and its [result], returns the
 * memory to keep and the line to log now (null = nothing). A step without a message ends the episode
 * (a 2xx, an auth reject), except an outage blip ([PostResult.Transient]) or a fault short of a trip
 * ([PostResult.ServerFault]), which say nothing new about the hold. Event messages (a skip, a
 * narrowing) name their rows, so one event never repeats another's text.
 */
internal fun heldLog(lastLog: String?, log: String?, result: PostResult): Pair<String?, String?> = when {
    log == null -> (if (result == PostResult.Transient || result == PostResult.ServerFault) lastLog else null) to null
    log == lastLog -> lastLog to null
    else -> log to log
}

/**
 * What the loop appends to an [ingestStep] log line: the batch's seq, and for a server-fault skip the
 * skipped row's payload size too (DATA-22: its id is already in the line). Identity and size only,
 * never the payload.
 */
internal fun stepLogSuffix(seq: Int, rows: List<OutboxEntity>, effects: List<OutboxEffect>): String {
    val skipped = effects.firstNotNullOfOrNull { e -> (e as? OutboxEffect.SkipOne)?.let { s -> rows.firstOrNull { it.id == s.id } } }
    return if (skipped != null) " (seq=$seq, ${skipped.payload.toByteArray().size} bytes)" else " (seq=$seq)"
}
