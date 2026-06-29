package dev.joely.bmsmon.ui

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import dev.joely.bmsmon.BatteryViewModel
import dev.joely.bmsmon.BmsDeviceAdminReceiver
import dev.joely.bmsmon.Screen
import dev.joely.bmsmon.ble.blePermissions
import dev.joely.bmsmon.ble.hasBlePermissions
import dev.joely.bmsmon.ui.detail.BatteryDetailScreen
import dev.joely.bmsmon.ui.history.GroupHealthScreen
import dev.joely.bmsmon.ui.history.HealthReviewScreen
import dev.joely.bmsmon.ui.history.SessionTimelineScreen
import dev.joely.bmsmon.ui.home.HomeScreen
import androidx.activity.compose.BackHandler
import dev.joely.bmsmon.ui.scan.ScanSheet
import dev.joely.bmsmon.ui.settings.SettingsScreen
import dev.joely.bmsmon.ui.theme.Bm
import dev.joely.bmsmon.ui.theme.BmTheme

@Composable
fun App(vm: BatteryViewModel) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    LaunchedEffect(systemDark) { vm.applySystemMode(systemDark) }

    // System / gesture back navigates within the app (detail → list, settings → home) instead of
    // exiting the activity. Disabled on Home so back there still backgrounds the app as usual.
    BackHandler(enabled = state.screen != Screen.Home) {
        when (state.screen) {
            Screen.Detail -> vm.closeDetail()
            Screen.Settings -> vm.goHome()
            Screen.History -> vm.goHome()
            Screen.Review -> vm.closeReview()
            Screen.Timeline -> vm.closeTimeline()
            else -> {}
        }
    }

    // Hold the screen on (at the user's brightness) while the app is open, when enabled.
    val activity = context.findActivity()
    val window = activity?.window
    // Locking forces the screen on regardless of the setting; unlocking reverts to the setting.
    val keepOn = state.keepScreenOn || state.locked
    DisposableEffect(window, keepOn) {
        if (keepOn) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // Match the system-bar icon contrast to the resolved theme. With edge-to-edge the bars are
    // transparent and the app background shows through, so in Light mode the icons (clock, signal,
    // *battery*) must switch to dark — otherwise they're white-on-white and unreadable.
    DisposableEffect(window, state.isDark) {
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.isAppearanceLightStatusBars = !state.isDark
            controller.isAppearanceLightNavigationBars = !state.isDark
        }
        onDispose {}
    }

    // Enter/exit Android Lock Task Mode to pin the app while locked. Device-owner-aware:
    // an ordinary install gets screen pinning (escapable via the system gesture); a
    // provisioned device owner gets a true kiosk with no escape gesture.
    LaunchedEffect(activity, state.locked) {
        val act = activity ?: return@LaunchedEffect
        if (state.locked) startLockTaskCompat(act) else runCatching { act.stopLockTask() }
    }

    // Notification permission (Android 13+) is requested opportunistically for the foreground-
    // service notification — it never gates monitoring; BLE only needs the BLE permissions.
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* result ignored: monitoring runs regardless */ }
    fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.all { it }) {
            askNotificationPermission()
            vm.startMonitoring()
        }
    }

    val onMonitorToggle: () -> Unit = {
        if (state.monitoring) {
            vm.stopMonitoring()
        } else if (hasBlePermissions(context)) {
            askNotificationPermission()
            vm.startMonitoring()
        } else {
            permLauncher.launch(blePermissions())
        }
    }

    var showScan by remember { mutableStateOf(false) }
    val scanPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result -> if (result.values.all { it }) showScan = true }
    val onAddScan: () -> Unit = {
        if (hasBlePermissions(context)) showScan = true else scanPermLauncher.launch(blePermissions())
    }

    BmTheme(dark = state.isDark, accent = state.accent, power = state.power) {
        Box(Modifier.fillMaxSize().background(Bm.colors.bg)) {
            Box(Modifier.fillMaxSize().systemBarsPadding()) {
                when (state.screen) {
                    Screen.Home -> HomeScreen(
                        state = state,
                        onCycleAppearance = vm::cycleAppearance,
                        onSettings = vm::goSettings,
                        onHistory = vm::goHistory,
                        onToggleMonitoring = onMonitorToggle,
                        onSetSort = vm::setSort,
                        onToggleFilter = vm::toggleFilter,
                        onSetFilterBase = vm::setFilterBase,
                        onPinStage = vm::pinStage,
                        onDisconnect = vm::disconnectBattery,
                        onReconnect = vm::reconnectBattery,
                        onDisconnectAll = vm::disconnectAll,
                        onReconnectAll = vm::reconnectAll,
                        onAcknowledge = vm::acknowledgeAlert,
                        onAddScan = onAddScan,
                        onOpenDetail = vm::openDetail,
                        onRemove = vm::removeBattery,
                        onRename = vm::renameBattery,
                        onSetGroup = vm::setBatteryGroup,
                        onCreateGroup = vm::createGroupForBattery,
                        onRenameGroup = vm::renameGroup,
                        onPinSingle = { addr -> vm.pinStage(dev.joely.bmsmon.model.StageTarget.Single(addr)) },
                        onHomePageChanged = vm::setHomePage,
                        locked = state.locked,
                        onToggleLock = { vm.setLocked(!state.locked) },
                    )
                    Screen.History -> {
                        val packs by androidx.compose.runtime.produceState<List<dev.joely.bmsmon.data.PackHealth>?>(null, vm) {
                            value = vm.loadFleetHealth()
                        }
                        val p = packs
                        if (p == null) HistoryLoading() else GroupHealthScreen(packs = p, onBack = vm::goHome)
                    }
                    Screen.Review -> {
                        val addr = state.reviewAddress
                        val pack by androidx.compose.runtime.produceState<dev.joely.bmsmon.data.PackHealth?>(null, addr) {
                            value = addr?.let { vm.loadPackHealth(it) }
                        }
                        val p = pack
                        if (p == null) HistoryLoading()
                        else HealthReviewScreen(pack = p, onBack = vm::closeReview, onOpenTimeline = vm::openTimeline)
                    }
                    Screen.Timeline -> {
                        val sid = state.timelineSession
                        val data by androidx.compose.runtime.produceState<Triple<String, dev.joely.bmsmon.data.db.SessionEntity, List<dev.joely.bmsmon.data.TimelineBucket>>?>(null, sid) {
                            value = sid?.let { vm.loadTimeline(it) }
                        }
                        val d = data
                        if (d == null) HistoryLoading()
                        else SessionTimelineScreen(alias = d.first, session = d.second, buckets = d.third, onBack = vm::closeTimeline)
                    }
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
                        onSetAppearance = vm::setAppearance,
                        onSetAutoLux = vm::setAutoLuxThreshold,
                    )
                    Screen.Detail -> {
                        val addr = state.detailAddress
                        val sessionsFlow = remember(addr) {
                            if (addr != null) vm.sessionsFor(addr) else kotlinx.coroutines.flow.flowOf(emptyList())
                        }
                        val sessions by sessionsFlow.collectAsState(initial = emptyList())
                        BatteryDetailScreen(state = state, sessions = sessions, onBack = vm::closeDetail,
                            onOpenReview = { addr?.let(vm::openReview) })
                    }
                }
            }
            if (showScan) {
                ScanSheet(
                    roster = state.roster,
                    onAdd = { address, name -> vm.addBattery(address, name) },
                    onDismiss = { showScan = false },
                )
            }
        }
    }
}

/** Full-screen placeholder while a history surface computes its derived data off Room. */
@Composable
private fun HistoryLoading() {
    Box(Modifier.fillMaxSize().background(Bm.colors.bg), contentAlignment = androidx.compose.ui.Alignment.Center) {
        androidx.compose.material3.Text("Loading…", color = Bm.colors.text3, fontSize = 13.sp)
    }
}

/** Walk the context chain to the hosting Activity (for window-level flags). */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Enter lock-task mode; if we're the device owner, allowlist ourselves first for true kiosk. */
private fun startLockTaskCompat(activity: Activity) {
    val dpm = activity.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    if (dpm.isDeviceOwnerApp(activity.packageName)) {
        val admin = ComponentName(activity, BmsDeviceAdminReceiver::class.java)
        runCatching { dpm.setLockTaskPackages(admin, arrayOf(activity.packageName)) }
    }
    // startLockTask throws if another task already holds lock-task mode — never crash the app.
    runCatching { activity.startLockTask() }
}
