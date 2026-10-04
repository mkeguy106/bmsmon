package dev.joely.bmsmon.cloud

import dev.joely.bmsmon.data.SAMPLE_RETENTION_DAYS
import dev.joely.bmsmon.data.cutoffMs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * Re-sync from local history (DATA-19). A sample that leaves the outbox without reaching the cloud —
 * evicted when the queue is full, in a batch the server permanently rejected, or a row the server
 * kept crashing on — still sits in the phone's Room `samples` table for SAMPLE_RETENTION_DAYS. A
 * window is a span of those samples to send again, rebuilt from Room with GPS (ResyncSend.kt). The
 * server dedups on its primary key, so re-sending a sample it already has costs bandwidth, never a
 * duplicate row. The one-shot history import is just the first window ([importWindow]).
 */

/** Most windows kept; past this the two closest merge — coarser, never dropped. */
internal const val RESYNC_MAX_WINDOWS = 32

/** Windows closer than this merge on insert (evictions arrive as many small adjacent spans). */
internal const val RESYNC_MERGE_GAP_MS = 60_000L

/** How long a rejected batch or a faulting row waits before its re-send is tried. */
internal const val RESYNC_PARK_MS = 6 * 60 * 60_000L

/**
 * Samples with [fromMs] <= tsMs <= [toMs] still to send. ([afterTs], [afterId]) is the keyset cursor
 * in (tsMs, id) order: everything at or before it was sent; null = nothing yet. A window whose
 * [notBeforeMs] is after "now" waits ("parked", see [isParked]).
 */
@Serializable
internal data class ResyncWindow(
    val fromMs: Long,
    val toMs: Long,
    val notBeforeMs: Long = 0L,
    val afterTs: Long? = null,
    val afterId: Long = -1L,
) {
    val cursorTs: Long get() = afterTs ?: fromMs

    /** Parked iff its wait is still in the future; an elapsed park is ready like any other window. */
    fun isParked(nowMs: Long): Boolean = notBeforeMs > nowMs
}

@Serializable
internal data class ResyncState(val windows: List<ResyncWindow> = emptyList())

/** What the Cloud sync page shows: windows ready, windows parked, and where the next one resumes. */
data class ResyncSummary(val pending: Int = 0, val parked: Int = 0, val fromMs: Long? = null)

private val codec = Json { ignoreUnknownKeys = true }

internal fun encodeResync(s: ResyncState): String = codec.encodeToString(ResyncState.serializer(), s)

/**
 * A missing or unreadable blob is an empty state, never a crash — the samples are still in Room. A
 * readable one is normalised: malformed windows (reversed or negative) are dropped and the rest are
 * folded in through [addResyncWindow], so the result is sorted, merged and capped.
 */
internal fun decodeResync(json: String?, nowMs: Long): ResyncState {
    val raw = json?.let { runCatching { codec.decodeFromString(ResyncState.serializer(), it) }.getOrNull() }
        ?: return ResyncState()
    return runCatching {
        raw.windows
            .filter { it.fromMs >= 0 && it.toMs >= it.fromMs && it.notBeforeMs >= 0 && it.afterId >= -1 }
            .fold(ResyncState()) { acc, w -> addResyncWindow(acc, w, nowMs) }
    }.getOrDefault(ResyncState())
}

/** The one-shot history import as a window: everything in local history up to now. */
internal fun importWindow(nowMs: Long) = ResyncWindow(fromMs = 0L, toMs = nowMs)

/**
 * Queue the history import ([importWindow]) unless it is already queued. A window starting at 0 is the
 * import, whole, partly sent, or merged with another (no sample is dated 1970). Adding it again would
 * restart it from 0, because a union resumes at the earlier cursor, so a repeat (after the "import
 * queued" flag failed to save, say) is a no-op.
 */
internal fun queueImportWindow(s: ResyncState, nowMs: Long): ResyncState =
    if (s.windows.any { it.fromMs == 0L }) s else addResyncWindow(s, importWindow(nowMs), nowMs)

private fun earlierCursor(a: ResyncWindow, b: ResyncWindow): Pair<Long, Long> {
    val ca = a.cursorTs to a.afterId
    val cb = b.cursorTs to b.afterId
    return if (ca.first < cb.first || (ca.first == cb.first && ca.second <= cb.second)) ca else cb
}

private fun union(a: ResyncWindow, b: ResyncWindow): ResyncWindow {
    val from = minOf(a.fromMs, b.fromMs)
    val (ts, id) = earlierCursor(a, b)
    return ResyncWindow(
        fromMs = from,
        toMs = maxOf(a.toMs, b.toMs),
        notBeforeMs = maxOf(a.notBeforeMs, b.notBeforeMs),
        afterTs = if (ts == from && id == -1L) null else ts,
        afterId = id,
    )
}

private fun touches(a: ResyncWindow, b: ResyncWindow): Boolean =
    a.fromMs <= b.toMs + RESYNC_MERGE_GAP_MS && b.fromMs <= a.toMs + RESYNC_MERGE_GAP_MS

/** Drop windows whose samples have aged out of Room — nothing is left to send. */
internal fun pruneExpired(windows: List<ResyncWindow>, nowMs: Long): List<ResyncWindow> {
    val cutoff = cutoffMs(nowMs, SAMPLE_RETENTION_DAYS)
    return windows.filter { it.toMs >= cutoff }
}

