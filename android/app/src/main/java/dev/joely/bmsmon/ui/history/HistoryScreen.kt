package dev.joely.bmsmon.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.joely.bmsmon.data.db.SessionEntity
import dev.joely.bmsmon.ui.charts.ChartSeries
import dev.joely.bmsmon.ui.charts.LineChart
import dev.joely.bmsmon.ui.theme.Bm
import dev.joely.bmsmon.ui.theme.ThemeSwatches

/**
 * Fleet-wide history: every pack's internal-resistance aging trend overlaid on one axis so an
 * outlier (a weakening pack) stands out. One line per address, colored from the theme swatches.
 */
@Composable
fun HistoryScreen(sessions: List<SessionEntity>, accent: Color, onBack: () -> Unit) {
    val c = Bm.colors
    val byAddress = sessions.groupBy { it.address }.toList()
    Column(Modifier.fillMaxSize().background(c.bg).verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Fleet history", color = c.text, fontSize = 18.sp, modifier = Modifier.padding(bottom = 8.dp))
        if (byAddress.isEmpty()) {
            Text("No sessions recorded yet.", color = c.text3, fontSize = 12.sp)
        } else {
            Text(
                "Internal resistance (mΩ) — higher / rising lines are weaker packs",
                color = c.text2, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp),
            )
            LineChart(
                series = byAddress.mapIndexed { i, (_, list) ->
                    ChartSeries(
                        label = list.first().address,
                        color = ThemeSwatches[i % ThemeSwatches.size],
                        points = list.map { it.estInternalResistanceMohm ?: 0f },
                    )
                },
                modifier = Modifier.fillMaxWidth().height(200.dp),
            )
            byAddress.forEachIndexed { i, (addr, _) ->
                Text(
                    "● $addr", color = ThemeSwatches[i % ThemeSwatches.size], fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Text(
            "Back", color = accent, fontSize = 14.sp,
            modifier = Modifier.padding(top = 16.dp).clickable { onBack() },
        )
    }
}
