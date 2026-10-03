package dev.joely.bmsmon.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.joely.bmsmon.BmsApp
import dev.joely.bmsmon.ble.hasBlePermissions
import dev.joely.bmsmon.location.LocationSource
import dev.joely.bmsmon.MainActivity
import dev.joely.bmsmon.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the [MonitorEngine] alive while the app is backgrounded, so BLE
 * monitoring and usage logging continue with the screen off. It does NOT own the engine (the
 * Application does) — it only anchors the process in the foreground and mirrors engine state into
 * an ongoing notification.
 *
 * Lifecycle:
 *  - START: promote to foreground (connectedDevice type) and stream the engine state to the notification.
 *    A null intent (START_STICKY restart) or [ACTION_RESTORE] (boot / app update, BLE-17) first
 *    restores monitoring headlessly from the persisted settings.
 *  - [ACTION_STOP] — the notification's Stop, and the in-app Stop, which routes through it: stop the
 *    engine, persist `monitoring = false` (BLE-30) so a later reboot / update restore can't undo the
 *    user's Stop, and tear down.
 *  - engine monitoring -> false: tear down the service.
 *  - app swiped away (onTaskRemoved): cleanly stop the engine (disconnecting every BLE link) and exit,
 *    so closing the app never leaves a zombie connection blocking the phone app. Deliberately does
 *    NOT persist `monitoring = false`: a swipe means "close the app", not "stop monitoring" — the
 *    next app open resumes monitoring, and on this dedicated device a reboot brings it back too.
 */
