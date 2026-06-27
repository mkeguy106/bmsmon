package dev.joely.bmsmon.model

/** Poll cadences: stage matches the official Redodo app (~1.65 s); the rest poll slowly. */
const val STAGE_POLL_MS = 1650L
const val SLOW_POLL_MS = 10_000L

/** Default minutes the active (discharging) base holds the stage after its last discharge. */
const val DEFAULT_STAGE_HOLD_MIN = 15
val STAGE_HOLD_OPTIONS_MIN = listOf(5, 10, 15, 30, 60)

/** How long a manual pin holds the stage. */
const val PIN_HOLD_MS = 30 * 60 * 1000L

/**
 * Power (W) at which the inner wattage ring reads "full". Placeholder until we measure
 * the chair's real peak draw via usage logging — then set this to that peak.
 */
const val POWER_RING_FULL_W = 80f

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
    val holdMs: Long,
    val current: StageTarget,
    val now: Long,
)

/**
 * Resolve which target owns the stage:
 *  - a manual pin wins (permanently if dynamic is off, else for PIN_HOLD_MS);
 *  - otherwise dynamic:
 *      1. a base discharging right now (the active chair),
 *      2. a base that discharged within [holdMs] keeps the stage even if it's now idle OR
 *         briefly out of BLE range (you're still using it),
 *      3. only once that hold expires, switch to a charging base if there is one,
 *      4. if everything is idle, don't change the stage at all.
 *  Daily driver breaks ties.
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

    // 1. discharging right now = the active chair
    pick(GroupActivity.Discharging)?.let { return StageTarget.Base(it.id) }

    // 2. hold: most recent discharge within the window keeps the stage (idle or out of range)
    ALL_GROUPS
        .mapNotNull { g -> i.lastDischargeAt[g.id]?.let { ts -> g to ts } }
        .filter { i.now - it.second < i.holdMs }
        .maxByOrNull { it.second }
        ?.let { return StageTarget.Base(it.first.id) }

    // 3. hold expired, nothing discharging -> a charging base may take over
    pick(GroupActivity.Charging)?.let { return StageTarget.Base(it.id) }

    // 4. everything idle -> leave the stage where it is
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
