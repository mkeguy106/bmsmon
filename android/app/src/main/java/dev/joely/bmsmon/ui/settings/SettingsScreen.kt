package dev.joely.bmsmon.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DisplaySettings
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.joely.bmsmon.ALERT_THRESHOLDS
import dev.joely.bmsmon.Appearance
import dev.joely.bmsmon.UiState
import dev.joely.bmsmon.fractionToLux
import dev.joely.bmsmon.luxToFraction
import dev.joely.bmsmon.model.BatteryGroup
import dev.joely.bmsmon.model.STAGE_HOLD_OPTIONS_MIN
import dev.joely.bmsmon.model.groupViews
import dev.joely.bmsmon.ui.theme.AlertCritical
import dev.joely.bmsmon.ui.theme.Bm
import dev.joely.bmsmon.ui.theme.MonoFont
import dev.joely.bmsmon.ui.theme.PowerSwatches
import dev.joely.bmsmon.ui.theme.RegenGreen
import dev.joely.bmsmon.ui.theme.ThemeSwatches
import kotlin.math.roundToInt

private fun Color.hex(): String = "#%06X".format(0xFFFFFF and toArgb())
private fun shortMac(addr: String): String = addr.removePrefix("C8:47:80:")

/** Category colors for the hub icon tiles — drawn from the app's own swatch palette. */
private val CatPurple = Color(0xFF8B6BC9)
private val CatBlue = Color(0xFF3E86C9)

/** The nine category detail pages the hub drills into. */
private enum class SettingsPage { Monitoring, Alerts, Groups, Appearance, Display, Lock, Data, About, Cloud }

