package dev.joely.bmsmon.ble

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dev.joely.bmsmon.ble.profile.BatteryProfile
import dev.joely.bmsmon.ble.profile.ProfileRegistry
import dev.joely.bmsmon.ble.profile.RedodoBekenProfile
import dev.joely.bmsmon.model.BmsTarget
import dev.joely.bmsmon.model.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
 * Connect and poll worker coroutines post outcomes back through the loop's event channel
 * (Channel.UNLIMITED, so [Channel.trySend] never blocks/suspends), which the control loop drains
 * at the top of every tick.  No shared mutable state is touched outside that coroutine.
 *
 * Generation isolation (BLE-5): each start() creates a FRESH event channel + wake signal and
 * passes them into that generation's control loop and workers as captured parameters. The loop
 * and its workers never read the [resultChannel]/[currentWake] properties, so a not-yet-cancelled
 * old loop from a stop()→start() cycle can only ever drain its own (closed) channel — it can't
 * steal the new loop's ConnectSuccess events (and close their sessions in its finally) or eat the
 * new loop's wake. The properties exist only for the external API (setStage/kickAll/stop/…) to
 * address the CURRENT generation.
 */
class BmsRepository(
    private val context: Context,
    /** Scheduling clock (BLE-9): backoff/rotation/priority timers use a MONOTONIC source so a
     *  wall-clock step (NTP correction, user change) can't distort them. Injectable for tests.
     *  Persisted/user-visible timestamps stay wall-clock — this is repository-internal only. */
    private val now: () -> Long = { SystemClock.elapsedRealtime() },
) {

    // Cap on simultaneous *connection attempts* (the LE initiator can't pursue many).
    private val gate = Semaphore(2)

    @Volatile private var allTargets: List<BmsTarget> = emptyList()
    @Volatile private var stageAddrs: Set<String> = emptySet()
    @Volatile private var disabledAddrs: Set<String> = emptySet()
    @Volatile private var running = false
    // Current generation's wake signal — external API only; the loop uses its captured instance.
    @Volatile private var currentWake: LoopWake? = null
    // Launch priority barrier: until [stagePriorityUntil], hold off connecting any non-stage pack so
    // the (restored) main stage connects and starts polling first. [stageInitialized] gates the very
    // first ticks before setStage lands, so we never admit a background pack ahead of the stage.
    @Volatile private var stagePriorityUntil = 0L
    @Volatile private var stageInitialized = false

    private var onPoll: (String, ByteArray, Telemetry?) -> Unit = { _, _, _ -> }
    private var onReachable: (String, Boolean) -> Unit = { _, _ -> }

    // Current generation's event channel (workers → control loop, single reader). UNLIMITED:
    // trySend never suspends, so workers are never blocked by the loop's pace. External API only
    // (kickAll posts, stop closes+drains); the loop and workers use their captured instance.
    @Volatile private var resultChannel: Channel<LoopEvent> = Channel(Channel.UNLIMITED)

    /**
     * Wake signal for one control-loop generation (BLE-5/BLE-6). CONFLATED: a wake posted while
     * the loop is mid-tick is retained, so the very next [waitOrWake] returns immediately instead
     * of sleeping a full tick — no lost wakes. Workers hold a reference to their OWN generation's
     * instance, so a stale worker can never wake the wrong loop.
     */
    private class LoopWake {
        private val signal = Channel<Unit>(Channel.CONFLATED)
        fun wake() { signal.trySend(Unit) }
        /** Sleep up to [ms], waking early on [wake] (including one posted before this call). */
        suspend fun waitOrWake(ms: Long) { withTimeoutOrNull(ms) { signal.receive() } }
    }

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
        // Fresh channel + wake for THIS generation (BLE-5): captured by the loop/workers below;
        // the properties only let the external API address the current generation.
        val ch = Channel<LoopEvent>(Channel.UNLIMITED)
        val wake = LoopWake()
        resultChannel = ch
        currentWake = wake
        // Arm the launch barrier: prioritize the stage's connect/poll for a grace window.
        stagePriorityUntil = now() + STAGE_PRIORITY_GRACE_MS
        stageInitialized = false
        running = true
        // SupervisorJob: a failing worker doesn't tear down siblings or the control loop.
        // Cancelling it stops the control loop and every worker it launched.
        val childJob = SupervisorJob(scope.coroutineContext[Job])
        val childScope = CoroutineScope(scope.coroutineContext + childJob + Dispatchers.IO)
        monitoringJob = childJob
        childScope.launch { controlLoop(childScope, ch, wake) }
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

    /** Wake the CURRENT generation's control loop (external API paths only). */
    private fun wake() { currentWake?.wake() }

    // ---- control loop: the only coroutine that mutates per-pack state ----

    /**
     * @param ch   this generation's event channel — never read from the [resultChannel] property.
     * @param wake this generation's wake signal — never read from the [currentWake] property.
     */
    private suspend fun controlLoop(
        childScope: CoroutineScope,
        ch: Channel<LoopEvent>,
        wake: LoopWake,
    ) {
        // All per-pack state lives here.  Nothing outside this coroutine touches these.
        val held         = mutableMapOf<String, BleSession>()
        val pollJobs     = mutableMapOf<String, Job>()
        val connecting   = mutableSetOf<String>()
        val failCount    = mutableMapOf<String, Int>()
        val backoffUntil = mutableMapOf<String, Long>()
        val heldSince    = mutableMapOf<String, Long>()

        try {
            // `running` is shared across generations; the scope check makes a cancelled old loop
            // exit even if a rapid stop()→start() has already flipped `running` back to true.
            while (running && childScope.isActive) {
                val now = now()

                // 1. Consume outcomes posted by workers since the last tick.
                drainEvents(held, pollJobs, connecting, failCount, backoffUntil, heldSince, childScope, ch, wake, now)

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

                // 3. Drop connections the planner no longer wants. Reason matters (BLE-8): only a
                // genuine drop (user-disabled / removed from roster) is reported unreachable —
                // which shows DISCONNECTED and logs a link-down. An overflow ROTATION of a healthy
                // pack is planner bookkeeping, not a link loss: the pack keeps its last telemetry
                // and stays "reachable-stale" until its next scheduled connect refreshes it.
                for (drop in plan.toDisconnect) {
                    pollJobs.remove(drop.addr)?.cancel()
                    connecting -= drop.addr
                    held.remove(drop.addr)?.close()
                    if (drop.reason == DropReason.Undesired) onReachable(drop.addr, false)
                }

                // 4. Kick off connect attempts the planner requested.
                for (addr in plan.toConnect) {
                    val target = allTargets.firstOrNull { it.address == addr } ?: continue
                    connecting += addr
                    val profile = ProfileRegistry.profileFor(target.name) ?: RedodoBekenProfile
                    val highPriority = addr in stageAddrs
                    // Workers capture THIS generation's ch + wake (BLE-5): outcomes can only ever
                    // land on — and wake — the loop that launched them.
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
                            // Process the outcome now (start polling a fresh session / schedule the
                            // retry backoff) instead of at the next 1 s tick.
                            wake.wake()
                        }
                    }
                }

                // 5. Re-assert connection priority on held links whose stage membership changed
                // (BLE-4). Poll cadence follows stageAddrs live, but the connection interval was
                // only requested at connect time — without this, a pack pinned to the stage keeps
                // its LOW_POWER interval and misses 1.5 s polls. setHighPriority is idempotent
                // (no-op when unchanged), so calling it every tick is cheap. Runs on the control
                // loop, preserving the single-writer discipline for held-session management.
                val stageNow = stageAddrs
                for ((addr, session) in held) session.setHighPriority(addr in stageNow)

                wake.waitOrWake(CONTROL_TICK_MS)
            }
        } finally {
            // Any exit path (normal, cancel, exception): close every open GATT session.
            pollJobs.values.forEach { it.cancel() }
            held.values.forEach { it.close() }
        }
    }

    /** Drain all pending worker events; all mutations to [held]/[connecting]/etc. happen here.
     *  Reads only the loop's own [ch] (BLE-5), never the [resultChannel] property. */
    private fun drainEvents(
        held: MutableMap<String, BleSession>,
        pollJobs: MutableMap<String, Job>,
        connecting: MutableSet<String>,
        failCount: MutableMap<String, Int>,
        backoffUntil: MutableMap<String, Long>,
        heldSince: MutableMap<String, Long>,
        childScope: CoroutineScope,
        ch: Channel<LoopEvent>,
        wake: LoopWake,
        now: Long,
    ) {
        while (true) {
            val event = ch.tryReceive().getOrNull() ?: break
            when (event) {
                is LoopEvent.ConnectSuccess -> {
                    connecting -= event.addr
                    // Disabled (or removed from the roster) while the connect was in flight:
                    // the user expects a disconnected pack to be FREE for the Redodo phone app
                    // (single-client Beken module), so close the fresh link right here instead of
                    // holding + polling it until the next plan tick, and don't report it
                    // reachable. Reads the same @Volatile fields the control loop's plan step
                    // uses; this runs on the control-loop coroutine (single-writer preserved).
                    val wanted = event.addr !in disabledAddrs &&
                        allTargets.any { it.address == event.addr }
                    if (!wanted) {
                        event.session.close()
                        continue
                    }
                    held[event.addr] = event.session
                    heldSince[event.addr] = now
                    failCount[event.addr] = 0
                    backoffUntil -= event.addr
                    onReachable(event.addr, true)
                    // Start a persistent poll loop for this session.
                    val name    = allTargets.firstOrNull { it.address == event.addr }?.name ?: event.addr
                    val profile = ProfileRegistry.profileFor(name) ?: RedodoBekenProfile
                    pollJobs[event.addr] = childScope.launch {
                        pollLoop(event.addr, event.session, profile, ch, wake)
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
        wake: LoopWake,
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
                    // BLE-6: wake the control loop so the frame is parsed/delivered NOW — without
                    // this it sat in the channel until the next 1 s control tick, adding 0–1 s of
                    // jitter to stage telemetry and delaying alert evaluation. wake is this
                    // generation's own signal, so a stale worker can't wake the wrong loop.
                    wake.wake()
                    val pollMs = if (addr in stageAddrs) profile.stagePollMs else profile.slowPollMs
                    delay(pollMs)
                }
                PollAction.RETRY -> {
                    consecutiveTimeouts++
                    delay(POLL_RETRY_DELAY_MS)  // brief breather; keep the link open and re-poll
                }
                PollAction.DROP -> {
                    ch.trySend(LoopEvent.PollDrop(addr))
                    wake.wake()  // BLE-6: mark unreachable + schedule the reconnect immediately
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
