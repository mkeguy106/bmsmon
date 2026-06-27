package dev.joely.bmsmon.model

/** Poll cadences: stage matches the official Redodo app (~1.65 s); the rest poll slowly. */
const val STAGE_POLL_MS = 1650L
const val SLOW_POLL_MS = 10_000L

/** How long a base stays on the stage after activity stops / after a manual pin. */
const val STICKY_HOLD_MS = 30 * 60 * 1000L
const val PIN_HOLD_MS = 30 * 60 * 1000L

/** What's on the main stage: a whole base (2 packs) or a single battery. */
sealed interface StageTarget {
    data class Base(val groupId: String) : StageTarget
    data class Single(val address: String) : StageTarget
}

fun StageTarget.addresses(): Set<String> = when (this) {
    is StageTarget.Base -> groupById(groupId).targets.map { it.address }.toSet()
    is StageTarget.Single -> setOf(address)
}

data class StageInputs(
    val fleet: Map<String, BatteryStatus>,
    val dailyDriverId: String,
    val dynamicEnabled: Boolean,
    val manualStage: StageTarget?,
    val manualPinnedAt: Long,
    val lastDischargeAt: Map<String, Long>,
    val current: StageTarget,
    val now: Long,
)

/**
 * Resolve which target owns the stage:
 *  - a manual pin wins (permanently if dynamic is off, else for PIN_HOLD_MS);
 *  - otherwise dynamic: discharging base > sticky recently-in-use base (STICKY_HOLD_MS) >
 *    charging base > keep current/any reachable. Daily driver breaks ties.
 */
fun resolveStage(i: StageInputs): StageTarget {
    i.manualStage?.let { pin ->
        if (!i.dynamicEnabled) return pin
        if (i.now - i.manualPinnedAt < PIN_HOLD_MS) return pin
    }
    if (!i.dynamicEnabled) return i.current

    fun pick(act: GroupActivity): BatteryGroup? {
        val matches = ALL_GROUPS.filter { groupActivity(it, i.fleet) == act }
        if (matches.isEmpty()) return null
        return matches.firstOrNull { it.id == i.dailyDriverId } ?: matches.first()
    }
    pick(GroupActivity.Discharging)?.let { return StageTarget.Base(it.id) }

    // sticky: the base most recently seen discharging, if still within the hold and reachable
    ALL_GROUPS
        .mapNotNull { g -> i.lastDischargeAt[g.id]?.let { ts -> g to ts } }
        .filter { i.now - it.second < STICKY_HOLD_MS && hasReachable(it.first, i.fleet) }
        .maxByOrNull { it.second }
        ?.let { return StageTarget.Base(it.first.id) }

    pick(GroupActivity.Charging)?.let { return StageTarget.Base(it.id) }

    val curReachable = when (val cur = i.current) {
        is StageTarget.Base -> hasReachable(groupById(cur.groupId), i.fleet)
        is StageTarget.Single -> i.fleet[cur.address]?.reachable == true
    }
    if (curReachable) return i.current
    ALL_GROUPS.firstOrNull { hasReachable(it, i.fleet) }?.let { return StageTarget.Base(it.id) }
    return i.current
}

/** Per-battery fleet status, keyed by address in the ViewModel. */
data class BatteryStatus(
    val telemetry: Telemetry? = null,
    val reachable: Boolean = false,
)

enum class GroupActivity { Discharging, Charging, Idle, Unknown }

private const val CURRENT_EPS = 0.05f

fun hasReachable(group: BatteryGroup, fleet: Map<String, BatteryStatus>): Boolean =
    group.targets.any { fleet[it.address]?.reachable == true }

/** Activity of a base, derived from its reachable batteries' BMS state (current as backup). */
fun groupActivity(group: BatteryGroup, fleet: Map<String, BatteryStatus>): GroupActivity {
    val tele = group.targets.mapNotNull { t ->
        fleet[t.address]?.takeIf { it.reachable }?.telemetry
    }
    if (tele.isEmpty()) return GroupActivity.Unknown
    if (tele.any { it.state == BatteryState.Discharging || it.current < -CURRENT_EPS }) return GroupActivity.Discharging
    if (tele.any { it.state == BatteryState.Charging || it.current > CURRENT_EPS }) return GroupActivity.Charging
    return GroupActivity.Idle
}

fun GroupActivity.isActive(): Boolean = this == GroupActivity.Discharging || this == GroupActivity.Charging
