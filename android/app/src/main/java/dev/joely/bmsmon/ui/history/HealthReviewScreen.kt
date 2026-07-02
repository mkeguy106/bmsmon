@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.joely.bmsmon.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.joely.bmsmon.data.PackHealth
import dev.joely.bmsmon.data.SessionRollup
import dev.joely.bmsmon.data.Verdict
import dev.joely.bmsmon.data.verdictFor
import dev.joely.bmsmon.ui.charts.Baseline
import dev.joely.bmsmon.ui.charts.ChartAxis
import dev.joely.bmsmon.ui.charts.ChartSeries
import dev.joely.bmsmon.ui.charts.ScatterPlot
import dev.joely.bmsmon.ui.charts.TimeSeriesChart
import dev.joely.bmsmon.ui.charts.niceCeil
import dev.joely.bmsmon.ui.charts.spreadIndices
import dev.joely.bmsmon.ui.theme.Bm
import dev.joely.bmsmon.ui.theme.MonoFont
import kotlin.math.roundToInt

private data class UsageMetric(
    val key: String, val label: String, val unit: String, val color: Color,
    val values: List<Float?>, val fmt: (Float) -> String,
)

/**
 * The recommended per-battery screen: a health verdict, then the derived effective-resistance metric
 * (with the R-vs-SOC aging curve), the V–I operating cloud the number comes from, usage across
 * sessions, and a tap-through list of recent runs. In-app port of the prototype's `review()`.
 */
@Composable
fun HealthReviewScreen(pack: PackHealth, onBack: () -> Unit, onOpenTimeline: (Long) -> Unit) {
    val c = Bm.colors
    val accent = Bm.accent
    val power = Bm.power
    val r = pack.rMohm
    val cd = pack.cellDeltaMv

    Column(Modifier.fillMaxSize().background(c.bg)) {
        val role = if (pack.active) "duty pack" else "standby pack"
        ScreenHeader(pack.alias, "${pack.address} · $role · ${pack.sessionCount} sessions", onBack)
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            // (a) verdict
            val verdict = verdictFor(r, cd)
            val vColor = when (verdict) {
                Verdict.Service -> c.critical; Verdict.Watch -> c.warn; Verdict.Healthy -> c.regen
            }
            SectionCard("Health verdict") {
                Row(Modifier.fillMaxWidth().padding(bottom = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(vColor))
                    Text(verdict.name, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 8.dp))
                    Box(Modifier.weight(1f))
                    Text("derived from ${pack.resistance?.bins ?: 0} SOC-bins · ${pack.dischCount} loaded runs",
                        color = c.text3, fontSize = 10.5f.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KpiTile("Effective R", if (r != null) "${trim(r)} mΩ" else "—",
                        if (r != null) "V/I regression" else "needs load",
                        if (r != null && r >= 15f) c.warn else c.text)
                    KpiTile("Cell Δ", if (cd != null) "${cd.roundToInt()} mV" else "—",
                        if (cd != null) (if (cd >= 20f) "imbalanced" else "balanced") else "not sampled",
                        if (cd != null && cd >= 20f) c.warn else c.text)
                    KpiTile("Energy", "${trim(pack.totEnergyWh)} Wh", "2-day total", c.text)
                }
            }

            // (b) effective internal resistance + R-vs-SOC
            val perBin = pack.resistance?.perBin.orEmpty()
            if (r != null && perBin.size >= 2) {
                ChartCard(
                    "Effective internal resistance",
                    "R = ΔV/ΔI, binned by SOC so OCV drift can’t fake it · median ${trim(r)} mΩ",
                ) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 9.dp), verticalAlignment = Alignment.Bottom) {
                        Text(trim(r), color = c.text, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, fontFamily = MonoFont)
                        Text(" mΩ effective", color = c.text3, fontSize = 13.sp, modifier = Modifier.padding(start = 5.dp, bottom = 4.dp))
                        Box(Modifier.weight(1f))
                        Pill("derived", accent)
                    }
                    val rmax = niceCeil(perBin.maxOf { it.rMohm }, 2f)
                    TimeSeriesChart(
                        xs = perBin.map { it.soc.toFloat() },
                        xLabels = perBin.map { "${it.soc}%" },
                        xTickIndices = spreadIndices(minOf(5, perBin.size), perBin.size),
                        axes = listOf(ChartAxis("r", 0f, rmax, fmt = { it.roundToInt().toString() })),
                        series = listOf(ChartSeries("r", "r", accent, perBin.map { it.rMohm },
                            area = true, dots = true, dotR = 3f, label = "R @ SOC", fmt = { "${trim(it)} mΩ" })),
                        baseline = Baseline("r", r),
                        modifier = Modifier.fillMaxWidth().height(132.dp),
                    )
                    Text("Flat across SOC = healthy. A rising left edge (high R at low SOC) is the early aging tell — track it week to week.",
                        color = c.text3, fontSize = 10.sp, modifier = Modifier.padding(top = 7.dp))
                }
            } else {
                ChartCard("Effective internal resistance", "R = ΔV/ΔI from logged V & I") {
                    Column(Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Not enough loaded current to measure.", color = c.text2, fontSize = 12.sp)
                        Text("Needs a run pulling >8 A across the SOC range.", color = c.text3, fontSize = 10.5f.sp,
                            modifier = Modifier.padding(top = 3.dp))
                    }
                }
            }

            // (c) V–I operating cloud
            val scatter = pack.scatter
            if (scatter != null) {
                var hideDis by remember { mutableStateOf(false) }
                var hideChg by remember { mutableStateOf(false) }
                ChartCard(
                    "V–I operating cloud",
                    "Every sample: terminal voltage vs current · slope of the fit = effective R",
                    legend = {
                        FlowRow(Modifier.padding(bottom = 9.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            LegendChip("Discharge", power, !hideDis) { hideDis = !hideDis }
                            LegendChip("Charge / regen", c.regen, !hideChg) { hideChg = !hideChg }
                        }
                    },
                ) {
                    ScatterPlot(scatter.pts, scatter.rMohm, scatter.ocv,
                        modifier = Modifier.fillMaxWidth().height(170.dp),
                        hideDischarge = hideDis, hideCharge = hideChg)
                }
            }

            // (d) usage across sessions
            UsageSection(pack)

            // (e) runs list
            SectionCard("Runs · tap to open timeline") {
                val recent = pack.sessions.asReversed().take(5)
                recent.forEach { s -> RunRow(s) { onOpenTimeline(s.id) } }
            }
        }
    }
}

