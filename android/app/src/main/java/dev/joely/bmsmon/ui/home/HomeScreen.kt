package dev.joely.bmsmon.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.joely.bmsmon.FilterKey
import dev.joely.bmsmon.SortKey
import dev.joely.bmsmon.UiState
import dev.joely.bmsmon.model.GroupActivity
import dev.joely.bmsmon.model.StageTarget
import dev.joely.bmsmon.ui.all.AllBatteriesScreen
import dev.joely.bmsmon.ui.theme.Bm
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    state: UiState,
    onToggleMode: () -> Unit,
    onSettings: () -> Unit,
    onToggleMonitoring: () -> Unit,
    onSetSort: (SortKey) -> Unit,
    onToggleFilter: (FilterKey) -> Unit,
    onSetFilterBase: (String) -> Unit,
    onPinStage: (StageTarget) -> Unit,
    onDisconnect: (String) -> Unit,
    onReconnect: (String) -> Unit,
    onDisconnectAll: () -> Unit,
) {
    val c = Bm.colors
    val pager = rememberPagerState(initialPage = 1) { 2 }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().background(c.bg)) {
        TopBar(state, onToggleMode, onSettings, onToggleMonitoring)
        PageDots(current = pager.currentPage)
        HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
            when (page) {
                0 -> AllBatteriesScreen(
                    state = state,
                    onSetSort = onSetSort,
                    onToggleFilter = onToggleFilter,
                    onSetFilterBase = onSetFilterBase,
                    onPinBase = { groupId ->
                        onPinStage(StageTarget.Base(groupId))
                        scope.launch { pager.animateScrollToPage(1) }
                    },
                    onDisconnect = onDisconnect,
                    onReconnect = onReconnect,
                    onDisconnectAll = onDisconnectAll,
                    modifier = Modifier.padding(top = 6.dp),
                )
                else -> StageScreen(state.stageBatteries())
            }
        }
    }
}

@Composable
private fun TopBar(
    state: UiState,
    onToggleMode: () -> Unit,
    onSettings: () -> Unit,
    onToggleMonitoring: () -> Unit,
) {
    val c = Bm.colors
    val (label, labelColor, showPin) = when {
        !state.monitoring -> Triple("DEMO DATA", c.text3, false)
        state.pinned -> Triple("${state.stageLabel} · PINNED", Bm.accent, true)
        !state.dynamicStage -> Triple("${state.stageLabel} · MANUAL", c.text2, false)
        state.stageActivity == GroupActivity.Discharging -> Triple("${state.stageLabel} · DISCHARGING", Bm.power, false)
        state.stageActivity == GroupActivity.Charging -> Triple("${state.stageLabel} · CHARGING", Bm.accent, false)
        state.stageActivity == GroupActivity.Idle -> Triple("${state.stageLabel} · IDLE", c.text2, false)
        else -> Triple("${state.stageLabel} · …", c.text3, false)
    }
    Row(
        Modifier.fillMaxWidth().background(c.bg).padding(horizontal = 18.dp, vertical = 14.dp),
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
            Box(Modifier.size(40.dp).clickable(onClick = onToggleMode), contentAlignment = Alignment.Center) {
                Icon(if (state.isDark) Icons.Filled.DarkMode else Icons.Filled.LightMode,
                    "Toggle theme", Modifier.size(21.dp), tint = c.icon)
            }
            Box(Modifier.size(40.dp).clickable(onClick = onSettings), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Settings, "Settings", Modifier.size(22.dp), tint = c.icon)
            }
        }
    }
}

@Composable
private fun PageDots(current: Int) {
    val c = Bm.colors
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.Center) {
        for (i in 0..1) {
            Box(
                Modifier.padding(horizontal = 4.dp).size(if (i == current) 7.dp else 6.dp)
                    .clip(CircleShape).background(if (i == current) Bm.accent else c.border),
            )
        }
    }
}
