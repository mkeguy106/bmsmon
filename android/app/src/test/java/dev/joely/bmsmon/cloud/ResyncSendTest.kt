package dev.joely.bmsmon.cloud

import dev.joely.bmsmon.data.db.SampleEntity
import dev.joely.bmsmon.model.DEFAULT_ROSTER
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val NOW = 1_700_000_000_000L

class ResyncSendTest {
    private val a = "C8:47:80:15:67:44"   // "2012 · A" in DEFAULT_ROSTER
    private val b = "C8:47:80:15:62:1B"   // "2012 · B"

    private fun row(
        id: Long, address: String, ts: Long,
        lat: Double? = null, lon: Double? = null, acc: Float? = null, link: String? = null,
    ) = SampleEntity(
        id = id, address = address, tsMs = ts, sessionId = 1,
        state = if (link == null) "Discharging" else null, soc = if (link == null) 60f else null,
        currentA = if (link == null) -3f else null, powerW = if (link == null) 39f else null,
        voltageV = if (link == null) 13f else null, tempC = null, mosfetTempC = null, soh = null,
        fullChargeAh = null, remainingAh = null, cycles = null, cellMinV = null, cellMaxV = null,
        regen = false, lat = lat, lon = lon, gpsAccuracyM = acc, linkEvent = link,
    )

    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    // --- payloads: the import's mapping, now with GPS, sent once per fix ---

    @Test fun aRepeatedCachedFixIsSentOncePerPack() {
        val page = listOf(
            row(1, a, 100, 43.05, -87.9, 5f),
            row(2, b, 100, 43.05, -87.9, 5f),      // the other pack: its own first fix
            row(3, a, 1_600, 43.05, -87.9, 5f),    // the same cached fix: omitted
            row(4, a, 3_100, 43.0501, -87.9, 4f),  // a new fix: sent
        )
        val (rows, sent) = resyncPayloads(page, DEFAULT_ROSTER, emptyMap())
        assertEquals(listOf(true, true, false, true), rows.map { "lat" in obj(it) })
        assertEquals(GpsKey(43.0501, -87.9, 4f), sent[a])
    }

    @Test fun theDedupCarriesAcrossPages() {
        val (_, sent) = resyncPayloads(listOf(row(1, a, 100, 43.05, -87.9, 5f)), DEFAULT_ROSTER, emptyMap())
        val (rows, _) = resyncPayloads(listOf(row(2, a, 1_600, 43.05, -87.9, 5f)), DEFAULT_ROSTER, sent)
        assertFalse("lat" in obj(rows.single()))
    }

    // The live path's rounding (CloudJson.roundCoord, 6 dp; accuracy to 0.1 m), not a second one.
    @Test fun aSentFixIsRoundedLikeTheLivePath() {
        val (rows, _) = resyncPayloads(listOf(row(1, a, 100, 43.123456789, -87.987654321, 4.56f)), DEFAULT_ROSTER, emptyMap())
        val o = obj(rows.single())
        assertEquals("43.123457", o["lat"]!!.jsonPrimitive.content)
        assertEquals("-87.987654", o["lon"]!!.jsonPrimitive.content)
        assertEquals("4.6", o["gps_accuracy_m"]!!.jsonPrimitive.content)
    }

    // With "Send GPS location" off, the live path sends no coordinates, so neither does a re-send.
    @Test fun noFixIsSentWhileGpsSendingIsOff() {
        val (rows, sent) = resyncPayloads(listOf(row(1, a, 100, 43.05, -87.9, 5f)), DEFAULT_ROSTER, emptyMap(), withGps = false)
        val o = obj(rows.single())
        assertFalse("lat" in o || "lon" in o || "gps_accuracy_m" in o)
        assertTrue(sent.isEmpty())
    }

    @Test fun rowsCarryTheRosterIdentityAndLinkEvents() {
        val (rows, _) = resyncPayloads(listOf(row(1, a, 100), row(2, a, 200, link = "Disconnected")), DEFAULT_ROSTER, emptyMap())
        val first = obj(rows[0])
        assertEquals("2012 · A", first["alias"]!!.jsonPrimitive.content)
        assertEquals("2012", first["group_id"]!!.jsonPrimitive.content)
        assertEquals("Disconnected", obj(rows[1])["link_event"]!!.jsonPrimitive.content)
    }

    // --- the step: the ingest stream's breakers, over stream positions ---

