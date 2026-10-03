package dev.joely.bmsmon.ble

import dev.joely.bmsmon.ble.profile.BackoffSpec
import kotlinx.coroutines.CancellationException

/** Wait before the next connect attempt after [failCount] consecutive failures (0 → eligible now). */
fun BackoffSpec.delayFor(failCount: Int): Long {
    if (failCount <= 0) return 0L
    var d = baseMs
    repeat(failCount - 1) { d = (d * factor).coerceAtMost(capMs) }
    return d.coerceAtMost(capMs)
}

/** Why the planner wants a link dropped (BLE-8) — the engine reacts differently per reason. */
enum class DropReason {
    /** No longer desired (user-disconnected or removed from the roster): a genuine drop — the
     *  engine reports the pack unreachable and a link-down event is logged. */
    Undesired,
    /** Healthy pack rotated out only to lend its budget slot to a waiter (overflow). NOT a link
     *  failure: the engine keeps it "reachable" with its last telemetry (reachable-stale) until
     *  its next scheduled connect, and no link-down event is logged. */
    Rotated,
}

data class PlannedDrop(val addr: String, val reason: DropReason)

data class FleetPlan(val toConnect: List<String>, val toDisconnect: List<PlannedDrop>)

/** Outcome of one poll attempt, fed to [pollAction]. */
enum class PollOutcome {
    FRAME,
    TIMEOUT,
    /** A complete response arrived but did not parse (UI-16) — a miss, see [pollAction]. */
    UNDECODABLE,
    ERROR,
}

/** What the poll loop does with one [PollOutcome]. */
enum class PollAction { DELIVER, RETRY, DROP }

/**
 * Classify one poll (UI-16): only a response that PARSES is a delivered frame. A buffer that
 * completes but fails realignment / the plausibility gate used to count as FRAME and reset the
 * miss streak, keeping a decode-fail-only pack "connected" with a frozen SOC indefinitely.
 */
fun pollOutcome(gotFrame: Boolean, decoded: Boolean): PollOutcome = when {
    !gotFrame -> PollOutcome.TIMEOUT
    !decoded -> PollOutcome.UNDECODABLE
    else -> PollOutcome.FRAME
}

/**
 * Decide what a persistent poll loop does after one poll, given how many *consecutive* misses
 * have already happened and the profile's tolerance. Pure so the retry-before-drop policy is
 * unit-testable without BLE.
 *
 * A single missed status frame ([PollOutcome.TIMEOUT]) does NOT mean the link is dead — the Beken
 * module just skipped/slowed one notification. On the fast-polled stage this happens routinely, and
 * tearing the GATT link down + reconnecting on the first miss is what produced the "occasional stage
 * disconnect". So a miss only drops once [maxMisses] consecutive misses accumulate; before that we
 * [RETRY] in place and keep the link. An undecodable response is a miss too (UI-16). A hard
 * [PollOutcome.ERROR] (STATE_DISCONNECTED, a failed write) means the link really is gone → [DROP]
 * immediately. A [PollOutcome.FRAME] resets the streak → [DELIVER].
 *
 * @param priorConsecutiveMisses misses (timeouts or undecodable frames) since the last delivered
 *   frame — ONE streak shared by both kinds, so a pack that never yields a parsed frame drops
 *   after [maxMisses] polls whichever way it fails.
 */
fun pollAction(outcome: PollOutcome, priorConsecutiveMisses: Int, maxMisses: Int): PollAction =
    when (outcome) {
        PollOutcome.FRAME -> PollAction.DELIVER
        PollOutcome.ERROR -> PollAction.DROP
        PollOutcome.TIMEOUT, PollOutcome.UNDECODABLE ->
            if (priorConsecutiveMisses + 1 >= maxMisses) PollAction.DROP else PollAction.RETRY
    }

/** Breather before re-polling after a timed-out poll ([missDelayMs]). */
const val POLL_RETRY_DELAY_MS = 500L

/**
 * How long a poll loop waits before re-polling after a miss it retries ([pollAction] → RETRY),
 * given the pack's normal [cadenceMs]. A TIMEOUT has already waited out the whole response window,
 * so only the short [POLL_RETRY_DELAY_MS] breather follows. An UNDECODABLE frame means the link
 * answered: re-polling sooner than the normal cadence would only add load on a module that is
 * already sending garbage, so it keeps the cadence. A delivered frame or an ERROR (which always
 * drops) isn't a miss; they map to the cadence only so the function is total.
 */
fun missDelayMs(outcome: PollOutcome, cadenceMs: Long): Long = when (outcome) {
    PollOutcome.TIMEOUT -> POLL_RETRY_DELAY_MS
    PollOutcome.UNDECODABLE, PollOutcome.FRAME, PollOutcome.ERROR -> cadenceMs
}

/**
 * Decode one complete status response with [parse]. A parser that throws yields null after
 * reporting the throw to [onThrow] — exactly like a parse that rejects the frame — so the poll
 * counts it as UNDECODABLE: a miss that keeps the cadence, with the frame still handed over for the
 * decode_fail log. It used to fall into the poll loop's catch-all as ERROR (an immediate drop and a
 * reconnect ~2 s later), so one frame shape the parser chokes on looped connect/drop forever with
 * no decode_fail evidence. Same policy as [isolateCallback]: cancellation propagates, and an Error
 * is not a bad frame.
 */
internal inline fun <T : Any> decodeOrNull(onThrow: (Exception) -> Unit, parse: () -> T?): T? =
    try {
        parse()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onThrow(e)
        null
    }