@Composable
fun SettingsScreen(
    state: UiState,
    onBack: () -> Unit,
    onToggleMonitoring: () -> Unit,
    onAddScan: () -> Unit,
    onSetDailyDriver: (String) -> Unit,
    onSetDynamicStage: (Boolean) -> Unit,
    onSetStageHold: (Int) -> Unit,
    onSetAlertsOn: (Boolean) -> Unit,
    onToggleThreshold: (Int) -> Unit,
    onSetCriticalThreshold: (Int) -> Unit,
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
    onEnroll: (String, String) -> Unit,
    onSetCloudEnabled: (Boolean) -> Unit,
    onForget: () -> Unit,
) {
    var page by remember { mutableStateOf<SettingsPage?>(null) }

    // A detail page is a child of the hub: system/gesture back returns here to the landing list
    // first. When we're already on the landing (page == null) this is disabled, so App's handler
    // takes over and backs out to Home.
    BackHandler(enabled = page != null) { page = null }

    when (page) {
        null -> SettingsHub(state, onBack, onToggleMonitoring) { page = it }
        SettingsPage.Monitoring -> DetailScaffold("Monitoring & Stage", { page = null }) {
            MonitoringStageContent(state, onToggleMonitoring, onSetDynamicStage, onSetStageHold)
        }
        SettingsPage.Alerts -> DetailScaffold("Alerts", { page = null }) {
            AlertsContent(state, onSetAlertsOn, onToggleThreshold, onSetCriticalThreshold)
        }
        SettingsPage.Groups -> DetailScaffold("Battery Groups", { page = null }) {
            GroupsContent(state, onSetDailyDriver, onAddScan)
        }
        SettingsPage.Appearance -> DetailScaffold("Appearance & Color", { page = null }) {
            AppearanceColorContent(state, onSetAppearance, onSetAutoLux, onSetAccent, onSetPower)
        }
        SettingsPage.Display -> DetailScaffold("Display & Units", { page = null }) {
            DisplayUnitsContent(state, onSetTempFahrenheit, onSetKeepScreenOn)
        }
        SettingsPage.Lock -> DetailScaffold("Lock Screen", { page = null }) {
            LockScreenContent(state, onSetLockShowTime, onSetLockShowWifi, onSetLockShowBattery)
        }
        SettingsPage.Data -> DetailScaffold("Data & Logging", { page = null }) {
            DataLoggingContent(state, onSetLogging, onClearLog)
        }
        SettingsPage.About -> DetailScaffold("About", { page = null }) { AboutContent() }
        SettingsPage.Cloud -> DetailScaffold("Cloud sync", { page = null }) {
            CloudSyncContent(state, onEnroll, onSetCloudEnabled, onForget)
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────
// Landing hub
// ────────────────────────────────────────────────────────────────────────────

@Composable
private fun SettingsHub(
    state: UiState,
    onBack: () -> Unit,
    onToggleMonitoring: () -> Unit,
    onOpen: (SettingsPage) -> Unit,
) {
    val c = Bm.colors
    Column(Modifier.fillMaxSize().background(c.bg)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(32.dp).clickable(onClick = onBack), contentAlignment = Alignment.CenterStart) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", Modifier.size(22.dp), tint = c.icon)
            }
            Text("Settings", color = c.text, fontSize = 21.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 14.dp))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatusHero(state, onToggleMonitoring, big = true)

            val bases = state.roster.groups.size
            val packs = state.roster.batteries.size

            GroupedCard {
                CategoryRow(
                    Icons.Filled.Bluetooth, Bm.accent, "Monitoring & stage",
                    if (state.dynamicStage) "Dynamic · hold ${state.stageHoldMinutes}m" else "Fixed stage",
                ) { onOpen(SettingsPage.Monitoring) }
                RowHairline()
                CategoryRow(
                    Icons.Filled.Notifications, AlertCritical, "Alerts", alertsValue(state),
                ) { onOpen(SettingsPage.Alerts) }
                RowHairline()
                CategoryRow(
                    Icons.Filled.BatteryFull, RegenGreen, "Battery groups",
                    "$bases bases · driver ${state.dailyDriverId}",
                ) { onOpen(SettingsPage.Groups) }
            }

            GroupedCard {
                CategoryRow(
                    Icons.Filled.Palette, CatPurple, "Appearance & color",
                    "${appearanceLabel(state.appearance)} theme",
                    trailing = {
                        SwatchDot(state.accent)
                        SwatchDot(state.power, Modifier.padding(start = 4.dp))
                        Spacer(Modifier.width(8.dp))
                    },
                ) { onOpen(SettingsPage.Appearance) }
                RowHairline()
                CategoryRow(
                    Icons.Filled.DisplaySettings, CatBlue, "Display & units",
                    "${if (state.tempFahrenheit) "°F" else "°C"} · ${if (state.keepScreenOn) "screen stays on" else "screen times out"}",
                ) { onOpen(SettingsPage.Display) }
                RowHairline()
                CategoryRow(
                    Icons.Filled.Lock, c.text3, "Lock screen", lockValue(state),
                ) { onOpen(SettingsPage.Lock) }
            }

            GroupedCard {
                CategoryRow(
                    Icons.Filled.Storage, Bm.power, "Data & logging",
                    "${if (state.logging) "On" else "Off"}${if (state.dbSize.isNotEmpty()) " · ${state.dbSize}" else ""} · peak ${state.peakPowerW.toInt()} W",
                ) { onOpen(SettingsPage.Data) }
                RowHairline()
                CategoryRow(
                    Icons.Filled.CloudUpload, CatBlue, "Cloud sync", cloudValue(state),
                ) { onOpen(SettingsPage.Cloud) }
                RowHairline()
                CategoryRow(
                    Icons.Filled.Info, c.text3, "About", "v1.0",
                ) { onOpen(SettingsPage.About) }
            }
        }
    }
}

private fun appearanceLabel(a: Appearance): String = when (a) {
    Appearance.Dark -> "Dark"
    Appearance.Light -> "Light"
    Appearance.System -> "System"
    Appearance.Auto -> "Auto"
}

private fun alertsValue(state: UiState): String {
    if (!state.alertsOn || state.enabledThresholds.isEmpty()) return "Off"
    return "On · " + state.enabledThresholds.sortedDescending().joinToString("/") + "%"
}

