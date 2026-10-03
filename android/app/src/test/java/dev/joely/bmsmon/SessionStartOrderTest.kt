package dev.joely.bmsmon

import dev.joely.bmsmon.ble.launchBarrierHolds
import dev.joely.bmsmon.ble.planFleet
import dev.joely.bmsmon.model.DEFAULT_ROSTER
import dev.joely.bmsmon.model.StageConfig
import dev.joely.bmsmon.model.StageTarget
import dev.joely.bmsmon.model.allTargets
import dev.joely.bmsmon.model.engineDecision
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Session-start ordering for the BLE launch barrier (T1.2, fix round 1).
 *
 * BmsRepository holds every pack until the session's FIRST setStage, then connects stage packs
 * (and, once the barrier lifts, the rest) from `targets − disabled`. Its start() calls stop(),
 * which wipes the disabled set — so if the user's disconnects reach BLE only after the engine's
 * first stage push, the first planned ticks open a GATT link to a pack the user freed for the
 * Redodo app (a Beken module serves one client). The fix installs the disabled set inside
 * BmsRepository.start(), before its control loop runs; MonitorEngine.start() hands it over.
 *
 * Neither class can be built on the JVM (Context, GMS, NotificationManager), so this drives a
 * recording model of BmsRepository's barrier state through the session-start sequence, running
 * the control loop's REAL admission rules ([launchBarrierHolds] + [planFleet]) after every call —
 * what the loop would plan if it woke at that instant — with the engine's first stage push
 * computed by the real [engineDecision]. Source guards (here and in EngineWiringTest) pin that
 * the production start() paths keep the modelled order.
 */
class SessionStartOrderTest {

    private val roster = DEFAULT_ROSTER
    private val all = roster.allTargets().map { it.address }.toSet()
    private val a = "C8:47:80:15:67:44"   // 2012 · A
    private val b = "C8:47:80:15:62:1B"   // 2012 · B

    /** BmsRepository's barrier-relevant state, re-planning one loop tick after each call. */
    private class RecordingBle(private val targets: Set<String>) {
        data class Tick(val desired: Set<String>, val toConnect: List<String>)

        val calls = mutableListOf<Pair<String, Set<String>>>()
        val ticks = mutableListOf<Tick>()
        private var disabled = emptySet<String>()
        private var stage = emptySet<String>()
        private var stageInitialized = false

        /** BmsRepository.start(): stop() wipes the disabled + stage sets and re-arms the barrier;
         *  [installed] is the set start() installs before its loop runs (empty = the old start). */
        fun start(installed: Set<String>) {
            calls += "start" to installed
            stage = emptySet(); stageInitialized = false
            disabled = installed
            tick()
        }
        fun setDisabled(addresses: Set<String>) { calls += "disabled" to addresses; disabled = addresses; tick() }
        fun setStage(addresses: Set<String>) {
            calls += "stage" to addresses
            stage = addresses; stageInitialized = true
            tick()
        }

        private fun tick() {
            val desired = targets - disabled
            val stageFirst = launchBarrierHolds(
                desired = desired, stage = stage, held = emptySet(),
                stageInitialized = stageInitialized, now = 0L, priorityUntil = 20_000L,
            )
            val plan = planFleet(
                desired = desired, stage = stage, held = emptySet(), connecting = emptySet(),
                backoffUntil = emptyMap(), heldSince = emptyMap(), maxHeld = 8, now = 0L, stageFirst = stageFirst,
            )
            ticks += Tick(desired, plan.toConnect)
        }
    }

    /** The engine's first stage push (applyStage on start()'s first reevaluate), for the daily
     *  driver's base 2012 = {A, B}, given the disabled set the ENGINE knows at that moment. */
    private fun firstStagePush(ble: RecordingBle, engineDisabled: Set<String>) {
        val d = engineDecision(
            roster = roster, fleet = emptyMap(), nowElapsedMs = 0L, nowMs = 0L,
            lastDischargeAt = emptyMap(), stageCfg = StageConfig(), current = StageTarget.Base("2012"),
            disabled = engineDisabled, alertCfg = null, chargeAt = emptyMap(),
        )
        ble.setStage(d.stageAddrs)
    }

    private fun RecordingBle.planned(addr: String) = ticks.any { addr in it.toConnect }

    @Test
    fun `a disconnected stage pack is never planned on a session start`() {
        val ble = RecordingBle(all)
        ble.start(installed = setOf(a))
        firstStagePush(ble, engineDisabled = setOf(a))

        assertEquals(listOf("start" to setOf(a), "stage" to setOf(b)), ble.calls)
        assertFalse(ble.planned(a))
        // The barrier releases with the disconnect already in force: the first tick that may
        // connect anything has A out of its desired set and admits only the live stage pack.
        val first = ble.ticks.first { it.toConnect.isNotEmpty() }
        assertFalse(a in first.desired)
        assertEquals(listOf(b), first.toConnect)
    }

    @Test
    fun `a fully disconnected stage releases the barrier without its packs`() {
        val ble = RecordingBle(all)
        ble.start(installed = setOf(a, b))
        firstStagePush(ble, engineDisabled = setOf(a, b))

        assertEquals(listOf("start" to setOf(a, b), "stage" to emptySet<String>()), ble.calls)
        assertFalse(ble.planned(a) || ble.planned(b))
        val first = ble.ticks.first { it.toConnect.isNotEmpty() }
        assertEquals(all - setOf(a, b), first.toConnect.toSet())
    }

    @Test
    fun `before the first stage push nothing is planned at all`() {
        val ble = RecordingBle(all)
        ble.start(installed = emptySet())
        assertEquals(emptyList<String>(), ble.ticks.single().toConnect)
    }

    // Negative control: the harness does catch the bug. The pre-fix order was start() (wiping the
    // set) → first stage push → setDisabled.
    @Test
    fun `the pre-fix order plans a link to a pack the user disconnected`() {
        // Headless restore — a fresh engine doesn't know the disconnects yet, so it stages A too.
        val fresh = RecordingBle(all)
        fresh.start(installed = emptySet())
        firstStagePush(fresh, engineDisabled = emptySet())
        fresh.setDisabled(setOf(a))
        assertTrue(fresh.planned(a))

        // Same process — the engine knows the whole stage is disconnected, pushes an empty stage,
        // and that releases the barrier for every pack BLE still thinks is wanted.
        val warm = RecordingBle(all)
        warm.start(installed = emptySet())
        firstStagePush(warm, engineDisabled = setOf(a, b))
        warm.setDisabled(setOf(a, b))
        assertTrue(warm.planned(a) && warm.planned(b))
    }

    // --- source guard: the real BmsRepository.start() is what the model's start(installed) says ---

    @Test
    fun `BmsRepository installs the disabled set before its control loop runs`() {
        val src = listOf("src/main/java", "app/src/main/java")
            .map { File(it, "dev/joely/bmsmon/ble/BmsRepository.kt") }
            .first { it.isFile }
            .readText()
            .replace(Regex("\\s+"), " ")
        val start = src.substringAfter("fun start(").substringBefore("fun setStage(")
        val wipe = start.indexOf("stop()")
        val install = start.indexOf("disabledAddrs = disabled.")
        val loop = start.indexOf("controlLoop(")
        assertTrue("start() must take the disabled set", start.contains("disabled: Set<String>,"))
        assertTrue("installed after stop() wipes it", wipe in 0 until install)
        assertTrue("installed before the loop is launched", install < loop)
    }
}
