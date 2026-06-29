package dev.joely.bmsmon.ui.history

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import dev.joely.bmsmon.data.db.SessionEntity
import dev.joely.bmsmon.ui.charts.ChartSeries
import dev.joely.bmsmon.ui.charts.LineChart
import dev.joely.bmsmon.ui.theme.Bm

/**
 * The three core aging graphs for a set of [sessions] (one point per session, x = session index so
 * gaps collapse): peak discharge power, estimated internal resistance, and capacity & SOH.
 */
@Composable
fun BatteryGraphs(sessions: List<SessionEntity>, accent: Color, power: Color) {
    val c = Bm.colors
    if (sessions.size < 2) {
        Text("Not enough history yet — keep monitoring to build trends.",
            color = c.text3, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
        return
    }

    Graph("Peak power per session (W)", c.text2) {
        LineChart(
            series = listOf(
                ChartSeries("peak", power, sessions.map { it.peakPowerW }),
                ChartSeries("p95", accent, sessions.map { it.p95PowerW }),
            ),
            modifier = Modifier.fillMaxWidth().height(140.dp),
        )
    }

    // Skip sessions with no trustworthy estimate (null = too little load variation) rather than
    // plotting them as a misleading 0 mΩ dip.
    val irSeries = sessions.mapNotNull { it.estInternalResistanceMohm }
    Graph("Internal resistance (mΩ) — rising = aging", c.text2) {
        if (irSeries.size < 2) {
            Text("Not enough load variation yet to estimate resistance.",
                color = c.text3, fontSize = 11.sp, modifier = Modifier.padding(4.dp))
        } else {
            LineChart(
                series = listOf(ChartSeries("mΩ", accent, irSeries)),
                modifier = Modifier.fillMaxWidth().height(140.dp),
            )
        }
    }

    Graph("Capacity (Ah) & SOH (%) vs cycles", c.text2) {
        LineChart(
            series = listOf(
                ChartSeries("Ah", accent, sessions.map { it.fullChargeAhEnd }),
                ChartSeries("SOH", power, sessions.map { it.sohEnd.toFloat() }),
            ),
            modifier = Modifier.fillMaxWidth().height(140.dp),
        )
    }
}

@Composable
private fun Graph(title: String, titleColor: Color, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(title, color = titleColor, fontSize = 12.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 4.dp))
        content()
    }
}
