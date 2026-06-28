package dev.joely.bmsmon.ui.all

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import dev.joely.bmsmon.model.BatteryGroup
import dev.joely.bmsmon.model.groupViews
import dev.joely.bmsmon.model.BatteryState
import dev.joely.bmsmon.model.BatteryStatus
import dev.joely.bmsmon.model.BmsTarget
import dev.joely.bmsmon.model.Telemetry
import dev.joely.bmsmon.ui.ChargingBolt
import dev.joely.bmsmon.ui.rememberBoltAlpha
import dev.joely.bmsmon.ui.theme.Bm
import dev.joely.bmsmon.ui.theme.MonoFont
import dev.joely.bmsmon.ui.theme.socSeverity
import kotlin.math.roundToInt

private data class Row(val group: BatteryGroup, val target: BmsTarget, val status: BatteryStatus?) {
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
    onPinBase: (String) -> Unit,
    onDisconnect: (String) -> Unit,
    onReconnect: (String) -> Unit,
    onDisconnectAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Bm.colors
    var rows = state.roster.groupViews().flatMap { g -> g.targets.map { t -> Row(g, t, state.fleet[t.address]) } }

    if (FilterKey.ReachableOnly in state.filters) rows = rows.filter { it.reachable }
    if (FilterKey.ActiveOnly in state.filters) rows = rows.filter { activityRank(it.tele) <= 1 }
    if (FilterKey.ByBase in state.filters) rows = rows.filter { it.group.id == state.filterBaseId }
    if (FilterKey.DailyDriverOnly in state.filters) rows = rows.filter { it.group.id == state.dailyDriverId }

    rows = when (state.sortKey) {
        SortKey.Activity -> rows.sortedWith(compareBy({ activityRank(it.tele) }, { -(it.tele?.soc ?: -1f) }))
        SortKey.Soc -> rows.sortedByDescending { it.tele?.soc ?: -1f }
        SortKey.Base -> rows
    }

    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text("All Batteries", color = c.text, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            if (state.monitoring) {
                Text("Disconnect all", color = Bm.power, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable(onClick = onDisconnectAll).padding(4.dp))
            }
        }

        ChipRow("Sort") {
            Chip("Activity", state.sortKey == SortKey.Activity) { onSetSort(SortKey.Activity) }
            Chip("SOC", state.sortKey == SortKey.Soc) { onSetSort(SortKey.Soc) }
            Chip("Base", state.sortKey == SortKey.Base) { onSetSort(SortKey.Base) }
        }
        ChipRow("Filter") {
            Chip("Reachable", FilterKey.ReachableOnly in state.filters) { onToggleFilter(FilterKey.ReachableOnly) }
            Chip("Active", FilterKey.ActiveOnly in state.filters) { onToggleFilter(FilterKey.ActiveOnly) }
            Chip("Daily driver", FilterKey.DailyDriverOnly in state.filters) { onToggleFilter(FilterKey.DailyDriverOnly) }
            Chip("By base", FilterKey.ByBase in state.filters) { onToggleFilter(FilterKey.ByBase) }
        }
        if (FilterKey.ByBase in state.filters) {
            ChipRow("Base") {
                state.roster.groupViews().forEach { g -> Chip(g.label, state.filterBaseId == g.id) { onSetFilterBase(g.id) } }
            }
        }

