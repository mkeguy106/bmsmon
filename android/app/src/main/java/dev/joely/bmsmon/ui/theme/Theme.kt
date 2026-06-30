package dev.joely.bmsmon.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
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
    // Chart-series / status tokens (light & dark variants from the history handoff). Accent and
    // power stay user-themeable via LocalAccent / LocalPower; these are the fixed semantic colors.
    val grid: Color,       // chart gridlines
    val regen: Color,      // regen / charge series
    val warn: Color,       // watch / caution
    val critical: Color,   // service / alarm / link-loss
)

val DarkBmColors = BmColors(
    bg = Color(0xFF121212), card = Color(0xFF161616), card2 = Color(0xFF151515),
    border = Color(0xFF2A2A2A), inputBg = Color(0xFF1D1D1D), inputBorder = Color(0xFF333333),
    text = Color(0xFFECECEC), text2 = Color(0xFF8A8A8A), text3 = Color(0xFF777777),
    name = Color(0xFFBCBCBC), icon = Color(0xFFD8D8D8), divider = Color(0xFF1F1F1F),
    segEmpty = Color(0xFFD6D6D6), innerTrack = Color(0xFF2B2B2B),
    grid = Color.White.copy(alpha = 0.06f), regen = Color(0xFF2ECC71),
    warn = Color(0xFFE2B01E), critical = Color(0xFFE5342B),
)

val LightBmColors = BmColors(
    bg = Color(0xFFF3F3F5), card = Color(0xFFFFFFFF), card2 = Color(0xFFFAFAFB),
    border = Color(0xFFE4E4E7), inputBg = Color(0xFFF0F0F2), inputBorder = Color(0xFFD8D8DC),
    text = Color(0xFF18181B), text2 = Color(0xFF5F5F66), text3 = Color(0xFF9A9AA0),
    name = Color(0xFF52525B), icon = Color(0xFF3A3A40), divider = Color(0xFFE7E7EA),
    segEmpty = Color(0xFFE2E2E6), innerTrack = Color(0xFFEBEBEE),
    grid = Color.Black.copy(alpha = 0.07f), regen = Color(0xFF1F9E54),
    warn = Color(0xFFB6860D), critical = Color(0xFFD62F26),
)

/** Voltage series in the session timeline — a literal warm tone (same in both themes). */
val VoltageSeries = Color(0xFFD39150)

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

// Not static: color tokens change every frame during the theme crossfade, so only composables that
// actually read them should recompose (not the whole tree).
val LocalBmColors = compositionLocalOf { DarkBmColors }
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

/** Duration of the light/dark theme crossfade. */
const val ThemeTransitionMillis = 550

@Composable
private fun animColor(target: Color, spec: AnimationSpec<Color>): Color =
    animateColorAsState(target, spec, label = "bmColor").value

/**
 * The active color tokens, each crossfading toward its dark/light target over
 * [ThemeTransitionMillis] when the theme flips — so toggling glides instead of hard-cutting. On
 * first composition each token starts at its target, so a cold launch shows no spurious fade.
 */
@Composable
fun animatedBmColors(dark: Boolean): BmColors {
    val target = if (dark) DarkBmColors else LightBmColors
    val spec: AnimationSpec<Color> = tween(ThemeTransitionMillis, easing = FastOutSlowInEasing)
    return BmColors(
        bg = animColor(target.bg, spec), card = animColor(target.card, spec), card2 = animColor(target.card2, spec),
        border = animColor(target.border, spec), inputBg = animColor(target.inputBg, spec),
        inputBorder = animColor(target.inputBorder, spec),
        text = animColor(target.text, spec), text2 = animColor(target.text2, spec), text3 = animColor(target.text3, spec),
        name = animColor(target.name, spec), icon = animColor(target.icon, spec), divider = animColor(target.divider, spec),
        segEmpty = animColor(target.segEmpty, spec), innerTrack = animColor(target.innerTrack, spec),
        grid = animColor(target.grid, spec), regen = animColor(target.regen, spec),
        warn = animColor(target.warn, spec), critical = animColor(target.critical, spec),
    )
}

@Composable
fun BmTheme(
    dark: Boolean,
    accent: Color,
    power: Color,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalBmColors provides animatedBmColors(dark),
        LocalAccent provides accent,
        LocalPower provides power,
        // Default all text to Inter; mono readouts opt into MonoFont explicitly.
        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = SansFont),
        content = content,
    )
}
