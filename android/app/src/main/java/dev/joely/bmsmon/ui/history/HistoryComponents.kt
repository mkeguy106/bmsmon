package dev.joely.bmsmon.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.joely.bmsmon.ui.theme.Bm
import dev.joely.bmsmon.ui.theme.MonoFont
import java.util.Calendar

/** Back chevron + screen title (alias) + a mono subtitle line — the in-app screen header. */
@Composable
fun ScreenHeader(title: String, subtitle: String, onBack: () -> Unit) {
    val c = Bm.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(c.inputBg).clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.AutoMirrored.Filled.ArrowBackIos, "Back", Modifier.size(16.dp), tint = c.icon) }
        Column(Modifier.padding(start = 9.dp)) {
            Text(title, color = c.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = c.text3, fontSize = 11.sp, fontFamily = MonoFont)
        }
    }
}

/** Section card: card2 fill, hairline border, 13dp radius/padding, optional uppercase title. */
@Composable
fun SectionCard(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = Bm.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(c.card2)
            .border(1.dp, c.border, RoundedCornerShape(13.dp)).padding(13.dp),
    ) {
        if (title != null) {
            Text(
                title.uppercase(), color = c.text3, fontSize = 10.5f.sp, fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.6.sp, modifier = Modifier.padding(bottom = 10.dp),
            )
        }
        content()
    }
}

/** A titled chart card (title + note + optional legend, then the chart). */
@Composable
fun ChartCard(title: String, note: String, legend: (@Composable () -> Unit)? = null, chart: @Composable () -> Unit) {
    val c = Bm.colors
    SectionCard {
        Text(title, color = c.text, fontSize = 12.5f.sp, fontWeight = FontWeight.SemiBold)
        Text(note, color = c.text3, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp, bottom = 9.dp))
        legend?.invoke()
        chart()
    }
}

/** KPI tile — bg fill, border, uppercase label, big mono value (colored), small sub. */
@Composable
fun RowScope.KpiTile(label: String, value: String, sub: String, valueColor: Color) {
    val c = Bm.colors
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(9.dp)).background(c.bg)
            .border(1.dp, c.border, RoundedCornerShape(9.dp)).padding(horizontal = 9.dp, vertical = 8.dp),
    ) {
        Text(label.uppercase(), color = c.text3, fontSize = 9.5f.sp, letterSpacing = 0.4.sp)
        Text(value, color = valueColor, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, fontFamily = MonoFont,
            modifier = Modifier.padding(top = 3.dp, bottom = 1.dp))
        Text(sub, color = c.text3, fontSize = 9.5f.sp)
    }
}

/** A small status pill (e.g. Duty / Standby / derived). */
@Composable
fun Pill(text: String, color: Color) {
    Text(
        text, color = color, fontSize = 9.5f.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.13f))
            .border(1.dp, color.copy(alpha = 0.33f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 1.5.dp),
    )
}

/** Legend chip: color swatch + label; toggles a series, dimming when [on] is false. */
@Composable
fun LegendChip(label: String, color: Color, on: Boolean, onToggle: () -> Unit) {
    val c = Bm.colors
    Row(
        Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(c.inputBg)
            .border(1.dp, c.border, RoundedCornerShape(7.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .then(if (on) Modifier else Modifier.alpha(0.42f)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Text(label, color = c.text2, fontSize = 11.sp)
    }
}

/** Single-select metric chip — accent border + tint when selected. */
@Composable
fun MetricChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = Bm.colors
    val accent = Bm.accent
    Text(
        label,
        color = if (selected) c.text else c.text2,
        fontSize = 11.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) accent.copy(alpha = 0.12f) else c.inputBg)
            .border(1.dp, if (selected) accent else c.border, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

// --- small helpers reused across the history screens ---

fun hhmm(ts: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = ts }
    return "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
}

fun dayLabel(ts: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = ts }
    return "%d %02d:%02d".format(cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
}
