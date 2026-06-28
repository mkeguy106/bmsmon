package dev.joely.bmsmon.ui.detail

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.joely.bmsmon.UiState
import dev.joely.bmsmon.model.batteryAt
import dev.joely.bmsmon.model.groupOf
import dev.joely.bmsmon.ui.theme.Bm
import dev.joely.bmsmon.ui.theme.MonoFont
import kotlin.math.roundToInt

@Composable
fun BatteryDetailScreen(state: UiState, onBack: () -> Unit) {
    val c = Bm.colors
    val address = state.detailAddress
    val battery = address?.let { state.roster.batteryAt(it) }
    val tele = address?.let { state.fleet[it]?.telemetry }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onBack),
                contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", Modifier.size(22.dp), tint = c.icon)
            }
            Text(battery?.alias ?: "Battery", color = c.text, fontSize = 19.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp))
        }

        if (battery == null) {
            Text("Battery not found.", color = c.text3, fontSize = 14.sp, modifier = Modifier.padding(18.dp))
            return@Column
        }

        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {

            Section("Identity") {
                KeyVal("Alias", battery.alias)
                KeyVal("Real name", battery.advertisedName)
                KeyVal("MAC", battery.address, mono = true)
                KeyVal("Group", state.roster.groupOf(battery.address)?.label ?: "Ungrouped")
            }

            if (tele == null) {
                Section("Telemetry") {
                    Text("No live or stored reading yet. Start monitoring to populate.",
                        color = Bm.colors.text3, fontSize = 13.sp)
                }
            } else {
                val tempC = tele.temp
                val temp = if (state.tempFahrenheit) tempC * 9f / 5f + 32f else tempC
                val tUnit = if (state.tempFahrenheit) "°F" else "°C"
                val mosC = tele.mosfetTemp.toFloat()
                val mos = if (state.tempFahrenheit) mosC * 9f / 5f + 32f else mosC

                Section("State of charge") {
                    KeyVal("SOC", "${tele.soc.roundToInt()} %", mono = true)
                    KeyVal("SOH", "${tele.soh} %", mono = true)
                    KeyVal("Cycles", tele.cycles.toString(), mono = true)
                    KeyVal("Capacity", "%.1f / %.1f Ah".format(tele.capacityAh, tele.fullChargeAh), mono = true)
                }
                Section("Power") {
                    KeyVal("Voltage", "%.2f V".format(tele.voltage), mono = true)
                    KeyVal("Current", "%.2f A".format(tele.current), mono = true)
                    KeyVal("Power", "%.1f W".format(tele.powerW), mono = true)
                    KeyVal("State", tele.state.name, mono = false)
                }
                Section("Temperature") {
                    KeyVal("Cell temp", "%.1f %s".format(temp, tUnit), mono = true)
                    KeyVal("MOSFET temp", "%.1f %s".format(mos, tUnit), mono = true)
                }
                Section("Cells (${tele.cells.size})") {
                    if (tele.cells.isEmpty()) {
                        Text("No per-cell data in the last reading.", color = c.text3, fontSize = 13.sp)
                    } else {
                        val mn = tele.cells.min(); val mx = tele.cells.max()
                        KeyVal("Min / Max", "%.3f / %.3f V".format(mn, mx), mono = true)
                        KeyVal("Delta", "%.0f mV".format((mx - mn) * 1000f), mono = true)
                        tele.cells.forEachIndexed { i, v ->
                            val color = when {
                                mn < mx && v == mx -> Bm.accent
                                mn < mx && v == mn -> Bm.power
                                else -> c.text
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Cell ${i + 1}", color = c.text2, fontSize = 13.sp)
                                Text("%.3f V".format(v), color = color, fontFamily = MonoFont, fontSize = 13.sp)
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

            Section("History") {
                Text("Graphs from logged data — coming soon.", color = Bm.colors.text3, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val c = Bm.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(c.card2)
            .border(1.dp, c.border, RoundedCornerShape(11.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title.uppercase(), color = c.text3, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
        content()
    }
}

@Composable
private fun KeyVal(key: String, value: String, mono: Boolean = false) {
    val c = Bm.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(key, color = c.text2, fontSize = 13.sp)
        Text(value, color = c.text, fontSize = 13.sp,
            fontFamily = if (mono) MonoFont else null, fontWeight = FontWeight.SemiBold)
    }
}
