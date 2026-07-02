package dev.joely.bmsmon.ui.all

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import dev.joely.bmsmon.FilterKey
import dev.joely.bmsmon.SortKey
import dev.joely.bmsmon.UiState
import dev.joely.bmsmon.model.BatteryState
import dev.joely.bmsmon.model.BatteryStatus
import dev.joely.bmsmon.model.BmsTarget
import dev.joely.bmsmon.model.Roster
import dev.joely.bmsmon.model.Telemetry
import dev.joely.bmsmon.model.groupViews
import dev.joely.bmsmon.ui.ChargingBolt
import dev.joely.bmsmon.ui.FleetActions
import dev.joely.bmsmon.ui.RosterActions
import dev.joely.bmsmon.ui.rememberBoltAlpha
import dev.joely.bmsmon.ui.theme.Bm
import dev.joely.bmsmon.ui.theme.MonoFont
import dev.joely.bmsmon.ui.theme.socSeverity
import kotlin.math.roundToInt

/** A row's group context: id+label, or null when the battery is ungrouped. */
private data class RowGroup(val id: String, val label: String)

/** One list row's data (UI-13a: named PackRow — `Row` shadowed Compose's Row composable). */
private data class PackRow(val group: RowGroup?, val target: BmsTarget, val status: BatteryStatus?) {
    val tele: Telemetry? get() = status?.telemetry
    val reachable: Boolean get() = status?.reachable == true
}

private fun activityRank(t: Telemetry?): Int = when (t?.state) {
    BatteryState.Discharging -> 0
    BatteryState.Charging -> 1
    BatteryState.Idle -> 2
    else -> 3
}

/** The full join → filter → sort pipeline, extracted so the screen can memoize it on its inputs. */
private fun buildRows(
    roster: Roster,
    fleet: Map<String, BatteryStatus>,
    filters: Set<FilterKey>,
    filterBaseId: String,
    dailyDriverId: String,
    sortKey: SortKey,
): List<PackRow> {
    val grouped = roster.groupViews().flatMap { g ->
        g.targets.map { t -> PackRow(RowGroup(g.id, g.label), t, fleet[t.address]) }
    }
    val ungrouped = roster.batteries.filter { it.groupId == null }
        .map { b -> PackRow(null, BmsTarget(b.address, b.alias), fleet[b.address]) }
    var rows = grouped + ungrouped

    if (FilterKey.ReachableOnly in filters) rows = rows.filter { it.reachable }
    if (FilterKey.ActiveOnly in filters) rows = rows.filter { activityRank(it.tele) <= 1 }
    if (FilterKey.ByBase in filters) rows = rows.filter { it.group?.id == filterBaseId }
    if (FilterKey.DailyDriverOnly in filters) rows = rows.filter { it.group?.id == dailyDriverId }

    return when (sortKey) {
        SortKey.Activity -> rows.sortedWith(compareBy({ activityRank(it.tele) }, { -(it.tele?.soc ?: -1f) }))
        SortKey.Soc -> rows.sortedByDescending { it.tele?.soc ?: -1f }
        SortKey.Base -> rows
    }
}

