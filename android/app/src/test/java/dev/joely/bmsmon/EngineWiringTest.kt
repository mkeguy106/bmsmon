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

    // Fix round 1: the user's disconnects must be in force on BLE before the session's first stage
    // push releases BmsRepository's launch barrier (see SessionStartOrderTest for why and what
    // the barrier then plans). start() takes the set from its caller and hands it to ble.start(),
    // which installs it before its control loop runs — never as a follow-up setDisabled.
    @Test fun theDisabledSetIsInForceBeforeTheFirstStagePush() {
        val start = flat
            .substringAfter("fun start(roster: Roster, seed: Map<String, BatteryStatus>, loggingEnabled: Boolean, disabled: Set<String>) {")
            .substringBefore("suspend fun restoreFromPersisted(")
        val taken = start.indexOf("disabledAddrs = disabled.")
        val bleStart = start.indexOf("ble.start(")
        val handedOver = start.indexOf("disabled = disabledAddrs,")
        val firstPush = start.indexOf("markStageAuthoritative() reevaluate()")
        assertTrue("start() takes the disabled set before BLE starts", taken in 0 until bleStart)
        assertTrue("ble.start() receives it", handedOver > bleStart)
        assertTrue("before the first stage push", firstPush > handedOver)
    }

    // forceStage writes stageTarget BEFORE reevaluate(), so applyStage sees no change and would
    // never persist it — a headless restore would then start on an older stage.
    @Test fun forceStagePersistsItsTarget() {
        val body = flat.substringAfter("fun forceStage(target: StageTarget) {").substringBefore("fun setStageConfig(")
        assertTrue(body.contains("persistStage(target)"))
    }

    // M1: a poll worker's cancellation is cooperative, so a frame (or a connect) already in flight
    // when the user disconnects a pack still reaches the engine after setDisabled. It must not mark
    // that pack reachable — a fresh stamp would read LIVE and could alert or seize for up to 60 s.
    @Test fun anInFlightFrameNeverRevivesADisabledPack() {
        val onPoll = flat.substringAfter("private fun onPoll(").substringBefore("private fun onReachable(")
        val early = onPoll.indexOf("if (isDisabled(addr)) return ")
        val update = onPoll.indexOf("_state.update { st ->")
        assertTrue("onPoll returns early for a disabled pack", early in 0 until update)
        val lambda = onPoll.substring(update).substringBefore("val fleet = st.fleet +")
        assertTrue(
            "…and re-checks inside the CAS loop, so a racing setDisabled can't be overtaken",
            lambda.contains("if (isDisabled(addr)) return@update st"),
        )
    }

    @Test fun aDisabledPackIsNeverMarkedReachable() {
        val onReachable = flat.substringAfter("private fun onReachable(")
            .substringBefore("private suspend fun learnTail(")
        assertTrue(onReachable.contains("up = reachable && !isDisabled(addr)"))
        assertTrue(onReachable.contains(".copy(reachable = up)"))
        assertFalse(onReachable.contains(".copy(reachable = reachable)"))
    }

    // M7: no CoroutineExceptionHandler on the engine scope — an unguarded import throw kills the process.
    @Test fun theLegacyCsvImportCannotCrashTheProcess() {
        val body = flat.substringAfter("fun importLegacyCsvIfNeeded(")
            .substringBefore("@Volatile private var gpsWanted")
        assertTrue(body.contains("runCatching { repository.importCsvOnce("))
    }
}
