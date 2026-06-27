package dev.joely.bmsmon.ui.home

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.joely.bmsmon.UiState
import dev.joely.bmsmon.model.Telemetry
import dev.joely.bmsmon.ui.gauge.DualRingGauge
import dev.joely.bmsmon.ui.theme.Bm
import dev.joely.bmsmon.ui.theme.MonoFont
import kotlin.math.roundToInt

@Composable
fun HomeScreen(
    state: UiState,
    onToggleMode: () -> Unit,
    onSettings: () -> Unit,
) {
    val c = Bm.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(c.bg),
    ) {
        Header(state, onToggleMode, onSettings)
        state.batteries.forEach { b ->
            BatteryBlock(b, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Header(state: UiState, onToggleMode: () -> Unit, onSettings: () -> Unit) {
    val c = Bm.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.bg)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // connection status
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.connected) {
                Icon(Icons.Filled.Bluetooth, null, Modifier.size(18.dp), tint = Bm.accent)
                Text(
                    "CONNECTED",
                    color = Bm.accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.9.sp,
                    modifier = Modifier.padding(start = 7.dp),
                )
            } else {
                Icon(Icons.Filled.BluetoothDisabled, null, Modifier.size(18.dp), tint = c.text3)
                Text(
                    "DEMO DATA",
                    color = c.text3,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.9.sp,
                    modifier = Modifier.padding(start = 7.dp),
                )
            }
        }
        // actions
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clickable(onClick = onToggleMode),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (state.isDark) Icons.Filled.DarkMode else Icons.Filled.LightMode,
                    contentDescription = "Toggle theme",
                    modifier = Modifier.size(21.dp),
                    tint = c.icon,
                )
            }
            Box(
                Modifier.size(40.dp).clickable(onClick = onSettings),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Settings, "Settings", Modifier.size(22.dp), tint = c.icon)
            }
        }
    }
}

@Composable
private fun BatteryBlock(b: Telemetry, modifier: Modifier = Modifier) {
    val c = Bm.colors
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(170.dp), contentAlignment = Alignment.Center) {
            DualRingGauge(
                soc = b.soc,
                powerW = b.powerW,
                accent = Bm.accent,
                power = Bm.power,
                segEmpty = c.segEmpty,
                innerTrack = c.innerTrack,
                modifier = Modifier.fillMaxSize(),
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "${b.soc.roundToInt()}%",
                    color = Bm.accent,
                    fontFamily = MonoFont,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "${b.powerW.roundToInt()}W",
                    color = c.text2,
                    fontFamily = MonoFont,
                    fontSize = 14.sp,
                )
            }
        }
        Text(
            b.name.uppercase(),
            color = c.name,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 2.2.sp,
            modifier = Modifier.padding(top = 10.dp),
        )
        StatGrid(b, Modifier.padding(top = 12.dp))
    }
}

private data class Stat(val label: String, val value: String, val unit: String)

@Composable
private fun StatGrid(b: Telemetry, modifier: Modifier = Modifier) {
    val stats = listOf(
        Stat("Power", "%.1f".format(b.powerW), "W"),
        Stat("Current", "%.2f".format(b.current), "A"),
        Stat("Voltage", "%.1f".format(b.voltage), "V"),
        Stat("Capacity", b.capacityAh.roundToInt().toString(), "Ah"),
        Stat("Cell V", "%.2f".format(b.cellV), "V"),
        Stat("Temp", "%.1f".format(b.temp), "°C"),
    )
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in stats.chunked(3)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (s in row) StatCell(s, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatCell(s: Stat, modifier: Modifier = Modifier) {
    val c = Bm.colors
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(c.card2)
            .border(1.dp, c.border, RoundedCornerShape(8.dp))
            .padding(horizontal = 11.dp, vertical = 9.dp),
    ) {
        Text(
            s.label.uppercase(),
            color = c.text3,
            fontSize = 10.sp,
            letterSpacing = 0.7.sp,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                s.value,
                color = Bm.accent,
                fontFamily = MonoFont,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                s.unit,
                color = c.text2,
                fontSize = 11.sp,
                textAlign = TextAlign.Start,
                modifier = Modifier.padding(start = 3.dp),
            )
        }
    }
}
