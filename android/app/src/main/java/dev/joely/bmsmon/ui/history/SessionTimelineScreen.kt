@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.joely.bmsmon.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.joely.bmsmon.data.TimelineBucket
import dev.joely.bmsmon.data.db.SessionEntity
import dev.joely.bmsmon.ui.charts.ChartAxis
import dev.joely.bmsmon.ui.charts.ChartMarker
import dev.joely.bmsmon.ui.charts.ChartSeries
import dev.joely.bmsmon.ui.charts.TimeSeriesChart
import dev.joely.bmsmon.ui.charts.niceCeil
import dev.joely.bmsmon.ui.charts.spreadIndices
import dev.joely.bmsmon.ui.theme.Bm
import dev.joely.bmsmon.ui.theme.MonoFont
import dev.joely.bmsmon.ui.theme.VoltageSeries
import kotlin.math.roundToInt

/**
 * Full-resolution drill-down into one run: discharge/regen power, voltage sag and SOC on three
 * stacked tracks sharing one scrub crosshair, with BLE link-loss marked. Fed by peak-pooled buckets
 * so transient spikes survive. In-app port of the prototype's `session()`.
 */
@Composable
fun SessionTimelineScreen(alias: String, session: SessionEntity, buckets: List<TimelineBucket>, onBack: () -> Unit) {
    val c = Bm.colors
    val accent = Bm.accent
    val power = Bm.power
    val durMin = ((session.endMs - session.startMs) / 60_000L).toInt()

    Column(Modifier.fillMaxSize().background(c.bg)) {
        ScreenHeader(
            "Session · ${hhmm(session.startMs)}",
            "${hhmm(session.startMs)}–${hhmm(session.endMs)} · ${"%.1f".format(durMin / 60f)}h · ${session.sampleCount} samples",
            onBack,
        )
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            // run summary chips
            SectionCard("$alias · run summary") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    SummaryChip("Duration", "${"%.1f".format(durMin / 60f)} h")
                    SummaryChip("Energy", "${"%.1f".format(session.energyWh)} Wh")
                    SummaryChip("Peak", "${session.peakPowerW.roundToInt()} W")
                    SummaryChip("Regen", "${session.peakRegenW.roundToInt()} W")
                    SummaryChip("SOC", "${session.socStart.roundToInt()}→${session.socEnd.roundToInt()}%")
                    SummaryChip("Min V", "${"%.2f".format(session.minVoltageUnderLoad)} V")
                }
            }

            if (buckets.size < 2) {
                SectionCard("Run timeline") {
                    Text("Not enough samples in this run to chart.", color = c.text3, fontSize = 12.sp)
                }
                return@Column
            }

            // hidden-series state
            var hidden by remember { mutableStateOf(setOf<String>()) }
            fun toggle(k: String) { hidden = if (k in hidden) hidden - k else hidden + k }

            val start = buckets.first().tsMs
            val xs = buckets.map { (it.tsMs - start).toFloat() }
            val xLabels = buckets.map { hhmm(it.tsMs) }
            val dis = buckets.map { it.dischargeW }
            val reg = buckets.map { it.regenW }
            val volt = buckets.map { it.voltageV }
            val soc = buckets.map { it.soc }
            val validV = volt.filterNotNull()
            val validS = soc.filterNotNull()
            val pwMax = niceCeil(maxOf(dis.maxOrNull() ?: 0f, reg.maxOrNull() ?: 0f), 100f)
            val markers = buckets.mapIndexedNotNull { i, b -> if (b.link) ChartMarker(i, c.critical) else null }

            ChartCard(
                "Run timeline",
                "Discharge / regen power, voltage sag & SOC · scrub anywhere · ● = link-loss",
                legend = {
                    FlowRow(Modifier.padding(bottom = 9.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LegendChip("Discharge", power, "dis" !in hidden) { toggle("dis") }
                        LegendChip("Regen", c.regen, "reg" !in hidden) { toggle("reg") }
                        LegendChip("Voltage", VoltageSeries, "v" !in hidden) { toggle("v") }
                        LegendChip("SOC", accent, "soc" !in hidden) { toggle("soc") }
                    }
                },
            ) {
                TimeSeriesChart(
                    xs = xs,
                    xLabels = xLabels,
                    xTickIndices = spreadIndices(5, buckets.size),
                    axes = listOf(
                        ChartAxis("pw", 0f, pwMax, band = 0, title = "Power (W)", ticks = 2, fmt = { it.roundToInt().toString() }),
                        ChartAxis("v", (validV.minOrNull() ?: 0f) - 0.05f, (validV.maxOrNull() ?: 1f) + 0.05f,
                            band = 1, title = "Voltage (V)", ticks = 2, fmt = { "%.1f".format(it) }),
                        ChartAxis("soc", (validS.minOrNull() ?: 0f) - 1f, (validS.maxOrNull() ?: 100f) + 1f,
                            band = 2, title = "SOC (%)", ticks = 2, fmt = { it.roundToInt().toString() }),
                    ),
                    series = listOf(
                        ChartSeries("dis", "pw", power, dis, area = true, fillAlpha = 0.34f, widthDp = 1.4f, label = "Discharge", fmt = { "${it.roundToInt()} W" }),
                        ChartSeries("reg", "pw", c.regen, reg, area = true, fillAlpha = 0.4f, widthDp = 1.4f, label = "Regen", fmt = { "${it.roundToInt()} W" }),
                        ChartSeries("v", "v", VoltageSeries, volt, widthDp = 1.8f, label = "Voltage", fmt = { "%.2f V".format(it) }),
                        ChartSeries("soc", "soc", accent, soc, widthDp = 2f, label = "SOC", fmt = { "${it.roundToInt()} %" }),
                    ),
                    bands = 3,
                    hidden = hidden,
                    markers = markers,
                    padTopDp = 16f,
                    padBottomDp = 22f,
                    modifier = Modifier.fillMaxWidth().height(350.dp),
                )
            }
        }
    }
}

@Composable
private fun SummaryChip(key: String, value: String) {
    val c = Bm.colors
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).background(c.bg).border(1.dp, c.border, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Text("$key ", color = c.text3, fontSize = 9.5f.sp)
        Text(value, color = c.text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, fontFamily = MonoFont)
    }
}
