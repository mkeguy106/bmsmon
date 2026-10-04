package dev.joely.bmsmon

import java.io.File
import org.junit.Assert.assertEquals
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

    // Every BLE event of the CURRENT session runs the decision step; one from an ended session
    // (BLE-21) changes nothing, so it decides nothing either — it can no longer move the stage
    // (and persist it) after a Stop.
    @Test fun everyBleEventRunsTheDecisionStep() {
        assertTrue(flat.contains(
            "onPoll = { addr, raw, t -> onPoll(session, addr, raw, t); if (session == currentSession) reevaluate() }",
        ))
        assertTrue(flat.contains(
            "onReachable = { addr, reachable -> onReachable(session, addr, reachable); if (session == currentSession) reevaluate() }",
        ))
    }

    // BLE-21: a callback already running on the control loop when monitoring stopped (cancellation
    // can't interrupt it) must not mark a pack live under MONITORING OFF, post anything, or leak into
    // the next session.
    @Test fun aCallbackFromAnEndedSessionChangesNothing() {
        val stop = flat.substringAfter("fun stop() {").substringBefore("fun persistMonitoringOff(")
        assertTrue("stop() ends the session before tearing BLE down",
            stop.indexOf("currentSession = 0L") in 0 until stop.indexOf("ble.stop()"))
        val start = flat.substringAfter("fun start(roster: Roster,").substringBefore("suspend fun restoreFromPersisted(")
        assertTrue(start.contains("val session = ++sessionSeq"))
        assertTrue("minted before BLE starts", start.indexOf("currentSession = session") in 0 until start.indexOf("ble.start("))
        val bodies = mapOf(
            "private fun onPoll(" to "private fun onReachable(",
            "private fun onReachable(" to "private suspend fun learnTail(",
        )
        for ((from, to) in bodies) {
            val body = flat.substringAfter(from).substringBefore(to)
            assertTrue("$from returns early for an ended session", body.contains("if (session != currentSession) return"))
            assertTrue("$from re-checks inside its state update",
                body.contains("if (session != currentSession || !st.monitoring) return@update st"))
        }
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

    // Final-wave MUST-FIX: CLAUDE.md says a refused frame is not logged or uploaded. An undecodable
    // frame used to be logged before the disabled check, and with no re-check of the session at the
    // write: now a disabled pack's frame returns before either branch, and the decode_fail row is
    // written only if the frame is still accepted right at the write.
    @Test fun aRefusedUndecodableFrameIsNeverLogged() {
        val onPoll = flat.substringAfter("private fun onPoll(").substringBefore("private fun onReachable(")
        val disabled = onPoll.indexOf("if (isDisabled(addr)) return ")
        val undecodable = onPoll.indexOf("if (t == null) {")
        assertTrue("the disabled check comes first", disabled in 0 until undecodable)
        val branch = onPoll.substring(undecodable).substringBefore("return }")
        assertTrue(branch.contains(
            "if (logging && session == currentSession && _state.value.monitoring && !isDisabled(addr)) { " +
                "repository.ingestRawOnly(addr, raw, \"decode_fail\", now)",
        ))
        assertEquals("one raw-only write site", 1, Regex(Regex.escape("repository.ingestRawOnly(")).findAll(src).count())
    }

    @Test fun aDisabledPackIsNeverMarkedReachable() {
        val onReachable = flat.substringAfter("private fun onReachable(")
            .substringBefore("private suspend fun learnTail(")
        assertTrue(onReachable.contains("up = reachable && !isDisabled(addr)"))
        assertTrue(onReachable.contains(".copy(reachable = up)"))
        assertFalse(onReachable.contains(".copy(reachable = reachable)"))
    }

    // BLE-19 / BLE-23: the request is driven on every gate evaluation (a later permission grant is
    // picked up), gpsActive comes from what is actually registered, and a sample reads the fix with
    // the read-time staleness check.
    @Test fun theGpsGateDrivesTheRequestEveryEvaluation() {
        val gate = flat.substringAfter("private fun applyGpsGate(").substringBefore("private fun shutdownGps(")
        assertTrue(gate.contains("val active = driveLocation(run, locationSource)"))
        assertFalse(gate.contains("if (active) locationSource.start() else locationSource.stop()"))
        val onPoll = flat.substringAfter("private fun onPoll(").substringBefore("private fun onReachable(")
        assertTrue(onPoll.contains("locationSource.current(now)"))
    }

    // Final-wave MUST-FIX: every location-mode switch is guarded. The unguarded one in stopPowerLoop()
    // aborted stop() just after it ended the session: BLE kept running with every frame refused, the
    // throw crashed the main-thread caller, and the sticky restart undid the user's Stop.
    @Test fun everyLocationModeSwitchIsGuardedSoStopAlwaysCompletes() {
        assertEquals("one switch site", 1, Regex(Regex.escape("locationSource.setBalanced(")).findAll(src).count())
        assertTrue(flat.contains(
            "private fun applyLocationMode(balanced: Boolean) { runCatching { locationSource.setBalanced(balanced) }",
        ))
        val stopPower = flat.substringAfter("private fun stopPowerLoop() {").substringBefore("}")
        assertTrue(stopPower.contains("applyLocationMode(false)"))
        val powerLoop = flat.substringAfter("private fun startPowerLoop() {").substringBefore("private fun stopPowerLoop()")
        assertTrue(powerLoop.contains("applyLocationMode(d.gpsBalanced)"))
        // stop() reaches its teardown after the power loop: BLE, GPS, the state reset.
        val stop = flat.substringAfter("fun stop() {").substringBefore("fun persistMonitoringOff(")
        val power = stop.indexOf("stopPowerLoop()")
        assertTrue(power in 0 until stop.indexOf("ble.stop()"))
        assertTrue(stop.indexOf("ble.stop()") < stop.indexOf("shutdownGps()"))
    }

    // Final wave: a request Play Services fails after accepting it re-runs the gate, the single
    // writer of gpsActive, under its lock — LocationSource forgets it (LocationSourceTest).
    @Test fun aLostLocationRequestReRunsTheGpsGate() {
        assertTrue(flat.contains("private val locationSource = LocationSource(appContext) { onLocationRequestLost() }"))
        val lost = flat.substringAfter("private fun onLocationRequestLost() {").substringBefore("}")
        assertTrue(lost.contains("runCatching { applyGpsGate(now())"))
        assertTrue(Regex("@Synchronized\\s+private fun applyGpsGate\\(").containsMatchIn(src))
    }

    // Task 6 review carry: a roster left with no wanted pack must stop GPS at once — no BLE frame
    // will drive the gate, and with the wakelock released the 5-min tick may never run.
    @Test fun aRosterEditReRunsTheGpsGate() {
        val body = flat.substringAfter("fun setRoster(roster: Roster) {").substringBefore("fun seedStage(")
        assertTrue(body.contains("if (_state.value.monitoring) { ble.setTargets(roster.allTargets()) applyGpsGate(now())"))
    }

    // M7: no CoroutineExceptionHandler on the engine scope — an unguarded import throw kills the process.
    @Test fun theLegacyCsvImportCannotCrashTheProcess() {
        val body = flat.substringAfter("fun importLegacyCsvIfNeeded(")
            .substringBefore("@Volatile private var gpsWanted")
        assertTrue(body.contains("runCatching { repository.importCsvOnce("))
    }

    // BLE-25: the range pass streams Room pages; it never loads a pack's whole window as a list.
    @Test fun theRangePassStreamsTheWindow() {
        val pass = flat.substringAfter("private suspend fun rangePass()").substringBefore("private fun recomputeLastDischarge(")
        assertTrue(pass.contains("repository.forEachRangeRow(addr, since)"))
        assertFalse(pass.contains("repository.rangeRows("))
    }
}