private fun lockValue(state: UiState): String {
    val parts = buildList {
        if (state.lockShowTime) add("Time")
        if (state.lockShowWifi) add("Wi-Fi")
        if (state.lockShowBattery) add("Battery")
    }
    return if (parts.isEmpty()) "Off" else parts.joinToString(" · ")
}

private fun cloudValue(state: UiState): String =
    if (state.enrolled) "Enrolled" else "Not set up"

// ────────────────────────────────────────────────────────────────────────────
// Shared primitives
// ────────────────────────────────────────────────────────────────────────────

/** Pinned monitoring status block — green & live when monitoring, muted when off. */
@Composable
private fun StatusHero(state: UiState, onToggleMonitoring: () -> Unit, big: Boolean) {
    val c = Bm.colors
    val on = state.monitoring
    val bg = if (on) RegenGreen.copy(alpha = 0.08f) else c.inputBg
    val border = if (on) RegenGreen.copy(alpha = 0.32f) else c.border
    val bases = state.roster.groups.size
    val packs = state.roster.batteries.size
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(if (big) 14.dp else 12.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(if (big) 14.dp else 12.dp))
            .padding(horizontal = 15.dp, vertical = if (big) 15.dp else 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PulsingDot(active = on)
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 12.dp)) {
            Text(
                if (on) "Monitoring active" else "Monitoring off",
                color = c.text, fontSize = if (big) 15.sp else 14.sp, fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (on) "$bases bases · $packs packs · ${state.stageLabel} on stage"
                else "Tap Start to connect to your packs",
                color = c.text2, fontSize = 11.5.sp, modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (on) {
            PillButton("Stop", outlined = true, onClick = onToggleMonitoring)
        } else {
            PillButton("Start", outlined = false, onClick = onToggleMonitoring)
        }
    }
}

@Composable
private fun PulsingDot(active: Boolean, size: Dp = 9.dp) {
    val color = if (active) RegenGreen else Bm.colors.text3
    val alpha = if (active) {
        val transition = rememberInfiniteTransition(label = "dot")
        transition.animateFloat(
            initialValue = 1f, targetValue = 0.35f,
            animationSpec = infiniteRepeatable(
                tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse,
            ),
            label = "dotAlpha",
        ).value
    } else 1f
    Box(Modifier.size(size).clip(CircleShape).background(color).alpha(alpha))
}

@Composable
private fun PillButton(label: String, outlined: Boolean, onClick: () -> Unit) {
    val c = Bm.colors
    val mod = if (outlined) {
        Modifier.clip(RoundedCornerShape(20.dp)).border(1.dp, c.inputBorder, RoundedCornerShape(20.dp))
    } else {
        Modifier.clip(RoundedCornerShape(20.dp)).background(Bm.accent)
    }
    Box(
        mod.clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (outlined) c.text else Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** A grouped-list container: card background, hairline border, 13dp radius. */
@Composable
internal fun GroupedCard(content: @Composable ColumnScope.() -> Unit) {
    val c = Bm.colors
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(c.card)
            .border(1.dp, c.border, RoundedCornerShape(13.dp)),
        content = content,
    )
}

/** Hairline divider between rows inside a grouped card, inset past the leading icon tile. */
@Composable
internal fun RowHairline(inset: Dp = 58.dp) {
    Box(Modifier.fillMaxWidth().padding(start = inset)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Bm.colors.border))
    }
}

@Composable
private fun CategoryRow(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    value: String,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val c = Bm.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(iconColor.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(17.dp), tint = iconColor)
        }
        Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp)) {
            Text(title, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(value, color = c.text2, fontSize = 11.5.sp, modifier = Modifier.padding(top = 2.dp))
        }
        trailing?.invoke(this)
        Icon(Icons.Filled.ChevronRight, null, Modifier.size(20.dp), tint = c.text3)
    }
}

@Composable
private fun SwatchDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(15.dp).clip(CircleShape).background(color)
        .border(1.5.dp, Bm.colors.border, CircleShape))
}

