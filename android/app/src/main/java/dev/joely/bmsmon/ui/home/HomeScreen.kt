package dev.joely.bmsmon.ui.home

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.joely.bmsmon.FilterKey
import dev.joely.bmsmon.SortKey
import dev.joely.bmsmon.StageAlert
import dev.joely.bmsmon.Appearance
import dev.joely.bmsmon.UiState
import dev.joely.bmsmon.model.GroupActivity
import dev.joely.bmsmon.model.StageTarget
import dev.joely.bmsmon.ui.all.AllBatteriesScreen
import dev.joely.bmsmon.ui.theme.AlertCritical
import dev.joely.bmsmon.ui.theme.AlertWarn
import dev.joely.bmsmon.ui.theme.Bm
import dev.joely.bmsmon.ui.theme.RegenGreen
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    state: UiState,
    onCycleAppearance: () -> Unit,
    onSettings: () -> Unit,
    onToggleMonitoring: () -> Unit,
    onSetSort: (SortKey) -> Unit,
    onToggleFilter: (FilterKey) -> Unit,
    onSetFilterBase: (String) -> Unit,
    onPinStage: (StageTarget) -> Unit,
    onDisconnect: (String) -> Unit,
    onReconnect: (String) -> Unit,
    onDisconnectAll: () -> Unit,
    onAcknowledge: () -> Unit,
) {
    val c = Bm.colors
    // Page 0 = stage (main); page 1 = all batteries — swipe LEFT from the stage to reach it.
    val pager = rememberPagerState(initialPage = 0) { 2 }
    val scope = rememberCoroutineScope()
    val alert = state.stageAlert()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(c.bg)) {
            TopBar(state, pager.currentPage, onCycleAppearance, onSettings, onToggleMonitoring)
            HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
                when (page) {
                    0 -> StageScreen(state.stageItems(), tempInF = state.tempFahrenheit)
                    else -> AllBatteriesScreen(
                        state = state,
                        onSetSort = onSetSort,
                        onToggleFilter = onToggleFilter,
                        onSetFilterBase = onSetFilterBase,
                        onPinBase = { groupId ->
                            onPinStage(StageTarget.Base(groupId))
                            scope.launch { pager.animateScrollToPage(0) }
                        },
                        onDisconnect = onDisconnect,
                        onReconnect = onReconnect,
                        onDisconnectAll = onDisconnectAll,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
        // Alerts are evaluated only while the stage (page 0) is foregrounded.
        if (alert.flashing && pager.currentPage == 0) {
            DangerOverlay(alert, onAcknowledge)
        }
    }
}

/** Full-screen pulsing wash + bottom Acknowledge bar, severity-colored. */
@Composable
private fun DangerOverlay(alert: StageAlert, onAcknowledge: () -> Unit) {
    val flashColor = if (alert.critical) AlertCritical else AlertWarn
    val peak = if (alert.critical) 0.58f else 0.34f
    val duration = if (alert.critical) 1000 else 1500
    val label = if (alert.critical) "CRITICAL" else "LOW BATTERY"

    val transition = rememberInfiniteTransition(label = "danger")
    val washAlpha by transition.animateFloat(
        initialValue = 0f,
        targetValue = peak,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = duration, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "washAlpha",
    )

    // Non-interactive wash over the whole screen.
    Box(Modifier.fillMaxSize().alpha(washAlpha).background(flashColor))

    // Acknowledge bar pinned to the bottom.
    Column(
        Modifier.fillMaxSize().padding(18.dp),
        verticalArrangement = Arrangement.Bottom,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Warning, null, Modifier.size(18.dp), tint = Color.White)
            Text(
                "$label · ${alert.lowSoc}% · BELOW ${alert.activeThreshold}%",
                color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp, modifier = Modifier.padding(start = 9.dp),
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .shadow(14.dp, RoundedCornerShape(13.dp))
                .clip(RoundedCornerShape(13.dp))
                .background(flashColor)
                .border(1.dp, Color.White.copy(alpha = 0.30f), RoundedCornerShape(13.dp))
                .clickable(onClick = onAcknowledge)
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("ACKNOWLEDGE", color = Color.White, fontSize = 15.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }
    }
}

@Composable
private fun TopBar(
    state: UiState,
    currentPage: Int,
    onCycleAppearance: () -> Unit,
    onSettings: () -> Unit,
    onToggleMonitoring: () -> Unit,
) {
    val c = Bm.colors
    val (label, labelColor, showPin) = when {
        !state.monitoring -> Triple("DEMO DATA", c.text3, false)
        state.stageRegen -> Triple("${state.stageLabel} · REGEN ↻", RegenGreen, false)
        state.pinned -> Triple("${state.stageLabel} · PINNED", Bm.accent, true)
        !state.dynamicStage -> Triple("${state.stageLabel} · MANUAL", c.text2, false)
        state.stageActivity == GroupActivity.Discharging -> Triple("${state.stageLabel} · DISCHARGING", Bm.power, false)
        state.stageActivity == GroupActivity.Charging -> Triple("${state.stageLabel} · CHARGING", Bm.accent, false)
        state.stageActivity == GroupActivity.Idle -> Triple("${state.stageLabel} · IDLE", c.text2, false)
        else -> Triple("${state.stageLabel} · …", c.text3, false)
    }
    Box(Modifier.fillMaxWidth().background(c.bg).padding(horizontal = 18.dp, vertical = 12.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.clickable(onClick = onToggleMonitoring), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (state.monitoring) Icons.Filled.Bluetooth else Icons.Filled.BluetoothDisabled,
                    null, Modifier.size(18.dp),
                    tint = if (state.monitoring) Bm.accent else c.text3,
                )
                if (showPin) Icon(Icons.Filled.PushPin, null, Modifier.padding(start = 6.dp).size(13.dp), tint = Bm.accent)
                Text(label, color = labelColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.9.sp, modifier = Modifier.padding(start = 7.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clickable(onClick = onCycleAppearance), contentAlignment = Alignment.Center) {
                    when (state.appearance) {
                        Appearance.Dark -> Icon(Icons.Filled.DarkMode, "Appearance: Dark", Modifier.size(21.dp), tint = c.icon)
                        Appearance.Light -> Icon(Icons.Filled.LightMode, "Appearance: Light", Modifier.size(21.dp), tint = c.icon)
                        Appearance.System -> Text("S", color = c.icon, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Appearance.Auto -> Text("A", color = c.icon, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Box(Modifier.size(40.dp).clickable(onClick = onSettings), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Settings, "Settings", Modifier.size(22.dp), tint = c.icon)
                }
            }
        }
        // Page dots centered on the top-bar row (between the status label and the icons).
        PageDots(currentPage, Modifier.align(Alignment.Center))
    }
}

@Composable
private fun PageDots(current: Int, modifier: Modifier = Modifier) {
    val c = Bm.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        for (i in 0..1) {
            Box(
                Modifier.padding(horizontal = 4.dp).size(if (i == current) 7.dp else 6.dp)
                    .clip(CircleShape).background(if (i == current) Bm.accent else c.border),
            )
        }
    }
}
