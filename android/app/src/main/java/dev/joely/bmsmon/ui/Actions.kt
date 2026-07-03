package dev.joely.bmsmon.ui

import androidx.compose.ui.graphics.Color
import dev.joely.bmsmon.Appearance
import dev.joely.bmsmon.FilterKey
import dev.joely.bmsmon.SortKey
import dev.joely.bmsmon.model.GaugeSide
import dev.joely.bmsmon.model.TempThresholds

/**
 * Cohesive callback holders (UI-13e): `SettingsScreen` grew a ~32-lambda signature and
 * `HomeScreen` a ~22-lambda one — pure noise at every call site. Each holder groups one screen
 * area's actions; all are immutable data classes constructed in `App.kt` and passed down whole.
 * Purely mechanical — every lambda keeps its original name and wiring.
 */

/** Home top bar / global chrome. */
data class TopBarActions(
    val onCycleAppearance: () -> Unit,
    val onSettings: () -> Unit,
    val onHistory: () -> Unit,
    val onToggleMonitoring: () -> Unit,
    val onToggleLock: () -> Unit,
)

/** All-Batteries list: sort/filter, stage pinning, link control, drill-down, add. */
data class FleetActions(
    val onSetSort: (SortKey) -> Unit,
    val onToggleFilter: (FilterKey) -> Unit,
    val onSetFilterBase: (String) -> Unit,
    val onPinBase: (String) -> Unit,
    val onPinSingle: (String) -> Unit,
    val onDisconnect: (String) -> Unit,
    val onReconnect: (String) -> Unit,
    val onDisconnectAll: () -> Unit,
    val onReconnectAll: () -> Unit,
    val onOpenDetail: (String) -> Unit,
    val onAddScan: () -> Unit,
)

/** Roster editing (remove/rename/regroup). */
data class RosterActions(
    val onRemove: (String) -> Unit,
    val onRename: (String, String) -> Unit,
    val onSetGroup: (String, String?) -> Unit,
    val onCreateGroup: (String, String) -> Unit,
    val onRenameGroup: (String, String) -> Unit,
)

/** Settings › Monitoring & stage (+ Battery groups). */
data class MonitoringActions(
    val onToggleMonitoring: () -> Unit,
    val onAddScan: () -> Unit,
    val onSetDailyDriver: (String) -> Unit,
    val onSetDynamicStage: (Boolean) -> Unit,
    val onSetStageHold: (Int) -> Unit,
)

/** Settings › Alerts (low-battery). */
data class AlertActions(
    val onSetAlertsOn: (Boolean) -> Unit,
    val onToggleThreshold: (Int) -> Unit,
    val onSetCriticalThreshold: (Int) -> Unit,
    val onSetSeizeLowToStage: (Boolean) -> Unit,
    val onResetAlerts: () -> Unit,
)

/** Settings › Temperature. */
data class TempActions(
    val onSetTempAlertsEnabled: (Boolean) -> Unit,
    val onSetShowTempGauge: (Boolean) -> Unit,
    val onSetTempGaugeSide: (GaugeSide) -> Unit,
    val onSetTempThresholds: (String, TempThresholds) -> Unit,
    val onResetTempThresholds: (String) -> Unit,
    val onSetCloudSyncAlerts: (Boolean) -> Unit,
    val onToggleTempUnit: () -> Unit,
)

/** Settings › Appearance & color. */
data class AppearanceActions(
    val onSetAppearance: (Appearance) -> Unit,
    val onSetAutoLux: (Float) -> Unit,
    val onSetAccent: (Color) -> Unit,
    val onSetPower: (Color) -> Unit,
    val onResetColors: () -> Unit,
)

/** Settings › Display & units. */
data class DisplayActions(
    val onSetTempFahrenheit: (Boolean) -> Unit,
    val onSetKeepScreenOn: (Boolean) -> Unit,
)

/** Settings › Lock screen. */
data class LockActions(
    val onSetLockShowTime: (Boolean) -> Unit,
    val onSetLockShowWifi: (Boolean) -> Unit,
    val onSetLockShowBattery: (Boolean) -> Unit,
)

/** Settings › Data & logging. */
data class DataActions(
    val onSetLogging: (Boolean) -> Unit,
    val onClearLog: () -> Unit,
)

/** Settings › Cloud sync. */
data class CloudActions(
    val onEnroll: (String, String) -> Unit,
    val onSetCloudEnabled: (Boolean) -> Unit,
    val onForget: () -> Unit,
    val onSetGpsEnabled: (Boolean) -> Unit,
)
