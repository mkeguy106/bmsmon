package dev.joely.bmsmon.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.joely.bmsmon.ui.theme.AlertCritical
import dev.joely.bmsmon.ui.theme.Bm
import dev.joely.bmsmon.ui.theme.MonoFont
import dev.joely.bmsmon.ui.theme.hexOf
import dev.joely.bmsmon.ui.theme.hsvToRgb
import dev.joely.bmsmon.ui.theme.parseHexColor
import dev.joely.bmsmon.ui.theme.rgbToHsv
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** RGB int (0xRRGGBB) → opaque Compose [Color]. */
private fun opaque(rgb: Int) = Color(0xFF000000.toInt() or (rgb and 0xFFFFFF))

/**
 * Custom color control for the Appearance settings: a live preview swatch + an **editable** hex
 * field (any `#RGB`/`#RRGGBB`, with or without `#`) beside an HSV **color wheel** (hue = angle,
 * saturation = radius) and a brightness slider. Every path — typing, wheel drag, slider — funnels
 * through [onSelect]; the field/wheel re-sync whenever [selected] changes (including from the preset
 * swatches). Pure color math lives in `ui/theme/ColorMath.kt` (unit-tested).
 */
@Composable
fun CustomColorPicker(selected: Color, onSelect: (Color) -> Unit) {
    val c = Bm.colors
    Text("Custom color", color = c.text2, fontSize = 12.sp,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 46.dp, height = 38.dp).clip(RoundedCornerShape(8.dp)).background(selected))
        EditableHexField(selected, onSelect, Modifier.padding(start = 11.dp))
    }
    Spacer(Modifier.height(16.dp))
    HueSatWheel(selected, onSelect)
}

@Composable
private fun EditableHexField(selected: Color, onSelect: (Color) -> Unit, modifier: Modifier) {
    val c = Bm.colors
    var text by remember { mutableStateOf(hexOf(selected.toArgb())) }
    var focused by remember { mutableStateOf(false) }
    // Re-sync from an external change (swatch / wheel / slider) unless the user is mid-edit, so we
    // never clobber their keystrokes but always reflect the wheel.
    LaunchedEffect(selected) {
        if (!focused && parseHexColor(text) != (selected.toArgb() and 0xFFFFFF)) {
            text = hexOf(selected.toArgb())
        }
    }
    val valid = parseHexColor(text) != null
    val border = when {
        !valid -> AlertCritical
        focused -> Bm.accent
        else -> c.inputBorder
    }
    Box(
        modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.inputBg)
            .border(1.dp, border, RoundedCornerShape(8.dp)).padding(horizontal = 13.dp, vertical = 11.dp),
    ) {
        BasicTextField(
            value = text,
            onValueChange = { v ->
                text = v.take(7)
                parseHexColor(v)?.let { onSelect(opaque(it)) }
            },
            singleLine = true,
            textStyle = TextStyle(color = c.text, fontFamily = MonoFont, fontSize = 14.sp),
            cursorBrush = SolidColor(Bm.accent),
            keyboardOptions = KeyboardOptions(
                autoCorrect = false, capitalization = KeyboardCapitalization.Characters,
            ),
            modifier = Modifier.fillMaxWidth().onFocusChanged {
                focused = it.isFocused
                // On blur, discard an incomplete/invalid entry and snap back to the live color.
                if (!it.isFocused && parseHexColor(text) == null) text = hexOf(selected.toArgb())
            },
        )
    }
}

private val HUE_RING = listOf(
    Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red,
)

@Composable
private fun HueSatWheel(selected: Color, onSelect: (Color) -> Unit) {
    val hsv = rgbToHsv(selected.toArgb() and 0xFFFFFF)
    val hue = hsv[0]; val sat = hsv[1]; val value = hsv[2]

    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier.size(210.dp)
                // Key on `value` so a wheel touch keeps the current brightness; hue/sat come from
                // the touch geometry, so they don't need capturing.
                .pointerInput(value) {
                    fun pick(pos: Offset) {
                        val r = size.width / 2f
                        val dx = pos.x - r
                        val dy = pos.y - r
                        val s = (hypot(dx, dy) / r).coerceIn(0f, 1f)
                        var h = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
                        if (h < 0f) h += 360f
                        onSelect(opaque(hsvToRgb(h, s, value)))
                    }
                    detectTapGestures { pick(it) }
                }
                .pointerInput(value) {
                    fun pick(pos: Offset) {
                        val r = size.width / 2f
                        val dx = pos.x - r
                        val dy = pos.y - r
                        val s = (hypot(dx, dy) / r).coerceIn(0f, 1f)
                        var h = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
                        if (h < 0f) h += 360f
                        onSelect(opaque(hsvToRgb(h, s, value)))
                    }
                    detectDragGestures(onDragStart = { pick(it) }) { change, _ -> pick(change.position) }
                },
        ) {
            val r = size.minDimension / 2f
            drawCircle(Brush.sweepGradient(HUE_RING, center), radius = r, center = center)
            drawCircle(
                Brush.radialGradient(listOf(Color.White, Color.Transparent), center, r),
                radius = r, center = center,
            )
            // Dim the whole disc toward black as brightness drops, so the wheel previews `value`.
            if (value < 1f) drawCircle(Color.Black.copy(alpha = 1f - value), radius = r, center = center)
            // Thumb at (hue angle, saturation radius).
            val ang = Math.toRadians(hue.toDouble())
            val tx = center.x + cos(ang).toFloat() * sat * r
            val ty = center.y + sin(ang).toFloat() * sat * r
            drawCircle(Color.White, radius = 8.dp.toPx(), center = Offset(tx, ty))
            drawCircle(selected, radius = 6.dp.toPx(), center = Offset(tx, ty))
        }
    }
    Slider(
        value = value,
        onValueChange = { v -> onSelect(opaque(hsvToRgb(hue, sat, v))) },
    )
    Text("Brightness", color = Bm.colors.text3, fontSize = 11.sp,
        modifier = Modifier.padding(top = 2.dp))
}
