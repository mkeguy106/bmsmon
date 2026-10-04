package dev.joely.bmsmon.model

/*
 * Engine-side stage control (T1.2, 2026-10-02 review: BLE-14 = UI-20, UI-29).
 *
 * Stage resolution — the low-pack seize included — used to run only in the Activity-scoped
 * ViewModel, so with no ViewModel (a sticky or boot restore) the stage froze and nothing seized,
 * and the engine re-evaluated fleet-wide alerts only when a (possibly dark) stage pack reported.
 * These pure pieces let the process-lifetime MonitorEngine own all of it: it runs
 * [engineDecision] on every BLE event and tick; the ViewModel pushes [StageConfig] and mirrors
 * the result. [resolveStage] itself (Fleet.kt) is unchanged — the seize semantics are byte for
 * byte the old ones; only its candidates are narrowed (roster members, session readings).
 */

/** Stage inputs that aren't telemetry: pushed by the ViewModel on every change, or rebuilt from
 *  the persisted settings by the headless restore (MonitorRestore.restorePlan). */
data class StageConfig(
    val dailyDriverId: String = DEFAULT_GROUP_ID,
    val dynamicEnabled: Boolean = true,
    val manualStage: StageTarget? = null,
    val manualPinnedAt: Long = 0L,
    val holdMs: Long = DEFAULT_STAGE_HOLD_MIN * 60_000L,
    /** [seizeThresholdFor]; null disables the seize. */
    val seizeThreshold: Int? = null,
)

data class StageResolution(val target: StageTarget, val pinned: Boolean)

/** The seize threshold (unchanged rule): the highest enabled ladder rung, only when alerts AND the
 *  "Pull low packs to stage" toggle are on. Shared by the ViewModel and the headless restore. */
fun seizeThresholdFor(alertsOn: Boolean, seizeLowToStage: Boolean, enabledThresholds: Set<Int>): Int? =
    if (alertsOn && seizeLowToStage && enabledThresholds.isNotEmpty()) enabledThresholds.max() else null

/** The target's addresses that are actually in the roster (uppercased). A Single for a removed
 *  battery, or a base whose packs were all regrouped, has none. */
fun StageTarget.memberAddresses(roster: Roster): Set<String> =
    addresses(roster).filter { roster.batteryAt(it) != null }.map { it.uppercase() }.toSet()

/** The stage's BLE address set: roster members minus user-disconnected packs (case-insensitive). */
fun stageAddrsFor(target: StageTarget, roster: Roster, disabled: Set<String>): Set<String> {
    val off = disabled.map { it.uppercase() }.toSet()
    return target.memberAddresses(roster) - off
}

/** The packs BLE should hold links to: every roster member the user hasn't disconnected
 *  (uppercased, case-insensitive). */
fun wantedAddrs(roster: Roster, disabled: Set<String>): Set<String> {
    val off = disabled.map { it.uppercase() }.toSet()
    return roster.allTargets().map { it.address.uppercase() }.filter { it !in off }.toSet()
}

/** BLE-27: whether BLE has any link to want — false after "Disconnect all" or with an empty roster.
 *  With nothing wanted, nothing is polled: the service releases its wakelock and GPS stops. */
fun hasDesiredLinks(roster: Roster, disabled: Set<String>): Boolean = wantedAddrs(roster, disabled).isNotEmpty()

/** UI-29 fallback for a target with no members: the daily-driver base, else the first base with
 *  members, else the first battery as a single. Null only for an empty roster. */
fun fallbackStage(roster: Roster, dailyDriverId: String): StageTarget? {
    roster.groupById(dailyDriverId)?.takeIf { it.targets.isNotEmpty() }?.let { return StageTarget.Base(it.id) }
    roster.groupViews().firstOrNull { it.targets.isNotEmpty() }?.let { return StageTarget.Base(it.id) }
    return roster.batteries.firstOrNull()?.let { StageTarget.Single(it.address) }
}

/** Drop fleet entries for batteries no longer in the roster (UI-29: no ghosts). */
fun pruneToRoster(fleet: Map<String, BatteryStatus>, roster: Roster): Map<String, BatteryStatus> =
    fleet.filterKeys { roster.batteryAt(it) != null }

/**
 * Resolve the stage the engine way: [resolveStage] over roster members only (a removed pack still
 * reachable in the moment before its link drops must never stage itself), then the empty-stage
 * fallback, then the pin flag (same expression the ViewModel used). [fleet] should be the
 * freshness decision view ([decisionView]).
 */
fun resolveEngineStage(
    roster: Roster,
    fleet: Map<String, BatteryStatus>,
    lastDischargeAt: Map<String, Long>,
    cfg: StageConfig,
    current: StageTarget,
    nowMs: Long,
): StageResolution {
    val resolved = resolveStage(
        StageInputs(
            fleet = pruneToRoster(fleet, roster),
            dailyDriverId = cfg.dailyDriverId,
            dynamicEnabled = cfg.dynamicEnabled,
            manualStage = cfg.manualStage,
            manualPinnedAt = cfg.manualPinnedAt,
            lastDischargeAt = lastDischargeAt,
            holdMs = cfg.holdMs,
            current = current,
            now = nowMs,
            groups = roster.groupViews(),
            seizeThreshold = cfg.seizeThreshold,
        ),
    )
    val target = if (resolved.memberAddresses(roster).isEmpty()) {
        fallbackStage(roster, cfg.dailyDriverId) ?: resolved
    } else {
        resolved
    }
    val pinned = cfg.manualStage != null && target == cfg.manualStage &&
        (!cfg.dynamicEnabled || nowMs - cfg.manualPinnedAt < PIN_HOLD_MS)
    return StageResolution(target, pinned)
}

/** One run of the engine's decision step. [capacity] is null until an AlertConfig is pushed. */
data class EngineDecision(
    val stage: StageResolution,
    val stageAddrs: Set<String>,
    val capacity: Map<String, AlertEval>?,
    val chargeAt: Map<String, Long>,
    /** The roster-pruned freshness decision view the decisions were made on. */
    val view: Map<String, BatteryStatus>,
)

/**
 * The whole decision step, pure. MonitorEngine runs it on EVERY BLE event (frame, decode failure,
 * reachability change), every config push and a periodic tick — never only on stage-pack events
 * (BLE-14) — and applies the result: publish the stage, push the BLE stage set, notify per pack.
 */
fun engineDecision(
    roster: Roster,
    fleet: Map<String, BatteryStatus>,
    nowElapsedMs: Long,
    nowMs: Long,
    lastDischargeAt: Map<String, Long>,
    stageCfg: StageConfig,
    current: StageTarget,
    disabled: Set<String>,
    alertCfg: AlertConfig?,
    chargeAt: Map<String, Long>,
    /** Packs inside their regen window: never "charging" for the notifier ([fleetCapacityEvals]). */
    regenAddrs: Set<String> = emptySet(),
): EngineDecision {
    val view = decisionView(pruneToRoster(fleet, roster), nowElapsedMs)
    val stage = resolveEngineStage(roster, view, lastDischargeAt, stageCfg, current, nowMs)
    val fc = alertCfg?.let { fleetCapacityEvals(view, it, chargeAt, nowMs, regenAddrs) }
    return EngineDecision(
        stage = stage,
        stageAddrs = stageAddrsFor(stage.target, roster, disabled),
        capacity = fc?.evals,
        chargeAt = fc?.chargeAt ?: chargeAt,
        view = view,
    )
}
