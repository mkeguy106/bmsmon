package dev.joely.bmsmon.ui.theme

import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import dev.joely.bmsmon.R

/** Semantic color tokens — one instance for dark, one for light (from the handoff). */
data class BmColors(
    val bg: Color,
    val card: Color,
    val card2: Color,
    val border: Color,
    val inputBg: Color,
    val inputBorder: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    val name: Color,
    val icon: Color,
    val divider: Color,
    val segEmpty: Color,
    val innerTrack: Color,
)

val DarkBmColors = BmColors(
    bg = Color(0xFF121212), card = Color(0xFF161616), card2 = Color(0xFF151515),
    border = Color(0xFF2A2A2A), inputBg = Color(0xFF1D1D1D), inputBorder = Color(0xFF333333),
    text = Color(0xFFECECEC), text2 = Color(0xFF8A8A8A), text3 = Color(0xFF777777),
    name = Color(0xFFBCBCBC), icon = Color(0xFFD8D8D8), divider = Color(0xFF1F1F1F),
    segEmpty = Color(0xFFD6D6D6), innerTrack = Color(0xFF2B2B2B),
)

val LightBmColors = BmColors(
    bg = Color(0xFFF3F3F5), card = Color(0xFFFFFFFF), card2 = Color(0xFFFAFAFB),
    border = Color(0xFFE4E4E7), inputBg = Color(0xFFF0F0F2), inputBorder = Color(0xFFD8D8DC),
    text = Color(0xFF18181B), text2 = Color(0xFF5F5F66), text3 = Color(0xFF9A9AA0),
    name = Color(0xFF52525B), icon = Color(0xFF3A3A40), divider = Color(0xFFE7E7EA),
    segEmpty = Color(0xFFE2E2E6), innerTrack = Color(0xFFEBEBEE),
)

val DefaultAccent = Color(0xFFE67E22)
val DefaultPower = Color(0xFFC85A1A)

/** Regen / current-dump indication (green, distinct from accent/power). */
val RegenGreen = Color(0xFF2ECC71)

/** Low-battery alert colors (severity-graded): amber warning, red critical. */
val AlertWarn = Color(0xFFE2B01E)
val AlertCritical = Color(0xFFE5342B)

/** SOC / capacity severity ramp: <15% critical, <30% warning, else accent. */
fun socSeverity(soc: Float, accent: Color): Color =
    if (soc < 15f) AlertCritical else if (soc < 30f) AlertWarn else accent

/** The eight preset swatches (theme list leads with accent default, power list with power default). */
val ThemeSwatches = listOf(
    0xFFE67E22, 0xFF2A6C9C, 0xFF2E8B57, 0xFF8B2520,
    0xFF6A3D8B, 0xFF7A7A1A, 0xFF1F8585, 0xFF9B1F52,
).map { Color(it) }

val PowerSwatches = listOf(
    0xFFC85A1A, 0xFF2A6C9C, 0xFF2E8B57, 0xFF8B2520,
    0xFF6A3D8B, 0xFF7A7A1A, 0xFF1F8585, 0xFF9B1F52,
).map { Color(it) }

val LocalBmColors = staticCompositionLocalOf { DarkBmColors }
val LocalAccent = staticCompositionLocalOf { DefaultAccent }
val LocalPower = staticCompositionLocalOf { DefaultPower }

/** Numeric readouts use JetBrains Mono; everything else uses Inter. */
val MonoFont = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    Font(R.font.jetbrains_mono_semibold, FontWeight.SemiBold),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)
val SansFont = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

/** Convenience accessors: `Bm.colors`, `Bm.accent`, `Bm.power`. */
object Bm {
    val colors: BmColors @Composable get() = LocalBmColors.current
    val accent: Color @Composable get() = LocalAccent.current
    val power: Color @Composable get() = LocalPower.current
}

@Composable
fun BmTheme(
    dark: Boolean,
    accent: Color,
    power: Color,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalBmColors provides if (dark) DarkBmColors else LightBmColors,
        LocalAccent provides accent,
        LocalPower provides power,
        // Default all text to Inter; mono readouts opt into MonoFont explicitly.
        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = SansFont),
        content = content,
    )
}
