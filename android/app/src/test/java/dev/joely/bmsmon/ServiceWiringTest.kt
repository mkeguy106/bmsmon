package dev.joely.bmsmon

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source guards for BLE-27 — the wakelock and GPS follow "is there a pack to poll" — until T3.2's
 * service harness exists. The decisions are pure and tested (MonitorRestoreTest, StageControlTest).
 * Gradle runs unit tests from the module dir.
 */
class ServiceWiringTest {

    private fun read(path: String) = listOf("src/main/java", "app/src/main/java")
        .map { File(it, path) }.first { it.isFile }.readText().replace(Regex("\\s+"), " ")

    private val service by lazy { read("dev/joely/bmsmon/monitor/MonitoringService.kt") }
    private val engine by lazy { read("dev/joely/bmsmon/monitor/MonitorEngine.kt") }

    // The in-app start takes the CPU only through the wanted-set decision; a sticky / boot restore
    // (final-wave ruling) takes it at the top of the restore — loading the persisted settings
    // suspends, and a phone asleep there would leave monitoring un-restored — and the same decision
    // releases it right after if nothing is wanted.
    @Test fun theWakeLockFollowsWhetherAnyPackIsWanted() {
        val start = service.substringAfter("override fun onStartCommand(").substringBefore("collectorJob = scope.launch")
        assertFalse("onStartCommand takes no wakelock before the collector", start.contains("acquireWakeLock()"))
        val launch = service.substringAfter("collectorJob = scope.launch {").substringBefore("engine.state")
        val restore = launch.substringAfter("if (restore) {")
        assertTrue("only a restore holds it up front", launch.startsWith(" if (restore) {"))
        assertTrue("…before the settings load", restore.indexOf("acquireWakeLock()") in 0 until restore.indexOf("engine.restoreFromPersisted()"))
        assertTrue(service.contains("if (wantsCpuWakeLock(st)) acquireWakeLock() else releaseWakeLock()"))
    }

    // Re-acquire: the decision runs on EVERY engine emission, ahead of the de-duplication — so the
    // flip back to "wanted" on Reconnect (or a pack added to the roster), and a retry after a failed
    // acquire, always reach acquireWakeLock(), not only the session's first emission.
    @Test fun aFlipBackToWantedIsNeverDeduplicatedAway() {
        val chain = service.substringAfter("engine.state").substringBefore("return START_STICKY")
        val decision = chain.indexOf(".onEach { st -> if (wantsCpuWakeLock(st)) acquireWakeLock() else releaseWakeLock() }")
        assertTrue("decided per emission", decision >= 0)
        assertTrue("…before anything is de-duplicated", decision < chain.indexOf(".distinctUntilChanged()"))
        assertFalse("not folded into the de-duplicated notification state", service.contains("holdCpu"))
    }

    // The wakelock is taken only by that decision and the restore, and dropped only by the decision
    // (nothing wanted), a stop or the service's destruction — never as a side effect elsewhere.
    @Test fun theWakeLockHasNoOtherAcquireOrReleaseSite() {
        fun sites(call: String) = Regex(Regex.escape(call)).findAll(service).count()
        assertEquals("definition + restore + the wanted-set decision", 3, sites("acquireWakeLock()"))
        assertEquals("definition + the decision + stopCleanly + onDestroy", 4, sites("releaseWakeLock()"))
        val stop = service.substringAfter("private fun stopCleanly() {").substringBefore("private fun acquireWakeLock(")
        assertTrue(stop.contains("releaseWakeLock()"))
    }

    @Test fun gpsNeedsAPackToAttachFixesTo() {
        val gate = engine.substringAfter("private fun applyGpsGate(").substringBefore("private fun shutdownGps(")
        assertTrue(gate.contains("val wanted = gpsWanted && _state.value.linksWanted"))
        assertFalse(gate.contains("wanted = gpsWanted,"))
    }

    // Task 6 review carry: removing a pack after "Disconnect all" must not make it wanted for a
    // moment (wakelock, GPS and FGS type flapping): the roster edit lands before the disabled set.
    @Test fun removingAPackNeverMakesItWantedOnTheWayOut() {
        val vm = read("dev/joely/bmsmon/BatteryViewModel.kt")
        val remove = vm.substringAfter("fun removeBattery(address: String) {").substringBefore(" fun ")
        val roster = remove.indexOf("updateRoster { it.removeBattery(a) }")
        assertTrue(roster >= 0)
        assertTrue(roster < remove.indexOf("engine.setDisabled(_state.value.disabled)"))
    }

    @Test fun everyChangeToTheWantedSetRepublishesIt() {
        val bodies = mapOf(
            "fun start(roster: Roster," to "suspend fun restoreFromPersisted(",
            "fun setRoster(roster: Roster) {" to "fun seedStage(",
            "fun setDisabled(addresses: Set<String>) {" to "fun kickAll()",
        )
        for ((from, to) in bodies) {
            val body = engine.substringAfter(from).substringBefore(to)
            assertTrue("$from must republish linksWanted", body.contains("linksWanted = hasDesiredLinks(roster, disabledAddrs)"))
        }
    }
}