/**
 * Add [w], merged with every window of the same kind (parked or ready) it overlaps or nearly
 * touches. A union resumes at the EARLIER cursor: re-sending a little is free, skipping is not. Past
 * [maxWindows] the two closest windows merge.
 */
internal fun addResyncWindow(
    s: ResyncState,
    w: ResyncWindow,
    nowMs: Long,
    maxWindows: Int = RESYNC_MAX_WINDOWS,
): ResyncState {
    var cur = if (w.toMs >= w.fromMs) w else w.copy(fromMs = w.toMs, toMs = w.fromMs, afterTs = null, afterId = -1L)
    val list = pruneExpired(s.windows, nowMs).toMutableList()
    while (true) {
        val i = list.indexOfFirst { it.isParked(nowMs) == cur.isParked(nowMs) && touches(it, cur) }
        if (i < 0) break
        cur = union(list.removeAt(i), cur)
    }
    list += cur
    list.sortWith(compareBy<ResyncWindow>({ it.fromMs }, { it.toMs }))
    while (list.size > maxWindows) {
        // Closest adjacent pair of the same kind first, so a parked row nested in a ready window
        // can't park that window; only with no such pair does any pair merge (max(notBefore) delays).
        val pairs = 0 until list.size - 1
        val sameKind = pairs.filter { list[it].isParked(nowMs) == list[it + 1].isParked(nowMs) }
        val i = (sameKind.ifEmpty { pairs.toList() }).minByOrNull { list[it + 1].fromMs - list[it].toMs } ?: break
        list[i] = union(list[i], list[i + 1])
        list.removeAt(i + 1)
    }
    return ResyncState(list)
}

/** The first window that may be sent now (windows are kept sorted by fromMs). */
internal fun nextEligibleWindow(s: ResyncState, nowMs: Long): ResyncWindow? =
    pruneExpired(s.windows, nowMs).firstOrNull { !it.isParked(nowMs) }

/**
 * Move [expected]'s cursor past a page that ended at ([afterTs], [afterId]); [exhausted] (a short
 * page) completes it. Compare-and-set on the whole window: if it changed underneath (a merge), this
 * is a no-op and the loop re-reads — at worst a page is re-sent, which the server dedups.
 */
internal fun advanceResync(
    s: ResyncState,
    expected: ResyncWindow,
    afterTs: Long,
    afterId: Long,
    exhausted: Boolean,
): ResyncState {
    val i = s.windows.indexOf(expected)
    if (i < 0) return s
    val list = s.windows.toMutableList()
    if (exhausted || afterTs > expected.toMs) list.removeAt(i)
    else list[i] = expected.copy(afterTs = afterTs, afterId = afterId)
    return ResyncState(list)
}

/** [expected] has nothing left in Room (an empty page): drop it. Same compare-and-set rule. */
internal fun completeResync(s: ResyncState, expected: ResyncWindow): ResyncState =
    if (expected in s.windows) ResyncState(s.windows - expected) else s

/**
 * The server keeps crashing on one sample at ([tsMs], [id]): step [expected] past it and park that
 * sample in its own window, retried after [RESYNC_PARK_MS] until Room ages it out. Compare-and-set like
 * [advanceResync]: if [expected] changed underneath (a merge during the POST), nothing is parked.
 */
internal fun parkResyncRow(s: ResyncState, expected: ResyncWindow, tsMs: Long, id: Long, nowMs: Long): ResyncState {
    if (expected !in s.windows) return s
    // A window that is itself a parked single row is replaced, not advanced past itself.
    val wasParkedRow = expected.notBeforeMs > 0L && expected.fromMs == tsMs && expected.toMs == tsMs
    val advanced = if (wasParkedRow) completeResync(s, expected) else advanceResync(s, expected, tsMs, id, exhausted = false)
    return addResyncWindow(advanced, ResyncWindow(tsMs, tsMs, notBeforeMs = nowMs + RESYNC_PARK_MS), nowMs)
}

/**
 * The server permanently rejected a re-sent page ([firstTsMs]..[lastTsMs], ending at [lastId]): step
 * [expected] past it ([exhausted]: it was the window's last page) and park the page's span for a retry
 * after [RESYNC_PARK_MS], until Room ages it out. The re-send's version of the ingest stream's poison
 * skip, which parks its batch the same way: a rejected sample is retried later, never abandoned.
 * Compare-and-set like [advanceResync]: if [expected] changed underneath, nothing is parked.
 */
internal fun parkResyncPage(
    s: ResyncState,
    expected: ResyncWindow,
    firstTsMs: Long,
    lastTsMs: Long,
    lastId: Long,
    exhausted: Boolean,
    nowMs: Long,
): ResyncState {
    if (expected !in s.windows) return s
    val advanced = advanceResync(s, expected, lastTsMs, lastId, exhausted)
    return addResyncWindow(advanced, ResyncWindow(firstTsMs, lastTsMs, notBeforeMs = nowMs + RESYNC_PARK_MS), nowMs)
}

internal fun resyncSummary(s: ResyncState, nowMs: Long): ResyncSummary {
    val live = pruneExpired(s.windows, nowMs)
    val ready = live.filter { !it.isParked(nowMs) }
    return ResyncSummary(pending = ready.size, parked = live.size - ready.size, fromMs = ready.firstOrNull()?.cursorTs)
}
