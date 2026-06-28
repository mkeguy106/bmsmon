package dev.joely.bmsmon.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Lightning-bolt glyph (24×24 viewBox), shared by the Stage gauge and the All Batteries rows. */
val ChargingBolt: ImageVector by lazy {
    ImageVector.Builder(
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(13f, 2f)
            lineTo(4.5f, 13.5f)
            horizontalLineTo(11f)
            lineTo(10f, 22f)
            lineTo(19.5f, 9.5f)
            horizontalLineTo(13f)
            close()
        }
    }.build()
}

/**
 * Opacity for a pulsing charging bolt — swings [min]→[max] and back, ease-in-out,
 * bolder at the top of each beat. Used as the bolt's tint alpha.
 */
@Composable
fun rememberBoltAlpha(min: Float, max: Float, periodMs: Int = 900): Float {
    val transition = rememberInfiniteTransition(label = "bolt")
    val alpha by transition.animateFloat(
        initialValue = min,
        targetValue = max,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = periodMs, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "boltAlpha",
    )
    return alpha
}
