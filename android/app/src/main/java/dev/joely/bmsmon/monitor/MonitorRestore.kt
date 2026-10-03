package dev.joely.bmsmon.monitor

import dev.joely.bmsmon.DEFAULT_CRITICAL_THRESHOLD
import dev.joely.bmsmon.DEFAULT_THRESHOLDS
import dev.joely.bmsmon.data.Persisted
import dev.joely.bmsmon.model.AlertConfig
import dev.joely.bmsmon.model.BatteryStatus
import dev.joely.bmsmon.model.DEFAULT_GROUP_ID
import dev.joely.bmsmon.model.DEFAULT_ROSTER
import dev.joely.bmsmon.model.DEFAULT_STAGE_HOLD_MIN
import dev.joely.bmsmon.model.Roster
import dev.joely.bmsmon.model.StageConfig
import dev.joely.bmsmon.model.StageTarget
import dev.joely.bmsmon.model.TempThresholds
import dev.joely.bmsmon.model.TempUnit
import dev.joely.bmsmon.model.groupById
import dev.joely.bmsmon.model.groupViews
import dev.joely.bmsmon.model.seizeThresholdFor
import dev.joely.bmsmon.model.targetFor
import kotlin.math.roundToInt

/**
 * Everything the engine needs to resume monitoring headlessly after a sticky service restart
 * (BLE-11): the OS killed the process while monitoring was on, restarted [MonitoringService]
 * with a null intent, and there is no ViewModel to push roster/stage/alert state down.
 */
data class RestorePlan(
    val roster: Roster,
    val seed: Map<String, BatteryStatus>,
    val logging: Boolean,
    val disabled: Set<String>,
    /** The validated persisted stage — the engine's starting point; it resolves from there (T1.2). */
    val stage: StageTarget,
    /** The stage inputs the ViewModel would push (no manual pin: pins never survive a restart). */
    val stageConfig: StageConfig,
    val alertConfig: AlertConfig,
    val tempAlertsEnabled: Boolean,
    val tempThresholdsByProfile: Map<String, TempThresholds>,
    val tempUnit: TempUnit,
    val gpsActive: Boolean,
    val gpsPauseParked: Boolean,
)

/**
 * Pure mapping from the persisted settings to a headless engine start. Mirrors what
 * BatteryViewModel does on launch (roster fallback, last-stage validation, daily-driver
 * fallback, GPS gating) and rebuilds the [StageConfig] the ViewModel would push — daily driver,
 * dynamic stage, stage hold and the low-pack seize threshold (same [seizeThresholdFor] rule) —
 * so the engine resolves the stage, seize included, exactly as it would in the foreground (T1.2).
 * A sticky restart therefore restores the same monitoring the user had. Returns null when
 * monitoring was off at the time of death — nothing to restore.
 */
fun restorePlan(p: Persisted): RestorePlan? {
    if (!p.monitoring) return null
    val roster = p.roster ?: DEFAULT_ROSTER
    val disabled = (p.disabledAddrs ?: emptySet()).map { it.uppercase() }.toSet()
    // Same daily-driver reducer as the ViewModel's launch restore (its default is DEFAULT_GROUP_ID).
    val dailyDriverId = roster.groupById(p.dailyDriverId ?: DEFAULT_GROUP_ID)?.id
        ?: roster.groupViews().firstOrNull()?.id
        ?: DEFAULT_GROUP_ID
    // Same validation as the ViewModel's launch restore: a persisted stage that no longer exists
    // in the roster falls back to the daily-driver base (then the first group).
    val stageTarget = p.lastStage?.let { ls ->
        when (ls) {
            is StageTarget.Base -> ls.takeIf { roster.groupById(ls.groupId) != null }
            is StageTarget.Single -> ls.takeIf { roster.targetFor(ls.address) != null }
        }
    } ?: StageTarget.Base(dailyDriverId)
    val enabled = p.enabledThresholds ?: DEFAULT_THRESHOLDS.toSet()
    return RestorePlan(
        roster = roster,
        // Last-known readings render dimmed until the first live poll, as on a normal launch.
        seed = p.lastTelemetry.mapValues { (_, t) -> BatteryStatus(telemetry = t, reachable = false) },
        logging = p.logging,
        disabled = disabled,
        stage = stageTarget,
        stageConfig = StageConfig(
            dailyDriverId = dailyDriverId,
            dynamicEnabled = p.dynamicStage ?: true,
            holdMs = (p.stageHoldMinutes ?: DEFAULT_STAGE_HOLD_MIN) * 60_000L,
            seizeThreshold = seizeThresholdFor(p.alertsOn, p.seizeLowToStage, enabled),
        ),
        alertConfig = AlertConfig(
            alertsOn = p.alertsOn,
            enabledThresholds = enabled,
            criticalThreshold = p.criticalThreshold ?: DEFAULT_CRITICAL_THRESHOLD,
        ),
        tempAlertsEnabled = p.tempAlertsEnabled,
        tempThresholdsByProfile = p.tempThresholdsByProfile,
        tempUnit = if (p.tempFahrenheit) TempUnit.F else TempUnit.C,
        // Effective GPS-active, same gate as the ViewModel: gpsEnabled (default = cloudEnabled)
        // AND enrolled AND cloud sync on. `monitoring` is implied (we only restore when on).
        gpsActive = (p.gpsEnabled ?: p.cloudEnabled) && p.enrolled && p.cloudEnabled,
        // Battery saver: a headless restart is a fresh process, so without this the engine would
        // run on its field default (true) and keep pausing GNSS for a user who turned that off.
        gpsPauseParked = p.gpsPauseParked,
    )
}

/**
 * The ongoing notification's content text for one engine state. Pure so the
 * de-churn contract (BLE-11: only re-post when this string actually changes —
 * `distinctUntilChanged` in [MonitoringService]) is unit-testable.
 */
fun monitoringNotificationText(st: MonitorState): String {
    val reachable = st.fleet.values.filter { it.reachable && it.telemetry != null }
    return when {
        reachable.isEmpty() -> "Connecting…"
        else -> {
            val low = reachable.minOf { it.telemetry!!.soc }.roundToInt()
            val n = reachable.size
            "$n ${if (n == 1) "pack" else "packs"} connected · lowest $low%"
        }
    }
}
