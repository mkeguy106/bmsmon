package dev.joely.bmsmon.ble

import dev.joely.bmsmon.ble.profile.BackoffSpec

/** Wait before the next connect attempt after [failCount] consecutive failures (0 → eligible now). */
fun BackoffSpec.delayFor(failCount: Int): Long {
    if (failCount <= 0) return 0L
    var d = baseMs
    repeat(failCount - 1) { d = (d * factor).coerceAtMost(capMs) }
    return d.coerceAtMost(capMs)
}

data class FleetPlan(val toConnect: List<String>, val toDisconnect: List<String>)

/**
 * Decide this tick's connect/disconnect actions. Pure: no BLE, no clock beyond [now].
 * Holds up to [maxHeld] links; stage packs get slots first; backed-off packs wait; non-desired
 * packs are dropped; true overflow rotates the oldest-held non-stage pack out to admit a waiter.
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
): FleetPlan {
    val toDisconnect = (held + connecting).filter { it !in desired }.toMutableList()
    val activeAfterDrop = (held + connecting).filter { it in desired }
    val eligible = desired
        .filter { it !in held && it !in connecting }
        .filter { (backoffUntil[it] ?: 0L) <= now }
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
        if (victim != null) { toDisconnect += victim; toConnect += waiting.removeAt(0) }
    }
    return FleetPlan(toConnect = toConnect, toDisconnect = toDisconnect)
}
