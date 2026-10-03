package dev.joely.bmsmon

import java.io.File
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
            "links.connectSucceeded(", "links.connectFailed(", "links.pollFrame(", "links.pollDropped(", "links.kick()",
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
    @Test fun aPackRemovedFromTheRosterIsForgotten() {
        val tick = src.substringAfter("// 2. Decide connects/disconnects").substringBefore("// 4. Kick off")
        assertTrue(tick.contains("val roster = allTargets.map { it.address }.toSet()"))
        assertTrue(tick.contains("val desired = roster - disabledAddrs"))
        val drop = tick.substringAfter("// 3. Drop")
        val forget = drop.indexOf("for (addr in lastRoster - roster) links.forget(addr)?.close()")
        assertTrue("forgets packs that left the roster", forget >= 0)
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

    // BLE-16: a background attempt can never take the permit a stage pack needs.
    @Test fun connectAttemptsGoThroughTheStageReservingGate() {
        assertTrue(src.contains("private val gate = ConnectGate(total = 2)"))
        val connect = src.substringAfter("// 4. Kick off").substringBefore("// 5. Re-assert")
        assertTrue(connect.contains("gate.withPermit(stage = highPriority)"))
    }

    // BLE-16: only the app resume is rate-limited; a user Reconnect and Bluetooth-on kick at once.
    @Test fun onlyTheAppResumeIsRateLimited() {
        fun read(path: String) = listOf("src/main/java", "app/src/main/java")
            .map { File(it, path) }.first { it.isFile }.readText().replace(Regex("\\s+"), " ")
        val vm = read("dev/joely/bmsmon/BatteryViewModel.kt")
        val resume = vm.substringAfter("fun onAppForeground() {").substringBefore("}")
        assertTrue(resume.contains("engine.kickOnResume()"))
        assertFalse(resume.contains("engine.kickAll()"))
        assertTrue("a user Reconnect still kicks at once", vm.contains("persistDisabled() engine.kickAll()"))
        val engine = read("dev/joely/bmsmon/monitor/MonitorEngine.kt")
        assertTrue(engine.contains("if (state == BluetoothAdapter.STATE_ON) ble.kickAll()"))
        assertTrue(engine.contains("fun kickOnResume() = ble.kickOnResume()"))
    }
}
