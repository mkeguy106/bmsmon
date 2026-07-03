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
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import dev.joely.bmsmon.StageAlert
import dev.joely.bmsmon.Appearance
import dev.joely.bmsmon.UiState
import dev.joely.bmsmon.model.GroupActivity
import dev.joely.bmsmon.ui.FleetActions
import dev.joely.bmsmon.ui.RosterActions
import dev.joely.bmsmon.ui.TopBarActions
import dev.joely.bmsmon.ui.all.AllBatteriesScreen
import dev.joely.bmsmon.ui.theme.AlertCritical
import dev.joely.bmsmon.ui.theme.AlertWarn
import dev.joely.bmsmon.ui.theme.Bm
import dev.joely.bmsmon.ui.theme.MonoFont
import dev.joely.bmsmon.ui.theme.RegenGreen
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    state: UiState,
    topBar: TopBarActions,
    fleet: FleetActions,
    rosterEdit: RosterActions,
    onAcknowledge: () -> Unit,
    onHomePageChanged: (Int) -> Unit,
    locked: Boolean,
) {
    val c = Bm.colors
    // Page 0 = stage (main); page 1 = all batteries — swipe LEFT from the stage to reach it.
    // Start on the remembered page so returning from the detail screen lands you back where you were.
    val pager = rememberPagerState(initialPage = state.homePage) { 2 }
    val scope = rememberCoroutineScope()
    // Remember the current page so the detail screen's back button can restore it.
    LaunchedEffect(pager.currentPage) { onHomePageChanged(pager.currentPage) }
    val alert = state.stageAlert()
    // While locked, force the stage (page 0) and keep it there.
    LaunchedEffect(locked) { if (locked) pager.scrollToPage(0) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(c.bg)) {
            TopBar(state, pager.currentPage, topBar, locked)
            // Contextual status line under the utility bar: fleet activity + cloud sync normally,
            // escalating to the red alert strip in the same slot — so it never collides with the bar.
            StatusLine(
                state = state,
                alert = alert,
                showAlert = pager.currentPage == 0 && alert.present && !alert.flashing,
                locked = locked,
            )
            HorizontalPager(state = pager, userScrollEnabled = !locked, modifier = Modifier.weight(1f)) { page ->
                when (page) {
                    0 -> StageScreen(
                        items = state.stageItems(),
                        tempInF = state.tempFahrenheit,
                        isEmpty = state.roster.batteries.isEmpty(),
                        onAddScan = fleet.onAddScan,
                        showTempGauge = state.showTempGauge,
                        tempGaugeSide = state.tempGaugeSide,
                        thresholds = state.tempThresholdsFor(state.stageProfile().id),
                        envelope = state.stageProfile().tempEnvelope,
                    )
                    else -> AllBatteriesScreen(
                        state = state,
                        // Pinning from the list also swings the pager back to the stage.
                        fleet = fleet.copy(
                            onPinBase = { groupId ->
                                fleet.onPinBase(groupId)
                                scope.launch { pager.animateScrollToPage(0) }
                            },
                            onPinSingle = { addr ->
                                fleet.onPinSingle(addr)
                                scope.launch { pager.animateScrollToPage(0) }
                            },
                        ),
                        rosterEdit = rosterEdit,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
        // A flashing (un-acknowledged) alert still takes over the whole stage. The acknowledged
        // strip and the cloud-upload status now live in the StatusLine directly under the top bar,
        // so neither overlaps the utility row any more.
        if (pager.currentPage == 0 && alert.flashing) DangerOverlay(alert, onAcknowledge)
    }
}

/** Layered shadow so white overlay text stays legible on the colored wash in light or dark theme. */
private val overlayTextShadow = androidx.compose.ui.graphics.Shadow(
    color = Color.Black.copy(alpha = 0.55f),
    offset = androidx.compose.ui.geometry.Offset(0f, 4f),
    blurRadius = 12f,
)

/** Full-screen pulsing wash + naming headline + bottom Acknowledge bar, severity-colored. */
@Composable
internal fun DangerOverlay(alert: StageAlert, onAcknowledge: () -> Unit) {
    val flashColor = if (alert.critical) AlertCritical else AlertWarn
    val peak = if (alert.critical) 0.58f else 0.34f
    val duration = if (alert.critical) 1000 else 1500
    // Safe fallback (UI-13c): activeThreshold is null for temperature alerts — never render
    // "BELOW null%"; without a threshold just name the SOC.
    val fallbackDetail = alert.activeThreshold
        ?.let { "LOW BATTERY · ${alert.lowSoc}% · BELOW $it%" }
        ?: "LOW BATTERY · ${alert.lowSoc}%"
    val detail = alert.detail.ifBlank { fallbackDetail }

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

    // Naming headline + detail near the top.
    Column(
        Modifier.fillMaxWidth().padding(top = 96.dp, start = 22.dp, end = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.Warning, null, Modifier.size(20.dp), tint = Color.White)
        Text(
            alert.headline, color = Color.White, fontSize = 42.sp, fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
            style = androidx.compose.ui.text.TextStyle(shadow = overlayTextShadow),
        )
        Text(
            detail, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
            style = androidx.compose.ui.text.TextStyle(
                fontFamily = dev.joely.bmsmon.ui.theme.MonoFont, shadow = overlayTextShadow,
            ),
        )
    }

    // Acknowledge bar pinned to the bottom.
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.Bottom) {
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

/**
 * Contextual status line directly under the utility bar. It carries one honest line about the fleet:
 * normally the stage's activity/mode on the left and cloud-sync status on the right; when a capacity
 * or temperature alert is acknowledged, the whole line escalates to the red [AlertPill] in the same
 * slot. Because it's a real row in the column (not an overlay), it can never collide with the bar.
 */
@Composable
private fun StatusLine(state: UiState, alert: StageAlert, showAlert: Boolean, locked: Boolean) {
    val c = Bm.colors
    Box(Modifier.fillMaxWidth().background(c.bg).padding(start = 18.dp, end = 18.dp, bottom = 10.dp)) {
        if (showAlert) {
            AlertPill(alert)
        } else {
            val st = stageState(state, locked)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(st.text, color = st.color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.9.sp, fontFamily = MonoFont)
                if (state.cloudEnabled && state.enrolled) UploadBadge(state)
            }
        }
    }
}

private data class StageStateLabel(val text: String, val color: Color)

/** The stage's live activity/mode as a short colored label (base name lives in the utility row). */
@Composable
private fun stageState(state: UiState, locked: Boolean): StageStateLabel {
    val c = Bm.colors
    val (text, color) = when {
        !state.monitoring -> "MONITORING OFF" to c.text3
        state.stageRegen -> "REGEN ↻" to RegenGreen
        state.pinned -> "PINNED" to Bm.accent
        !state.dynamicStage -> "MANUAL" to c.text2
        state.stageActivity == GroupActivity.Discharging -> "DISCHARGING" to Bm.power
        state.stageActivity == GroupActivity.Charging -> "CHARGING" to Bm.accent
        state.stageActivity == GroupActivity.Idle -> "IDLE" to c.text2
        else -> "…" to c.text3
    }
    return StageStateLabel(if (locked) "$text · LOCKED" else text, color)
}

/** The acknowledged-alert form of the status line: a red strip naming the alert + its reading. */
@Composable
private fun AlertPill(alert: StageAlert) {
    val c = Bm.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(AlertCritical.copy(alpha = 0.10f))
            .border(1.dp, AlertCritical.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Warning, null, Modifier.size(14.dp), tint = AlertCritical)
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(alert.headline, color = AlertCritical, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp, fontFamily = MonoFont)
            Text(alert.detail.ifBlank { "${alert.lowSoc}%" }, color = c.text2, fontSize = 9.sp,
                fontFamily = MonoFont, maxLines = 1)
        }
        Text("ACK'D", color = c.text3, fontSize = 9.sp, fontFamily = MonoFont)
    }
}

@Composable
private fun TopBar(
    state: UiState,
    currentPage: Int,
    actions: TopBarActions,
    locked: Boolean,
) {
    val c = Bm.colors
    // Utility row = pure identity (base name + pin) · page dots · actions. The live activity/mode
    // and alert now live one line down in StatusLine, so this row stays uncrowded and fixed-width.
    Box(Modifier.fillMaxWidth().background(c.bg).padding(start = 18.dp, top = 12.dp, end = 18.dp, bottom = 4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                if (locked) Modifier else Modifier.clickable(onClick = actions.onToggleMonitoring),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (state.monitoring) Icons.Filled.Bluetooth else Icons.Filled.BluetoothDisabled,
                    null, Modifier.size(18.dp),
                    tint = if (state.monitoring) Bm.accent else c.text3,
                )
                if (state.pinned) Icon(Icons.Filled.PushPin, null, Modifier.padding(start = 6.dp).size(13.dp), tint = Bm.accent)
                Text(
                    state.stageLabel,
                    color = if (state.monitoring) c.text else c.text3,
                    fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp,
                    modifier = Modifier.padding(start = 7.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // While locked, hide appearance + settings; only the lock control remains (hold to unlock).
                if (!locked) {
                    Box(Modifier.size(40.dp).clickable(onClick = actions.onCycleAppearance), contentAlignment = Alignment.Center) {
                        when (state.appearance) {
                            Appearance.Dark -> Icon(Icons.Filled.DarkMode, "Appearance: Dark", Modifier.size(21.dp), tint = c.icon)
                            Appearance.Light -> Icon(Icons.Filled.LightMode, "Appearance: Light", Modifier.size(21.dp), tint = c.icon)
                            Appearance.System -> Text("S", color = c.icon, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            Appearance.Auto -> Text("A", color = c.icon, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Box(Modifier.size(40.dp).clickable(onClick = actions.onHistory), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.ShowChart, "History", Modifier.size(22.dp), tint = c.icon)
                    }
                    Box(Modifier.size(40.dp).clickable(onClick = actions.onSettings), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Settings, "Settings", Modifier.size(22.dp), tint = c.icon)
                    }
                }
                LockButton(
                    locked = locked,
                    onToggle = actions.onToggleLock,
                    iconTint = if (locked) Bm.accent else c.icon,
                    ringColor = Bm.accent,
                )
            }
        }
        // Page dots centered on the top-bar row (hidden while locked — the stage is frozen on page 0).
        if (!locked) PageDots(currentPage, Modifier.align(Alignment.Center))
    }
}

/** Tiny cloud-upload status in the stage's bottom-right: live KB/s while uploading, else synced/queued. */
@Composable
private fun UploadBadge(state: UiState, modifier: Modifier = Modifier) {
    val c = Bm.colors
    val kbps = state.cloudUploadKbps
    val (text, color) = when {
        state.cloudAuthFailed -> "↑ auth failed" to AlertCritical
        kbps > 0.05f -> "↑ %.1f KB/s".format(kbps) to RegenGreen
        state.cloudOutboxDepth > 0 -> "↑ ${state.cloudOutboxDepth} queued" to AlertWarn
        state.cloudLastUploadMs > 0L -> "↑ synced" to c.text3
        else -> "↑ idle" to c.text3
    }
    Text(
        text, color = color, fontSize = 9.5.sp, fontWeight = FontWeight.Medium,
        letterSpacing = 0.6.sp, modifier = modifier,
    )
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
