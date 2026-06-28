package dev.joely.bmsmon.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.joely.bmsmon.model.LOCK_HOLD_MS
import dev.joely.bmsmon.model.lockHoldComplete
import dev.joely.bmsmon.model.lockHoldFraction

/**
 * Lock/unlock control: press and hold ~LOCK_HOLD_MS to toggle. A ring fills while held and the
 * action only commits on completion, so a stray tap/bump can neither lock nor unlock.
 */
@Composable
fun LockButton(
    locked: Boolean,
    onToggle: () -> Unit,
    iconTint: Color,
    ringColor: Color,
    modifier: Modifier = Modifier,
) {
    var pressed by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(0L) }

    LaunchedEffect(pressed) {
        if (!pressed) { elapsed = 0L; return@LaunchedEffect }
        val start = withFrameMillis { it }
        while (true) {
            val now = withFrameMillis { it }
            elapsed = now - start
            if (lockHoldComplete(elapsed)) {
                onToggle()
                pressed = false
                break
            }
        }
    }

    val progress = lockHoldFraction(elapsed)

    Box(
        modifier
            .size(40.dp)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    pressed = true
                    tryAwaitRelease()
                    pressed = false
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(34.dp)) {
            if (progress > 0f) {
                drawArc(
                    color = ringColor,
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }
        Icon(
            if (locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
            contentDescription = if (locked) "Unlock screen" else "Lock screen",
            modifier = Modifier.size(if (locked) 22.dp else 21.dp),
            tint = iconTint,
        )
    }
}
