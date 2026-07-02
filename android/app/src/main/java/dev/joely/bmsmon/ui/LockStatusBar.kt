package dev.joely.bmsmon.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.joely.bmsmon.ui.theme.Bm
import dev.joely.bmsmon.ui.theme.MonoFont
import kotlinx.coroutines.delay
import java.util.Date

/**
 * A minimal status strip drawn at the very top while the app is locked. Android screen pinning hides
 * the real system status bar, so this replicates just the essentials — clock, Wi‑Fi/connectivity and
 * the phone's battery — each toggleable in Settings. Read straight from Android APIs; no permissions.
 */
@Composable
fun LockStatusBar(showTime: Boolean, showWifi: Boolean, showBattery: Boolean) {
    val c = Bm.colors
    val context = LocalContext.current

    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 18.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showTime) {
            Text(rememberClock(context), color = c.text2, fontSize = 13.sp, fontFamily = MonoFont)
        }
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (showWifi) {
                val t = rememberTransport(context)
                Icon(t.icon, t.desc, Modifier.size(16.dp), tint = c.text2)
            }
            if (showBattery) {
                val b = rememberBattery(context)
                if (b != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        PhoneBattery(pct = b.pct, charging = b.charging)
                        Text("${b.pct}%", color = c.text2, fontSize = 13.sp, fontFamily = MonoFont)
                    }
                }
            }
        }
    }
}

/**
 * Horizontal battery glyph mirroring the system status-bar look: a rounded body with a terminal nub
 * on the right, a level-proportional fill, and a green bolt overlaid while charging.
 */
@Composable
private fun PhoneBattery(pct: Int, charging: Boolean) {
    val c = Bm.colors
    val outline = c.text2
    val fill = if (!charging && pct <= 15) c.critical else c.text2
    val level = (pct.coerceIn(0, 100)) / 100f

    Canvas(Modifier.size(width = 25.dp, height = 12.dp)) {
        val stroke = 1.4.dp.toPx()
        val termW = 2.dp.toPx()
        val termH = size.height * 0.42f
        val bodyW = size.width - termW
        val bodyR = CornerRadius(2.4.dp.toPx())

        // Body outline
        drawRoundRect(
            color = outline,
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(bodyW - stroke, size.height - stroke),
            cornerRadius = bodyR,
            style = Stroke(width = stroke),
        )
        // Positive terminal nub on the right
        drawRoundRect(
            color = outline,
            topLeft = Offset(bodyW - stroke / 2f, (size.height - termH) / 2f),
            size = Size(termW, termH),
            cornerRadius = CornerRadius(1.dp.toPx()),
        )
        // Level fill
        val pad = stroke + 1.dp.toPx()
        val innerW = bodyW - stroke - 2f * 1.dp.toPx()
        val innerH = size.height - 2f * pad
        val fillW = (innerW * level).coerceIn(0f, innerW)
        if (fillW > 0f) {
            drawRoundRect(
                color = fill,
                topLeft = Offset(pad, pad),
                size = Size(fillW, innerH),
                cornerRadius = CornerRadius(1.2.dp.toPx()),
            )
        }
        // Charging bolt (drawn regardless of level so it's always visible)
        if (charging) {
            val boltH = size.height * 0.9f
            val boltW = boltH * 0.5f
            val bx = (bodyW - boltW) / 2f
            val by = (size.height - boltH) / 2f
            fun p(nx: Float, ny: Float) = Offset(bx + nx * boltW, by + ny * boltH)
            val bolt = Path().apply {
                moveTo(p(0.58f, 0f).x, p(0.58f, 0f).y)
                lineTo(p(0.12f, 0.56f).x, p(0.12f, 0.56f).y)
                lineTo(p(0.46f, 0.56f).x, p(0.46f, 0.56f).y)
                lineTo(p(0.40f, 1f).x, p(0.40f, 1f).y)
                lineTo(p(0.88f, 0.40f).x, p(0.88f, 0.40f).y)
                lineTo(p(0.52f, 0.40f).x, p(0.52f, 0.40f).y)
                close()
            }
            // Halo in the background color separates the bolt from the green fill beneath it.
            drawPath(bolt, color = c.bg, style = Stroke(width = 1.2.dp.toPx()))
            drawPath(bolt, color = c.regen)
        }
    }
}

@Composable
private fun rememberClock(context: Context): String {
    val fmt = remember { DateFormat.getTimeFormat(context) }  // locale + 24h-aware
    var now by remember { mutableStateOf(fmt.format(Date())) }
    LaunchedEffect(Unit) {
        while (true) {
            now = fmt.format(Date())
            delay(10_000L)
        }
    }
    return now
}

private data class BatteryInfo(val pct: Int, val charging: Boolean)

/**
 * Reads phone battery via the sticky ACTION_BATTERY_CHANGED broadcast — this reports charging state
 * reliably across OEMs (unlike BatteryManager.isCharging) and updates the instant the cable is
 * plugged/unplugged rather than on a poll interval.
 */
@Composable
private fun rememberBattery(context: Context): BatteryInfo? {
    var info by remember { mutableStateOf<BatteryInfo?>(null) }
    DisposableEffect(Unit) {
        fun parse(intent: Intent?): BatteryInfo? {
            intent ?: return null
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0) return null
            val pct = (level * 100 / scale).coerceIn(0, 100)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            val charging = plugged != 0 ||
                status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            return BatteryInfo(pct, charging)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                parse(intent)?.let { info = it }
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        // registerReceiver returns the current sticky intent, giving us the initial state at once.
        val sticky = runCatching { context.registerReceiver(receiver, filter) }.getOrNull()
        parse(sticky)?.let { info = it }
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return info
}

private enum class Transport(val icon: ImageVector, val desc: String) {
    Wifi(Icons.Filled.Wifi, "Wi-Fi"),
    Cellular(Icons.Filled.SignalCellularAlt, "Cellular"),
    None(Icons.Filled.WifiOff, "No network"),
}

@Composable
private fun rememberTransport(context: Context): Transport {
    var t by remember { mutableStateOf(Transport.None) }
    DisposableEffect(Unit) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        fun update() {
            t = runCatching {
                val caps = cm?.let { it.getNetworkCapabilities(it.activeNetwork) }
                when {
                    caps == null -> Transport.None
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Transport.Wifi
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Transport.Cellular
                    else -> Transport.None
                }
            }.getOrDefault(Transport.None)
        }
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = update()
            override fun onLost(network: Network) = update()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = update()
        }
        update()
        runCatching { cm?.registerDefaultNetworkCallback(cb) }
        onDispose { runCatching { cm?.unregisterNetworkCallback(cb) } }
    }
    return t
}
