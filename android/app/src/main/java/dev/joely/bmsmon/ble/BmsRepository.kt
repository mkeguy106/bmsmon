package dev.joely.bmsmon.ble

import android.content.Context
import android.util.Log
import dev.joely.bmsmon.ble.profile.BatteryProfile
import dev.joely.bmsmon.ble.profile.ProfileRegistry
import dev.joely.bmsmon.ble.profile.RedodoBekenProfile
import dev.joely.bmsmon.model.BmsTarget
import dev.joely.bmsmon.model.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Fleet engine that holds persistent BLE connections up to the profile's link budget, polls
 * stage packs fast ([BatteryProfile.stagePollMs]) and background packs slowly
 * ([BatteryProfile.slowPollMs]).  Uses [planFleet] for the connect/disconnect decision each tick.
 *
 * Concurrency model — single-writer: all per-pack mutable state (held sessions, connecting set,
 * fail counts, backoff times, held-since timestamps) lives exclusively inside [controlLoop].
 * Connect and poll worker coroutines post outcomes back through [resultChannel]
 * (Channel.UNLIMITED, so [trySend] never blocks/suspends), which the control loop drains at the
 * top of every tick.  No shared mutable state is touched outside that coroutine.
 */
class BmsRepository(private val context: Context) {

    // Cap on simultaneous *connection attempts* (the LE initiator can't pursue many).
    private val gate = Semaphore(2)

    @Volatile private var allTargets: List<BmsTarget> = emptyList()
    @Volatile private var stageAddrs: Set<String> = emptySet()
    @Volatile private var disabledAddrs: Set<String> = emptySet()
    @Volatile private var running = false
    @Volatile private var wakeDeferred: CompletableDeferred<Unit>? = null
    // Launch priority barrier: until [stagePriorityUntil], hold off connecting any non-stage pack so
    // the (restored) main stage connects and starts polling first. [stageInitialized] gates the very
    // first ticks before setStage lands, so we never admit a background pack ahead of the stage.
    @Volatile private var stagePriorityUntil = 0L
    @Volatile private var stageInitialized = false

    private var onPoll: (String, ByteArray, Telemetry?) -> Unit = { _, _, _ -> }
    private var onReachable: (String, Boolean) -> Unit = { _, _ -> }

    // Events from connect/poll workers → control loop (single reader).
    // UNLIMITED: trySend never suspends, so workers are never blocked by the loop's pace.
    @Volatile private var resultChannel: Channel<LoopEvent> = Channel(Channel.UNLIMITED)

    // SupervisorJob wrapping the control loop and all workers: cancel once to stop everything.
    private var monitoringJob: Job? = null

    private sealed class LoopEvent {
        data class ConnectSuccess(val addr: String, val session: BleSession) : LoopEvent()
        data class ConnectFailure(val addr: String) : LoopEvent()
        data class PollFrame(val addr: String, val raw: ByteArray) : LoopEvent()
        data class PollDrop(val addr: String) : LoopEvent()
        object Kick : LoopEvent()
    }

    fun start(
        scope: CoroutineScope,
        targets: List<BmsTarget>,
        onPoll: (String, ByteArray, Telemetry?) -> Unit,
        onReachable: (String, Boolean) -> Unit,
    ) {
        stop()
        allTargets = targets.map { it.copy(address = it.address.trim().uppercase()) }
        this.onPoll = onPoll
        this.onReachable = onReachable
        resultChannel = Channel(Channel.UNLIMITED)
        // Arm the launch barrier: prioritize the stage's connect/poll for a grace window.
        stagePriorityUntil = System.currentTimeMillis() + STAGE_PRIORITY_GRACE_MS
        stageInitialized = false
        running = true
        // SupervisorJob: a failing worker doesn't tear down siblings or the control loop.
        // Cancelling it stops the control loop and every worker it launched.
        val childJob = SupervisorJob(scope.coroutineContext[Job])
        val childScope = CoroutineScope(scope.coroutineContext + childJob + Dispatchers.IO)
        monitoringJob = childJob
        childScope.launch { controlLoop(childScope) }
    }

    /** Set which batteries are on the stage (persistent, fast poll). */
    fun setStage(addresses: Set<String>) {
        stageAddrs = addresses.map { it.uppercase() }.toSet()
        stageInitialized = true  // the launch stage is now known; the barrier can admit it
        wake()
    }

    /** Update the full target set live (roster add/remove). */
    fun setTargets(targets: List<BmsTarget>) {
        allTargets = targets.map { it.copy(address = it.address.trim().uppercase()) }
        wake()
    }

    /** User-disconnected batteries: drop their links and don't connect them. */
    fun setDisabled(addresses: Set<String>) {
        disabledAddrs = addresses.map { it.uppercase() }.toSet()
        wake()
    }

