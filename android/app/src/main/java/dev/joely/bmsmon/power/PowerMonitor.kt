package dev.joely.bmsmon.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The phone's own power situation — not to be confused with any BMS pack state. */
data class PowerStatus(val onExternal: Boolean, val levelPct: Int)

/**
 * Watches the phone's charger and battery level via ACTION_BATTERY_CHANGED.
 *
 * That broadcast is sticky, so [start] gets the current state back from registerReceiver
 * immediately — there is nothing to poll. Follows the same register/unregister shape as the
 * engine's Bluetooth adapter receiver.
 */
class PowerMonitor(private val context: Context) {

    private val _status = MutableStateFlow(SAFE_DEFAULT)
    val status: StateFlow<PowerStatus> = _status.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            _status.value = readPowerStatus(intent)
        }
    }

    @Volatile private var registered = false

    fun start() {
        if (registered) return
        runCatching {
            // Sticky broadcast: this returns the current battery intent, so the first status is
            // live rather than the safe default.
            val sticky = context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            registered = true
            _status.value = readPowerStatus(sticky)
        }
    }

    fun stop() {
        if (!registered) return
        registered = false
        // runCatching: unregistering an already-unregistered receiver throws IllegalArgumentException.
        runCatching { context.unregisterReceiver(receiver) }
        _status.value = SAFE_DEFAULT
    }

    companion object {
        /**
         * Fails safe: not plugged in, battery full. Screen is not held and GPS stays high
         * accuracy, so a missing or malformed reading can never fabricate a low-power state.
         */
        val SAFE_DEFAULT = PowerStatus(onExternal = false, levelPct = 100)

        internal fun readPowerStatus(intent: Intent?): PowerStatus {
            if (intent == null) return SAFE_DEFAULT
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            // Any nonzero EXTRA_PLUGGED counts as external power (AC, USB, wireless, dock) —
            // masking to the named AC|USB|WIRELESS constants missed dock chargers, which report
            // EXTRA_PLUGGED=8 and read as unplugged.
            val onExternal = plugged != 0
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val pct = if (level < 0 || scale <= 0) 100 else level * 100 / scale
            return PowerStatus(onExternal = onExternal, levelPct = pct)
        }
    }
}
