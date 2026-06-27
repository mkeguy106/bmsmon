package dev.joely.bmsmon.model

/** Poll cadences: stage matches the official Redodo app (~1.65 s); the rest poll slowly. */
const val STAGE_POLL_MS = 1650L
const val SLOW_POLL_MS = 10_000L

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

/**
 * Pick the base for the main stage:
 *   discharging > charging  (daily driver wins ties), else
 *   keep the last active pair (if still reachable), else daily driver, else any reachable.
 */
fun computeStageGroup(
    groups: List<BatteryGroup>,
    fleet: Map<String, BatteryStatus>,
    dailyDriverId: String,
    lastActiveId: String,
): String {
    fun pick(activity: GroupActivity): BatteryGroup? {
        val matches = groups.filter { groupActivity(it, fleet) == activity }
        if (matches.isEmpty()) return null
        return matches.firstOrNull { it.id == dailyDriverId } ?: matches.first()
    }
    pick(GroupActivity.Discharging)?.let { return it.id }
    pick(GroupActivity.Charging)?.let { return it.id }

    // nothing active -> keep last active pair if still reachable
    groups.firstOrNull { it.id == lastActiveId && hasReachable(it, fleet) }?.let { return it.id }
    groups.firstOrNull { it.id == dailyDriverId && hasReachable(it, fleet) }?.let { return it.id }
    groups.firstOrNull { hasReachable(it, fleet) }?.let { return it.id }
    return lastActiveId
}