    /** Reset backoff and retry everything immediately (e.g. app returned to foreground). */
    fun kickAll() {
        resultChannel.trySend(LoopEvent.Kick)
        wake()
    }

    fun stop() {
        running = false
        wake()
        monitoringJob?.cancel()
        monitoringJob = null
        resultChannel.close()
        // Drain in-transit ConnectSuccess events so their sessions aren't leaked as zombie GATT links.
        var ev = resultChannel.tryReceive().getOrNull()
        while (ev != null) {
            if (ev is LoopEvent.ConnectSuccess) ev.session.close()
            ev = resultChannel.tryReceive().getOrNull()
        }
        stageAddrs = emptySet()
        disabledAddrs = emptySet()
        stageInitialized = false
        stagePriorityUntil = 0L
    }

    private fun wake() { wakeDeferred?.complete(Unit) }

    /** Sleep up to [ms], but wake early on kick / stage / target change. */
    private suspend fun waitOrWake(ms: Long) {
        val w = CompletableDeferred<Unit>()
        wakeDeferred = w
        withTimeoutOrNull(ms) { w.await() }
        wakeDeferred = null
    }

    // ---- control loop: the only coroutine that mutates per-pack state ----

    private suspend fun controlLoop(childScope: CoroutineScope) {
        // All per-pack state lives here.  Nothing outside this coroutine touches these.
        val held         = mutableMapOf<String, BleSession>()
        val pollJobs     = mutableMapOf<String, Job>()
        val connecting   = mutableSetOf<String>()
        val failCount    = mutableMapOf<String, Int>()
        val backoffUntil = mutableMapOf<String, Long>()
        val heldSince    = mutableMapOf<String, Long>()

        try {
            while (running) {
                val now = System.currentTimeMillis()

                // 1. Consume outcomes posted by workers since the last tick.
                drainEvents(held, pollJobs, connecting, failCount, backoffUntil, heldSince, childScope, now)

                // 2. Decide connects/disconnects for this tick.
                val desired = allTargets.map { it.address }.toSet() - disabledAddrs
                // Launch barrier: while within the grace window and the stage isn't fully up yet,
                // admit only stage packs. Releases the moment every stage pack is held (their poll
                // loops are then already running) or the grace window expires — then normal rotation.
                val stageDesired = desired.filter { it in stageAddrs }
                val allStageHeld = stageDesired.isNotEmpty() && stageDesired.all { it in held.keys }
                val stageFirst = now < stagePriorityUntil &&
                    (!stageInitialized || (stageDesired.isNotEmpty() && !allStageHeld))
                val plan = planFleet(
                    desired      = desired,
                    stage        = stageAddrs,
                    held         = held.keys.toSet(),
                    connecting   = connecting.toSet(),
                    backoffUntil = backoffUntil,
                    heldSince    = heldSince,
                    maxHeld      = RedodoBekenProfile.maxHeldConnections,
                    now          = now,
                    stageFirst   = stageFirst,
                )

                // 3. Drop connections the planner no longer wants.
                for (addr in plan.toDisconnect) {
                    pollJobs.remove(addr)?.cancel()
                    connecting -= addr
                    held.remove(addr)?.close()
                    onReachable(addr, false)
                }

                // 4. Kick off connect attempts the planner requested.
                for (addr in plan.toConnect) {
                    val target = allTargets.firstOrNull { it.address == addr } ?: continue
                    connecting += addr
                    val profile = ProfileRegistry.profileFor(target.name) ?: RedodoBekenProfile
                    val ch = resultChannel
                    val highPriority = addr in stageAddrs
                    childScope.launch {
                        val session = BleSession(context, addr, profile, highPriority = highPriority)
                        var handed = false
                        try {
                            val ok = gate.withPermit { session.connect(profile.connectTimeoutMs) }
                            if (ok) {
                                handed = ch.trySend(LoopEvent.ConnectSuccess(addr, session)).isSuccess
                            } else {
                                ch.trySend(LoopEvent.ConnectFailure(addr))
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.d(TAG, "connect $addr: ${e.message}")
                            ch.trySend(LoopEvent.ConnectFailure(addr))
                        } finally {
                            if (!handed) session.close()
                        }
                    }
                }

                waitOrWake(CONTROL_TICK_MS)
            }
        } finally {
            // Any exit path (normal, cancel, exception): close every open GATT session.
            pollJobs.values.forEach { it.cancel() }
            held.values.forEach { it.close() }
        }
    }

    /** Drain all pending worker events; all mutations to [held]/[connecting]/etc. happen here. */
    private fun drainEvents(
        held: MutableMap<String, BleSession>,
        pollJobs: MutableMap<String, Job>,
        connecting: MutableSet<String>,
        failCount: MutableMap<String, Int>,
        backoffUntil: MutableMap<String, Long>,
        heldSince: MutableMap<String, Long>,
        childScope: CoroutineScope,
        now: Long,
    ) {
        while (true) {
            val event = resultChannel.tryReceive().getOrNull() ?: break
            when (event) {
                is LoopEvent.ConnectSuccess -> {
                    connecting -= event.addr
                    held[event.addr] = event.session
                    heldSince[event.addr] = now
                    failCount[event.addr] = 0
                    backoffUntil -= event.addr
                    onReachable(event.addr, true)
                    // Start a persistent poll loop for this session.
                    val name    = allTargets.firstOrNull { it.address == event.addr }?.name ?: event.addr
                    val profile = ProfileRegistry.profileFor(name) ?: RedodoBekenProfile
                    val ch      = resultChannel
                    pollJobs[event.addr] = childScope.launch {
                        pollLoop(event.addr, event.session, profile, ch)
                    }
                }
                is LoopEvent.ConnectFailure -> {
                    connecting -= event.addr
                    val fc = (failCount[event.addr] ?: 0) + 1
                    failCount[event.addr] = fc
                    val profile = ProfileRegistry.profileFor(
                        allTargets.firstOrNull { it.address == event.addr }?.name
                    ) ?: RedodoBekenProfile
                    backoffUntil[event.addr] = now + profile.backoff.delayFor(fc)
                    if (fc >= profile.failThreshold) onReachable(event.addr, false)
                }
                is LoopEvent.PollFrame -> {
                    val name = allTargets.firstOrNull { it.address == event.addr }?.name ?: event.addr
                    val profile = ProfileRegistry.profileFor(name) ?: RedodoBekenProfile
                    onPoll(
                        event.addr,
                        event.raw,
                        BmsProtocol.parseTelemetry(event.raw, name, profile.layout, profile.responseHeader),
                    )
                }
                is LoopEvent.PollDrop -> {
                    pollJobs.remove(event.addr)?.cancel()
                    held.remove(event.addr)?.close()
                    onReachable(event.addr, false)
                    // Short backoff so the control loop reconnects promptly.
                    backoffUntil[event.addr] = now + RECONNECT_BACKOFF_MS
                }
                is LoopEvent.Kick -> {
                    backoffUntil.clear()
                    failCount.clear()
                }
            }
        }
    }

    /**
     * Per-session poll loop: poll immediately, then delay between iterations. A single missed status
     * frame (timeout) is NOT fatal — the Beken module routinely skips/slows one notification on the
     * fast-polled stage, and tearing the link down + reconnecting on the first miss is what caused the
     * "occasional stage disconnect". So a timeout retries in place up to [BatteryProfile.maxPollMisses]
     * consecutive misses before dropping; a hard error (link actually gone) drops immediately.
     */
    private suspend fun pollLoop(
        addr: String,
        session: BleSession,
        profile: BatteryProfile,
        ch: Channel<LoopEvent>,
    ) {
        var consecutiveTimeouts = 0
        while (true) {
            var raw: ByteArray? = null
            val outcome = try {
                raw = session.poll(POLL_TIMEOUT_MS)
                if (raw != null) PollOutcome.FRAME else PollOutcome.TIMEOUT
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "poll $addr: ${e.message}")
                PollOutcome.ERROR
            }
            when (pollAction(outcome, consecutiveTimeouts, profile.maxPollMisses)) {
                PollAction.DELIVER -> {
                    consecutiveTimeouts = 0
                    ch.trySend(LoopEvent.PollFrame(addr, raw!!))
                    val pollMs = if (addr in stageAddrs) profile.stagePollMs else profile.slowPollMs
                    delay(pollMs)
                }
                PollAction.RETRY -> {
                    consecutiveTimeouts++
                    delay(POLL_RETRY_DELAY_MS)  // brief breather; keep the link open and re-poll
                }
                PollAction.DROP -> {
                    ch.trySend(LoopEvent.PollDrop(addr))
                    return
                }
            }
        }
    }

    private companion object {
        const val TAG = "BmsRepository"
        const val CONTROL_TICK_MS      = 1_000L
        const val POLL_TIMEOUT_MS      = 4_000L
        const val POLL_RETRY_DELAY_MS  = 500L
        const val RECONNECT_BACKOFF_MS = 2_000L
        // Launch window during which the stage connects/polls before any background pack. Releases
        // early once the stage is fully connected; this is just the safety cap so an unreachable
        // stage pack can't starve the rest of the fleet forever.
        const val STAGE_PRIORITY_GRACE_MS = 20_000L
    }
}