    private val page = PageMeta(headPos = 0, size = 500, firstTsMs = 1_000, firstId = 7, lastTsMs = 9_000, lastId = 900)
    private fun out(r: PostResult, retryAfterMs: Long? = null) = PostOutcome(r, fromApi = true, retryAfterMs = retryAfterMs)

    @Test fun anAcceptedPageAdvancesPastItsLastRow() {
        val s = resyncStep(ResyncStepState(), page, out(PostResult.Ok), 0L)
        assertEquals(ResyncAction.Advance(9_000, 900), s.action)
        assertEquals(RESYNC_PACE_MS, s.delayMs)
    }

    // Like the ingest stream's poison skip: the first reject since a 2xx is parked for a later
    // retry (never abandoned); another before any 2xx is held.
    @Test fun aRejectedPageIsParkedOnceThenHeld() {
        val first = resyncStep(ResyncStepState(), page, out(PostResult.Poison), 0L)
        assertEquals(ResyncAction.ParkPage(1_000, 9_000, 900), first.action)
        val second = resyncStep(first.state, page.copy(headPos = 500), out(PostResult.Poison), 1_000L)
        assertEquals(ResyncAction.Hold, second.action)
        val accepted = resyncStep(second.state, page.copy(headPos = 500), out(PostResult.Ok), 2_000L)
        val third = resyncStep(accepted.state, page.copy(headPos = 1_000), out(PostResult.Poison), 3_000L)
        assertEquals(ResyncAction.ParkPage(1_000, 9_000, 900), third.action)   // a 2xx re-armed it
    }

    // A marked 2xx on ANY stream (the live upload's, every ~15 s) proves the server accepts data: both
    // one-park budgets re-arm, so a re-send reject parks again instead of being held.
    @Test fun aMarked2xxOnAnyStreamReArmsBothParks() {
        val first = resyncStep(ResyncStepState(), page, out(PostResult.Poison), 0L, serverOks = 0L)
        assertEquals(ResyncAction.ParkPage(1_000, 9_000, 900), first.action)
        val next = page.copy(headPos = 500)
        assertEquals(ResyncAction.Hold, resyncStep(first.state, next, out(PostResult.Poison), 1L, serverOks = 0L).action)
        assertEquals(ResyncAction.ParkPage(1_000, 9_000, 900), resyncStep(first.state, next, out(PostResult.Poison), 1L, serverOks = 1L).action)

        // A row already parked since the last 2xx, its streak about to trip at one row.
        val one = PageMeta(headPos = 0, size = 1, firstTsMs = 5_000, firstId = 50, lastTsMs = 5_000, lastId = 50)
        val spent = ResyncStepState(
            fault = HeadFaultState(headId = 0L, limit = 1, streak = FAULT_STREAK - 1, firstFaultAtMs = 0L, skipsSinceOk = 1),
            serverOks = 7L,
        )
        assertEquals(ResyncAction.Hold, resyncStep(spent, one, out(PostResult.ServerFault), FAULT_MIN_SPAN_MS, serverOks = 7L).action)
        val rearmed = resyncStep(spent, one, out(PostResult.ServerFault), FAULT_MIN_SPAN_MS, serverOks = 8L)
        assertEquals(ResyncAction.ParkRow(5_000, 50), rearmed.action)
        assertEquals(8L, rearmed.state.serverOks)
    }

    @Test fun aMarked503WaitsForItsRetryAfter() {
        val s = resyncStep(ResyncStepState(), page, out(PostResult.Transient, retryAfterMs = 30_000), 0L)
        assertEquals(ResyncAction.Hold, s.action)
        assertEquals(30_000L, s.delayMs)
    }

