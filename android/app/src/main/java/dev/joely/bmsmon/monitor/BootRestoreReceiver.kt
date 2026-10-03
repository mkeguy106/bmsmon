package dev.joely.bmsmon.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.joely.bmsmon.BmsApp
import dev.joely.bmsmon.ble.hasBlePermissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * BLE-17: after a reboot (the documented "phone died at 0 % → reboots on the charger" path, or an
 * OTA) or an app update (incl. `adb install -r`), restart monitoring headlessly through the same
 * path as a START_STICKY restart ([MonitorEngine.restoreFromPersisted]) — but only if monitoring
 * was on and BLE is still granted ([shouldRestoreMonitoring]). It never relaunches the Activity.
 * BOOT_COMPLETED and MY_PACKAGE_REPLACED are documented exemptions from the background
 * FGS-start restriction. Never throws: a failed restore must not crash the process at boot.
 */
class BootRestoreReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (!isRestoreTrigger(action)) return
        val app = context.applicationContext as? BmsApp ?: return
        // The DataStore read runs off the main thread; goAsync() keeps the broadcast open until
        // it finishes, and the read is bounded so finish() always lands inside the receiver's
        // budget. A read that times out counts as unreadable settings: don't restore.
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val monitoring = runCatching {
                    withTimeoutOrNull(SETTINGS_READ_TIMEOUT_MS) { app.settings.load().monitoring }
                }.getOrNull()
                if (monitoring == null) Log.w(TAG, "settings unreadable after $action — not restoring")
                if (shouldRestoreMonitoring(action, monitoring, hasBlePermissions(app))) {
                    runCatching { MonitoringService.startRestore(app) }
                        .onFailure { Log.w(TAG, "headless restore after $action refused", it) }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootRestore"
        /** Well inside the ~10 s a receiver may hold its broadcast open via goAsync(). */
        const val SETTINGS_READ_TIMEOUT_MS = 5_000L
    }
}