@Composable
private fun UsageSection(pack: PackHealth) {
    val c = Bm.colors
    val accent = Bm.accent
    val power = Bm.power
    val ss = pack.sessions
    // Colors are remember inputs too (UI-11): captured-only, they'd go stale after a theme or
    // accent/power swatch change until the pack itself changed.
    val metrics = remember(pack, accent, power, c.regen) {
        listOf(
            UsageMetric("energy", "Energy", "Wh", accent, ss.map { it.energyWh }) { "%.1f".format(it) },
            UsageMetric("peak", "Peak W", "W", power, ss.map { it.peakW }) { it.roundToInt().toString() },
            UsageMetric("regen", "Regen", "W", c.regen, ss.map { it.regenW }) { it.roundToInt().toString() },
            UsageMetric("minv", "Min V", "V", power, ss.map { it.minVload }) { "%.2f".format(it) },
            UsageMetric("celld", "Cell Δ", "mV", accent, ss.map { it.cellDeltaMv }) { it.roundToInt().toString() },
        )
    }
    var selected by remember { mutableStateOf("energy") }
    val m = metrics.first { it.key == selected }

    SectionCard("Usage across sessions") {
        FlowRow(Modifier.padding(bottom = 11.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            metrics.forEach { mc -> MetricChip(mc.label, mc.key == selected) { selected = mc.key } }
        }
        val present = m.values.filterNotNull()
        if (present.isEmpty()) {
            Text("No data for this metric yet.", color = c.text3, fontSize = 12.sp)
            return@SectionCard
        }
        val cur = present.last()
        val mn = present.min(); val mx = present.max()
        val pad = ((mx - mn) * 0.14f).takeIf { it > 0f } ?: 1f
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
            Column {
                Text("${m.label} · latest", color = c.text3, fontSize = 10.5f.sp, letterSpacing = 0.4.sp)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(m.fmt(cur), color = c.text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, fontFamily = MonoFont)
                    Text(" ${m.unit}", color = c.text3, fontSize = 13.sp, modifier = Modifier.padding(bottom = 3.dp))
                }
            }
            Box(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text("${present.size} of ${ss.size} runs", color = c.text2, fontSize = 11.sp, fontFamily = MonoFont)
                Text("range ${m.fmt(mn)}–${m.fmt(mx)}", color = c.text3, fontSize = 10.sp, fontFamily = MonoFont)
            }
        }
        TimeSeriesChart(
            xs = ss.indices.map { it.toFloat() },
            xLabels = ss.map { dayLabel(it.startMs) },
            xTickIndices = spreadIndices(minOf(5, ss.size), ss.size),
            axes = listOf(ChartAxis("a", mn - pad, mx + pad, fmt = m.fmt)),
            series = listOf(ChartSeries("v", "a", m.color, m.values, area = true, widthDp = 2.2f,
                dots = true, dotR = 2.6f, label = m.label, fmt = { "${m.fmt(it)} ${m.unit}" })),
            modifier = Modifier.fillMaxWidth().height(160.dp),
        )
    }
}

@Composable
private fun RunRow(s: SessionRollup, onClick: () -> Unit) {
    val c = Bm.colors
    val power = Bm.power
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("${dayLabel(s.startMs)} · ${s.durMin}m", color = c.text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text("SOC ${s.socStart}→${s.socEnd}% · ${if (s.disc) "${s.energyWh.roundToInt()} Wh" else "idle"}",
                color = c.text3, fontSize = 10.sp, fontFamily = MonoFont)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(if (s.disc) "${s.peakW.roundToInt()} W" else "—",
                color = if (s.disc) power else c.text3, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = MonoFont)
            Text(if (s.disc) "peak" else "standby", color = c.text3, fontSize = 10.sp)
        }
    }
}

/** Drop a trailing ".0" so 5.0 → "5" but 5.3 stays. */
internal fun trim(v: Float): String = if (v % 1f == 0f) v.toInt().toString() else "%.1f".format(v)
