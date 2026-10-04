package dev.joely.bmsmon

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source guard for the BLE attempt model's wiring (T2.6: BLE-15, BLE-21). The rules are pure and
 * tested (LinkLedgerTest); what can't be unit-tested without a GATT fake (T3.2) is that
 * BmsRepository's control loop routes EVERY worker event through the ledger, cancels a connect
 * still in flight when the planner drops its pack, and calls back only into its own generation.
 * Gradle runs unit tests from the module dir.
 */
class AttemptWiringTest {

    private val src: String by lazy {
        listOf("src/main/java", "app/src/main/java")
            .map { File(it, "dev/joely/bmsmon/ble/BmsRepository.kt") }
            .first { it.isFile }
            .readText()
            .replace(Regex("\\s+"), " ")
    }

    @Test fun everyWorkerEventGoesThroughTheLedger() {
        val drain = src.substringAfter("private fun drainEvents(").substringBefore("private suspend fun pollLoop(")
        for (call in listOf(
            "links.connectSucceeded(", "links.connectFailed(", "links.connectSkipped(", "links.pollFrame(",
            "links.pollDropped(", "links.kick()",
        )) {
            assertTrue("drainEvents must call $call", drain.contains(call))
        }
        // No second, ledger-less copy of the per-pack state.
        assertFalse(src.contains("val held = mutableMapOf"))
        assertFalse(src.contains("val connecting = mutableSetOf"))
    }

    @Test fun everyEventCarriesItsAttempt() {
        for (decl in listOf(
            "ConnectSuccess(val addr: String, val attempt: Long,",
            "ConnectFailure(val addr: String, val attempt: Long)",
            "ConnectSkipped(val addr: String, val attempt: Long)",
            "PollFrame(val addr: String, val attempt: Long,",
            "PollDrop(val addr: String, val attempt: Long)",
        )) {
            assertTrue(decl, src.contains(decl))
        }
    }

    @Test fun aPlannerDropCancelsAnInFlightConnect() {
        val drop = src.substringAfter("// 3. Drop").substringBefore("// 4. Kick off")
        assertTrue(drop.contains("connectJobs.remove(drop.addr)?.cancel()"))
        assertTrue(drop.contains("links.drop(drop.addr)?.close()"))
        val connect = src.substringAfter("// 4. Kick off").substringBefore("// 5. Re-assert")
        assertTrue(connect.contains("val attempt = links.beginConnect(addr)"))
        assertTrue(connect.contains("connectJobs[addr] = childScope.launch {"))
    }

    // Task 1 review carry: LinkLedger.drop() keeps a pack's backoff, failure count and garbage
    // streak (a disabled pack's still apply when it is re-enabled). A pack REMOVED from the roster
    // must leave none of it behind, held, connecting or merely backing off — so the loop forgets
    // every address that left the roster since the previous tick, after the planner's drops.
    // Final wave: the forget loop tears the pack down whole — its poll and connect workers too —
    // instead of leaning on the planner having dropped it a few lines earlier.
    @Test fun aPackRemovedFromTheRosterIsForgotten() {
        val tick = src.substringAfter("// 2. Decide connects/disconnects").substringBefore("// 4. Kick off")
        assertTrue(tick.contains("val roster = allTargets.map { it.address }.toSet()"))
        assertTrue(tick.contains("val desired = roster - disabledAddrs"))
        val drop = tick.substringAfter("// 3. Drop")
        val forget = drop.indexOf(
            "for (addr in lastRoster - roster) { pollJobs.remove(addr)?.cancel() " +
                "connectJobs.remove(addr)?.cancel() links.forget(addr)?.close() }",
        )
        assertTrue("forgets packs that left the roster, workers included", forget >= 0)
        assertTrue("after the planner's drops", drop.indexOf("links.drop(drop.addr)?.close()") in 0 until forget)
        assertTrue("and remembers this tick's roster", drop.indexOf("lastRoster = roster") > forget)
    }

    @Test fun theLoopStopsConnectWorkersOnExit() {
        val tail = src.substringAfter("// Any exit path").substringBefore("private fun drainEvents(")
        assertTrue(tail.contains("connectJobs.values.forEach { it.cancel() }"))
        assertTrue(tail.contains("links.heldSessions().values.forEach { it.close() }"))
    }

    // BLE-21: callbacks are captured per generation, like the channel and the wake (BLE-5), so an old
    // loop still draining after a stop()→start() can only reach the session that started it.
    @Test fun callbacksAreCapturedPerGeneration() {
        assertFalse(src.contains("private var onPoll"))
        assertFalse(src.contains("private var onReachable"))
        assertTrue(src.contains("controlLoop(childScope, ch, wake, onPoll, onReachable)"))
    }