/** Detail-page chrome: accent "‹ Settings" on the left, title on the right, then a scroll body. */
@Composable
private fun DetailScaffold(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = Bm.colors
    Column(Modifier.fillMaxSize().background(c.bg)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onBack)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.ChevronLeft, "Back", Modifier.size(20.dp), tint = Bm.accent)
                Text("Settings", color = Bm.accent, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 2.dp))
            }
            Spacer(Modifier.weight(1f))
            Text(title, color = c.text, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(end = 6.dp))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

@Composable
internal fun SectionLabel(text: String, top: Dp = 8.dp) {
    Text(
        text.uppercase(), color = Bm.colors.text3, fontFamily = MonoFont,
        fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp,
        modifier = Modifier.padding(start = 4.dp, top = top, bottom = 9.dp),
    )
}

/** A bordered card holding free-form detail content (18dp padding). */
@Composable
internal fun PlainCard(content: @Composable ColumnScope.() -> Unit) {
    val c = Bm.colors
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(c.card)
            .border(1.dp, c.border, RoundedCornerShape(13.dp))
            .padding(16.dp),
        content = content,
    )
}

@Composable
private fun BmSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val c = Bm.colors
    Switch(
        checked = checked, onCheckedChange = onCheckedChange, enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = Bm.accent,
            uncheckedTrackColor = c.inputBg,
            uncheckedBorderColor = c.inputBorder,
        ),
    )
}

@Composable
private fun SelectChip(
    label: String,
    selected: Boolean,
    mono: Boolean = false,
    tint: Color? = null,
    onClick: () -> Unit,
) {
    val c = Bm.colors
    val accent = tint ?: Bm.accent
    val border = when {
        selected -> accent
        tint != null -> tint.copy(alpha = 0.4f)
        else -> c.border
    }
    val bg = if (selected) accent.copy(alpha = 0.14f) else Color.Transparent
    val textColor = when {
        selected -> accent
        tint != null -> tint
        else -> c.text2
    }
    Box(
        Modifier.clip(RoundedCornerShape(20.dp)).background(bg).border(1.dp, border, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick).padding(horizontal = 15.dp, vertical = 8.dp),
    ) {
        Text(
            label, color = textColor,
            fontFamily = if (mono) MonoFont else null,
            fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

private fun Modifier.dashedBorder(color: Color, radius: Dp): Modifier = drawBehind {
    drawRoundRect(
        color = color,
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f, 7f))),
    )
}

// ────────────────────────────────────────────────────────────────────────────
// 1 · Monitoring & Stage
// ────────────────────────────────────────────────────────────────────────────

@Composable
private fun ColumnScope.MonitoringStageContent(
    state: UiState,
    onToggleMonitoring: () -> Unit,
    onSetDynamicStage: (Boolean) -> Unit,
    onSetStageHold: (Int) -> Unit,
) {
    val c = Bm.colors
    SectionLabel("Bluetooth", top = 2.dp)
    PlainCard {
        StatusHero(state, onToggleMonitoring, big = false)
        Text(
            "Connects to every reachable base. The base that's discharging (or charging) takes the main " +
                "stage and polls fast; the rest poll slowly.",
            color = c.text2, fontSize = 12.sp, lineHeight = 17.sp,
            modifier = Modifier.padding(top = 14.dp),
        )
    }

    SectionLabel("Main stage")
    GroupedCard {
        ToggleRow(
            "Dynamic main stage",
            "Automatically show the base you're using. Off: the stage stays fixed on your pick.",
            state.dynamicStage, onSetDynamicStage,
        )
        if (state.dynamicStage) {
            RowHairline(inset = 0.dp)
            Column(Modifier.padding(horizontal = 15.dp, vertical = 14.dp)) {
                Text(
                    "Active-chair hold — keep the discharging base on stage this long after it stops:",
                    color = c.text2, fontSize = 12.sp, lineHeight = 17.sp,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    STAGE_HOLD_OPTIONS_MIN.forEach { m ->
                        SelectChip("${m}m", state.stageHoldMinutes == m) { onSetStageHold(m) }
                    }
                }
            }
        }
    }
}

