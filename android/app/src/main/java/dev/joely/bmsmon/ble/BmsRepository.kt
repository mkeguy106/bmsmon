package dev.joely.bmsmon.ble

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dev.joely.bmsmon.ble.profile.BatteryProfile
import dev.joely.bmsmon.ble.profile.ProfileRegistry
import dev.joely.bmsmon.ble.profile.RedodoBekenProfile
import dev.joely.bmsmon.data.FailureLogThrottle
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
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Fleet engine that holds persistent BLE connections up to the profile's link budget, polls
 * stage packs fast ([BatteryProfile.stagePollMs]) and background packs slowly
 * ([BatteryProfile.slowPollMs]).  Uses [planFleet] for the connect/disconnect decision each tick.
 *
 * Concurrency model — single-writer: all per-pack mutable state (attempt ids, held sessions, fail
 * counts, backoff times, held-since timestamps — a [LinkLedger]) lives exclusively inside
 * [controlLoop]. Connect and poll worker coroutines post outcomes back through the loop's event
 * channel (Channel.UNLIMITED, so [Channel.trySend] never blocks/suspends), which the control loop
 * drains at the top of every tick.  No shared mutable state is touched outside that coroutine.
 *
 * Attempt model (BLE-15): every connect attempt has an id, and every outcome and poll event carries
 * the id of the attempt that produced it. Only a pack's CURRENT attempt changes state; a stale one
 * (dropped while connecting, or superseded) is closed and otherwise ignored, and a planner drop
 * cancels an attempt still connecting. A Disconnect → Reconnect during a slow connect therefore
 * holds one link, never two.
 *
 * Generation isolation (BLE-5): each start() creates a FRESH event channel + wake signal and
 * passes them — and that session's engine callbacks (BLE-21) — into that generation's control loop
 * and workers as captured parameters. The loop and its workers never read the
 * [resultChannel]/[currentWake] properties, so a not-yet-cancelled old loop from a stop()→start()
 * cycle can only ever drain its own (closed) channel — it can't steal the new loop's ConnectSuccess
 * events (and close their sessions in its finally), eat the new loop's wake, or call back into the
 * new session. The properties exist only for the external API (setStage/kickAll/stop/…) to address
 * the CURRENT generation.
 */