@Composable
fun AllBatteriesScreen(
    state: UiState,
    fleet: FleetActions,
    rosterEdit: RosterActions,
    modifier: Modifier = Modifier,
) {
    val c = Bm.colors
    val groupViews = remember(state.roster) { state.roster.groupViews() }
    // Memoized on its actual inputs so the join/filter/sort pipeline doesn't re-run on every
    // recomposition (frame-rate animations like the charging bolt recompose this screen far more
    // often than the ~1.5 s poll actually changes the data).
    val rows = remember(
        state.roster, state.fleet, state.filters, state.filterBaseId,
        state.dailyDriverId, state.sortKey,
    ) {
        buildRows(state.roster, state.fleet, state.filters, state.filterBaseId,
            state.dailyDriverId, state.sortKey)
    }

    // Fill the page height so content is top-aligned (the HorizontalPager centers wrap-height pages).
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text("All Batteries", color = c.text, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.monitoring) {
                    // Toggle to "Reconnect all" once every pack the user can act on is disconnected.
                    val allDisabled = rows.isNotEmpty() && rows.all { it.target.address in state.disabled }
                    if (allDisabled) {
                        Text("Reconnect all", color = Bm.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable(onClick = fleet.onReconnectAll).padding(4.dp))
                    } else {
                        Text("Disconnect all", color = Bm.power, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable(onClick = fleet.onDisconnectAll).padding(4.dp))
                    }
                }
                Box(
                    Modifier.padding(start = 6.dp).size(34.dp).clip(RoundedCornerShape(9.dp))
                        .clickable(onClick = fleet.onAddScan),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Add, "Add battery", Modifier.size(22.dp), tint = Bm.accent)
                }
            }
        }

        ChipRow("Sort") {
            Chip("Activity", state.sortKey == SortKey.Activity) { fleet.onSetSort(SortKey.Activity) }
            Chip("SOC", state.sortKey == SortKey.Soc) { fleet.onSetSort(SortKey.Soc) }
            Chip("Base", state.sortKey == SortKey.Base) { fleet.onSetSort(SortKey.Base) }
        }
        ChipRow("Filter") {
            Chip("Reachable", FilterKey.ReachableOnly in state.filters) { fleet.onToggleFilter(FilterKey.ReachableOnly) }
            Chip("Active", FilterKey.ActiveOnly in state.filters) { fleet.onToggleFilter(FilterKey.ActiveOnly) }
            Chip("Daily driver", FilterKey.DailyDriverOnly in state.filters) { fleet.onToggleFilter(FilterKey.DailyDriverOnly) }
            Chip("By base", FilterKey.ByBase in state.filters) { fleet.onToggleFilter(FilterKey.ByBase) }
        }
        if (FilterKey.ByBase in state.filters) {
            ChipRow("Base") {
                groupViews.forEach { g -> Chip(g.label, state.filterBaseId == g.id) { fleet.onSetFilterBase(g.id) } }
            }
        }

        LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            items(rows, key = { it.target.address }) { row ->
                SwipeableBatteryRow(
                    row = row,
                    groups = groupViews.map { RowGroup(it.id, it.label) },
                    isStage = row.group?.id == state.stageGroupId && state.monitoring,
                    isDailyDriver = row.group?.id == state.dailyDriverId,
                    disabled = row.target.address in state.disabled,
                    monitoring = state.monitoring,
                    onOpenDetail = { fleet.onOpenDetail(row.target.address) },
                    onPin = { if (row.group != null) fleet.onPinBase(row.group.id) else fleet.onPinSingle(row.target.address) },
                    onDisconnect = { fleet.onDisconnect(row.target.address) },
                    onReconnect = { fleet.onReconnect(row.target.address) },
                    onRemove = { rosterEdit.onRemove(row.target.address) },
                    onRename = { rosterEdit.onRename(row.target.address, it) },
                    onSetGroup = { rosterEdit.onSetGroup(row.target.address, it) },
                    onCreateGroup = { rosterEdit.onCreateGroup(row.target.address, it) },
                    onRenameGroup = { row.group?.let { g -> rosterEdit.onRenameGroup(g.id, it) } },
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

@OptIn(ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableBatteryRow(
    row: PackRow,
    groups: List<RowGroup>,
    isStage: Boolean,
    isDailyDriver: Boolean,
    disabled: Boolean,
    monitoring: Boolean,
    onOpenDetail: () -> Unit,
    onPin: () -> Unit,
    onDisconnect: () -> Unit,
    onReconnect: () -> Unit,
    onRemove: () -> Unit,
    onRename: (String) -> Unit,
    onSetGroup: (String?) -> Unit,
    onCreateGroup: (String) -> Unit,
    onRenameGroup: (String) -> Unit,
) {
    val c = Bm.colors
    var confirmDelete by remember { mutableStateOf(false) }

    SwipeLeftToDelete(onTriggered = { confirmDelete = true }) {
        BatteryRow(
            row = row, groups = groups, isStage = isStage, isDailyDriver = isDailyDriver,
            disabled = disabled, monitoring = monitoring,
            onOpenDetail = onOpenDetail, onPin = onPin,
            onDisconnect = onDisconnect, onReconnect = onReconnect,
            onRemoveRequest = { confirmDelete = true },
            onRename = onRename, onSetGroup = onSetGroup,
            onCreateGroup = onCreateGroup, onRenameGroup = onRenameGroup,
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = c.card,
            title = { Text("Remove battery?", color = c.text) },
            text = { Text("Remove “${row.target.name}” from the roster? Are you sure?", color = c.text2) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onRemove() }) { Text("Remove", color = Bm.power) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = c.text2) } },
        )
    }
}

