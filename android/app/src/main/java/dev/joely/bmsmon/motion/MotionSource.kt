package dev.joely.bmsmon.motion

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity
import dev.joely.bmsmon.model.MotionReading
import java.util.concurrent.atomic.AtomicReference

/**
 * Phone-motion sampling for the parked-GPS gate, mirroring [dev.joely.bmsmon.location.LocationSource]:
 * start/stop, latest reading held in an [AtomicReference], no Android types leaking upward.
 *
 * Uses the **periodic** Activity Recognition API rather than Activity Transitions. Transitions are
 * cheaper but hinge on catching a single edge — one missed or late `ENTER IN_VEHICLE` loses the
 * outing, which is precisely the failure this feature exists to fix, and a silently lapsed
 * subscription is indistinguishable from "never moved". Periodic updates re-assert current state
 * every cycle, so a missed sample self-corrects on the next one.
 */
class MotionSource(private val context: Context) {

    private val client = ActivityRecognition.getClient(context)
    private val cache = AtomicReference<MotionReading?>(null)
    private var requesting = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val result = ActivityRecognitionResult.extractResult(intent) ?: return
            val top = result.mostProbableActivity
            // UNKNOWN, IN_VEHICLE, WALKING, ON_FOOT, ON_BICYCLE and TILTING all yield still=false,
            // which keeps GPS on. Not knowing is not the same as knowing it is stationary.
            cache.set(
                MotionReading(
                    still = top.type == DetectedActivity.STILL,
                    confidence = top.confidence,
                    atMs = System.currentTimeMillis(),
                ),
            )
        }
    }

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    /**
     * No-op when already requesting or the permission is missing. Registration and the update
     * request happen together: if the request fails after the receiver is registered, the
     * receiver is torn back down in the same call so a failed start never leaves a registered
     * receiver with [requesting] still false — the two must agree, or a later [stop] would either
     * skip a live receiver or crash unregistering one that was never added.
     */
    @Synchronized
    fun start() {
        if (requesting || !hasPermission(context)) return
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(ACTION), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        runCatching { client.requestActivityUpdates(INTERVAL_MS, pendingIntent()) }
            .onFailure { runCatching { context.unregisterReceiver(receiver) }; return }
        requesting = true
    }

    /** Latest reading, or null when none has arrived — null fails open to "not still". */
    fun current(): MotionReading? = cache.get()

    /**
     * Clears the cache along with the subscription. Without this, a reading cached before [stop]
     * would still satisfy `confidentlyStill` after a later [start] — read as fresh evidence the
     * phone is stationary — even though it describes a session that already ended and GNSS may
     * have moved since.
     */
    @Synchronized
    fun stop() {
        if (!requesting) return
        requesting = false
        runCatching { client.removeActivityUpdates(pendingIntent()) }
        runCatching { context.unregisterReceiver(receiver) }
        cache.set(null)
    }

    companion object {
        private const val ACTION = "dev.joely.bmsmon.MOTION_UPDATE"

        /** ~30 s. Fast enough to notice a van pulling away, slow enough to stay cheap. */
        private const val INTERVAL_MS = 30_000L

        fun hasPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
                PackageManager.PERMISSION_GRANTED
    }
}
