package dev.joely.bmsmon.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.joely.bmsmon.BmsApp
import dev.joely.bmsmon.MainActivity
import dev.joely.bmsmon.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Foreground service that keeps the [MonitorEngine] alive while the app is backgrounded, so BLE
 * monitoring and usage logging continue with the screen off. It does NOT own the engine (the
 * Application does) — it only anchors the process in the foreground and mirrors engine state into
 * an ongoing notification.
 *
 * Lifecycle:
 *  - START: promote to foreground (connectedDevice type) and stream the engine state to the notification.
 *  - engine monitoring -> false (in-app Stop, or [ACTION_STOP]): tear down the service.
 *  - app swiped away (onTaskRemoved): cleanly stop the engine (disconnecting every BLE link) and exit,
 *    so closing the app never leaves a zombie connection blocking the phone app.
 */
class MonitoringService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collectorJob: Job? = null
    private val engine get() = (application as BmsApp).engine

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            engine.stop()  // Notification "Stop" tapped: drop BLE links cleanly, then exit.
            stopCleanly()
            return START_NOT_STICKY
        }
        startForegroundCompat(buildNotification())
        if (collectorJob == null) {
            collectorJob = engine.state
                .onEach { st ->
                    if (!st.monitoring) {
                        stopCleanly()
                    } else {
                        NotificationManagerCompat.from(this).notify(NOTIF_ID, buildNotification())
                    }
                }
                .launchIn(scope)
        }
        return START_NOT_STICKY
    }

    /** App removed from Recents: drop every BLE link cleanly, then exit. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        engine.stop()
        stopCleanly()
        super.onTaskRemoved(rootIntent)
    }

    private fun stopCleanly() {
        collectorJob?.cancel()
        collectorJob = null
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        // The connectedDevice FGS-type constant exists from API 30; targetSdk 34 makes the typed
        // call mandatory on API 34+. Older devices read the type from the manifest.
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val st = engine.state.value
        val reachable = st.fleet.values.filter { it.reachable && it.telemetry != null }
        val text = when {
            reachable.isEmpty() -> "Connecting…"
            else -> {
                val low = reachable.minOf { it.telemetry!!.soc }.roundToInt()
                val n = reachable.size
                "$n ${if (n == 1) "pack" else "packs"} connected · lowest $low%"
            }
        }
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
        private const val CHANNEL_ID = "monitoring"
        private const val NOTIF_ID = 1
        const val ACTION_STOP = "dev.joely.bmsmon.action.STOP_MONITORING"

        fun start(context: android.content.Context) {
            val intent = Intent(context, MonitoringService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: android.content.Context) {
            context.startService(Intent(context, MonitoringService::class.java).setAction(ACTION_STOP))
        }
    }
}
