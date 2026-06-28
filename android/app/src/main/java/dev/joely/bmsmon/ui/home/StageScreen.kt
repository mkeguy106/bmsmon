package dev.joely.bmsmon.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.joely.bmsmon.model.BatteryState
import dev.joely.bmsmon.model.StageItem
import dev.joely.bmsmon.model.Telemetry
import dev.joely.bmsmon.ui.ChargingBolt
import dev.joely.bmsmon.ui.gauge.DualRingGauge
import dev.joely.bmsmon.ui.rememberBoltAlpha
import dev.joely.bmsmon.ui.theme.Bm
import dev.joely.bmsmon.ui.theme.MonoFont
import dev.joely.bmsmon.ui.theme.RegenGreen
import kotlin.math.roundToInt

/** The main stage: the active base's two packs, full gauges. */
@Composable
fun StageScreen(items: List<StageItem>, tempInF: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        items.forEach { item ->
            BatteryBlock(item, tempInF, Modifier.weight(1f))
        }
    }
}

@Composable
private fun BatteryBlock(item: StageItem, tempInF: Boolean, modifier: Modifier = Modifier) {
    val c = Bm.colors
    val b = item.telemetry
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(170.dp), contentAlignment = Alignment.Center) {
            DualRingGauge(
                // When disconnected we have no trustworthy SOC/power — draw an empty, dimmed ring
                // rather than a real-looking 0% that would imply a flat battery.
                soc = if (item.connected) b.soc else 0f,
                powerW = if (item.connected) b.powerW else 0f,
                accent = if (item.connected) Bm.accent else c.text3,
                power = if (item.connected) Bm.power else c.text3,
                segEmpty = c.segEmpty,
                innerTrack = c.innerTrack,
                modifier = Modifier.fillMaxSize(),
                regen = item.connected && item.regen,
            )
            // Charging bolt pulses behind the readout, peaking bolder at the top of each beat.
            if (item.connected && b.state == BatteryState.Charging) {
                ChargingBoltIcon()
            }
            if (item.connected) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "${b.soc.roundToInt()}%",
                        color = Bm.accent,
                        fontFamily = MonoFont,
                        fontSize = 40.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (item.regen) "↻ +${b.powerW.roundToInt()}W" else "${b.powerW.roundToInt()}W",
                        color = if (item.regen) RegenGreen else c.text2,
                        fontFamily = MonoFont,
                        fontSize = 14.sp,
                        fontWeight = if (item.regen) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            } else {
                DisconnectedReadout()
            }
        }
        Text(
            b.name.uppercase(),
            color = if (item.connected) c.name else c.text3,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 2.2.sp,
            modifier = Modifier.padding(top = 10.dp),
        )
        StatGrid(b, tempInF, item.connected, Modifier.padding(top = 12.dp))
    }
}

/** Center readout for a stage pack that isn't reachable: a dash and a DISCONNECTED tag, no %. */
@Composable
private fun DisconnectedReadout() {
    val c = Bm.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            Icons.Filled.BluetoothDisabled,
            contentDescription = null,
            modifier = Modifier.size(30.dp),
            tint = c.text3,
        )
        Text(
            "—",
            color = c.text3,
            fontFamily = MonoFont,
            fontSize = 34.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "DISCONNECTED",
            color = c.text3,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.4.sp,
        )
    }
}

/** The charging bolt, pulsing its opacity (bolder at the peak of each beat). */
@Composable
private fun ChargingBoltIcon() {
    Icon(
        ChargingBolt,
        contentDescription = null,
        modifier = Modifier.size(width = 116.dp, height = 158.dp),
        tint = Bm.accent.copy(alpha = rememberBoltAlpha(0.20f, 0.70f)),
    )
}

private data class Stat(val label: String, val value: String, val unit: String)

@Composable
private fun StatGrid(b: Telemetry, tempInF: Boolean, connected: Boolean, modifier: Modifier = Modifier) {
    val temp = if (tempInF) b.temp * 9f / 5f + 32f else b.temp
    // While disconnected we have no live values — show dashes instead of stale/zero numbers.
    fun v(value: String) = if (connected) value else "—"
    val stats = listOf(
        Stat("Power", v("%.1f".format(b.powerW)), "W"),
        Stat("Current", v("%.2f".format(b.current)), "A"),
        Stat("Voltage", v("%.1f".format(b.voltage)), "V"),
        Stat("Capacity", v(b.capacityAh.roundToInt().toString()), "Ah"),
        Stat("Cell V", v("%.2f".format(b.cellV)), "V"),
        Stat("Temp", v("%.1f".format(temp)), if (tempInF) "°F" else "°C"),
    )
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in stats.chunked(3)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (s in row) StatCell(s, connected, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatCell(s: Stat, connected: Boolean, modifier: Modifier = Modifier) {
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
            Text(s.value, color = if (connected) Bm.accent else c.text3, fontFamily = MonoFont,
                fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (connected) {
                Text(s.unit, color = c.text2, fontSize = 11.sp, modifier = Modifier.padding(start = 3.dp))
            }
        }
    }
}