        LazyColumn(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            items(rows, key = { it.target.address }) { row ->
                BatteryRow(
                    row = row,
                    isStage = row.group.id == state.stageGroupId && state.monitoring,
                    isDailyDriver = row.group.id == state.dailyDriverId,
                    disabled = row.target.address in state.disabled,
                    monitoring = state.monitoring,
                    onPin = { onPinBase(row.group.id) },
                    onDisconnect = { onDisconnect(row.target.address) },
                    onReconnect = { onReconnect(row.target.address) },
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
        Modifier.clip(RoundedCornerShape(20.dp)).background(bg).border(1.dp, border, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick).padding(horizontal = 13.dp, vertical = 7.dp),
    ) {
        Text(label, color = if (selected) Bm.accent else c.text2, fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/**
 * Long-press context menu for a battery row. Currently just "Set as main stage"; this is the
 * home for future per-battery actions (e.g. battery history).
 */
@Composable
private fun RowActionMenu(expanded: Boolean, onDismiss: () -> Unit, onPin: () -> Unit) {
    val c = Bm.colors
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.background(c.card),
    ) {
        DropdownMenuItem(
            text = { Text("Pin to Main Stage", color = c.text, fontSize = 14.sp) },
            leadingIcon = { Icon(Icons.Filled.PushPin, null, Modifier.size(18.dp), tint = Bm.accent) },
            onClick = { onDismiss(); onPin() },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BatteryRow(
    row: Row,
    isStage: Boolean,
    isDailyDriver: Boolean,
    disabled: Boolean,
    monitoring: Boolean,
    onPin: () -> Unit,
    onDisconnect: () -> Unit,
    onReconnect: () -> Unit,
) {
    val c = Bm.colors
    val t = row.tele
    val reachable = monitoring && row.reachable && !disabled
    val dim = disabled || (monitoring && !row.reachable)
    var menuOpen by remember { mutableStateOf(false) }

    val (stateLabel, stateColor) = when {
        disabled -> "Disconnected" to c.text3
        monitoring && !row.reachable -> "Out of range" to c.text3
        t?.state == BatteryState.Discharging -> "Discharging" to Bm.power
        t?.state == BatteryState.Charging -> "Charging" to Bm.accent
        t?.state == BatteryState.Idle -> "Idle" to c.text2
        t == null && monitoring -> "Connecting…" to c.text3
        else -> "—" to c.text3
    }
    val borderColor = if (isStage) Bm.accent else c.border
    // Show the last-known reading even when disconnected; the dim (below) signals it's stale.
    val socColor = if (t != null) socSeverity(t.soc, Bm.accent) else c.text3

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(if (isStage) Bm.accent.copy(alpha = 0.08f) else c.card2)
            .border(1.dp, borderColor, RoundedCornerShape(9.dp))
            // Single tap no longer jumps to the stage. Double-tap = quick set; long-press = menu.
            .combinedClickable(
                onClick = {},
                onDoubleClick = onPin,
                onLongClick = { menuOpen = true },
            )
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        RowActionMenu(expanded = menuOpen, onDismiss = { menuOpen = false }, onPin = onPin)
        // Header line: identity + state on the left, SOC% (+ link button) on the right.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).alpha(if (dim) 0.5f else 1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(row.target.name, color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                if (isDailyDriver) {
                    Icon(Icons.Filled.Star, "Daily driver", Modifier.padding(start = 6.dp).size(12.dp), tint = Bm.accent)
                }
                if (isStage) {
                    Text("STAGE", color = Bm.accent, fontSize = 8.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = 0.7.sp, modifier = Modifier.padding(start = 8.dp))
                }
                Text(stateLabel, color = stateColor, fontSize = 11.sp,
                    modifier = Modifier.padding(start = 8.dp))
                // last-known voltage stays visible even when out of range / disconnected
                if (t != null) {
                    Text(" · %.1f V".format(t.voltage), color = c.text3, fontFamily = MonoFont, fontSize = 11.sp)
                }
            }
            // Pulsing charging bolt sits just left of the percentage while this pack charges.
            if (reachable && t?.state == BatteryState.Charging) {
                Icon(
                    ChargingBolt,
                    contentDescription = "Charging",
                    modifier = Modifier.padding(start = 8.dp).size(width = 11.dp, height = 16.dp),
                    tint = Bm.accent.copy(alpha = rememberBoltAlpha(0.35f, 1f)),
                )
            }
            Text(if (t != null) "${t.soc.roundToInt()}%" else "—",
                color = socColor, fontFamily = MonoFont, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.alpha(if (dim) 0.5f else 1f).padding(start = 8.dp))
            // disconnect / reconnect (Bluetooth link)
            if (monitoring) {
                Box(
                    Modifier.padding(start = 4.dp).size(30.dp).clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = if (disabled) onReconnect else onDisconnect),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (disabled) Icons.Filled.Link else Icons.Filled.LinkOff,
                        if (disabled) "Reconnect" else "Disconnect",
                        Modifier.size(17.dp),
                        tint = if (disabled) Bm.accent else c.text3,
                    )
                }
            }
        }
        // Capacity bar — show the last-known reading whenever we have one, dimmed when the pack is
        // disconnected/stale. Fill = remaining ÷ the BMS's reported full-charge capacity (falls
        // back to 100 Ah nominal when the BMS hasn't reported a full-charge value yet).
        if (t != null) {
            val fullAh = if (t.fullChargeAh > 0f) t.fullChargeAh else 100f
            val capPct = (t.capacityAh / fullAh).coerceIn(0f, 1f)
            Row(Modifier.fillMaxWidth().alpha(if (dim) 0.5f else 1f), verticalAlignment = Alignment.CenterVertically) {
                Text("CAPACITY", color = c.text3, fontSize = 9.sp, fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp)
                Box(
                    Modifier.weight(1f).padding(horizontal = 10.dp).height(5.dp)
                        .clip(RoundedCornerShape(3.dp)).background(c.inputBg),
                ) {
                    Box(
                        Modifier.fillMaxWidth(capPct).height(5.dp)
                            .clip(RoundedCornerShape(3.dp)).background(socColor),
                    )
                }
                Text("${t.capacityAh.roundToInt()} Ah", color = c.text2, fontFamily = MonoFont, fontSize = 11.sp,
                    modifier = Modifier.width(48.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
        }
    }
}