class BmsRepository(
    private val context: Context,
    /** Scheduling clock (BLE-9): backoff/rotation/priority timers use a MONOTONIC source so a
     *  wall-clock step (NTP correction, user change) can't distort them. Injectable for tests.
     *  Persisted/user-visible timestamps stay wall-clock — this is repository-internal only. */
    private val now: () -> Long = { SystemClock.elapsedRealtime() },
) {

    // Cap on simultaneous *connection attempts* (the LE initiator can't pursue many); one permit is
    // kept for stage packs (BLE-16, ConnectGate).
    private val gate = ConnectGate(total = 2)
    // Monotonic time of the last app resume that retried every pack (BLE-16). Written on the main
    // thread (onResume); cleared by stop(), so a new monitoring session starts with no limit.
    @Volatile private var lastResumeKickAt: Long? = null

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

    // Rate limits for failure logs that can repeat on every frame (~100/min across the fleet): a
    // callback that throws deterministically, or a frame shape the parser chokes on. Each logs its
    // first failure in full, then at most one counted summary line a minute (FailureLogThrottle).
    // That class is single-consumer and poll workers run concurrently, so each throttle is only
    // touched under its own lock (see logRepeating).
    private val callbackFailures = FailureLogThrottle()
    private val parseFailures = FailureLogThrottle()

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

    /** Worker → loop events. Each carries the [LinkLedger] attempt that produced it (BLE-15). */
    private sealed class LoopEvent {
        data class ConnectSuccess(val addr: String, val attempt: Long, val session: BleSession) : LoopEvent()
        data class ConnectFailure(val addr: String, val attempt: Long) : LoopEvent()
        /** The pack was disabled or removed before the attempt connected: nothing was opened. */
        data class ConnectSkipped(val addr: String, val attempt: Long) : LoopEvent()
        /** [tel] is null when the response didn't decode — still delivered for the decode_fail log. */
        data class PollFrame(val addr: String, val attempt: Long, val raw: ByteArray, val tel: Telemetry?) : LoopEvent()
        data class PollDrop(val addr: String, val attempt: Long) : LoopEvent()
        object Kick : LoopEvent()
        /** [Kick] for [addrs] only (BLE-16: a resume inside the rate limit retries the stage). */
        data class KickPacks(val addrs: Set<String>) : LoopEvent()
    }

    /**
     * Begin a monitoring generation. [disabled] (user-disconnected packs) is installed here, after
     * stop() has wiped the previous generation's set and BEFORE this generation's control loop
     * runs (T1.2): the session's first [setStage] releases the launch barrier, and the planner
     * then connects from `targets − disabled` — a disabled set applied any later lets that first
     * plan open a GATT link to a pack the user freed for the Redodo app (single-client Beken).
     */
    fun start(
        scope: CoroutineScope,
        targets: List<BmsTarget>,
        disabled: Set<String>,
        onPoll: (String, ByteArray, Telemetry?) -> Unit,
        onReachable: (String, Boolean) -> Unit,
    ) {
        stop()
        allTargets = targets.map { it.copy(address = it.address.trim().uppercase()) }
        disabledAddrs = disabled.map { it.uppercase() }.toSet()
        // Fresh channel + wake for THIS generation (BLE-5): captured by the loop/workers below,
        // together with this session's callbacks (BLE-21); the properties only let the external
        // API address the current generation.
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
        childScope.launch { controlLoop(childScope, ch, wake, onPoll, onReachable) }
    }

    /** Set which batteries are on the stage (persistent, fast poll). */
    fun setStage(addresses: Set<String>) {
        stageAddrs = addresses.map { it.uppercase() }.toSet()
        // Written LAST (and read FIRST by the control loop): both are volatile, so a loop that sees
        // `stageInitialized = true` is guaranteed to see the stage written before it — never the
        // old empty set with the barrier already released.
        stageInitialized = true  // the launch stage is now known; the barrier can admit it
        // A pack promoted while its connect is queued takes the stage permit now (ConnectGate).
        gate.stageChanged()
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

    /** Reset backoff and retry everything immediately (a user Reconnect, Bluetooth back on). An app
     *  resume goes through the rate-limited [kickOnResume] instead. */
    fun kickAll() {
        resultChannel.trySend(LoopEvent.Kick)
        wake()
    }

    /**
     * App returned to the foreground (BLE-16). The stage packs, the 1–2 the user is watching, are
     * retried at once on every resume. Every other pack is retried ([kickAll]) at most once per
     * [RESUME_KICK_MIN_INTERVAL_MS]: every screen glance used to reset every spare's backoff, so
     * absent spares never climbed their ladder and kept the connect gate busy.
     */
    fun kickOnResume() {
        val t = now()
        if (resumeKickDue(lastResumeKickAt, t)) {
            lastResumeKickAt = t
            Log.d(TAG, "resume kick: retrying every pack now")
            kickAll()
        } else {
            kickPacks(stageAddrs)
        }
    }

    /** [kickAll] for [addrs] only: every other pack keeps its backoff. */
    private fun kickPacks(addrs: Set<String>) {
        resultChannel.trySend(LoopEvent.KickPacks(addrs))
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
        lastResumeKickAt = null
    }

    /** BLE-22: one engine callback; a throw drops this event (logged, rate-limited) instead of the loop. */
    private inline fun safely(what: String, block: () -> Unit) =
        isolateCallback({ e -> logRepeating(callbackFailures, "$what threw — event dropped", e, Log::e) }, block)

    /**
     * Log a failure that may repeat on every frame through [throttle]: the first of a burst with its
     * stack trace, later ones as at most one counted summary line per minute (none in between).
     */
    private fun logRepeating(
        throttle: FailureLogThrottle,
        what: String,
        e: Exception,
        log: (String, String, Throwable?) -> Int,
    ) {
        when (val line = synchronized(throttle) { throttle.onFailure(now()) }) {
            is FailureLogThrottle.Action.Full -> log(
                TAG,
                if (line.unreported == 0) what else "$what (${line.unreported} earlier failures went unreported)",
                e,
            )
            is FailureLogThrottle.Action.Summary ->
                log(TAG, "${line.count} more failures since the last report; latest: $what: $e", null)
            FailureLogThrottle.Action.Suppress -> Unit
        }
    }

    /** Wake the CURRENT generation's control loop (external API paths only). */
    private fun wake() { currentWake?.wake() }

    // ---- control loop: the only coroutine that mutates per-pack state ----

    /**
     * This generation's control loop. Its engine callbacks are captured per generation (BLE-21), like
     * its channel and wake (BLE-5): an old loop still draining after a stop()→start() can only call
     * back into the session that started it.
     *
     * @param ch          this generation's event channel — never read from the [resultChannel] property.
     * @param wake        this generation's wake signal — never read from the [currentWake] property.
     * @param onPoll      this generation's frame callback.
     * @param onReachable this generation's link up/down callback.
     */
    private suspend fun controlLoop(
        childScope: CoroutineScope,
        ch: Channel<LoopEvent>,
        wake: LoopWake,
        onPoll: (String, ByteArray, Telemetry?) -> Unit,
        onReachable: (String, Boolean) -> Unit,
    ) {
        // All per-pack state lives here. Nothing outside this coroutine touches it. The ledger owns
        // attempt ids, held sessions, failure counts and backoff (BLE-15); the loop keeps the jobs.
        val links       = LinkLedger<BleSession>()
        val pollJobs    = mutableMapOf<String, Job>()
        val connectJobs = mutableMapOf<String, Job>()
        // The previous tick's roster: an address that has left it is forgotten (see step 3).
        var lastRoster  = emptySet<String>()

        try {
            // `running` is shared across generations; the scope check makes a cancelled old loop
            // exit even if a rapid stop()→start() has already flipped `running` back to true.
            while (running && childScope.isActive) {
                val now = now()

                // 1. Consume outcomes posted by workers since the last tick.
                drainEvents(links, pollJobs, connectJobs, childScope, ch, wake, onPoll, onReachable, now)

                // 2. Decide connects/disconnects for this tick.
                val roster = allTargets.map { it.address }.toSet()
                val desired = roster - disabledAddrs
                // Read stageInitialized FIRST, then the stage it vouches for: setStage writes them in
                // the opposite order, so a released barrier always plans against the pushed stage.
                // (Reading the stage first could pair the old empty set with initialized = true and
                // admit background packs ahead of the stage for one tick.)
                val initialized = stageInitialized
                val stage = stageAddrs
                val held = links.heldAddrs
                // Launch barrier: while within the grace window and the stage isn't fully up yet,
                // admit only stage packs. Releases the moment every stage pack is held (their poll
                // loops are then already running) or the grace window expires — then normal rotation.
                val stageFirst = launchBarrierHolds(
                    desired = desired,
                    stage = stage,
                    held = held,
                    stageInitialized = initialized,
                    now = now,
                    priorityUntil = stagePriorityUntil,
                )
                val plan = planFleet(
                    desired      = desired,
                    stage        = stage,
                    held         = held,
                    connecting   = links.connectingAddrs,
                    backoffUntil = links.backoffSnapshot(),
                    heldSince    = links.heldSinceSnapshot(),
                    maxHeld      = RedodoBekenProfile.maxHeldConnections,
                    now          = now,
                    stageFirst   = stageFirst,
                )

                // 3. Drop connections the planner no longer wants. Reason matters (BLE-8): only a
                // genuine drop (user-disabled / removed from roster) is reported unreachable —
                // which shows DISCONNECTED and logs a link-down. An overflow ROTATION of a healthy
                // pack is planner bookkeeping, not a link loss: the pack keeps its last telemetry
                // and stays "reachable-stale" until its next scheduled connect refreshes it.
                // A pack still connecting is cancelled too (BLE-15): its worker closes its own
                // session, and the ledger turns any outcome it already posted stale.
                for (drop in plan.toDisconnect) {
                    pollJobs.remove(drop.addr)?.cancel()
                    connectJobs.remove(drop.addr)?.cancel()
                    links.drop(drop.addr)?.close()
                    if (drop.reason == DropReason.Undesired) {
                        safely("onReachable ${drop.addr}") { onReachable(drop.addr, false) }
                    }
                }
                // A pack REMOVED from the roster (not merely disabled) leaves nothing behind: drop()
                // keeps a pack's failure count, backoff and garbage streak, which a re-added pack
                // would otherwise inherit. Its link and workers are normally gone already (no longer
                // desired, so dropped just above) — cancelled here too, so the teardown is whole
                // without leaning on the planner. This also covers a pack that was only backing off.
                for (addr in lastRoster - roster) {
                    pollJobs.remove(addr)?.cancel()
                    connectJobs.remove(addr)?.cancel()
                    links.forget(addr)?.close()
                }
                lastRoster = roster

                // 4. Kick off connect attempts the planner requested. Each gets an attempt id
                // (BLE-15); its outcome only counts while that id is the pack's current attempt.
                for (addr in plan.toConnect) {
                    if (allTargets.none { it.address == addr }) continue
                    val attempt = links.beginConnect(addr)
                    val profile = profileOf(addr)
                    // Workers capture THIS generation's ch + wake (BLE-5): outcomes can only ever
                    // land on — and wake — the loop that launched them.
                    connectJobs[addr] = childScope.launch {
                        val session = BleSession(context, addr, profile, highPriority = addr in stageAddrs)
                        var handed = false
                        try {
                            // BLE-16: a background attempt may hold at most one of the two permits,
                            // and the class is the stage's at admission, not at launch. Holding the
                            // permit, right before connectGatt, the pack must still be wanted: a
                            // setDisabled after this plan — or while it sat queued — connects nothing.
                            val outcome = attemptConnect(gate, { addr in stageAddrs }, { isWanted(addr) }) {
                                session.setHighPriority(addr in stageAddrs)
                                session.connect(profile.connectTimeoutMs)
                            }
                            when (outcome) {
                                ConnectOutcome.CONNECTED ->
                                    handed = ch.trySend(LoopEvent.ConnectSuccess(addr, attempt, session)).isSuccess
                                ConnectOutcome.FAILED -> ch.trySend(LoopEvent.ConnectFailure(addr, attempt))
                                ConnectOutcome.SKIPPED -> ch.trySend(LoopEvent.ConnectSkipped(addr, attempt))
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.d(TAG, "connect $addr: ${e.message}")
                            ch.trySend(LoopEvent.ConnectFailure(addr, attempt))
                        } finally {
                            // A cancelled attempt (BLE-15: its pack was dropped while connecting)
                            // lands here too, before it could hand its session over: close it.
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
                for ((addr, session) in links.heldSessions()) session.setHighPriority(addr in stageNow)

                wake.waitOrWake(CONTROL_TICK_MS)
            }
        } finally {
            // Any exit path (normal, cancel, exception): stop every worker and close every GATT session.
            pollJobs.values.forEach { it.cancel() }
            connectJobs.values.forEach { it.cancel() }
            links.heldSessions().values.forEach { it.close() }
        }
    }

    /** Still in the roster and not user-disconnected: the only packs BLE may open a link to. Reads
     *  the same @Volatile fields the plan step uses. */
    private fun isWanted(addr: String): Boolean =
        addr !in disabledAddrs && allTargets.any { it.address == addr }

    /** The profile for [addr] (by its target name), defaulting to the one validated profile. */
    private fun profileOf(addr: String): BatteryProfile =
        ProfileRegistry.profileFor(allTargets.firstOrNull { it.address == addr }?.name) ?: RedodoBekenProfile

    /** Drain all pending worker events. Every event carries its attempt id, and only the current
     *  attempt's events change state ([LinkLedger], BLE-15). Reads only the loop's own [ch]
     *  (BLE-5), never the [resultChannel] property. */
    private fun drainEvents(
        links: LinkLedger<BleSession>,
        pollJobs: MutableMap<String, Job>,
        connectJobs: MutableMap<String, Job>,
        childScope: CoroutineScope,
        ch: Channel<LoopEvent>,
        wake: LoopWake,
        onPoll: (String, ByteArray, Telemetry?) -> Unit,
        onReachable: (String, Boolean) -> Unit,
        now: Long,
    ) {
        while (true) {
            val event = ch.tryReceive().getOrNull() ?: break
            when (event) {
                is LoopEvent.ConnectSuccess -> {
                    // Disabled (or removed from the roster) while the connect was in flight:
                    // the user expects a disconnected pack to be FREE for the Redodo phone app
                    // (single-client Beken module), so close the fresh link right here instead of
                    // holding + polling it until the next plan tick, and don't report it
                    // reachable. Reads the same @Volatile fields the control loop's plan step
                    // uses; this runs on the control-loop coroutine (single-writer preserved).
                    val wanted = isWanted(event.addr)
                    when (links.connectSucceeded(event.addr, event.attempt, event.session, wanted, now)) {
                        ConnectVerdict.HOLD -> {
                            connectJobs.remove(event.addr)
                            safely("onReachable ${event.addr}") { onReachable(event.addr, true) }
                            // Start a persistent poll loop for this session.
                            val profile = profileOf(event.addr)
                            pollJobs[event.addr] = childScope.launch {
                                pollLoop(event.addr, event.attempt, event.session, profile, ch, wake)
                            }
                        }
                        ConnectVerdict.UNWANTED, ConnectVerdict.ALREADY_HELD -> {
                            connectJobs.remove(event.addr)
                            event.session.close()
                        }
                        // BLE-15: the attempt was dropped (or superseded) while connecting. Close the
                        // orphan: it must never replace — or be polled alongside — the current link.
                        ConnectVerdict.STALE -> event.session.close()
                    }
                }
                is LoopEvent.ConnectFailure -> {
                    val profile = profileOf(event.addr)
                    // Null = a stale attempt's failure: it must not clear the current attempt's
                    // in-flight state (that let the planner start yet another worker), nor count.
                    val unreachable = links.connectFailed(
                        event.addr, event.attempt, profile.backoff, profile.failThreshold, now,
                    ) ?: continue
                    connectJobs.remove(event.addr)
                    if (unreachable) {
                        safely("onReachable ${event.addr}") { onReachable(event.addr, false) }
                    }
                }
                // Disabled or removed before it connected: the attempt ends without counting a
                // failure — the next plan drops nothing for it and, if it is wanted again, retries it.
                is LoopEvent.ConnectSkipped ->
                    if (links.connectSkipped(event.addr, event.attempt)) connectJobs.remove(event.addr)
                is LoopEvent.PollFrame ->
                    // A stale session's frame (its link was dropped or replaced) is not delivered:
                    // it would mark a disconnected pack live (BLE-15).
                    if (links.pollFrame(event.addr, event.attempt, decoded = event.tel != null)) {
                        safely("onPoll ${event.addr}") { onPoll(event.addr, event.raw, event.tel) }
                    }
                is LoopEvent.PollDrop -> {
                    // Null = a stale session's drop, which must not close the newer link.
                    val session = links.pollDropped(event.addr, event.attempt, profileOf(event.addr).backoff, now)
                        ?: continue
                    pollJobs.remove(event.addr)?.cancel()
                    session.close()
                    safely("onReachable ${event.addr}") { onReachable(event.addr, false) }
                }
                is LoopEvent.Kick -> links.kick()
                is LoopEvent.KickPacks -> links.kick(event.addrs)
            }
        }
    }

    /**
     * Per-session poll loop: poll immediately, then delay between iterations. A single missed status
     * frame (timeout) is NOT fatal — the Beken module routinely skips/slows one notification on the
     * fast-polled stage, and tearing the link down + reconnecting on the first miss is what caused the
     * "occasional stage disconnect". So a miss retries in place up to [BatteryProfile.maxPollMisses]
     * consecutive misses before dropping; a hard error (link actually gone) drops immediately.
     *
     * The frame is parsed HERE, not on the control loop (UI-16): whether it decodes decides whether
     * it resets the miss streak. An undecodable response — a parser throw included ([decode]) — is
     * still delivered (tel = null) so the engine logs the decode_fail evidence, but it counts as a
     * miss and keeps the normal cadence ([missDelayMs]): the link answered, so the 0.5 s retry
     * breather would only add load on a misbehaving module.
     *
     * Every event it posts carries [attempt], the connect attempt whose session it polls: once that
     * link is dropped or replaced, its frames and its drop are stale to the ledger (BLE-15).
     */
    private suspend fun pollLoop(
        addr: String,
        attempt: Long,
        session: BleSession,
        profile: BatteryProfile,
        ch: Channel<LoopEvent>,
        wake: LoopWake,
    ) {
        // One streak for every kind of miss (timeouts and undecodable frames, see pollAction).
        var consecutiveMisses = 0
        while (true) {
            var raw: ByteArray? = null
            var tel: Telemetry? = null
            val outcome = try {
                raw = session.poll(POLL_TIMEOUT_MS)
                tel = raw?.let { decode(addr, it, profile) }   // never throws: a throw is a miss
                pollOutcome(gotFrame = raw != null, decoded = tel != null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Only poll() itself lands here now (the link is gone or unusable — BLE-18); a
                // parser throw is already a miss (decode).
                Log.d(TAG, "poll $addr: ${e.message}")
                PollOutcome.ERROR
            }
            when (pollAction(outcome, consecutiveMisses, profile.maxPollMisses)) {
                PollAction.DELIVER -> {
                    consecutiveMisses = 0
                    deliverFrame(addr, attempt, raw!!, tel, ch, wake)
                    delay(pollCadenceMs(addr, profile))
                }
                PollAction.RETRY -> {
                    consecutiveMisses++
                    // An undecodable response is still handed over, for the engine's decode_fail log.
                    if (outcome == PollOutcome.UNDECODABLE) deliverFrame(addr, attempt, raw!!, null, ch, wake)
                    // Keep the link open and re-poll: a short breather after a timeout, the normal
                    // cadence after an undecodable frame (never faster — see missDelayMs).
                    delay(missDelayMs(outcome, pollCadenceMs(addr, profile)))
                }
                PollAction.DROP -> {
                    // The final undecodable frame is still logged before the link is dropped.
                    if (outcome == PollOutcome.UNDECODABLE) ch.trySend(LoopEvent.PollFrame(addr, attempt, raw!!, null))
                    ch.trySend(LoopEvent.PollDrop(addr, attempt))
                    wake.wake()  // BLE-6: mark unreachable + schedule the reconnect immediately
                    return
                }
            }
        }
    }

    /**
     * Parse one complete response for [addr]. A parser throw is an undecodable frame, not a dead
     * link ([decodeOrNull]): logged here with the frame (rate-limited), then counted as a miss.
     */
    private fun decode(addr: String, raw: ByteArray, profile: BatteryProfile): Telemetry? {
        val name = allTargets.firstOrNull { it.address == addr }?.name ?: addr
        return decodeOrNull({ e ->
            val hex = raw.joinToString(" ") { "%02X".format(it) }
            logRepeating(parseFailures, "parser threw on a ${raw.size}-byte frame from $addr (a miss): $hex", e, Log::w)
        }) {
            BmsProtocol.parseTelemetry(raw, name, profile.layout, profile.responseHeader)
        }
    }

    /**
     * Hand one response to the control loop and wake it. BLE-6: without the wake the frame sat in
     * the channel until the next 1 s control tick, adding 0–1 s of jitter to stage telemetry and
     * delaying alert evaluation. [wake] is this generation's own signal, so a stale worker can't
     * wake the wrong loop.
     */
    private fun deliverFrame(
        addr: String,
        attempt: Long,
        raw: ByteArray,
        tel: Telemetry?,
        ch: Channel<LoopEvent>,
        wake: LoopWake,
    ) {
        ch.trySend(LoopEvent.PollFrame(addr, attempt, raw, tel))
        wake.wake()
    }

    /** Delay between polls of [addr]: fast on the stage, slow for background packs. */
    private fun pollCadenceMs(addr: String, profile: BatteryProfile): Long =
        if (addr in stageAddrs) profile.stagePollMs else profile.slowPollMs

    private companion object {
        const val TAG = "BmsRepository"
        const val CONTROL_TICK_MS      = 1_000L
        const val POLL_TIMEOUT_MS      = 4_000L
        // Launch window during which the stage connects/polls before any background pack. Releases
        // early once the stage is fully connected; this is just the safety cap so an unreachable
        // stage pack can't starve the rest of the fleet forever.
        const val STAGE_PRIORITY_GRACE_MS = 20_000L
    }
}
