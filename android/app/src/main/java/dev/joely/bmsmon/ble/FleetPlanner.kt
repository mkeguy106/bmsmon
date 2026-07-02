package dev.joely.bmsmon.ble

import dev.joely.bmsmon.ble.profile.BackoffSpec

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
enum class PollOutcome { FRAME, TIMEOUT, ERROR }

/** What the poll loop does with one [PollOutcome]. */
enum class PollAction { DELIVER, RETRY, DROP }

/**
 * Decide what a persistent poll loop does after one poll, given how many *consecutive* timeouts
 * have already happened and the profile's tolerance. Pure so the retry-before-drop policy is
 * unit-testable without BLE.
 *
 * A single missed status frame ([PollOutcome.TIMEOUT]) does NOT mean the link is dead — the Beken
 * module just skipped/slowed one notification. On the fast-polled stage this happens routinely, and
 * tearing the GATT link down + reconnecting on the first miss is what produced the "occasional stage
 * disconnect". So a timeout only drops once [maxMisses] consecutive misses accumulate; before that we
 * [RETRY] in place and keep the link. A hard [PollOutcome.ERROR] (e.g. STATE_DISCONNECTED) means the
 * link really is gone → [DROP] immediately. A [PollOutcome.FRAME] resets the streak → [DELIVER].
 *
 * @param priorConsecutiveTimeouts timeouts seen since the last delivered frame (before this outcome).
 */
fun pollAction(outcome: PollOutcome, priorConsecutiveTimeouts: Int, maxMisses: Int): PollAction =
    when (outcome) {
        PollOutcome.FRAME -> PollAction.DELIVER
        PollOutcome.ERROR -> PollAction.DROP
        PollOutcome.TIMEOUT ->
            if (priorConsecutiveTimeouts + 1 >= maxMisses) PollAction.DROP else PollAction.RETRY
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