    // BLE-16: a background attempt can never take the permit a stage pack needs — and the class is
    // the stage's when the attempt is admitted (final wave): setStage re-runs the gate's admission.
    @Test fun connectAttemptsGoThroughTheStageReservingGate() {
        assertTrue(src.contains("private val gate = ConnectGate(total = 2)"))
        val connect = src.substringAfter("// 4. Kick off").substringBefore("// 5. Re-assert")
        assertTrue(connect.contains("attemptConnect(gate, { addr in stageAddrs }, { isWanted(addr) }) {"))
        assertFalse("no class snapshot taken at launch", connect.contains("withPermit(stage ="))
        val setStage = src.substringAfter("fun setStage(addresses: Set<String>) {").substringBefore("fun setTargets(")
        assertTrue(setStage.contains("gate.stageChanged()"))
    }

    // Final-wave MUST-FIX (hard rule: a disabled pack is never connected). The connect itself runs
    // inside attemptConnect, which re-checks isWanted holding the permit, immediately before it
    // (ConnectGateTest.aPackDisconnectedWhileQueuedIsNeverConnected); isWanted is the disabled set
    // and the roster, the same rule a late success is judged by.
    @Test fun theConnectRunsOnlyAfterTheWantedReCheck() {
        val connect = src.substringAfter("// 4. Kick off").substringBefore("// 5. Re-assert")
        val block = connect.substringAfter("{ isWanted(addr) }) {")
        assertTrue(block.substringBefore("}").contains("session.connect(profile.connectTimeoutMs)"))
        assertTrue(src.contains(
            "private fun isWanted(addr: String): Boolean = addr !in disabledAddrs && allTargets.any { it.address == addr }",
        ))
        val drain = src.substringAfter("private fun drainEvents(").substringBefore("private suspend fun pollLoop(")
        assertTrue(drain.contains("val wanted = isWanted(event.addr)"))
    }

    // Task 2 review carry: the STALE branch closes the orphan and touches nothing else. Removing the
    // pack's connectJobs entry there would drop the CURRENT attempt's handle, so a later planner
    // drop could no longer cancel that in-flight connect.
    @Test fun aStaleSuccessOnlyClosesItsSession() {
        val drain = src.substringAfter("private fun drainEvents(").substringBefore("private suspend fun pollLoop(")
        val stale = drain.substringAfter("ConnectVerdict.STALE ->").substringBefore("}")
        assertEquals(" event.session.close() ", stale)
    }

    // BLE-16: only the app resume is rate-limited; a user Reconnect and Bluetooth-on kick at once.
    @Test fun onlyTheAppResumeIsRateLimited() {
        fun read(path: String) = listOf("src/main/java", "app/src/main/java")
            .map { File(it, path) }.first { it.isFile }.readText().replace(Regex("\\s+"), " ")
        val vm = read("dev/joely/bmsmon/BatteryViewModel.kt")
        // Each body runs to the next declaration, not the first "}", so a block added to one of
        // these functions doesn't trip the guard.
        fun body(signature: String) = vm.substringAfter(signature).substringBefore(" fun ")
        val resume = body("fun onAppForeground() {")
        assertTrue(resume.contains("engine.kickOnResume()"))
        assertFalse(resume.contains("engine.kickAll()"))
        for (reconnect in listOf("fun reconnectBattery(", "fun reconnectAll(")) {
            assertTrue("a user Reconnect still kicks at once ($reconnect)", body(reconnect).contains("engine.kickAll()"))
        }
        val engine = read("dev/joely/bmsmon/monitor/MonitorEngine.kt")
        assertTrue(engine.contains("if (state == BluetoothAdapter.STATE_ON) ble.kickAll()"))
        assertTrue(engine.contains("fun kickOnResume() = ble.kickOnResume()"))
    }

    // BLE-16: the rate limit is for spares only. Every resume retries the stage packs, the ones the
    // user is watching; a monitoring stop forgets the last resume kick.
    @Test fun everyResumeRetriesTheStagePacks() {
        val resume = src.substringAfter("fun kickOnResume() {").substringBefore("fun stop() {")
        assertTrue(resume.contains("if (resumeKickDue(lastResumeKickAt, t)) {"))
        // Without the stamp every resume would kick every pack — the regression BLE-16 fixes.
        assertTrue(resume.contains("if (resumeKickDue(lastResumeKickAt, t)) { lastResumeKickAt = t"))
        assertTrue(resume.contains("kickAll() } else { kickPacks(stageAddrs) }"))
        val drain = src.substringAfter("private fun drainEvents(").substringBefore("private suspend fun pollLoop(")
        assertTrue(drain.contains("is LoopEvent.KickPacks -> links.kick(event.addrs)"))
        val stop = src.substringAfter("fun stop() {").substringBefore("private inline fun safely(")
        assertTrue(stop.contains("lastResumeKickAt = null"))
    }
}
