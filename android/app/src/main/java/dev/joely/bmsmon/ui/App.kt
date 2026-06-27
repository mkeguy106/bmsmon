package dev.joely.bmsmon.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
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

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.all { it }) vm.startConnect()
    }

    val onConnectToggle: () -> Unit = {
        if (state.connected) {
            vm.stopConnect()
        } else if (hasBlePermissions(context)) {
            vm.startConnect()
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
                    )
                    Screen.Settings -> SettingsScreen(
                        state = state,
                        onBack = vm::goHome,
                        onSetBms1 = vm::setBms1,
                        onSetBms2 = vm::setBms2,
                        onToggleConnect = onConnectToggle,
                        onSetAccent = vm::setAccent,
                        onSetPower = vm::setPower,
                        onSetMode = vm::setMode,
                    )
                }
            }
        }
    }
}
