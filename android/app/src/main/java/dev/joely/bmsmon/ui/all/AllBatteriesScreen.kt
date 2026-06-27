package dev.joely.bmsmon.ui.all

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.joely.bmsmon.FilterKey
import dev.joely.bmsmon.SortKey
import dev.joely.bmsmon.UiState
import dev.joely.bmsmon.model.ALL_GROUPS
import dev.joely.bmsmon.model.BatteryGroup
import dev.joely.bmsmon.model.BatteryState
import dev.joely.bmsmon.model.BatteryStatus
import dev.joely.bmsmon.model.BmsTarget
import dev.joely.bmsmon.model.Telemetry
import dev.joely.bmsmon.ui.theme.Bm
import dev.joely.bmsmon.ui.theme.MonoFont
import kotlin.math.roundToInt

private data class Row(
    val group: BatteryGroup,
    val target: BmsTarget,
    val status: BatteryStatus?,
) {
    val tele: Telemetry? get() = status?.telemetry
    val reachable: Boolean get() = status?.reachable == true
}

private fun activityRank(t: Telemetry?): Int = when (t?.state) {
    BatteryState.Discharging -> 0
    BatteryState.Charging -> 1
    BatteryState.Idle -> 2
    else -> 3
}

@Composable
fun AllBatteriesScreen(
    state: UiState,
    onSetSort: (SortKey) -> Unit,
    onToggleFilter: (FilterKey) -> Unit,
    onSetFilterBase: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Bm.colors
    var rows = ALL_GROUPS.flatMap { g -> g.targets.map { t -> Row(g, t, state.fleet[t.address]) } }

    // filters (AND)
    if (FilterKey.ReachableOnly in state.filters) rows = rows.filter { it.reachable }
    if (FilterKey.ActiveOnly in state.filters) rows = rows.filter { activityRank(it.tele) <= 1 }
    if (FilterKey.ByBase in state.filters) rows = rows.filter { it.group.id == state.filterBaseId }
    if (FilterKey.DailyDriverOnly in state.filters) rows = rows.filter { it.group.id == state.dailyDriverId }

    rows = when (state.sortKey) {
        SortKey.Activity -> rows.sortedWith(compareBy({ activityRank(it.tele) }, { -(it.tele?.soc ?: -1f) }))
        SortKey.Soc -> rows.sortedByDescending { it.tele?.soc ?: -1f }
        SortKey.Base -> rows // already in base/year order
    }

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text("All Batteries", color = c.text, fontSize = 21.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))

        // Sort chips
        ChipRow("Sort") {
            Chip("Activity", state.sortKey == SortKey.Activity) { onSetSort(SortKey.Activity) }
            Chip("SOC", state.sortKey == SortKey.Soc) { onSetSort(SortKey.Soc) }
            Chip("Base", state.sortKey == SortKey.Base) { onSetSort(SortKey.Base) }
        }
        // Filter chips
        ChipRow("Filter") {
            Chip("Reachable", FilterKey.ReachableOnly in state.filters) { onToggleFilter(FilterKey.ReachableOnly) }
            Chip("Active", FilterKey.ActiveOnly in state.filters) { onToggleFilter(FilterKey.ActiveOnly) }
            Chip("Daily driver", FilterKey.DailyDriverOnly in state.filters) { onToggleFilter(FilterKey.DailyDriverOnly) }
            Chip("By base", FilterKey.ByBase in state.filters) { onToggleFilter(FilterKey.ByBase) }
        }
        if (FilterKey.ByBase in state.filters) {
            ChipRow("Base") {
                ALL_GROUPS.forEach { g ->
                    Chip(g.label, state.filterBaseId == g.id) { onSetFilterBase(g.id) }
                }
            }
        }

        LazyColumn(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            items(rows, key = { it.target.address }) { row ->
                BatteryRow(
                    row = row,
                    isStage = row.group.id == state.stageGroupId && state.monitoring,
                    isDailyDriver = row.group.id == state.dailyDriverId,
                    monitoring = state.monitoring,
                )
            }
        }
    }
}

@Composable
private fun ChipRow(label: String, content: @Composable () -> Unit) {
    val c = Bm.colors
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = c.text3, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(end = 10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = Bm.colors
    val border = if (selected) Bm.accent else c.border
    val bg = if (selected) Bm.accent.copy(alpha = 0.14f) else Color.Transparent
    Box(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 7.dp),
    ) {
        Text(label, color = if (selected) Bm.accent else c.text2, fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun BatteryRow(row: Row, isStage: Boolean, isDailyDriver: Boolean, monitoring: Boolean) {
    val c = Bm.colors
    val t = row.tele
    val reachable = row.reachable || !monitoring  // in demo mode, don't grey everything
    val dim = monitoring && !row.reachable

    val (stateLabel, stateColor) = when {
        dim -> "Out of range" to c.text3
        t?.state == BatteryState.Discharging -> "Discharging" to Bm.power
        t?.state == BatteryState.Charging -> "Charging" to Bm.accent
        t?.state == BatteryState.Idle -> "Idle" to c.text2
        t == null && monitoring -> "Connecting…" to c.text3
        else -> "—" to c.text3
    }
    val borderColor = if (isStage) Bm.accent else c.border

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isStage) Bm.accent.copy(alpha = 0.08f) else c.card2)
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .padding(horizontal = 13.dp, vertical = 11.dp)
            .alpha(if (dim) 0.45f else 1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.target.name, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                if (isDailyDriver) {
                    Icon(Icons.Filled.Star, "Daily driver", Modifier.padding(start = 6.dp).size(14.dp), tint = Bm.accent)
                }
                if (isStage) {
                    Text("STAGE", color = Bm.accent, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp, modifier = Modifier.padding(start = 8.dp))
                }
            }
            Row(Modifier.padding(top = 3.dp)) {
                Text(stateLabel, color = stateColor, fontSize = 12.sp)
                if (t != null && !dim) {
                    Text(" · %.1f V".format(t.voltage), color = c.text3, fontFamily = MonoFont, fontSize = 12.sp)
                }
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                if (t != null && !dim) "${t.soc.roundToInt()}%" else "—",
                color = if (dim) c.text3 else Bm.accent,
                fontFamily = MonoFont, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
            )
            if (t != null && !dim) {
                Text("${t.capacityAh.roundToInt()} Ah", color = c.text2, fontFamily = MonoFont, fontSize = 11.sp)
            }
        }
    }
}
