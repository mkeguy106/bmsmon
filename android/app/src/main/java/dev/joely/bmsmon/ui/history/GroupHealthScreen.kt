@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.joely.bmsmon.ui.history

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.joely.bmsmon.data.PackHealth
import dev.joely.bmsmon.ui.charts.Sparkline
import dev.joely.bmsmon.ui.theme.Bm
import dev.joely.bmsmon.ui.theme.MonoFont
import kotlin.math.roundToInt

/**
 * Fleet view: every pack ranked by energy moved, with its derived effective R, energy bar and cell
 * imbalance side by side; a warning banner for any pack with elevated imbalance; and an expandable
 * per-pack detail. In-app port of the prototype's `fleet()`. Replaces the old IR-overlay screen.
 */
@Composable
fun GroupHealthScreen(packs: List<PackHealth>, onBack: () -> Unit) {
    val c = Bm.colors
    val accent = Bm.accent

    val ranked = packs.sortedByDescending { it.totEnergyWh }
    val totE = packs.sumOf { it.totEnergyWh.toDouble() }.roundToInt()
    val eMax = (packs.maxOfOrNull { it.totEnergyWh } ?: 1f).coerceAtLeast(1f)
    val measured = packs.filter { it.rMohm != null }
    val rSpread = if (measured.size >= 2)
        (measured.maxOf { it.rMohm!! } - measured.minOf { it.rMohm!! }) else null
    val days = spanDays(packs)
    val outlier = packs.filter { it.cellDeltaMv != null }.maxByOrNull { it.cellDeltaMv!! }
    val flag = outlier?.cellDeltaMv?.let { it >= 15f } == true

    Column(Modifier.fillMaxSize().background(c.bg)) {
        ScreenHeader("Group health", "${packs.size} packs · ${"%.1f".format(days)} days · $totE Wh", onBack)
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            if (packs.isEmpty()) {
                Text("No sessions recorded yet — keep monitoring to build trends.", color = c.text3, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 16.dp))
                return@Column
            }

            if (flag && outlier != null) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.warn.copy(alpha = 0.08f))
                        .border(1.dp, c.warn.copy(alpha = 0.33f), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 11.dp),
                ) {
                    Box(Modifier.padding(top = 3.dp).size(9.dp).clip(CircleShape).background(c.warn))
                    Column(Modifier.padding(start = 9.dp)) {
                        Text("${outlier.alias} — elevated cell imbalance", color = c.text, fontSize = 12.5f.sp, fontWeight = FontWeight.SemiBold)
                        Text("${outlier.cellDeltaMv?.roundToInt()} mV typical, up to ${outlier.cell?.maxMv?.roundToInt()} mV (others 1–3 mV). Worth inspecting — though it has seen little load.",
                            color = c.text2, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }

            SectionCard("Fleet · ${"%.1f".format(days)} days") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KpiTile("Measured", "${measured.size}/${packs.size}", "have a loaded run", c.text)
                    KpiTile("Effective R",
                        if (measured.isNotEmpty()) measured.joinToString(" / ") { trim(it.rMohm!!) } + " mΩ" else "—",
                        if (rSpread != null) "matched ±${trim(rSpread)} mΩ" else "derived",
                        if (measured.isNotEmpty()) c.text else c.text3)
                    KpiTile("Energy moved", "$totE Wh", "fleet total", accent)
                }
            }

            SectionCard("Packs · tap to expand") {
                val duty = packs.filter { it.active }
                Text(
                    "Ranked by energy moved." + if (duty.size >= 2)
                        " The duty pair (${duty.joinToString(" + ") { it.alias }}) carried the load and measures ~5.5 mΩ — matched, as siblings should be." else "",
                    color = c.text3, fontSize = 10.5f.sp, modifier = Modifier.padding(bottom = 9.dp),
                )
                ranked.forEach { p -> PackRow(p, eMax) }
            }
        }
    }
}

@Composable
private fun PackRow(p: PackHealth, eMax: Float) {
    val c = Bm.colors
    val accent = Bm.accent
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(p.alias, color = c.text, fontSize = 12.5f.sp, fontWeight = FontWeight.SemiBold)
                    if (p.active) Pill("Duty", accent) else Pill("Standby", c.text3)
                }
                Text(p.address, color = c.text3, fontSize = 9.5f.sp, fontFamily = MonoFont, modifier = Modifier.padding(top = 1.dp))
            }
            Column(Modifier.width(88.dp), horizontalAlignment = Alignment.End) {
                Text("${trim(p.totEnergyWh)} Wh", color = if (p.active) accent else c.text2,
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = MonoFont)
                val r = p.rMohm
                Text(if (r != null) "R ${trim(r)} mΩ" else "R —",
                    color = if (r != null) (if (r < 15f) c.regen else c.warn) else c.text3, fontSize = 10.sp, fontFamily = MonoFont)
                val cd = p.cellDeltaMv
                Text(if (cd != null) "cell Δ ${cd.roundToInt()} mV" else "cell Δ —",
                    color = if (cd != null && cd >= 15f) c.warn else c.text2, fontSize = 10.sp, fontFamily = MonoFont)
            }
        }
        // energy bar
        Box(Modifier.fillMaxWidth().padding(top = 7.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(c.inputBg)) {
            Box(Modifier.fillMaxWidth((p.totEnergyWh / eMax).coerceIn(0.02f, 1f)).height(5.dp)
                .clip(RoundedCornerShape(3.dp)).background(if (p.active) accent else c.innerTrack))
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(top = 8.dp)) {
                Text("Energy per session (${p.sessionCount} runs)", color = c.text3, fontSize = 10.sp, modifier = Modifier.padding(bottom = 6.dp))
                Sparkline(p.sessions.map { it.energyWh }, if (p.active) accent else c.text3,
                    Modifier.fillMaxWidth().height(34.dp))
                FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Stat("peak ${p.peakW.roundToInt()} W", c.text2)
                    Stat("regen ${p.peakRegenW.roundToInt()} W", c.text2)
                    p.minVload?.let { Stat("min V ${"%.2f".format(it)}", c.text2) }
                    p.rMohm?.let { Stat("R ${trim(it)} mΩ (${p.resistance?.bins} bins)", accent) }
                    p.sessions.lastOrNull()?.let { Stat("last SOC ${it.socEnd}%", c.text2) }
                }
            }
        }
    }
}

@Composable
private fun Stat(text: String, color: androidx.compose.ui.graphics.Color) =
    Text(text, color = color, fontSize = 10.5f.sp, fontFamily = MonoFont)

/** Days spanned by all packs' sessions (earliest start → latest end). */
private fun spanDays(packs: List<PackHealth>): Float {
    val starts = packs.flatMap { it.sessions }.minOfOrNull { it.startMs } ?: return 0f
    val ends = packs.flatMap { it.sessions }.maxOfOrNull { it.startMs } ?: return 0f
    return (ends - starts) / 86_400_000f
}
