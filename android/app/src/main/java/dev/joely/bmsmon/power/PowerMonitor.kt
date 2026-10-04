package dev.joely.bmsmon.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The phone's own power situation — not to be confused with any BMS pack state. */
data class PowerStatus(
    val onExternal: Boolean,
    val levelPct: Int,
    /** Raw `EXTRA_PLUGGED` (0 = unplugged). */
    val plugged: Int = 0,
    /** Real charge in mAh from the charge counter, or null when the device gives no usable one. */
    val chargeMah: Int? = null,
    /** `SystemClock.elapsedRealtime()` when this status was read; 0 for the safe default. */
    val atElapsedMs: Long = 0L,
)

/**
 * The charge counter in mAh from its two sources, or null when neither is usable.
 *
 * The sticky broadcast's `"charge_counter"` extra (µAh) is read first and the
 * `BATTERY_PROPERTY_CHARGE_COUNTER` property ([propertyMicroAh], read lazily, µAh) only when the
 * extra is missing or not positive. The order matters: `adb shell cmd battery set counter`
 * overrides the broadcast extra but not the HAL property. Anything <= 0 (including
 * `Long.MIN_VALUE`, the property's "unsupported") is "no reading", so the fault fold never fires
 * on it.
 */
internal fun chargeMahOf(extraMicroAh: Int, propertyMicroAh: () -> Long?): Int? {
    if (extraMicroAh > 0) return extraMicroAh / 1000
    val prop = propertyMicroAh() ?: return null
    if (prop <= 0L) return null
    return (prop / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

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

    private val batteryManager: BatteryManager? =
        runCatching { context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager }.getOrNull()

    private fun read(intent: Intent?): PowerStatus = runCatching {
        readPowerStatus(intent, SystemClock.elapsedRealtime()) {
            batteryManager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        }
    }.getOrDefault(SAFE_DEFAULT)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            _status.value = read(intent)
        }
    }

    @Volatile private var registered = false

    private var scope: CoroutineScope? = null
    private var ticker: Job? = null

    fun start() {
        if (registered) return
        runCatching {
            // Sticky broadcast: this returns the current battery intent, so the first status is
            // live rather than the safe default.
            val sticky = context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            registered = true
            _status.value = read(sticky)
            startTicker()
        }
    }

    /**
     * Re-reads the sticky intent every [TICK_MS] and stamps a fresh time, so the status emits even
     * when no broadcast arrives (a dead pad changes neither plug nor level, which is exactly when
     * the charge counter has to keep being sampled). A throw skips that tick; it never escapes.
     */
    private fun startTicker() {
        ticker?.cancel()
        val sc = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scope = it }
        ticker = sc.launch {
            while (isActive) {
                delay(TICK_MS)
                runCatching {
                    val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    if (registered) _status.value = read(sticky)
                }.onFailure { Log.w("PowerMonitor", "power tick failed", it) }
            }
        }
    }

    fun stop() {
        if (!registered) return
        registered = false
        ticker?.cancel()
        ticker = null
        scope?.cancel()
        scope = null
        // runCatching: unregistering an already-unregistered receiver throws IllegalArgumentException.
        runCatching { context.unregisterReceiver(receiver) }
        _status.value = SAFE_DEFAULT
    }

    companion object {
        const val TICK_MS = 60_000L

        /**
         * Fails safe: not plugged in, battery full. Screen is not held and GPS stays high
         * accuracy, so a missing or malformed reading can never fabricate a low-power state.
         */
        val SAFE_DEFAULT = PowerStatus(onExternal = false, levelPct = 100)

        internal fun readPowerStatus(
            intent: Intent?,
            nowElapsedMs: Long = 0L,
            propertyMicroAh: () -> Long? = { null },
        ): PowerStatus {
            if (intent == null) return SAFE_DEFAULT
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            // Any nonzero EXTRA_PLUGGED counts as external power (AC, USB, wireless, dock) —
            // masking to the named AC|USB|WIRELESS constants missed dock chargers, which report
            // EXTRA_PLUGGED=8 and read as unplugged.
            val onExternal = plugged != 0
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val pct = if (level < 0 || scale <= 0) 100 else level * 100 / scale
            val mah = chargeMahOf(intent.getIntExtra("charge_counter", Int.MIN_VALUE)) {
                runCatching(propertyMicroAh).getOrNull()
            }
            return PowerStatus(
                onExternal = onExternal,
                levelPct = pct,
                plugged = plugged,
                chargeMah = mah,
                atElapsedMs = nowElapsedMs,
            )
        }
    }
}