/**
 * Decide this tick's connect/disconnect actions. Pure: no BLE, no clock beyond [now].
 * Holds up to [maxHeld] links; stage packs get slots first; backed-off packs wait; non-desired
 * packs are dropped; true overflow rotates the oldest-held non-stage pack out to admit a waiter.
 *
 * When [stageFirst] is set (the launch priority barrier), only stage packs are admitted to connect
 * this tick — every background pack waits until the stage packs are all up. The engine arms this on
 * start and releases it once the stage is connected (or a grace window expires), so the restored
 * main stage connects and starts polling before anything else.
 */
fun planFleet(
    desired: Set<String>,
    stage: Set<String>,
    held: Set<String>,
    connecting: Set<String>,
    backoffUntil: Map<String, Long>,
    heldSince: Map<String, Long>,
    maxHeld: Int,
    now: Long,
    stageFirst: Boolean = false,
): FleetPlan {
    val toDisconnect = (held + connecting).filter { it !in desired }
        .map { PlannedDrop(it, DropReason.Undesired) }.toMutableList()
    val activeAfterDrop = (held + connecting).filter { it in desired }
    val eligible = desired
        .filter { it !in held && it !in connecting }
        .filter { (backoffUntil[it] ?: 0L) <= now }
        .filter { !stageFirst || it in stage }  // launch barrier: stage packs only
        .sortedWith(compareByDescending<String> { it in stage }.thenBy { it })
    val toConnect = mutableListOf<String>()
    var free = maxHeld - activeAfterDrop.size
    val waiting = eligible.toMutableList()
    while (free > 0 && waiting.isNotEmpty()) { toConnect += waiting.removeAt(0); free-- }
    // Overflow: budget full but a desired pack still waits → rotate oldest-held non-stage out.
    if (waiting.isNotEmpty()) {
        val victim = activeAfterDrop
            .filter { it in held && it !in stage }
            .minByOrNull { heldSince[it] ?: Long.MAX_VALUE }
        if (victim != null) {
            toDisconnect += PlannedDrop(victim, DropReason.Rotated)
            toConnect += waiting.removeAt(0)
        }
    }
    return FleetPlan(toConnect = toConnect, toDisconnect = toDisconnect)
}

/**
 * The launch barrier for one control-loop tick: within the grace window ([now] < [priorityUntil])
 * admit only stage packs while the stage is still unknown (no setStage yet this session) or not
 * every desired stage pack is held. It releases the moment the whole stage is held, or at once
 * when the stage has no desired pack — then normal rotation. [desired] is the tick's targets
 * minus the user-disabled packs, so the session's FIRST setStage hands the planner exactly what
 * it may connect: the disabled set must already be in force by then (T1.2 — BmsRepository.start
 * installs it before the loop runs).
 */
internal fun launchBarrierHolds(
    desired: Set<String>,
    stage: Set<String>,
    held: Set<String>,
    stageInitialized: Boolean,
    now: Long,
    priorityUntil: Long,
): Boolean {
    val stageDesired = desired.filter { it in stage }
    val allStageHeld = stageDesired.isNotEmpty() && stageDesired.all { it in held }
    return now < priorityUntil && (!stageInitialized || (stageDesired.isNotEmpty() && !allStageHeld))
}

/**
 * BLE-22: run one engine callback from the control loop. An Exception is reported and the event
 * dropped instead of killing the loop (and with it the process and the foreground service);
 * cancellation still propagates, and Errors (OOM, stack overflow) are deliberately not caught.
 *
 * Why this lets Errors through while the engine's other guards are `runCatching` (Throwable) —
 * the GMS location calls in applyGpsGate/shutdownGps, the stage tick, the power collector: those
 * each wrap one bounded step whose loss costs a feature (GPS, one tick) and whose throw, Error
 * included (a Play Services client LinkageError says nothing about this process), would otherwise
 * end monitoring on a scope with no exception handler. This guard instead runs on every BLE event,
 * ~100×/min: swallowing an OOM or StackOverflowError on each one would leave a broken process
 * polling and logging forever, so an Error crashes and the sticky foreground service restarts clean.
 */
internal inline fun isolateCallback(onError: (Exception) -> Unit, block: () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onError(e)
    }
}

/** What one status write did (BLE-18). The platform's return used to be ignored, so a write onto a
 *  dead link silently waited out the whole poll timeout. */
enum class WriteResult { WRITTEN, REFUSED, BUSY, LINK_ERROR }

/** `BluetoothStatusCodes.SUCCESS` / `ERROR_GATT_WRITE_REQUEST_BUSY` (API 33), as literals so this
 *  stays JVM-testable and lint-clean on minSdk 26 — pinned to the platform by LinkFailFastTest. */
internal const val GATT_WRITE_SUCCESS = 0
internal const val GATT_WRITE_REQUEST_BUSY = 201

/** API 33+ characteristic-write status → outcome. BUSY = a GATT op is still queued on a live link
 *  (a miss); anything else non-success (not connected, not allowed, unknown) = the link is unusable. */
fun classifyWriteStatus(status: Int): WriteResult = when (status) {
    GATT_WRITE_SUCCESS -> WriteResult.WRITTEN
    GATT_WRITE_REQUEST_BUSY -> WriteResult.BUSY
    else -> WriteResult.LINK_ERROR
}

/** The pre-33 characteristic write returns only a Boolean, which can't tell busy from gone: a miss. */
fun classifyLegacyWrite(accepted: Boolean): WriteResult =
    if (accepted) WriteResult.WRITTEN else WriteResult.BUSY