/**
 * Swipe a row LEFT to reveal a trash affordance and (past threshold) fire [onTriggered]; the row
 * always springs back. Only leftward drags are claimed — a rightward drag is left unconsumed so the
 * parent HorizontalPager can swipe the whole page back to the main stage. (Material3's
 * SwipeToDismissBox still consumed right-drags at rest even with start-to-end disabled, which blocked
 * the page swipe — hence this hand-rolled, direction-aware gesture.)
 */
@Composable
private fun SwipeLeftToDelete(onTriggered: () -> Unit, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    val density = LocalDensity.current
    val maxRevealPx = with(density) { 160.dp.toPx() }
    val triggerPx = with(density) { 96.dp.toPx() }

    Box(Modifier.fillMaxWidth()) {
        if (offsetX.value < -1f) {
            Box(
                Modifier.matchParentSize().clip(RoundedCornerShape(9.dp))
                    .background(Bm.power.copy(alpha = 0.18f)).padding(horizontal = 18.dp),
                contentAlignment = Alignment.CenterEnd,
            ) { Icon(Icons.Filled.Delete, "Remove", Modifier.size(22.dp), tint = Bm.power) }
        }
        Box(
            Modifier
                .offset { IntOffset(offsetX.value.toInt(), 0) }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val slop = viewConfiguration.touchSlop
                        var claimed = offsetX.value < -1f   // already open → keep handling
                        var current = offsetX.value
                        var total = 0f
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            val dx = ch.positionChange().x
                            if (!claimed) {
                                total += dx
                                if (total <= -slop) claimed = true        // leftward → ours
                                else if (total >= slop) break             // rightward → let the pager have it
                            }
                            if (claimed) {
                                ch.consume()
                                current = (current + dx).coerceIn(-maxRevealPx, 0f)
                                scope.launch { offsetX.snapTo(current) }
                            }
                        }
                        if (claimed) {
                            val fire = current <= -triggerPx
                            scope.launch { offsetX.animateTo(0f, tween(180)); if (fire) onTriggered() }
                        }
                    }
                },
        ) { content() }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BatteryRow(
    row: PackRow,
    groups: List<RowGroup>,
    isStage: Boolean,
    isDailyDriver: Boolean,
    disabled: Boolean,
    monitoring: Boolean,
    onOpenDetail: () -> Unit,
    onPin: () -> Unit,
    onDisconnect: () -> Unit,
    onReconnect: () -> Unit,
    onRemoveRequest: () -> Unit,
    onRename: (String) -> Unit,
    onSetGroup: (String?) -> Unit,
    onCreateGroup: (String) -> Unit,
    onRenameGroup: (String) -> Unit,
) {
    val c = Bm.colors
    val t = row.tele
    val reachable = monitoring && row.reachable && !disabled
    val dim = disabled || (monitoring && !row.reachable)
    var menuOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var groupPickOpen by remember { mutableStateOf(false) }
    var newGroupOpen by remember { mutableStateOf(false) }
    var renameGroupOpen by remember { mutableStateOf(false) }

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
    val socColor = if (t != null) socSeverity(t.soc, Bm.accent) else c.text3

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(if (isStage) Bm.accent.copy(alpha = 0.08f) else c.card2)
            .border(1.dp, borderColor, RoundedCornerShape(9.dp))
            .combinedClickable(onClick = onOpenDetail, onLongClick = { menuOpen = true })
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        RowActionMenu(
            expanded = menuOpen,
            inGroup = row.group != null,
            onDismiss = { menuOpen = false },
            onPin = onPin,
            onRename = { renameOpen = true },
            onChangeGroup = { groupPickOpen = true },
            onRenameGroup = { renameGroupOpen = true },
            onRemove = onRemoveRequest,
        )
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
                Text(stateLabel, color = stateColor, fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp))
                if (t != null) {
                    Text(" · %.1f V".format(t.voltage), color = c.text3, fontFamily = MonoFont, fontSize = 11.sp)
                }
            }
            if (reachable && t?.state == BatteryState.Charging) {
                Icon(ChargingBolt, "Charging",
                    Modifier.padding(start = 8.dp).size(width = 11.dp, height = 16.dp),
                    tint = Bm.accent.copy(alpha = rememberBoltAlpha(0.35f, 1f)))
            }
            Text(if (t != null) "${t.soc.roundToInt()}%" else "—",
                color = socColor, fontFamily = MonoFont, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.alpha(if (dim) 0.5f else 1f).padding(start = 8.dp))
            if (monitoring) {
                Box(
                    Modifier.padding(start = 4.dp).size(30.dp).clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = if (disabled) onReconnect else onDisconnect),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (disabled) Icons.Filled.Link else Icons.Filled.LinkOff,
                        if (disabled) "Reconnect" else "Disconnect",
                        Modifier.size(17.dp), tint = if (disabled) Bm.accent else c.text3,
                    )
                }
            }
        }
        if (t != null) {
            val fullAh = if (t.fullChargeAh > 0f) t.fullChargeAh else 100f
            val capPct = (t.capacityAh / fullAh).coerceIn(0f, 1f)
            Row(Modifier.fillMaxWidth().alpha(if (dim) 0.5f else 1f), verticalAlignment = Alignment.CenterVertically) {
                Text("CAPACITY", color = c.text3, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
                Box(Modifier.weight(1f).padding(horizontal = 10.dp).height(5.dp)
                    .clip(RoundedCornerShape(3.dp)).background(c.inputBg)) {
                    Box(Modifier.fillMaxWidth(capPct).height(5.dp).clip(RoundedCornerShape(3.dp)).background(socColor))
                }
                Text("${t.capacityAh.roundToInt()} Ah", color = c.text2, fontFamily = MonoFont, fontSize = 11.sp,
                    modifier = Modifier.width(48.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
        }
    }

    if (renameOpen) {
        TextPromptDialog("Rename battery", row.target.name, "Name",
            onConfirm = { renameOpen = false; onRename(it) }, onDismiss = { renameOpen = false })
    }
    if (newGroupOpen) {
        TextPromptDialog("New group", "", "Group name",
            onConfirm = { newGroupOpen = false; onCreateGroup(it) }, onDismiss = { newGroupOpen = false })
    }
    if (renameGroupOpen && row.group != null) {
        TextPromptDialog("Rename group", row.group.label, "Group name",
            onConfirm = { renameGroupOpen = false; onRenameGroup(it) }, onDismiss = { renameGroupOpen = false })
    }
    if (groupPickOpen) {
        GroupPickerDialog(
            groups = groups,
            currentGroupId = row.group?.id,
            onPick = { groupPickOpen = false; onSetGroup(it) },
            onNewGroup = { groupPickOpen = false; newGroupOpen = true },
            onDismiss = { groupPickOpen = false },
        )
    }
}

@Composable
private fun RowActionMenu(
    expanded: Boolean,
    inGroup: Boolean,
    onDismiss: () -> Unit,
    onPin: () -> Unit,
    onRename: () -> Unit,
    onChangeGroup: () -> Unit,
    onRenameGroup: () -> Unit,
    onRemove: () -> Unit,
) {
    val c = Bm.colors
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, modifier = Modifier.background(c.card)) {
        DropdownMenuItem(
            text = { Text("Pin to Main Stage", color = c.text, fontSize = 14.sp) },
            leadingIcon = { Icon(Icons.Filled.PushPin, null, Modifier.size(18.dp), tint = Bm.accent) },
            onClick = { onDismiss(); onPin() },
        )
        DropdownMenuItem(
            text = { Text("Rename battery", color = c.text, fontSize = 14.sp) },
            leadingIcon = { Icon(Icons.Filled.DriveFileRenameOutline, null, Modifier.size(18.dp), tint = c.icon) },
            onClick = { onDismiss(); onRename() },
        )
        DropdownMenuItem(
            text = { Text(if (inGroup) "Change group" else "Add to group", color = c.text, fontSize = 14.sp) },
            leadingIcon = { Icon(Icons.Filled.Folder, null, Modifier.size(18.dp), tint = c.icon) },
            onClick = { onDismiss(); onChangeGroup() },
        )
        if (inGroup) {
            DropdownMenuItem(
                text = { Text("Rename group", color = c.text, fontSize = 14.sp) },
                leadingIcon = { Icon(Icons.Filled.DriveFileRenameOutline, null, Modifier.size(18.dp), tint = c.icon) },
                onClick = { onDismiss(); onRenameGroup() },
            )
        }
        DropdownMenuItem(
            text = { Text("Remove battery", color = Bm.power, fontSize = 14.sp) },
            leadingIcon = { Icon(Icons.Filled.Delete, null, Modifier.size(18.dp), tint = Bm.power) },
            onClick = { onDismiss(); onRemove() },
        )
    }
}

@Composable
private fun GroupPickerDialog(
    groups: List<RowGroup>,
    currentGroupId: String?,
    onPick: (String?) -> Unit,
    onNewGroup: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Bm.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.card,
        title = { Text("Move to group", color = c.text) },
        text = {
            Column {
                DropdownRow("Ungrouped", currentGroupId == null) { onPick(null) }
                groups.forEach { g -> DropdownRow(g.label, currentGroupId == g.id) { onPick(g.id) } }
                DropdownRow("+ New group…", selected = false, accent = true) { onNewGroup() }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = c.text2) } },
    )
}