/** A grouped-list toggle row: title + subtitle on the left, a switch on the right. */
@Composable
internal fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val c = Bm.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = c.text2, fontSize = 12.sp, lineHeight = 17.sp,
                modifier = Modifier.padding(top = 4.dp))
        }
        BmSwitch(checked, onCheckedChange)
    }
}

// ────────────────────────────────────────────────────────────────────────────
// 2 · Alerts
// ────────────────────────────────────────────────────────────────────────────

@Composable
private fun ColumnScope.AlertsContent(
    state: UiState,
    onSetAlertsOn: (Boolean) -> Unit,
    onToggleThreshold: (Int) -> Unit,
    onSetCriticalThreshold: (Int) -> Unit,
) {
    val c = Bm.colors
    GroupedCard {
        ToggleRow("Flash stage when low", "Pulse the screen until acknowledged",
            state.alertsOn, onSetAlertsOn)
    }

    SectionLabel("Thresholds")
    PlainCard {
        Text(
            "Flash and require acknowledgement each time the lowest pack on the stage drops past one of " +
                "these levels.",
            color = c.text2, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(bottom = 12.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (row in ALERT_THRESHOLDS.chunked(3)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { t ->
                        SelectChip(
                            "$t%", t in state.enabledThresholds, mono = true,
                            tint = if (t <= state.criticalThreshold) AlertCritical else null,
                        ) { onToggleThreshold(t) }
                    }
                }
            }
        }
    }

    SectionLabel("Critical level")
    PlainCard {
        Text(
            "Alerts at or below this level flash red and pulse faster.",
            color = c.text2, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(bottom = 12.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (row in ALERT_THRESHOLDS.chunked(3)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { t ->
                        SelectChip("$t%", state.criticalThreshold == t, mono = true, tint = AlertCritical) {
                            onSetCriticalThreshold(t)
                        }
                    }
                }
            }
        }
    }

    val next = if (state.alertsOn) state.enabledThresholds.maxOrNull() else null
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(AlertCritical.copy(alpha = 0.07f))
            .border(1.dp, AlertCritical.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
            .padding(14.dp),
    ) {
        Text(
            if (next != null) "Next alert will fire when a pack on stage reaches $next%."
            else "Low-battery alerts are off.",
            color = c.text2, fontSize = 12.sp, lineHeight = 17.sp,
        )
    }
}

// ────────────────────────────────────────────────────────────────────────────
// 3 · Battery Groups
// ────────────────────────────────────────────────────────────────────────────

