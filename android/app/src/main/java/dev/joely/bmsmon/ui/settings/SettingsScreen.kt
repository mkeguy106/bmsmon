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
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.joely.bmsmon.ALERT_THRESHOLDS
import dev.joely.bmsmon.Appearance
import dev.joely.bmsmon.UiState
import dev.joely.bmsmon.fractionToLux
import dev.joely.bmsmon.luxToFraction
import kotlin.math.roundToInt
import dev.joely.bmsmon.model.BatteryGroup
import dev.joely.bmsmon.model.groupViews
import dev.joely.bmsmon.model.STAGE_HOLD_OPTIONS_MIN
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
    onToggleMonitoring: () -> Unit,
    onSetDailyDriver: (String) -> Unit,
    onSetDynamicStage: (Boolean) -> Unit,
    onSetStageHold: (Int) -> Unit,
    onSetAlertsOn: (Boolean) -> Unit,
    onToggleThreshold: (Int) -> Unit,
    onSetKeepScreenOn: (Boolean) -> Unit,
    onSetTempFahrenheit: (Boolean) -> Unit,
    onSetLogging: (Boolean) -> Unit,
    onClearLog: () -> Unit,
    onSetAccent: (Color) -> Unit,
    onSetPower: (Color) -> Unit,
    onSetAppearance: (Appearance) -> Unit,
    onSetAutoLux: (Float) -> Unit,
    onSetLockShowTime: (Boolean) -> Unit,
    onSetLockShowWifi: (Boolean) -> Unit,
    onSetLockShowBattery: (Boolean) -> Unit,
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
            BluetoothCard(state, onToggleMonitoring)
            MainStageCard(state, onSetDynamicStage, onSetStageHold)
            AlertsCard(state, onSetAlertsOn, onToggleThreshold)
            GroupsCard(state, onSetDailyDriver)
            ColorCard("Theme Color", null, ThemeSwatches, state.accent, onSetAccent)
            ColorCard("Power Color", "Inner ring — charge / discharge rate", PowerSwatches, state.power, onSetPower)
            AppearanceCard(state, onSetAppearance, onSetAutoLux)
            TemperatureCard(state, onSetTempFahrenheit)
            KeepScreenOnCard(state, onSetKeepScreenOn)
            LockedScreenCard(state, onSetLockShowTime, onSetLockShowWifi, onSetLockShowBattery)
            UsageLoggingCard(state, onSetLogging, onClearLog)
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
private fun BluetoothCard(state: UiState, onToggleMonitoring: () -> Unit) {
    val c = Bm.colors
    Card {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
            Icon(Icons.Filled.Bluetooth, null, Modifier.size(18.dp), tint = Bm.accent)
            Text("Bluetooth Monitoring", color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, modifier = Modifier.padding(start = 9.dp))
        }
        Text(
            "Connects to every reachable base. The base that's discharging (or charging) takes the main stage and polls fast; the rest poll slowly.",
            color = c.text2, fontSize = 12.sp, lineHeight = 17.sp,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(9.dp))
                .background(Bm.accent)
                .clickable(onClick = onToggleMonitoring)
                .padding(vertical = 13.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (state.monitoring) "Stop Monitoring" else "Start Monitoring",
                color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun MainStageCard(state: UiState, onSetDynamicStage: (Boolean) -> Unit, onSetStageHold: (Int) -> Unit) {
    val c = Bm.colors
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Dynamic main stage", color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Automatically show the base you're using. Off: the stage stays fixed on your pick.",
                    color = c.text2, fontSize = 12.sp, lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Switch(
                checked = state.dynamicStage,
                onCheckedChange = onSetDynamicStage,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Bm.accent,
                    uncheckedTrackColor = c.inputBg,
                    uncheckedBorderColor = c.inputBorder,
                ),
            )
        }
        if (state.dynamicStage) {
            Text(
                "Active-chair hold — keep the discharging base on stage this long after it stops:",
                color = c.text2, fontSize = 12.sp, lineHeight = 17.sp,
                modifier = Modifier.padding(top = 14.dp, bottom = 10.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                STAGE_HOLD_OPTIONS_MIN.forEach { m ->
                    SelectChip("${m}m", state.stageHoldMinutes == m) { onSetStageHold(m) }
                }
            }
        }
    }
}

