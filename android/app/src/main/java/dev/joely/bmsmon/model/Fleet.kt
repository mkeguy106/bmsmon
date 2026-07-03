package dev.joely.bmsmon.model

/**
 * Poll cadences. The stage rate is set to match the official Redodo app: an HCI capture of Redodo
 * actively viewing one battery's live detail page showed it polls `0x13` status every ~1.5 s
 * (mean 1.487 s, range 1.43–1.53 s, rock-steady). See docs/ble-connectivity-investigation.md.
 */
const val STAGE_POLL_MS = 1500L
const val SLOW_POLL_MS = 10_000L

/** Default minutes the active (discharging) base holds the stage after its last discharge. */
const val DEFAULT_STAGE_HOLD_MIN = 15
val STAGE_HOLD_OPTIONS_MIN = listOf(5, 10, 15, 30, 60)

/** How long a manual pin holds the stage. */
const val PIN_HOLD_MS = 30 * 60 * 1000L

/**
 * Power (W) at which the inner wattage ring reads "full", per pack. Calibrated from real
 * usage logging on the 2012 daily driver. With a fuller log (~96 k samples, ~5.5 k discharge):
 * per-pack discharge is p50 ~53 W, p90 ~127 W, p95 ~164 W, p99 ~341 W, with brief hard-pull
 * spikes to ~882 W (67 A). 300 W (≈p98) keeps everyday cruising expressive on the ring while
 * only genuine hard pulls peg it — the exact wattage always stays in the numeric readout, so a
 * pegged ring never hides the real draw. (Earlier, sparser data read p99 ~259 W → was 250 W.)
 */
const val POWER_RING_FULL_W = 300f

/** What's on the main stage: a whole base (2 packs) or a single battery. */
sealed interface StageTarget {
    data class Base(val groupId: String) : StageTarget
    data class Single(val address: String) : StageTarget
}

fun StageTarget.addresses(roster: Roster): Set<String> = when (this) {
    is StageTarget.Base -> roster.groupById(groupId)?.targets?.map { it.address }?.toSet() ?: emptySet()
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
    val groups: List<BatteryGroup>,
    /**
     * SOC at/below which a reachable pack seizes the stage (safety override), or null to disable.
     * The ViewModel sets this to the highest enabled capacity-alert threshold when the "pull low
     * packs to stage" setting and alerts are both on.
     */
    val seizeThreshold: Int? = null,
)

/** The group that owns [address] (case-insensitive), or null if it's ungrouped. */
private fun groupForAddress(groups: List<BatteryGroup>, address: String): BatteryGroup? =
    groups.firstOrNull { g -> g.targets.any { it.address.equals(address, ignoreCase = true) } }

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
    // Low-pack safety override: a reachable pack at/below the seize threshold pulls the stage to its
    // base ahead of everything — the active chair AND a manual pin — so a pack draining too low can
    // never sit hidden off-stage (that's what would let it discharge to damage). Lowest SOC wins; the
    // daily driver breaks exact ties. When the pack recovers above the threshold this branch yields
    // and the normal pin/auto resolution below takes back over (restoring any manual pin).
    i.seizeThreshold?.let { thr ->
        val low = i.fleet.entries
            .mapNotNull { (addr, s) ->
                s.telemetry?.takeIf { s.reachable && it.soc <= thr }?.let { addr to it.soc }
            }
            .minWithOrNull(
                compareBy<Pair<String, Float>> { it.second }
                    .thenByDescending { groupForAddress(i.groups, it.first)?.id == i.dailyDriverId }
                    .thenBy { it.first },
            )
        if (low != null) {
            val g = groupForAddress(i.groups, low.first)
            return if (g != null) StageTarget.Base(g.id) else StageTarget.Single(low.first)
        }
    }
    i.manualStage?.let { pin ->
        if (!i.dynamicEnabled) return pin
        if (i.now - i.manualPinnedAt < PIN_HOLD_MS) return pin
    }
    if (!i.dynamicEnabled) return i.current

    fun pick(act: GroupActivity): BatteryGroup? {
        val matches = i.groups.filter { groupActivity(it, i.fleet) == act }
        if (matches.isEmpty()) return null
        return matches.firstOrNull { it.id == i.dailyDriverId } ?: matches.first()
    }

    // 1. discharging right now = the active chair
    pick(GroupActivity.Discharging)?.let { return StageTarget.Base(it.id) }

    // 2. hold: most recent discharge within the window keeps the stage (idle or out of range)
    i.groups
        .mapNotNull { g -> i.lastDischargeAt[g.id]?.let { ts -> g to ts } }
        .filter { i.now - it.second < i.holdMs }
        .maxByOrNull { it.second }
        ?.let { return StageTarget.Base(it.first.id) }

    // 3. hold expired, nothing discharging -> a charging base may take over
    pick(GroupActivity.Charging)?.let { return StageTarget.Base(it.id) }

    // 4. everything idle -> leave the stage where it is
    return i.current
}

