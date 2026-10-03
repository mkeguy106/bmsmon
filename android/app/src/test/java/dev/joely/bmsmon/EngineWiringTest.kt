package dev.joely.bmsmon

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source guard for the BLE-14 class of bug: fleet-wide capacity notifications were only
 * re-evaluated when a STAGE pack reported, so a dark stage silenced every low-battery
 * notification. The decision itself is pure and tested (StageControlTest); what can't be
 * unit-tested without a GATT fake is that the engine runs it on EVERY BLE event, so pin the
 * wiring textually until T3.2's engine harness exists. Gradle runs unit tests from the module dir.
 */
class EngineWiringTest {

    private val src: String by lazy {
        listOf("src/main/java", "app/src/main/java")
            .map { File(it, "dev/joely/bmsmon/monitor/MonitorEngine.kt") }
            .first { it.isFile }
            .readText()
    }
    private val flat: String get() = src.replace(Regex("\\s+"), " ")

    @Test fun everyBleEventRunsTheDecisionStep() {
        assertTrue(flat.contains("onPoll = { addr, raw, t -> onPoll(addr, raw, t); reevaluate() }"))
        assertTrue(flat.contains("onReachable = { addr, reachable -> onReachable(addr, reachable); reevaluate() }"))
    }

    @Test fun noAlertOrStagePathIsGatedOnStageMembership() {
        assertFalse(Regex("in stageAddrs\\)\\s*(evaluateAlerts|reevaluate)\\(").containsMatchIn(src))
    }

    @Test fun theDecisionStepIsSynchronized() {
        assertTrue(Regex("@Synchronized\\s+private fun reevaluate\\(").containsMatchIn(src))
    }
}
