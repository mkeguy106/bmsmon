package dev.joely.bmsmon.cloud

import dev.joely.bmsmon.data.SAMPLE_RETENTION_DAYS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResyncWindowsTest {
    private val day = 86_400_000L
    private val now = 100 * day
    private fun add(s: ResyncState, w: ResyncWindow) = addResyncWindow(s, w, now)
    private fun state(vararg w: ResyncWindow) = ResyncState(w.toList())

    @Test fun theCodecRoundTripsAndNeverThrows() {
        val s = state(ResyncWindow(now - 5_000, now - 1_000, notBeforeMs = 7, afterTs = now - 3_000, afterId = 9))
        assertEquals(s, decodeResync(encodeResync(s), now))
        assertEquals(ResyncState(), decodeResync(null, now))
        assertEquals(ResyncState(), decodeResync("{not json", now))
    }

    @Test fun overlappingOrNearlyTouchingWindowsMergeAndDistantOnesDoNot() {
        var s = add(ResyncState(), ResyncWindow(now - 10_000, now - 8_000))
        s = add(s, ResyncWindow(now - 7_990, now - 5_000))            // 10 ms gap: merges
        assertEquals(listOf(ResyncWindow(now - 10_000, now - 5_000)), s.windows)
        s = add(s, ResyncWindow(now - 1_000_000, now - 900_000))     // far apart: separate
        assertEquals(2, s.windows.size)
        assertEquals(now - 1_000_000, s.windows.first().fromMs)      // kept sorted by fromMs
    }

    @Test fun aUnionResumesAtTheEarlierCursorSoNothingIsSkipped() {
        val started = ResyncWindow(now - 10_000, now - 1_000, afterTs = now - 4_000, afterId = 77)
        val s = add(state(started), ResyncWindow(now - 6_000, now - 5_000))   // inside the already-sent part
        assertEquals(listOf(ResyncWindow(now - 10_000, now - 1_000, afterTs = now - 6_000, afterId = -1)), s.windows)
    }

    @Test fun parkedAndReadyWindowsNeverMergeOnInsert() {
        val ready = ResyncWindow(now - 10_000, now - 1_000)
        val parked = ResyncWindow(now - 5_000, now - 5_000, notBeforeMs = now + RESYNC_PARK_MS)
        assertEquals(2, add(state(ready), parked).windows.size)
    }

    @Test fun pastTheCapTheClosestPairMergesAndNothingIsLost() {
        var s = ResyncState()
        val spans = (0 until 40).map { i -> (now - (i + 1) * 3_600_000L) to (now - (i + 1) * 3_600_000L + 1_000) }
        for ((f, t) in spans) s = add(s, ResyncWindow(f, t))
        assertEquals(RESYNC_MAX_WINDOWS, s.windows.size)
        for ((f, t) in spans) assertTrue("$f..$t covered", s.windows.any { it.fromMs <= f && it.toMs >= t })
    }

    @Test fun windowsOlderThanLocalRetentionAreDropped() {
        val expired = ResyncWindow(0L, now - (SAMPLE_RETENTION_DAYS + 1) * day)
        val s = add(state(expired), ResyncWindow(now - 1_000, now))
        assertEquals(listOf(ResyncWindow(now - 1_000, now)), s.windows)
    }

    @Test fun theNextEligibleWindowSkipsOnesStillParked() {
        val parked = ResyncWindow(now - 9_000, now - 9_000, notBeforeMs = now + 1)
        val ready = ResyncWindow(now - 5_000, now - 1_000)
        assertEquals(ready, nextEligibleWindow(state(parked, ready), now))
        assertNull(nextEligibleWindow(state(parked), now))
    }

    @Test fun advancingMovesTheCursorAndAShortPageCompletesTheWindow() {
        val w = ResyncWindow(now - 9_000, now - 1_000)
        val moved = advanceResync(state(w), w, afterTs = now - 5_000, afterId = 12, exhausted = false)
        assertEquals(listOf(w.copy(afterTs = now - 5_000, afterId = 12)), moved.windows)
        assertEquals(ResyncState(), advanceResync(state(w), w, now - 5_000, 12, exhausted = true))
    }

    @Test fun advancingAWindowThatChangedUnderneathIsANoOp() {
        val w = ResyncWindow(now - 9_000, now - 1_000)
        val merged = add(state(w), ResyncWindow(now - 20_000, now - 9_500))
        assertEquals(merged, advanceResync(merged, w, now - 5_000, 12, exhausted = false))
    }

    @Test fun aFaultingRowIsSteppedOverAndParkedInItsOwnWindow() {
        val w = ResyncWindow(now - 9_000, now - 1_000)
        val s = parkResyncRow(state(w), w, tsMs = now - 7_000, id = 40, nowMs = now)
        assertEquals(
            listOf(
                w.copy(afterTs = now - 7_000, afterId = 40),
                ResyncWindow(now - 7_000, now - 7_000, notBeforeMs = now + RESYNC_PARK_MS),
            ).sortedBy { it.fromMs },
            s.windows,
        )
    }

    // The re-send's poison skip: step past the rejected page and park its span for a later retry.
    @Test fun aRejectedPageIsSteppedOverAndItsSpanParked() {
        val w = ResyncWindow(now - 9_000, now - 1_000)
        val s = parkResyncPage(state(w), w, firstTsMs = now - 9_000, lastTsMs = now - 6_000, lastId = 42, exhausted = false, nowMs = now)
        assertEquals(
            listOf(
                ResyncWindow(now - 9_000, now - 6_000, notBeforeMs = now + RESYNC_PARK_MS),
                w.copy(afterTs = now - 6_000, afterId = 42),
            ),
            s.windows,
        )
        assertEquals(w.copy(afterTs = now - 6_000, afterId = 42), nextEligibleWindow(s, now))
        // The window's last (short) page: the window is done, only the parked span remains.
        val done = parkResyncPage(state(w), w, now - 3_000, now - 1_000, lastId = 9, exhausted = true, nowMs = now)
        assertEquals(listOf(ResyncWindow(now - 3_000, now - 1_000, notBeforeMs = now + RESYNC_PARK_MS)), done.windows)
    }

    // Parks are compare-and-set, like advances: a window that changed during the POST parks nothing.
    @Test fun parkingAWindowThatChangedUnderneathIsANoOp() {
        val w = ResyncWindow(now - 9_000, now - 1_000)
        val merged = add(state(w), ResyncWindow(now - 990, now - 500))
        assertEquals(merged, parkResyncPage(merged, w, now - 9_000, now - 6_000, 42, exhausted = false, nowMs = now))
        assertEquals(merged, parkResyncRow(merged, w, tsMs = now - 7_000, id = 40, nowMs = now))
    }

    // Queueing the import again (its flag failed to save) must not restart it from 0.
    @Test fun queueingTheImportAgainLeavesAQueuedImportWhereItIs() {
        val queued = queueImportWindow(ResyncState(), now - 60_000)
        assertEquals(listOf(importWindow(now - 60_000)), queued.windows)
        val partlySent = advanceResync(queued, queued.windows.single(), afterTs = now - 70_000, afterId = 42, exhausted = false)
        assertEquals(partlySent, queueImportWindow(partlySent, now))
        val mergedWithAnEviction = add(partlySent, ResyncWindow(now - 30_000, now - 20_000))
        assertEquals(0L, mergedWithAnEviction.windows.single().fromMs)
        assertEquals(mergedWithAnEviction, queueImportWindow(mergedWithAnEviction, now))
        // Another window doesn't count as the import: it is queued (and, overlapping, absorbs that window).
        val other = state(ResyncWindow(now - 9_000, now - 1_000))
        assertEquals(listOf(importWindow(now)), queueImportWindow(other, now).windows)
    }

    @Test fun reParkingAParkedRowRestartsItLater() {
        val p = ResyncWindow(now - 7_000, now - 7_000, notBeforeMs = now - 1)
        val s = parkResyncRow(state(p), p, tsMs = now - 7_000, id = 40, nowMs = now)
        assertEquals(listOf(ResyncWindow(now - 7_000, now - 7_000, notBeforeMs = now + RESYNC_PARK_MS)), s.windows)
    }

    @Test fun theImportWindowCoversAllLocalHistory() {
        assertEquals(ResyncWindow(0L, now), importWindow(now))
    }

    @Test fun theSummaryCountsReadyAndParkedWindows() {
        val s = state(
            ResyncWindow(now - 9_000, now - 8_000, afterTs = now - 8_500, afterId = 3),
            ResyncWindow(now - 7_000, now - 7_000, notBeforeMs = now + 1),
        )
        assertEquals(ResyncSummary(pending = 1, parked = 1, fromMs = now - 8_500), resyncSummary(s, now))
    }

    @Test fun capPressureMergesSameKindFirstSoAParkedRowCannotParkAReadyWindow() {
        val ready = ResyncWindow(now - 100_000_000, now - 1_000)
        val parked = ResyncWindow(now - 50_000_000, now - 50_000_000, notBeforeMs = now + RESYNC_PARK_MS)
        val s = addResyncWindow(state(ready, parked), ResyncWindow(now - 200_000_000, now - 190_000_000), now, maxWindows = 2)
        assertEquals(2, s.windows.size)
        assertEquals(1, s.windows.count { it.isParked(now) })
        assertEquals(now - 200_000_000, nextEligibleWindow(s, now)!!.fromMs)
        assertTrue(nextEligibleWindow(s, now)!!.toMs >= now - 1_000)
    }

    @Test fun anElapsedParkCountsAsReadyAndMergesWithReadyWindows() {
        val elapsed = ResyncWindow(now - 9_000, now - 9_000, notBeforeMs = now - 1)
        assertEquals(1, add(state(ResyncWindow(now - 10_000, now - 1_000)), elapsed).windows.size)
        assertEquals(elapsed, nextEligibleWindow(state(elapsed), now))
    }

    @Test fun decodingNormalisesAValidButMessyState() {
        val messy = ResyncState(
            listOf(
                ResyncWindow(now - 1_000, now - 500),
                ResyncWindow(now - 900_000, now - 800_000),
                ResyncWindow(now - 100, now - 200),   // reversed
                ResyncWindow(-5, 10),                // negative
            ),
        )
        val s = decodeResync(encodeResync(messy), now)
        assertEquals(listOf(ResyncWindow(now - 900_000, now - 800_000), ResyncWindow(now - 1_000, now - 500)), s.windows)
    }

    @Test fun decodingCapsAnOversizedState() {
        val many = ResyncState((0 until 50).map { ResyncWindow(now - (it + 1) * 3_600_000L, now - (it + 1) * 3_600_000L + 1_000) })
        assertEquals(RESYNC_MAX_WINDOWS, decodeResync(encodeResync(many), now).windows.size)
    }

    @Test fun theNextEligibleWindowIgnoresExpiredOnes() {
        val expired = ResyncWindow(0L, now - (SAMPLE_RETENTION_DAYS + 1) * day)
        assertNull(nextEligibleWindow(state(expired), now))
    }

    @Test fun completingDropsTheWindowButIsANoOpIfItChanged() {
        val w = ResyncWindow(now - 9_000, now - 1_000)
        assertEquals(ResyncState(), completeResync(state(w), w))
        val changed = state(w.copy(afterTs = now - 5_000, afterId = 1))
        assertEquals(changed, completeResync(changed, w))
    }

    @Test fun aPageEndingPastTheWindowCompletesItInsteadOfLeavingTheCursorBeyondTheEnd() {
        val w = ResyncWindow(now - 9_000, now - 1_000)
        assertEquals(ResyncState(), advanceResync(state(w), w, afterTs = now - 500, afterId = 3, exhausted = false))
    }
}
