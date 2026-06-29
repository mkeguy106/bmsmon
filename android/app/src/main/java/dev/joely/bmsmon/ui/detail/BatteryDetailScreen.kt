@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.joely.bmsmon.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.joely.bmsmon.UiState
import dev.joely.bmsmon.data.db.SessionEntity
import dev.joely.bmsmon.model.batteryAt
import dev.joely.bmsmon.model.groupOf
import dev.joely.bmsmon.ui.theme.Bm
import dev.joely.bmsmon.ui.theme.MonoFont
import kotlin.math.roundToInt

@Composable
fun BatteryDetailScreen(state: UiState, sessions: List<SessionEntity>, onBack: () -> Unit, onOpenReview: () -> Unit) {
    val c = Bm.colors
    val address = state.detailAddress
    val battery = address?.let { state.roster.batteryAt(it) }
    val tele = address?.let { state.fleet[it]?.telemetry }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onBack),
                contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", Modifier.size(20.dp), tint = c.icon)
            }
            Text(battery?.alias ?: "Battery", color = c.text, fontSize = 18.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp))
        }

        if (battery == null) {
            Text("Battery not found.", color = c.text3, fontSize = 14.sp, modifier = Modifier.padding(18.dp))
            return@Column
        }

        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {

            // Primary action at the top: the derived health & usage review.
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(c.card2)
                    .border(1.dp, Bm.accent.copy(alpha = 0.5f), RoundedCornerShape(11.dp))
                    .clickable(onClick = onOpenReview).padding(horizontal = 13.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Health & Usage Review", color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("Derived resistance, V–I cloud & usage trends", color = c.text3, fontSize = 11.sp)
                }
                Text("›", color = Bm.accent, fontSize = 22.sp)
            }

            Section("Identity") {
                KeyVal("Alias", battery.alias)
                KeyVal("Real name", battery.advertisedName)
                KeyVal("MAC", battery.address, mono = true)
                KeyVal("Group", state.roster.groupOf(battery.address)?.label ?: "Ungrouped")
            }

            if (tele == null) {
                Section("Telemetry") {
                    Text("No live or stored reading yet. Start monitoring to populate.",
                        color = c.text3, fontSize = 13.sp)
                }
            } else {
                val tUnit = if (state.tempFahrenheit) "°F" else "°C"
                fun conv(v: Float) = if (state.tempFahrenheit) v * 9f / 5f + 32f else v

                // One condensed "Telemetry" card instead of three (SOC / Power / Temperature).
                Section("Telemetry") {
                    KeyVal("SOC", "${tele.soc.roundToInt()} %", mono = true)
                    KeyVal("SOH", "${tele.soh} %", mono = true)
                    KeyVal("Cycles", tele.cycles.toString(), mono = true)
                    KeyVal("Capacity", "%.1f / %.1f Ah".format(tele.capacityAh, tele.fullChargeAh), mono = true)
                    KeyVal("Voltage", "%.2f V".format(tele.voltage), mono = true)
                    KeyVal("Current", "%.2f A".format(tele.current), mono = true)
                    KeyVal("Power", "%.1f W".format(tele.powerW), mono = true)
                    KeyVal("State", tele.state.name)
                    KeyVal("Cell temp", "%.1f %s".format(conv(tele.temp), tUnit), mono = true)
                    KeyVal("MOSFET temp", "%.1f %s".format(conv(tele.mosfetTemp.toFloat()), tUnit), mono = true)
                }

                Section("Cells (${tele.cells.size})") {
                    if (tele.cells.isEmpty()) {
                        Text("No per-cell data in the last reading.", color = c.text3, fontSize = 13.sp)
                    } else {
                        val mn = tele.cells.min(); val mx = tele.cells.max()
                        KeyVal("Min / Max", "%.3f / %.3f V".format(mn, mx), mono = true)
                        KeyVal("Delta", "%.0f mV".format((mx - mn) * 1000f), mono = true)
                        FlowRow(
                            Modifier.fillMaxWidth().padding(top = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            tele.cells.forEachIndexed { i, v ->
                                val color = when {
                                    mn < mx && v == mx -> Bm.accent
                                    mn < mx && v == mn -> Bm.power
                                    else -> c.text
                                }
                                CellChip(i + 1, v, color)
                            }
                        }
                    }
                }

                if (tele.protections.isNotEmpty()) {
                    Section("Active protections") {
                        tele.protections.forEach { p -> Text("• $p", color = Bm.power, fontSize = 13.sp) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CellChip(index: Int, v: Float, valueColor: Color) {
    val c = Bm.colors
    Row(
        Modifier.clip(RoundedCornerShape(7.dp)).background(c.inputBg).padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text("$index", color = c.text3, fontSize = 11.sp, fontFamily = MonoFont)
        Text("%.3f".format(v), color = valueColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, fontFamily = MonoFont)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val c = Bm.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(c.card2)
            .border(1.dp, c.border, RoundedCornerShape(11.dp)).padding(horizontal = 13.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(title.uppercase(), color = c.text3, fontSize = 10.5f.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.7.sp,
            modifier = Modifier.padding(bottom = 1.dp))
        content()
    }
}

@Composable
private fun KeyVal(key: String, value: String, mono: Boolean = false) {
    val c = Bm.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(key, color = c.text2, fontSize = 12.5f.sp)
        Text(value, color = c.text, fontSize = 12.5f.sp,
            fontFamily = if (mono) MonoFont else null, fontWeight = FontWeight.SemiBold)
    }
}