    @Test fun everyOtherAnswerHoldsAndBacksOff() {
        val unmarked2xx = PostOutcome(PostResult.Transient, code = 200, fromApi = false)
        for (o in listOf(unmarked2xx, out(PostResult.AuthFailed), out(PostResult.KeyMissing), out(PostResult.ServerFault))) {
            var s = ResyncStepState()
            val delays = (1..7).map {
                val step = resyncStep(s, page, o, it * 1_000L)
                assertEquals("$o", ResyncAction.Hold, step.action)
                s = step.state
                step.delayMs
            }
            assertEquals("$o", listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L), delays)
        }
    }

    // The import used to retry a page the server crashes on forever, holding every later page.
    @Test fun aRowTheServerKeepsCrashingOnIsBisectedOutAndParked() {
        val bad = 123L   // stream position of the faulting row
        var s = ResyncStepState()
        var head = 0L
        var t = 0L
        var parked: ResyncAction.ParkRow? = null
        var posts = 0
        while (parked == null && posts++ < 10_000) {
            val size = minOf(RESYNC_PAGE, s.fault.limit)
            val meta = PageMeta(
                headPos = head, size = size,
                firstTsMs = 1_000 + head, firstId = 10_000 + head,
                lastTsMs = 1_000 + head + size - 1, lastId = 10_000 + head + size - 1,
            )
            val r = if (bad in head until head + size) PostResult.ServerFault else PostResult.Ok
            val step = resyncStep(s, meta, out(r), t)
            when (val action = step.action) {
                is ResyncAction.Advance -> head += size
                is ResyncAction.ParkRow -> parked = action
                is ResyncAction.ParkPage, ResyncAction.Hold -> {}
            }
            s = step.state
            t += 60_000L
        }
        assertEquals(ResyncAction.ParkRow(1_000 + bad, 10_000 + bad), parked)
    }

    // Fresh telemetry always goes first.
    @Test fun reSyncWaitsForTheLiveQueueAndTheNetwork() {
        assertFalse(shouldResync(outboxDepth = MIN_BATCH, online = true, hasDueWindow = true))
        assertTrue(shouldResync(outboxDepth = MIN_BATCH - 1, online = true, hasDueWindow = true))
        assertFalse(shouldResync(outboxDepth = 0, online = false, hasDueWindow = true))
        assertFalse(shouldResync(outboxDepth = 0, online = true, hasDueWindow = false))
    }

    // --- the sender: one page per pass, against an in-memory store, Room and server ---

    /** Room rows at distinct times, alternating packs; ids follow time. */
    private fun history(n: Int, from: Long = NOW - 1_000_000L) =
        (1..n).map { i -> row(i.toLong(), if (i % 2 == 0) a else b, from + i) }

    private inner class Harness(rows: List<SampleEntity>, vararg windows: ResyncWindow) {
        var windows = ResyncState(windows.toList())
        var readFailures = 0
        var writeFailures = 0
        var elapsed = 0L
        var wall = NOW
        /** The shared observer's marked-2xx count: every Ok this sender gets, plus any a test adds for other streams. */
        var serverOks = 0L
        val logs = mutableListOf<String>()
        /** Each POST: its batch_seq and the ts_ms of every row it carried. */
        val posts = mutableListOf<Pair<Int, List<Long>>>()
        private val room = rows.sortedWith(compareBy({ it.tsMs }, { it.id }))

        val sender = ResyncSender(
            readWindows = {
                if (readFailures > 0) { readFailures--; throw IOException("read failed") }
                this.windows
            },
            mutateWindows = { transform ->
                if (readFailures > 0) { readFailures--; throw IOException("read failed") }
                val next = transform(this.windows)
                if (next != this.windows && writeFailures > 0) { writeFailures--; throw IOException("write failed") }
                this.windows = next
                next
            },
            readPage = { toMs, afterTs, afterId, limit ->
                room.filter { it.tsMs <= toMs && it.tsMs >= afterTs && (it.tsMs > afterTs || it.id > afterId) }.take(limit)
            },
            serverOks = { serverOks },
            warn = { m, _ -> logs += m },
            wallNow = { wall },
            elapsedNow = { elapsed },
        )

        /** Bodies of every POST, parsed (for the GPS checks). */
        val bodies = mutableListOf<kotlinx.serialization.json.JsonObject>()

        fun pass(reply: PostOutcome = ok, depth: Int = 0, online: Boolean = true): Long =
            passWith(depth = depth, online = online) { reply }

        /** One pass whose server answers by the rows' sample times. */
        fun passWith(
            depth: Int = 0,
            online: Boolean = true,
            roster: dev.joely.bmsmon.model.Roster = DEFAULT_ROSTER,
            withGps: Boolean = true,
            reply: (List<Long>) -> PostOutcome,
        ): Long = runBlocking {
            sender.pass(roster, withGps = withGps, liveDepth = depth, online = online) { body ->
                val o = Json.parseToJsonElement(String(body)).jsonObject
                bodies += o
                val ts = o["samples"]!!.jsonArray.map { it.jsonObject["ts_ms"]!!.jsonPrimitive.long }
                posts += o["batch_seq"]!!.jsonPrimitive.int to ts
                reply(ts).also { if (it.result == PostResult.Ok) serverOks++ }
            }.also { elapsed += it }
        }

        /** Passes until the window starting at [fromMs] is parked until [wall] + 6 h; false if [maxPasses] go by first. */
        fun untilParked(fromMs: Long, maxPasses: Int = 100, reply: (List<Long>) -> PostOutcome): Boolean {
            repeat(maxPasses) {
                passWith(reply = reply)
                if (windows.windows.any { it.fromMs == fromMs && it.notBeforeMs == wall + RESYNC_PARK_MS }) return true
            }
            return false
        }
    }

    private val ok = PostOutcome(PostResult.Ok, code = 200, fromApi = true)
    private val poison = PostOutcome(PostResult.Poison, code = 422, fromApi = true)
    private val crash = PostOutcome(PostResult.ServerFault, code = 500, fromApi = true)

    @Test fun aWindowIsSentPageByPageAsStoredOnlyBatchesThenDone() {
        val rows = history(1_250)
        val h = Harness(rows, ResyncWindow(rows.first().tsMs, rows.last().tsMs))
        assertEquals(listOf(RESYNC_PACE_MS, RESYNC_PACE_MS, RESYNC_PACE_MS), (1..3).map { h.pass() })
        assertEquals(listOf(500, 500, 250), h.posts.map { it.second.size })
        assertTrue(h.posts.all { it.first == -1 })                          // stored, never pushed live
        assertEquals(rows.map { it.tsMs }, h.posts.flatMap { it.second })   // every row once, in time order
        assertEquals(emptyList<ResyncWindow>(), h.windows.windows)          // the short page completed it
        assertEquals(RESYNC_IDLE_MS, h.pass())
        assertEquals(3, h.posts.size)
    }

    @Test fun anEmptyWindowIsCompletedWithoutAPost() {
        val h = Harness(history(3), ResyncWindow(NOW - 10, NOW - 5))
        assertEquals(0L, h.pass())
        assertTrue(h.windows.windows.isEmpty())
        assertTrue(h.posts.isEmpty())
    }

    @Test fun itYieldsToTheLiveQueueAndWaitsOffline() {
        val rows = history(10)
        val h = Harness(rows, ResyncWindow(rows.first().tsMs, rows.last().tsMs))
        assertEquals(RESYNC_IDLE_MS, h.pass(depth = MIN_BATCH))
        assertEquals(RESYNC_IDLE_MS, h.pass(online = false))
        assertTrue(h.posts.isEmpty())
        h.pass(depth = MIN_BATCH - 1)
        assertEquals(1, h.posts.size)
    }

    @Test fun aWindowWaitsWhileParked() {
        val rows = history(10)
        val h = Harness(rows, ResyncWindow(rows.first().tsMs, rows.last().tsMs, notBeforeMs = NOW + 1))
        assertEquals(RESYNC_IDLE_MS, h.pass())
        assertTrue(h.posts.isEmpty())
    }

    // A failing store pauses the re-sender, never crashes its loop, and logs at most once a minute.
    @Test fun aStoreThatCannotBeReadPausesTheReSenderAndLogsOncePerMinute() {
        val rows = history(10)
        val h = Harness(rows, ResyncWindow(rows.first().tsMs, rows.last().tsMs))
        h.readFailures = Int.MAX_VALUE
        repeat(30) { assertEquals(RESYNC_IDLE_MS, h.pass()) }            // 150 s of passes
        assertTrue(h.posts.isEmpty())
        assertEquals(3, h.logs.size)                                       // at 0 s, 60 s and 120 s
        h.readFailures = 0
        h.pass()
        assertEquals(rows.map { it.tsMs }, h.posts.single().second)       // and it resumes where it was
    }

    // The window is the only record of what was sent: a page whose advance didn't persist is sent
    // again (the server dedups), never skipped.
    @Test fun aPageWhoseAdvanceIsNotSavedIsSentAgain() {
        val rows = history(900)
        val h = Harness(rows, ResyncWindow(rows.first().tsMs, rows.last().tsMs))
        h.writeFailures = 1
        assertEquals(INITIAL_BACKOFF_MS, h.pass())
        h.pass()
        h.pass()
        val pages = h.posts.map { it.second }
        assertEquals(pages[0], pages[1])
        assertEquals(rows.drop(500).map { it.tsMs }, pages[2])
        assertTrue(h.windows.windows.isEmpty())
    }

    // The step's state is kept only once its window change took: a park that failed to save must
    // not spend the breaker's one park, or the same page would be held instead of parked.
    @Test fun aParkThatIsNotSavedKeepsItsBreakerArmed() {
        val rows = history(1_000)
        val h = Harness(rows, ResyncWindow(rows.first().tsMs, rows.last().tsMs))
        h.writeFailures = 1
        h.pass(poison)
        h.pass(poison)
        assertTrue(h.windows.windows.any { it.isParked(NOW) })
        val ready = h.windows.windows.single { !it.isParked(NOW) }
        assertEquals(rows[499].tsMs to rows[499].id, ready.cursorTs to ready.afterId)
    }

    @Test fun aRejectedPageIsParkedForALaterRetryAndTheWindowMovesOn() {
        val rows = history(1_000)
        val h = Harness(rows, ResyncWindow(rows.first().tsMs, rows.last().tsMs))
        h.pass(poison)
        val parked = h.windows.windows.single { it.isParked(NOW) }
        assertEquals(ResyncWindow(rows[0].tsMs, rows[499].tsMs, notBeforeMs = NOW + RESYNC_PARK_MS), parked)
        h.pass()
        assertEquals(rows.drop(500).map { it.tsMs }, h.posts[1].second)
        assertTrue(h.logs.single().startsWith("re-sync: server permanently rejected 500 local samples"))
    }

    @Test fun aHeldPageKeepsItsCursorAndIsSentAgain() {
        val rows = history(10)
        val w = ResyncWindow(rows.first().tsMs, rows.last().tsMs)
        val h = Harness(rows, w)
        assertEquals(30_000L, h.pass(PostOutcome(PostResult.Transient, code = 503, fromApi = true, retryAfterMs = 30_000)))
        assertEquals(listOf(w), h.windows.windows)
        h.pass()
        assertEquals(h.posts[0].second, h.posts[1].second)
        assertTrue(h.windows.windows.isEmpty())
    }

    // A window merged underneath the walk (an eviction or a skip landing in it) resumes at the
    // earlier cursor: a little is sent twice, nothing is skipped.
    @Test fun aWindowChangedUnderneathIsWalkedFromItsMergedCursor() {
        val rows = history(1_000)
        val h = Harness(rows, ResyncWindow(rows.first().tsMs, rows.last().tsMs))
        h.pass()
        h.windows = addResyncWindow(h.windows, ResyncWindow(rows[100].tsMs, rows[200].tsMs), NOW)
        h.pass()
        h.pass()
        assertEquals(rows.drop(100).take(500).map { it.tsMs }, h.posts[1].second)
        assertEquals(rows.drop(600).map { it.tsMs }, h.posts[2].second)
        assertTrue(h.windows.windows.isEmpty())
    }

    @Test fun theImportIsJustAWindowOverAllLocalHistory() {
        val rows = history(20, from = NOW - 3 * 86_400_000L)
        val h = Harness(rows, importWindow(NOW))
        h.pass()
        assertEquals(rows.map { it.tsMs }, h.posts.single().second)
        assertTrue(h.windows.windows.isEmpty())
    }

    // --- the one-park budgets across retries (DATA-22): an isolated bad row must never block later windows ---

    // The ingest stream isolated a row the server crashes on and parked it for 6 h. Each retry trips and
    // parks again while other streams keep landing 2xxs, and a later window is sent while it waits.
    @Test fun aBadRowRetriedEverySixHoursParksAgainWhileAnotherStreamIsAccepted() {
        val rows = history(10)
        val bad = rows[4].tsMs
        val h = Harness(rows, ResyncWindow(bad, bad, notBeforeMs = NOW - 1))   // its park has just elapsed
        val server = { ts: List<Long> -> if (bad in ts) crash else ok }
        assertTrue("1st try parks", h.untilParked(bad, reply = server))
        for (retry in 2..3) {
            h.serverOks++                                   // the live upload landed a 2xx meanwhile
            h.wall += RESYNC_PARK_MS
            assertTrue("retry $retry parks again", h.untilParked(bad, reply = server))
        }
        h.posts.clear()
        h.windows = addResyncWindow(h.windows, ResyncWindow(rows[6].tsMs, rows[9].tsMs), h.wall)
        h.passWith(reply = server)
        assertEquals(rows.drop(6).map { it.tsMs }, h.posts.single().second)   // not held behind the bad row
        assertTrue(h.windows.windows.single().isParked(h.wall))
    }

    // With no marked 2xx on any stream since the last park, the server is failing on everything: hold.
    @Test fun withNo2xxOnAnyStreamASecondRetryOfABadRowHolds() {
        val rows = history(10)
        val bad = rows[4].tsMs
        val h = Harness(rows, ResyncWindow(bad, bad, notBeforeMs = NOW - 1))
        val server = { ts: List<Long> -> if (bad in ts) crash else ok }
        assertTrue(h.untilParked(bad, reply = server))
        h.wall += RESYNC_PARK_MS
        assertFalse(h.untilParked(bad, maxPasses = 40, reply = server))
        assertFalse(h.windows.windows.single().isParked(h.wall))
        assertTrue(h.logs.any { "fault breaker open" in it })
    }

    // The same for a span the server rejects (Poison): parked again while other streams are accepted, held
    // when nothing is.
    @Test fun aRejectedSpanRetriedLaterParksAgainOnlyWhileAnotherStreamIsAccepted() {
        val rows = history(10)
        val from = rows[0].tsMs
        val h = Harness(rows, ResyncWindow(from, rows[4].tsMs, notBeforeMs = NOW - 1))
        h.pass(poison)
        assertEquals(listOf(ResyncWindow(from, rows[4].tsMs, notBeforeMs = h.wall + RESYNC_PARK_MS)), h.windows.windows)
        h.serverOks++
        h.wall += RESYNC_PARK_MS
        h.pass(poison)
        assertEquals(listOf(ResyncWindow(from, rows[4].tsMs, notBeforeMs = h.wall + RESYNC_PARK_MS)), h.windows.windows)
        h.wall += RESYNC_PARK_MS                            // no 2xx anywhere since that park
        val held = h.windows
        h.pass(poison)
        assertEquals(held, h.windows)
        assertTrue(h.logs.last().contains("poison breaker open"))
    }

    // A park is compare-and-set: if the window changed during the POST (a merge), nothing is parked and
    // nothing is spent; the page is sent again from the merged window, and that reject parks.
    @Test fun aParkWhoseWindowChangedDuringThePostParksNothingAndSpendsNothing() {
        val rows = history(1_000)
        val h = Harness(rows, ResyncWindow(rows.first().tsMs, rows.last().tsMs))
        h.passWith { _ ->
            h.windows = addResyncWindow(h.windows, ResyncWindow(rows.last().tsMs + 10, rows.last().tsMs + 20), h.wall)
            poison
        }
        assertTrue(h.windows.windows.none { it.isParked(h.wall) })
        h.pass(poison)
        assertEquals(h.posts[0].second, h.posts[1].second)
        assertEquals(ResyncWindow(rows[0].tsMs, rows[499].tsMs, notBeforeMs = h.wall + RESYNC_PARK_MS), h.windows.windows.single { it.isParked(h.wall) })
    }

    // --- the reporter's wiring, through the pure inputs it hands the sender ---

    private fun settings(
        cloudEnabled: Boolean = true,
        enrolled: Boolean = true,
        deviceId: String? = "dev-1",
        apiBaseUrl: String? = "https://bms.example",
        gpsEnabled: Boolean? = null,
        importDone: Boolean = true,
        roster: dev.joely.bmsmon.model.Roster? = null,
    ) = dev.joely.bmsmon.data.Persisted(
        accentArgb = null, powerArgb = null, manualMode = false, darkMode = false,
        dailyDriverId = null, lastStage = null, dynamicStage = null, stageHoldMinutes = null,
        monitoring = true, logging = true, alertsOn = true, enabledThresholds = null,
        criticalThreshold = null, seizeLowToStage = true, keepScreenOn = true, sortKey = null,
        filters = null, filterBaseId = null, lastTelemetry = emptyMap(), tempFahrenheit = true,
        roster = roster, appearance = null, autoLuxThreshold = null, locked = false, csvImported = false,
        lockShowTime = true, lockShowWifi = true, lockShowBattery = true, lockLowRefresh = true,
        lockDimScreen = false, lockDimLevel = 0.3f, gpsPauseParked = true, disabledAddrs = null,
        cloudEnabled = cloudEnabled, apiBaseUrl = apiBaseUrl, deviceId = deviceId, enrolled = enrolled,
        gpsEnabled = gpsEnabled, importWatermark = 0L, importDone = importDone,
        tempThresholdsByProfile = emptyMap(), tempAlertsEnabled = true, showTempGauge = true,
        tempGaugeSide = null, cloudSyncAlerts = true, pendingTempConfig = null,
    )

    private fun Harness.passFrom(inputs: ResyncPassInputs): Long =
        passWith(depth = inputs.liveDepth, roster = inputs.roster, withGps = inputs.withGps) { ok }

    @Test fun nothingIsReSentUntilTheLiveBacklogIsMeasuredAndBelowMinBatch() {
        val rows = history(10)
        val h = Harness(rows, ResyncWindow(rows.first().tsMs, rows.last().tsMs))
        h.passFrom(resyncPassInputs(settings(), depthKnown = false, publishedDepth = 0)!!)   // not measured yet
        h.passFrom(resyncPassInputs(settings(), depthKnown = true, publishedDepth = MIN_BATCH)!!)
        assertTrue(h.posts.isEmpty())
        h.passFrom(resyncPassInputs(settings(), depthKnown = true, publishedDepth = MIN_BATCH - 1)!!)
        assertEquals(1, h.posts.size)
    }

    @Test fun reSentGpsFollowsTheSendGpsLocationSetting() {
        val rows = listOf(row(1, a, NOW - 5_000, 43.05, -87.9, 5f))
        fun latSent(gpsEnabled: Boolean?): Boolean {
            val h = Harness(rows, ResyncWindow(rows[0].tsMs, rows[0].tsMs))
            h.passFrom(resyncPassInputs(settings(gpsEnabled = gpsEnabled), depthKnown = true, publishedDepth = 0)!!)
            return "lat" in h.bodies.single()["samples"]!!.jsonArray.single().jsonObject
        }
        assertFalse(latSent(gpsEnabled = false))
        assertTrue(latSent(gpsEnabled = true))
        assertTrue(latSent(gpsEnabled = null))              // unset = on with cloud sync, as everywhere else
    }

    @Test fun theReSyncIdlesUnlessUploadingAndCarriesTheRosterAndImportFlag() {
        assertEquals(null, resyncPassInputs(null, true, 0))
        assertEquals(null, resyncPassInputs(settings(cloudEnabled = false), true, 0))
        assertEquals(null, resyncPassInputs(settings(enrolled = false), true, 0))
        assertEquals(null, resyncPassInputs(settings(deviceId = null), true, 0))
        assertEquals(null, resyncPassInputs(settings(apiBaseUrl = null), true, 0))
        val i = resyncPassInputs(settings(importDone = false), true, 3)!!
        assertEquals(ResyncPassInputs("https://bms.example", "dev-1", DEFAULT_ROSTER, withGps = true, liveDepth = 3, importDue = true), i)
    }

    // The reporter (Android-only: Context, DataStore, Keystore, no Robolectric here) feeds the pass from these
    // exact expressions; pinned at the source so the JVM-tested pieces above are the ones it runs.
    @Test fun theReporterFeedsThePassFromTheTestedPieces() {
        val code = listOf("src/main/java", "app/src/main/java")
            .map { java.io.File(it, "dev/joely/bmsmon/cloud/TelemetryReporter.kt") }
            .first { it.isFile }
            .readLines().map { it.trim() }
        for (line in listOf(
            "val inputs = resyncPassInputs(cachedSettings, depthKnown, _status.value.outboxDepth)",
            "val wait = resync.pass(inputs.roster, inputs.withGps, inputs.liveDepth, conn.online.value) { body ->",
            "if (o.result == PostResult.Ok) serverOks.incrementAndGet()",
            "serverOks = { serverOks.get() },",
            "ledger.mutateResync { queueImportWindow(it, now) }",
            // The loop's last-resort catch logs, rate-limited, instead of stalling silently.
            "resyncLoopFailures, SystemClock.elapsedRealtime(), { msg, t -> Log.w(TAG, msg, t) },",
        )) {
            assertTrue(line, line in code)
        }
        assertEquals(listOf("depthKnown = true"), code.filter { it.startsWith("depthKnown = ") })   // set by publishQueue only
    }
}