@Composable
private fun ColumnScope.GroupsContent(
    state: UiState,
    onSetDailyDriver: (String) -> Unit,
    onAddScan: () -> Unit,
) {
    val c = Bm.colors
    Text(
        "Two batteries per base — tap to set the daily driver (wins the stage on ties).",
        color = c.text2, fontSize = 12.sp, lineHeight = 17.sp,
    )
    state.roster.groupViews().forEach { g ->
        GroupRow(g, isDailyDriver = g.id == state.dailyDriverId) { onSetDailyDriver(g.id) }
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .dashedBorder(c.border, 10.dp)
            .clickable(onClick = onAddScan)
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Add, null, Modifier.size(18.dp), tint = c.text3)
        Text("Add base from scan", color = c.text3, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun GroupRow(group: BatteryGroup, isDailyDriver: Boolean, onClick: () -> Unit) {
    val c = Bm.colors
    val border = if (isDailyDriver) Bm.accent else c.border
    val bg = if (isDailyDriver) Bm.accent.copy(alpha = 0.14f) else Color.Transparent
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(bg)
            .border(1.dp, border, RoundedCornerShape(10.dp)).clickable(onClick = onClick)
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

// ────────────────────────────────────────────────────────────────────────────
// 4 · Appearance & Color
// ────────────────────────────────────────────────────────────────────────────

@Composable
private fun ColumnScope.AppearanceColorContent(
    state: UiState,
    onSetAppearance: (Appearance) -> Unit,
    onSetAutoLux: (Float) -> Unit,
    onSetAccent: (Color) -> Unit,
    onSetPower: (Color) -> Unit,
) {
    val c = Bm.colors
    SectionLabel("Theme", top = 2.dp)
    PlainCard {
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
                color = c.text2, fontSize = 12.sp, fontFamily = MonoFont, modifier = Modifier.padding(top = 12.dp),
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

    SectionLabel("Theme color")
    PlainCard {
        SwatchGrid(ThemeSwatches, state.accent, onSetAccent)
        CustomColorRow(state.accent)
    }

    SectionLabel("Power ring color")
    PlainCard {
        Text("Inner ring — charge / discharge rate", color = c.text2, fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 14.dp))
        SwatchGrid(PowerSwatches, state.power, onSetPower)
        CustomColorRow(state.power)
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
        modifier.clip(RoundedCornerShape(9.dp)).background(bg).border(1.dp, border, RoundedCornerShape(9.dp))
            .alpha(if (enabled) 1f else 0.4f).clickable(enabled = enabled, onClick = onClick).padding(11.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(17.dp), tint = content)
        Text(label, color = content, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun SwatchGrid(swatches: List<Color>, selected: Color, onSelect: (Color) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
        for (row in swatches.chunked(4)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                for (sw in row) {
                    Box(
                        Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(11.dp))
                            .background(sw).clickable { onSelect(sw) },
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
}

@Composable
private fun CustomColorRow(selected: Color) {
    val c = Bm.colors
    Text("Custom Color", color = c.text2, fontSize = 12.sp, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 46.dp, height = 38.dp).clip(RoundedCornerShape(8.dp)).background(selected))
        Box(
            Modifier.padding(start = 11.dp).weight(1f).clip(RoundedCornerShape(8.dp)).background(c.inputBg)
                .border(1.dp, c.inputBorder, RoundedCornerShape(8.dp)).padding(horizontal = 13.dp, vertical = 10.dp),
        ) {
            Text(selected.hex(), color = c.text, fontFamily = MonoFont, fontSize = 14.sp)
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────
// 5 · Display & Units
// ────────────────────────────────────────────────────────────────────────────

@Composable
private fun ColumnScope.DisplayUnitsContent(
    state: UiState,
    onSetTempFahrenheit: (Boolean) -> Unit,
    onSetKeepScreenOn: (Boolean) -> Unit,
) {
    val c = Bm.colors
    SectionLabel("Units", top = 2.dp)
    GroupedCard {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Temperature", color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("Unit shown on the stage stat grid", color = c.text2, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectChip("°F", state.tempFahrenheit) { onSetTempFahrenheit(true) }
                SelectChip("°C", !state.tempFahrenheit) { onSetTempFahrenheit(false) }
            }
        }
    }

    SectionLabel("Display")
    GroupedCard {
        ToggleRow(
            "Keep screen on",
            "Hold the display at your set brightness while the app is open. Turn off to let the screen " +
                "time out normally.",
            state.keepScreenOn, onSetKeepScreenOn,
        )
    }
}

// ────────────────────────────────────────────────────────────────────────────
// 6 · Lock Screen
// ────────────────────────────────────────────────────────────────────────────

@Composable
private fun ColumnScope.LockScreenContent(
    state: UiState,
    onSetLockShowTime: (Boolean) -> Unit,
    onSetLockShowWifi: (Boolean) -> Unit,
    onSetLockShowBattery: (Boolean) -> Unit,
) {
    val c = Bm.colors
    Text(
        "Screen pinning hides Android's status bar while the app is locked. Pick which info to replicate " +
            "at the top.",
        color = c.text2, fontSize = 12.sp, lineHeight = 17.sp,
    )

    SectionLabel("Show at top")
    GroupedCard {
        LockToggleRow("Time", state.lockShowTime) { onSetLockShowTime(!state.lockShowTime) }
        RowHairline(inset = 0.dp)
        LockToggleRow("Wi-Fi", state.lockShowWifi) { onSetLockShowWifi(!state.lockShowWifi) }
        RowHairline(inset = 0.dp)
        LockToggleRow("Battery", state.lockShowBattery) { onSetLockShowBattery(!state.lockShowBattery) }
    }

    // Preview of the replicated top strip.
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF0E0E0E))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        val parts = buildList {
            if (state.lockShowTime) add("9:41")
            if (state.lockShowWifi) add("Wi-Fi")
            if (state.lockShowBattery) add("100%")
        }
        Text(
            if (parts.isEmpty()) "Nothing shown" else parts.joinToString("   ·   "),
            color = Color(0xFFCFCFCF), fontFamily = MonoFont, fontSize = 13.sp,
        )
    }
}

@Composable
private fun LockToggleRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = Bm.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 15.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        if (selected) {
            Box(
                Modifier.size(22.dp).clip(CircleShape).background(Bm.accent),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Check, "On", Modifier.size(15.dp), tint = Color.White) }
        } else {
            Box(Modifier.size(22.dp).clip(CircleShape).border(1.5.dp, c.inputBorder, CircleShape))
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────
// 7 · Data & Logging
// ────────────────────────────────────────────────────────────────────────────

@Composable
private fun ColumnScope.DataLoggingContent(
    state: UiState,
    onSetLogging: (Boolean) -> Unit,
    onClearLog: () -> Unit,
) {
    val c = Bm.colors
    GroupedCard {
        ToggleRow(
            "Usage logging",
            "Record telemetry to the database for usage history and aging graphs (and to calibrate the " +
                "power ring).",
            state.logging, onSetLogging,
        )
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        StatTile("Peak draw", "${state.peakPowerW.toInt()} W", "%.1f A".format(state.peakCurrentA), Bm.power)
        StatTile("Database", state.dbSize.ifEmpty { "—" }, "logged history", c.text)
    }

    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .border(1.dp, AlertCritical.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
            .clickable(onClick = onClearLog).padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("Clear data", color = AlertCritical, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
    Text("Clearing removes logged history but not your settings.", color = c.text3, fontSize = 11.sp,
        modifier = Modifier.padding(start = 2.dp))
}

@Composable
private fun RowScope.StatTile(label: String, value: String, sub: String, valueColor: Color) {
    val c = Bm.colors
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(13.dp)).background(c.card)
            .border(1.dp, c.border, RoundedCornerShape(13.dp)).padding(14.dp),
    ) {
        Text(label.uppercase(), color = c.text3, fontFamily = MonoFont, fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
        Text(value, color = valueColor, fontFamily = MonoFont, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 6.dp))
        Text(sub, color = c.text3, fontFamily = MonoFont, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

// ────────────────────────────────────────────────────────────────────────────
// 8 · About
// ────────────────────────────────────────────────────────────────────────────

@Composable
private fun ColumnScope.AboutContent() {
    val c = Bm.colors
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(62.dp).clip(RoundedCornerShape(16.dp)).background(Bm.accent.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Bluetooth, null, Modifier.size(30.dp), tint = Bm.accent)
        }
        Text("Battery Monitor", color = c.text, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 14.dp))
        Text("v1.0", color = c.text3, fontFamily = MonoFont, fontSize = 13.sp,
            modifier = Modifier.padding(top = 4.dp))
        Text(
            "Monitors dual battery systems via Bluetooth BMS connection.",
            color = Bm.accent, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp, start = 12.dp, end = 12.dp),
        )
    }
    PlainCard {
        Text(
            "Note: Live telemetry uses the reverse-engineered Beken BMS protocol with read-only commands; " +
                "destructive commands are never sent.",
            color = c.text3, fontSize = 11.5.sp, lineHeight = 16.5.sp,
        )
    }
}