/**
 * Per-battery fleet status, keyed by address in the ViewModel. [etaFullMin] is the engine-computed
 * time-to-full estimate for this pack's latest sample — computed ONCE per poll in MonitorEngine
 * (the same value that is uploaded to the cloud) and only displayed by the UI, so the stage and
 * the telemetry stream can never diverge.
 */
data class BatteryStatus(
    val telemetry: Telemetry? = null,
    val reachable: Boolean = false,
    val etaFullMin: Float? = null,
)

/**
 * Mark [disabled] packs unreachable (and clear their regen flags) in one synchronous step, so
 * reachability has a single writer: a just-disconnected pack can never transiently render as
 * connected while its BLE worker is still tearing down. Address comparison is case-insensitive.
 * Last-known telemetry is kept so the pack still renders (dimmed) as DISCONNECTED.
 */
fun applyDisabled(
    fleet: Map<String, BatteryStatus>,
    regenAddrs: Set<String>,
    disabled: Set<String>,
): Pair<Map<String, BatteryStatus>, Set<String>> {
    val norm = disabled.map { it.uppercase() }.toSet()
    val nextFleet = fleet.mapValues { (addr, s) ->
        if (s.reachable && addr.uppercase() in norm) s.copy(reachable = false) else s
    }
    val nextRegen = regenAddrs.filterNot { it.uppercase() in norm }.toSet()
    return nextFleet to nextRegen
}

enum class GroupActivity { Discharging, Charging, Idle, Unknown }

private const val CURRENT_EPS = 0.05f

// Regen / current dump: a brief charge-direction current while the pack was discharging
// moments ago (active driving) — distinct from steady charging (parked on a charger).
// Validated against real driving: 34 captured regen bursts ran 1.0–22.3 A (up to ~297 W),
// cleanly separated from the noise floor, so EPS=0.1 A never marginally misfires and the 30 s
// window correctly ties each burst to active driving. Left as-is.
const val REGEN_EPS = 0.1f
const val REGEN_WINDOW_MS = 30_000L

/**
 * True when [t] shows charge-direction current but the pack's base discharged within the
 * last [REGEN_WINDOW_MS] — i.e. regenerative braking dumping current in mid-use, not a
 * steady charge. [groupLastDischargeAt] is the base's last-seen-discharging timestamp.
 */
fun isRegen(t: Telemetry, groupLastDischargeAt: Long?, now: Long): Boolean =
    t.current > REGEN_EPS && groupLastDischargeAt != null && (now - groupLastDischargeAt) < REGEN_WINDOW_MS

/**
 * One pack on the stage, with its live regen flag. [connected] is false when the pack isn't
 * currently reachable over BLE (or has never reported) — the stage then shows it as DISCONNECTED
 * instead of a misleading 0%/low-battery state. [telemetry] is the last-known reading when present.
 */
data class StageItem(
    val telemetry: Telemetry,
    val regen: Boolean,
    val connected: Boolean = true,
    val etaFullMin: Float? = null,
)

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
