package dev.joely.bmsmon.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.joely.bmsmon.BatteryViewModel
import dev.joely.bmsmon.Screen
import dev.joely.bmsmon.ble.blePermissions
import dev.joely.bmsmon.ble.hasBlePermissions
import dev.joely.bmsmon.ui.home.HomeScreen
import dev.joely.bmsmon.ui.settings.SettingsScreen
import dev.joely.bmsmon.ui.theme.Bm
import dev.joely.bmsmon.ui.theme.BmTheme

@Composable
fun App(vm: BatteryViewModel) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    LaunchedEffect(systemDark) { vm.applySystemMode(systemDark) }

    // Hold the screen on (at the user's brightness) while the app is open, when enabled.
    val window = context.findActivity()?.window
    DisposableEffect(window, state.keepScreenOn) {
        if (state.keepScreenOn) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.all { it }) vm.startMonitoring()
    }

    val onMonitorToggle: () -> Unit = {
        if (state.monitoring) {
            vm.stopMonitoring()
        } else if (hasBlePermissions(context)) {
            vm.startMonitoring()
        } else {
            permLauncher.launch(blePermissions())
        }
    }

    BmTheme(dark = state.isDark, accent = state.accent, power = state.power) {
        Box(Modifier.fillMaxSize().background(Bm.colors.bg)) {
            Box(Modifier.fillMaxSize().systemBarsPadding()) {
                when (state.screen) {
                    Screen.Home -> HomeScreen(
                        state = state,
                        onToggleMode = vm::toggleMode,
                        onSettings = vm::goSettings,
                        onToggleMonitoring = onMonitorToggle,
                        onSetSort = vm::setSort,
                        onToggleFilter = vm::toggleFilter,
                        onSetFilterBase = vm::setFilterBase,
                        onPinStage = vm::pinStage,
                        onDisconnect = vm::disconnectBattery,
                        onReconnect = vm::reconnectBattery,
                        onDisconnectAll = vm::disconnectAll,
                        onAcknowledge = vm::acknowledgeAlert,
                    )
                    Screen.Settings -> SettingsScreen(
                        state = state,
                        onBack = vm::goHome,
                        onToggleMonitoring = onMonitorToggle,
                        onSetDailyDriver = vm::setDailyDriver,
                        onSetDynamicStage = vm::setDynamicStage,
                        onSetStageHold = vm::setStageHold,
                        onSetAlertsOn = vm::setAlertsOn,
                        onToggleThreshold = vm::toggleThreshold,
                        onSetKeepScreenOn = vm::setKeepScreenOn,
                        onSetTempFahrenheit = vm::setTempFahrenheit,
                        onSetLogging = vm::setLogging,
                        onClearLog = vm::clearLog,
                        onSetAccent = vm::setAccent,
                        onSetPower = vm::setPower,
                        onSetMode = vm::setMode,
                    )
                }
            }
        }
    }
}

/** Walk the context chain to the hosting Activity (for window-level flags). */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
