package dev.joely.bmsmon.ui.gauge

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.joely.bmsmon.model.TempRank
import dev.joely.bmsmon.model.TempSide
import dev.joely.bmsmon.model.TempZone
import dev.joely.bmsmon.model.tempFillPct
import dev.joely.bmsmon.ui.theme.AlertWarn
import dev.joely.bmsmon.ui.theme.Bm
import dev.joely.bmsmon.ui.theme.MonoFont
import dev.joely.bmsmon.ui.theme.RegenGreen
import dev.joely.bmsmon.ui.theme.TempCold
import dev.joely.bmsmon.ui.theme.TempCool

private val GAUGE_H = 158.dp
private val GAUGE_W = 16.dp

/** Marker/reading color for a temperature [zone] (matches the design's zone palette). */
@Composable
fun tempZoneColor(zone: TempZone): Color = when {
    zone.rank == TempRank.CRITICAL || zone.rank == TempRank.CUTOFF -> Bm.colors.critical
    zone.side == TempSide.COLD && zone.rank == TempRank.WARNING -> TempCold   // charge-lock blue
    zone.side == TempSide.COLD && zone.rank == TempRank.CAUTION -> TempCool
    zone.side == TempSide.HOT && zone.rank == TempRank.WARNING -> Bm.power    // hot charge-lock
    zone.side == TempSide.HOT && zone.rank == TempRank.CAUTION -> AlertWarn   // warm
    else -> RegenGreen  // safe
}

// Fixed zone-band washes (cold→hot), bottom fraction → height fraction, per the design handoff.
private data class Band(val start: Float, val end: Float, val color: Color)
private val BANDS = listOf(
    Band(0.00f, 0.10f, Color(0xFF2F6FE0).copy(alpha = 0.22f)),  // cold-crit
    Band(0.10f, 0.35f, Color(0xFF46B3C9).copy(alpha = 0.18f)),  // cool
    Band(0.35f, 0.75f, Color(0xFF2ECC71).copy(alpha = 0.16f)),  // safe
    Band(0.75f, 0.90f, Color(0xFFE2B01E).copy(alpha = 0.18f)),  // warm
    Band(0.90f, 1.00f, Color(0xFFE5342B).copy(alpha = 0.22f)),  // hot-crit
)

/**
 * Vertical temperature thermometer: five zone bands, a mercury fill to the current reading, a
 * zone-colored marker with glow, CUTOFF ticks (−20/+60°C → 10%/90%), and a reading chip. The
 * −30…+70°C span maps to 0…100% ([tempFillPct]).
 */
@Composable
fun TempGauge(
    tempC: Float,
    zoneColor: Color,
    label: String,
    critical: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = Bm.colors
    val fillFrac = tempFillPct(tempC) / 100f
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .width(GAUGE_W).height(GAUGE_H)
                .clip(RoundedCornerShape(8.dp))
                .background(c.inputBg)
                .border(1.dp, c.inputBorder, RoundedCornerShape(8.dp)),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val h = size.height
                val w = size.width
                BANDS.forEach { b ->
                    val top = h * (1f - b.end)
                    drawRect(b.color, topLeft = Offset(0f, top), size = Size(w, h * (b.end - b.start)))
                }
                // mercury fill, gradient of the zone color (denser at the bottom)
                val fillTop = h * (1f - fillFrac)
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to zoneColor.copy(alpha = 0.06f), 1f to zoneColor.copy(alpha = 0.30f),
                        startY = fillTop, endY = h,
                    ),
                    topLeft = Offset(0f, fillTop), size = Size(w, h - fillTop),
                )
                // marker glow + bar at the reading height
                val markerY = fillTop
                val glow = if (critical) 9.dp.toPx() else 5.dp.toPx()
                drawRect(zoneColor.copy(alpha = 0.30f),
                    topLeft = Offset(-3.dp.toPx(), markerY - glow / 2),
                    size = Size(w + 6.dp.toPx(), glow))
                drawRect(zoneColor,
                    topLeft = Offset(-2.dp.toPx(), markerY - 2.dp.toPx()),
                    size = Size(w + 4.dp.toPx(), 4.dp.toPx()))
            }
        }
        // tick labels + reading chip in a fixed-height column to the right
        Box(Modifier.height(GAUGE_H).width(56.dp).padding(start = 8.dp)) {
            CutoffTick(0.90f)   // hot cutoff (+60°C → 90%)
            CutoffTick(0.10f)   // cold cutoff (−20°C → 10%)
            // live reading chip centered on the fill line
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(y = GAUGE_H * (1f - fillFrac) - 9.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(c.bg)
                    .border(1.dp, zoneColor, RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                Text(label, color = zoneColor, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    fontFamily = MonoFont)
            }
        }
    }
}

@Composable
private fun BoxScope.CutoffTick(bottomFrac: Float) {
    Text(
        "CUTOFF",
        color = Bm.colors.critical, fontSize = 8.sp, fontFamily = MonoFont,
        modifier = Modifier.align(Alignment.TopStart).offset(y = GAUGE_H * (1f - bottomFrac) - 6.dp),
    )
}
