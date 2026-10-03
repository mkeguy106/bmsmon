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

    @Test fun theWakeLockFollowsWhetherAnyPackIsWanted() {
        val start = service.substringAfter("override fun onStartCommand(").substringBefore("collectorJob = scope.launch")
        assertFalse("onStartCommand must not hold the CPU on its own", start.contains("acquireWakeLock()"))
        assertTrue(service.contains("wantsCpuWakeLock(st)"))
        assertTrue(service.contains("if (ns.holdCpu) acquireWakeLock() else releaseWakeLock()"))
    }

    // Re-acquire: the decision is part of what distinctUntilChanged compares, and it is applied on
    // EVERY emission the collector lets through — so the flip back to "wanted" on Reconnect (or a
    // pack added to the roster) reaches acquireWakeLock(), not only the session's first emission.
    @Test fun aFlipBackToWantedIsNeverDeduplicatedAway() {
        assertTrue(service.contains("val fgsType: Int, val holdCpu: Boolean)"))
        val collector = service.substringAfter(".distinctUntilChanged()").substringBefore("return START_STICKY")
        assertTrue(collector.trimStart().startsWith(".collect { ns ->"))
        assertTrue(collector.contains("if (ns.holdCpu) acquireWakeLock() else releaseWakeLock()"))
    }

    // The wakelock is taken only by that decision, and dropped only by it (nothing wanted), a stop
    // or the service's destruction — never as a side effect elsewhere.
    @Test fun theWakeLockHasNoOtherAcquireOrReleaseSite() {
        fun sites(call: String) = Regex(Regex.escape(call)).findAll(service).count()
        assertEquals("definition + the holdCpu decision", 2, sites("acquireWakeLock()"))
        assertEquals("definition + holdCpu decision + stopCleanly + onDestroy", 4, sites("releaseWakeLock()"))
        val stop = service.substringAfter("private fun stopCleanly() {").substringBefore("private fun acquireWakeLock(")
        assertTrue(stop.contains("releaseWakeLock()"))
    }

    @Test fun gpsNeedsAPackToAttachFixesTo() {
        val gate = engine.substringAfter("private fun applyGpsGate(").substringBefore("private fun shutdownGps(")
        assertTrue(gate.contains("val wanted = gpsWanted && _state.value.linksWanted"))
        assertFalse(gate.contains("wanted = gpsWanted,"))
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