class MonitoringService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collectorJob: Job? = null
    private val engine get() = (application as BmsApp).engine
    // BLE poll cadence depends on the CPU being awake: the loop is a coroutine delay(), which
    // does not fire in suspend. Screen-on used to provide this incidentally; now that the screen
    // is allowed to sleep on battery, this wakelock is what keeps polling, alerting and GPS
    // capture at full cadence. Held while the monitoring session has a pack to poll (BLE-27,
    // wantsCpuWakeLock): released after "Disconnect all", taken again on Reconnect.
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    /** The parts of engine state the notification layer reacts to — anything else changing
     *  (~every 1.5 s poll) must NOT re-post the ongoing notification (BLE-11). [holdCpu] is the
     *  wakelock decision (BLE-27). */
    private data class NotifState(val monitoring: Boolean, val text: String, val fgsType: Int, val holdCpu: Boolean)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // User Stop (notification or in-app): persist it first (BLE-30), then drop BLE links
            // cleanly and exit.
            engine.persistMonitoringOff()
            engine.stop()
            stopCleanly()
            return START_NOT_STICKY
        }
        // BLE-26: promote FIRST, then check the BLE grant. startForegroundService() leaves an
        // obligation to call startForeground(); stopping the service before meeting it crashes the
        // app (ForegroundServiceDidNotStartInTimeException), and not every caller can guarantee
        // the grant — a cancelled permission request returns an empty "all granted" result, and a
        // revocation can land between the start and here. A startForeground() that completes OR is
        // refused discharges the obligation, so attempting it before the check makes the teardown
        // safe on every path. On Android 14+ a connectedDevice FGS without BLUETOOTH_CONNECT is
        // refused with SecurityException: caught here, where it used to crash-loop sticky restarts.
        // Only the FIRST promotion can end the service: a refused RE-promotion (collectorJob set ⇒
        // already foreground) keeps the foreground state it had, so tearing down would only lose it.
        val firstPromotion = collectorJob == null
        val promoted = runCatching {
            startForegroundCompat(buildNotification(monitoringNotificationText(engine.state.value, SystemClock.elapsedRealtime())))
        }.onFailure { Log.w(TAG, "startForeground refused", it) }.isSuccess
        if (firstPromotion && (!promoted || !hasBlePermissions(this))) {
            Log.w(TAG, if (promoted) "BLE permission missing — not monitoring" else "not promoted — not monitoring")
            stopCleanly()
            return START_NOT_STICKY
        }
        if (collectorJob == null) {
            // No ViewModel to drive us — restore headlessly from the persisted settings: a
            // START_STICKY restart (null intent: the OS killed the process while monitoring was on)
            // or the boot / package-replaced receiver (ACTION_RESTORE, BLE-17). If monitoring wasn't
            // actually on (or BLE permission is gone), exit quietly instead of collecting.
            val restore = intent == null || intent.action == ACTION_RESTORE
            collectorJob = scope.launch {
                if (restore && !engine.restoreFromPersisted()) {
                    stopCleanly()
                    return@launch
                }
                engine.state
                    // Derive the notification-relevant fields first, then de-duplicate: the engine
                    // emits on every poll (~1.5 s) but the text/type change rarely (BLE-11).
                    .map { st ->
                        NotifState(
                            st.monitoring,
                            monitoringNotificationText(st, SystemClock.elapsedRealtime()),
                            fgsType(st.gpsActive),
                            wantsCpuWakeLock(st),
                        )
                    }
                    .distinctUntilChanged()
                    .collect { ns ->
                        if (!ns.monitoring) {
                            stopCleanly()
                            return@collect
                        }
                        // BLE-27: hold the CPU while some pack is wanted; release it when "Disconnect
                        // all" (or an empty roster) leaves nothing to poll. Reconnect takes it again.
                        if (ns.holdCpu) acquireWakeLock() else releaseWakeLock()
                        if (Build.VERSION.SDK_INT >= 30 && ns.fgsType != appliedType) {
                            // The parked-GPS gate flips gpsActive on its own now, so the location
                            // bit of the FGS type changes *while the service runs* and often while
                            // the app is in the background. Android 14+ re-checks the FGS start
                            // restriction on every startForeground() call and can answer with
                            // SecurityException / ForegroundServiceStartNotAllowedException — and
                            // an uncaught throw in this collector kills the process, taking the
                            // wheelchair's battery monitor with it. Degrade instead: keep the
                            // previously applied type (GPS itself is already stopped/started by
                            // the engine either way) and still refresh the notification.
                            runCatching { startForegroundCompat(buildNotification(ns.text)) }
                                .onFailure { e ->
                                    Log.w(TAG, "FGS type update to ${ns.fgsType} refused", e)
                                    NotificationManagerCompat.from(this@MonitoringService)
                                        .notify(NOTIF_ID, buildNotification(ns.text))
                                }
                        } else {
                            NotificationManagerCompat.from(this@MonitoringService)
                                .notify(NOTIF_ID, buildNotification(ns.text))
                        }
                    }
            }
        }
        // STICKY (BLE-11): this is a safety monitor — if the OS reclaims the process, it must be
        // restarted (with a null intent, handled above) rather than silently ending monitoring.
        return START_STICKY
    }

    /** App removed from Recents: drop every BLE link cleanly, then exit. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        engine.stop()
        stopCleanly()
        super.onTaskRemoved(rootIntent)
    }

    private fun stopCleanly() {
        releaseWakeLock()
        collectorJob?.cancel()
        collectorJob = null
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        // runCatching: a wakelock failure must never take monitoring down with it — worst case
        // cadence degrades to the old screen-dependent behavior.
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG)
            wl.setReferenceCounted(false)
            wl.acquire()  // no timeout: held while monitoring has a pack to poll (see wantsCpuWakeLock)
            wakeLock = wl
        }
    }

    private fun releaseWakeLock() {
        val wl = wakeLock ?: return
        wakeLock = null
        runCatching { if (wl.isHeld) wl.release() }
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private fun fgsType(gpsActive: Boolean = engine.state.value.gpsActive): Int {
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (gpsActive && LocationSource.hasLocationPermission(this)) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        return type
    }

    private var appliedType: Int = -1
    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 30) {
            val type = fgsType()
            startForeground(NOTIF_ID, notification, type)
            appliedType = type
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, MonitoringService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("${getString(R.string.app_name)} · monitoring")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Battery monitoring", NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Ongoing notification while batteries are monitored in the background." }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "MonitoringService"
        private const val CHANNEL_ID = "monitoring"
        private const val NOTIF_ID = 1
        private const val WAKE_TAG = "bmsmon:monitoring"
        const val ACTION_STOP = "dev.joely.bmsmon.action.STOP_MONITORING"
        /** Boot / package-replaced restore (BLE-17): routes through restoreFromPersisted(). */
        const val ACTION_RESTORE = "dev.joely.bmsmon.action.RESTORE_MONITORING"

        fun startRestore(context: android.content.Context) = startInForeground(context, ACTION_RESTORE)

        fun start(context: android.content.Context) = startInForeground(context, action = null)

        // minSdk 26: startForegroundService() always exists.
        private fun startInForeground(context: android.content.Context, action: String?) {
            context.startForegroundService(Intent(context, MonitoringService::class.java).setAction(action))
        }

        fun stop(context: android.content.Context) {
            context.startService(Intent(context, MonitoringService::class.java).setAction(ACTION_STOP))
        }
    }
}