@Composable
private fun DropdownRow(label: String, selected: Boolean, accent: Boolean = false, onClick: () -> Unit) {
    val c = Bm.colors
    Text(
        label,
        color = when { accent -> Bm.accent; selected -> Bm.accent; else -> c.text },
        fontSize = 15.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 11.dp),
    )
}

@Composable
private fun TextPromptDialog(
    title: String,
    initial: String,
    label: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Bm.colors
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.card,
        title = { Text(title, color = c.text) },
        text = {
            OutlinedTextField(
                value = value, onValueChange = { value = it },
                label = { Text(label) }, singleLine = true,
                // The app themes via BmColors, not a Material ColorScheme, so Material3 text fields
                // fall back to their (light) defaults — dark, unreadable text in dark mode. Map the
                // field's colors to the active theme tokens explicitly.
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = c.text, unfocusedTextColor = c.text,
                    cursorColor = Bm.accent,
                    focusedBorderColor = Bm.accent, unfocusedBorderColor = c.border,
                    focusedLabelColor = Bm.accent, unfocusedLabelColor = c.text3,
                    focusedContainerColor = c.inputBg, unfocusedContainerColor = c.inputBg,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (value.isNotBlank()) onConfirm(value.trim()) }) {
                Text("Save", color = Bm.accent)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = c.text2) } },
    )
}