@Composable
private fun SelectChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = Bm.colors
    val border = if (selected) Bm.accent else c.border
    val bg = if (selected) Bm.accent.copy(alpha = 0.14f) else Color.Transparent
    Box(
        Modifier.clip(RoundedCornerShape(20.dp)).background(bg).border(1.dp, border, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(label, color = if (selected) Bm.accent else c.text2, fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun AlertsCard(state: UiState, onSetAlertsOn: (Boolean) -> Unit, onToggleThreshold: (Int) -> Unit) {
    val c = Bm.colors
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Flash stage when low", color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("Pulse the screen until acknowledged", color = c.text2, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp))
            }
            Switch(
                checked = state.alertsOn,
                onCheckedChange = onSetAlertsOn,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Bm.accent,
                    uncheckedTrackColor = c.inputBg,
                    uncheckedBorderColor = c.inputBorder,
                ),
            )
        }
        Text("Alert thresholds", color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 18.dp))
        Text(
            "Flash and require acknowledgement each time the lowest pack on the stage drops past one of these levels.",
            color = c.text2, fontSize = 12.sp, lineHeight = 17.sp,
            modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (row in ALERT_THRESHOLDS.chunked(3)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { t ->
                        ThresholdChip("$t%", t in state.enabledThresholds) { onToggleThreshold(t) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThresholdChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = Bm.colors
    val border = if (selected) Bm.accent else c.border
    val bg = if (selected) Bm.accent.copy(alpha = 0.14f) else Color.Transparent
    Box(
        Modifier.clip(RoundedCornerShape(20.dp)).background(bg).border(1.dp, border, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick).padding(horizontal = 15.dp, vertical = 8.dp),
    ) {
        Text(label, color = if (selected) Bm.accent else c.text2, fontFamily = MonoFont,
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun GroupsCard(state: UiState, onSetDailyDriver: (String) -> Unit) {
    val c = Bm.colors
    Card {
        Text("Battery Groups", color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text("Two batteries per base — tap to set the daily driver (wins the stage on ties)",
            color = c.text2, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
        Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            state.roster.groupViews().forEach { g ->
                GroupRow(g, isDailyDriver = g.id == state.dailyDriverId) { onSetDailyDriver(g.id) }
            }
        }
    }
}

@Composable
private fun GroupRow(group: BatteryGroup, isDailyDriver: Boolean, onClick: () -> Unit) {
    val c = Bm.colors
    val border = if (isDailyDriver) Bm.accent else c.border
    val bg = if (isDailyDriver) Bm.accent.copy(alpha = 0.14f) else Color.Transparent
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${group.label} base", color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                if (isDailyDriver) {
                    Text("DAILY DRIVER", color = Bm.accent, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp, modifier = Modifier.padding(start = 8.dp))
                }
            }
            Text(
                group.targets.joinToString("  ·  ") { shortMac(it.address) },
                color = c.text3, fontFamily = MonoFont, fontSize = 11.sp,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        Icon(
            if (isDailyDriver) Icons.Filled.Star else Icons.Filled.StarBorder,
            "Daily driver", Modifier.size(22.dp),
            tint = if (isDailyDriver) Bm.accent else c.text3,
        )
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
private fun AppearanceCard(
    state: UiState,
    onSetAppearance: (Appearance) -> Unit,
    onSetAutoLux: (Float) -> Unit,
) {
    val c = Bm.colors
    Card {
        Text("Appearance", color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            AppearanceButton("Dark", Icons.Filled.DarkMode, state.appearance == Appearance.Dark,
                Modifier.weight(1f)) { onSetAppearance(Appearance.Dark) }
            AppearanceButton("Light", Icons.Filled.LightMode, state.appearance == Appearance.Light,
                Modifier.weight(1f)) { onSetAppearance(Appearance.Light) }
        }
        Row(Modifier.padding(top = 9.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            AppearanceButton("System", Icons.Filled.PhoneAndroid, state.appearance == Appearance.System,
                Modifier.weight(1f)) { onSetAppearance(Appearance.System) }
            AppearanceButton("Auto", Icons.Filled.BrightnessAuto, state.appearance == Appearance.Auto,
                Modifier.weight(1f), enabled = state.hasLightSensor) { onSetAppearance(Appearance.Auto) }
        }
        if (!state.hasLightSensor) {
            Text("No light sensor on this device — Auto unavailable.", color = c.text3, fontSize = 11.sp,
                modifier = Modifier.padding(top = 10.dp))
        } else if (state.appearance == Appearance.Auto) {
            val lux = state.currentLux?.roundToInt()
            Text(
                "Current: ${lux?.toString() ?: "—"} lux   ·   switch around ${state.autoLuxThreshold.roundToInt()} lux",
                color = c.text2, fontSize = 12.sp, fontFamily = MonoFont,
                modifier = Modifier.padding(top = 12.dp),
            )
            Slider(
                value = luxToFraction(state.autoLuxThreshold),
                onValueChange = { onSetAutoLux(fractionToLux(it)) },
            )
            Text("Brighter than the cutover → Light; dimmer → Dark.", color = c.text3, fontSize = 11.sp)
        } else {
            val note = when (state.appearance) {
                Appearance.System -> "Follows your phone's theme."
                Appearance.Dark -> "Dark theme locked on."
                Appearance.Light -> "Light theme locked on."
                Appearance.Auto -> ""
            }
            Text(note, color = c.text3, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

@Composable
private fun AppearanceButton(
    label: String,
    icon: ImageVector,
    active: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val c = Bm.colors
    val border = if (active) Bm.accent else c.border
    val bg = if (active) Bm.accent.copy(alpha = 0.14f) else Color.Transparent
    val content = if (enabled) c.text else c.text3
    Row(
        modifier
            .clip(RoundedCornerShape(9.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(9.dp))
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(11.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(17.dp), tint = content)
        Text(label, color = content, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun TemperatureCard(state: UiState, onSetTempFahrenheit: (Boolean) -> Unit) {
    val c = Bm.colors
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Temperature", color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("Unit shown on the stage stat grid", color = c.text2, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectChip("°F", state.tempFahrenheit) { onSetTempFahrenheit(true) }
                SelectChip("°C", !state.tempFahrenheit) { onSetTempFahrenheit(false) }
            }
        }
    }
}

@Composable
private fun KeepScreenOnCard(state: UiState, onSetKeepScreenOn: (Boolean) -> Unit) {
    val c = Bm.colors
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Keep screen on", color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("Hold the display at your set brightness while the app is open. Turn off to let the screen time out normally.",
                    color = c.text2, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 4.dp))
            }
            Switch(
                checked = state.keepScreenOn,
                onCheckedChange = onSetKeepScreenOn,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Bm.accent,
                    uncheckedTrackColor = c.inputBg,
                    uncheckedBorderColor = c.inputBorder,
                ),
            )
        }
    }
}

@Composable
private fun LockedScreenCard(
    state: UiState,
    onSetLockShowTime: (Boolean) -> Unit,
    onSetLockShowWifi: (Boolean) -> Unit,
    onSetLockShowBattery: (Boolean) -> Unit,
) {
    val c = Bm.colors
    Card {
        Text("Locked screen status", color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Screen pinning hides Android's status bar while the app is locked. Pick which info to replicate at the top.",
            color = c.text2, fontSize = 12.sp, lineHeight = 17.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectChip("Time", state.lockShowTime) { onSetLockShowTime(!state.lockShowTime) }
            SelectChip("Wi-Fi", state.lockShowWifi) { onSetLockShowWifi(!state.lockShowWifi) }
            SelectChip("Battery", state.lockShowBattery) { onSetLockShowBattery(!state.lockShowBattery) }
        }
    }
}

@Composable
private fun UsageLoggingCard(state: UiState, onSetLogging: (Boolean) -> Unit, onClearLog: () -> Unit) {
    val c = Bm.colors
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Usage Logging", color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("Record telemetry to the database for usage history and aging graphs (and to calibrate the power ring).",
                    color = c.text2, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 4.dp))
            }
            Switch(
                checked = state.logging,
                onCheckedChange = onSetLogging,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Bm.accent,
                    uncheckedTrackColor = c.inputBg,
                    uncheckedBorderColor = c.inputBorder,
                ),
            )
        }
        Text(
            "Peak draw:  ${state.peakPowerW.toInt()} W  ·  ${"%.1f".format(state.peakCurrentA)} A",
            color = Bm.power, fontSize = 14.sp, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 14.dp),
        )
        Text("Database: ${state.dbSize}", color = c.text3, fontSize = 10.sp, fontFamily = MonoFont,
            modifier = Modifier.padding(top = 8.dp))
        Box(
            Modifier.padding(top = 14.dp).clip(RoundedCornerShape(8.dp))
                .border(1.dp, c.border, RoundedCornerShape(8.dp))
                .clickable(onClick = onClearLog).padding(horizontal = 16.dp, vertical = 9.dp),
        ) {
            Text("Clear data", color = c.text2, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
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
