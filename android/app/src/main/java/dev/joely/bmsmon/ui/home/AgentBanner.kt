package dev.joely.bmsmon.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.joely.bmsmon.agent.formatAgentRemaining
import dev.joely.bmsmon.ui.theme.Bm
import dev.joely.bmsmon.ui.theme.MonoFont

/**
 * Shown under the status line while an agent session runs (agent/AgentSession.kt), so the owner
 * can always see that the pin is lifted and for how long. RE-PIN NOW works even in locked mode.
 */
@Composable
fun AgentBanner(remainingMs: Long, onRepinNow: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "● CLAUDE IS USING THE PHONE · ${formatAgentRemaining(remainingMs)}",
            color = Bm.warnText, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp, fontFamily = MonoFont,
        )
        Text(
            "RE-PIN NOW",
            color = Bm.accentText, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp, fontFamily = MonoFont,
            modifier = Modifier.minimumInteractiveComponentSize().clickable(onClick = onRepinNow)
                .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
        )
    }
}

/**
 * Shown while locked mode is on but nothing is pinned and no agent session runs: typically after a
 * session timed out while another app held top focus, so Android refused the re-pin. Touching the
 * screen hands focus back and re-pins; TAP TO PIN asks directly.
 */
@Composable
fun PinLiftedBanner(onPinNow: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "● NOT PINNED",
            color = Bm.warnText, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp, fontFamily = MonoFont,
        )
        Text(
            "TAP TO PIN",
            color = Bm.accentText, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp, fontFamily = MonoFont,
            modifier = Modifier.minimumInteractiveComponentSize().clickable(onClick = onPinNow)
                .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
        )
    }
}
