package dev.joely.bmsmon.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.joely.bmsmon.Mode
import dev.joely.bmsmon.UiState
import dev.joely.bmsmon.model.ALL_GROUPS
import dev.joely.bmsmon.model.BatteryGroup
import dev.joely.bmsmon.ui.theme.Bm
import dev.joely.bmsmon.ui.theme.MonoFont
import dev.joely.bmsmon.ui.theme.PowerSwatches
import dev.joely.bmsmon.ui.theme.ThemeSwatches

private fun Color.hex(): String = "#%06X".format(0xFFFFFF and toArgb())
private fun shortMac(addr: String): String = addr.removePrefix("C8:47:80:")

@Composable
fun SettingsScreen(
    state: UiState,
    onBack: () -> Unit,
    onToggleConnect: () -> Unit,
    onSelectGroup: (String) -> Unit,
    onSetAccent: (Color) -> Unit,
    onSetPower: (Color) -> Unit,
    onSetMode: (Mode) -> Unit,
) {
    val c = Bm.colors
    Column(Modifier.fillMaxSize().background(c.bg)) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(c.bg)
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(32.dp).clickable(onClick = onBack), contentAlignment = Alignment.CenterStart) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", Modifier.size(22.dp), tint = c.icon)
            }
            Text("Settings", color = c.text, fontSize = 21.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 14.dp))
        }
        Box(Modifier.fillMaxWidth().background(c.divider).size(width = 0.dp, height = 1.dp))

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BluetoothCard(state, onToggleConnect)
            GroupsCard(state, onSelectGroup)
            ColorCard("Theme Color", null, ThemeSwatches, state.accent, onSetAccent)
            ColorCard("Power Color", "Inner ring — charge / discharge rate", PowerSwatches, state.power, onSetPower)
            AppearanceCard(state, onSetMode)
            AboutCard()
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    val c = Bm.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(c.card)
            .border(1.dp, c.border, RoundedCornerShape(14.dp))
            .padding(18.dp),
    ) { content() }
}

@Composable
private fun BluetoothCard(state: UiState, onToggleConnect: () -> Unit) {
    val c = Bm.colors
    val group = state.activeGroup
    Card {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 14.dp)) {
            Icon(Icons.Filled.Bluetooth, null, Modifier.size(18.dp), tint = Bm.accent)
            Text("Bluetooth Connection", color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, modifier = Modifier.padding(start = 9.dp))
        }
        Text("Active base: ${group.label}", color = c.text2, fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 10.dp))
        ReadonlyAddress(group.a.name, group.a.address)
        Box(Modifier.size(width = 0.dp, height = 10.dp))
        ReadonlyAddress(group.b.name, group.b.address)
        Box(Modifier.size(width = 0.dp, height = 16.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(9.dp))
                .background(Bm.accent)
                .clickable(onClick = onToggleConnect)
                .padding(vertical = 13.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (state.connected) "Disconnect" else "Connect to Base",
                color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ReadonlyAddress(label: String, address: String) {
    val c = Bm.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(c.inputBg)
            .border(1.dp, c.inputBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 13.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = c.text2, fontSize = 12.sp)
        Text(address, color = c.text, fontFamily = MonoFont, fontSize = 14.sp)
    }
}

@Composable
private fun GroupsCard(state: UiState, onSelectGroup: (String) -> Unit) {
    val c = Bm.colors
    Card {
        Text("Battery Groups", color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text("Two batteries per base — tap to select active", color = c.text2, fontSize = 12.sp,
            modifier = Modifier.padding(top = 5.dp))
        Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            ALL_GROUPS.forEach { g ->
                GroupRow(g, selected = g.id == state.activeGroupId) { onSelectGroup(g.id) }
            }
        }
    }
}

@Composable
private fun GroupRow(group: BatteryGroup, selected: Boolean, onClick: () -> Unit) {
    val c = Bm.colors
    val border = if (selected) Bm.accent else c.border
    val bg = if (selected) Bm.accent.copy(alpha = 0.14f) else Color.Transparent
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("${group.label} base", color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "${shortMac(group.a.address)}  ·  ${shortMac(group.b.address)}",
                color = c.text3, fontFamily = MonoFont, fontSize = 11.sp,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        if (selected) Icon(Icons.Filled.Check, "Selected", Modifier.size(22.dp), tint = Bm.accent)
    }
}

@Composable
private fun ColorCard(
    title: String,
    subtitle: String?,
    swatches: List<Color>,
    selected: Color,
    onSelect: (Color) -> Unit,
) {
    val c = Bm.colors
    Card {
        Text(title, color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        if (subtitle != null) {
            Text(subtitle, color = c.text2, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
        }
        Box(Modifier.size(width = 0.dp, height = if (subtitle != null) 14.dp else 16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
            for (row in swatches.chunked(4)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                    for (sw in row) {
                        Box(
                            Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(11.dp))
                                .background(sw)
                                .clickable { onSelect(sw) },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (sw.toArgb() == selected.toArgb()) {
                                Icon(Icons.Filled.Check, null, Modifier.size(26.dp), tint = Color.White)
                            }
                        }
                    }
                }
            }
        }
        Text("Custom Color", color = c.text2, fontSize = 12.sp, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(width = 46.dp, height = 38.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(selected),
            )
            Box(
                Modifier
                    .padding(start = 11.dp)
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(c.inputBg)
                    .border(1.dp, c.inputBorder, RoundedCornerShape(8.dp))
                    .padding(horizontal = 13.dp, vertical = 10.dp),
            ) {
                Text(selected.hex(), color = c.text, fontFamily = MonoFont, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun AppearanceCard(state: UiState, onSetMode: (Mode) -> Unit) {
    val c = Bm.colors
    Card {
        Text("Appearance", color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            AppearanceButton("Dark", Icons.Filled.DarkMode, state.isDark, Modifier.weight(1f)) { onSetMode(Mode.Dark) }
            AppearanceButton("Light", Icons.Filled.LightMode, !state.isDark, Modifier.weight(1f)) { onSetMode(Mode.Light) }
        }
        Text("Follows your system setting by default.", color = c.text3, fontSize = 11.sp,
            modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun AppearanceButton(
    label: String,
    icon: ImageVector,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val c = Bm.colors
    val border = if (active) Bm.accent else c.border
    val bg = if (active) Bm.accent.copy(alpha = 0.14f) else Color.Transparent
    Row(
        modifier
            .clip(RoundedCornerShape(9.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(11.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(17.dp), tint = c.text)
        Text(label, color = c.text, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun AboutCard() {
    val c = Bm.colors
    Card {
        Text("About", color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 12.dp))
        Text("Power Wheelchair Battery Monitor v1.0", color = c.text2, fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 7.dp))
        Text("Monitors dual battery systems via Bluetooth BMS connection", color = Bm.accent, fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 14.dp))
        Text(
            "Note: Live telemetry uses the reverse-engineered Beken BMS protocol with read-only commands; destructive commands are never sent.",
            color = c.text3, fontSize = 11.sp, lineHeight = 16.5.sp,
        )
    }
}
