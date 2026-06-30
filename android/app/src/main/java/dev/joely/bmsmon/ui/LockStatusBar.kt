package dev.joely.bmsmon.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryStd
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
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Icon(
                            if (b.charging) Icons.Filled.BatteryChargingFull else Icons.Filled.BatteryStd,
                            "Battery", Modifier.size(16.dp), tint = c.text2,
                        )
                        Text("${b.pct}%", color = c.text2, fontSize = 13.sp, fontFamily = MonoFont)
                    }
                }
            }
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

@Composable
private fun rememberBattery(context: Context): BatteryInfo? {
    var info by remember { mutableStateOf<BatteryInfo?>(null) }
    LaunchedEffect(Unit) {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        while (true) {
            val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            if (pct in 0..100) info = BatteryInfo(pct, bm?.isCharging == true)
            delay(15_000L)
        }
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
