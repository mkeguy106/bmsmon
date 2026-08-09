package dev.joely.bmsmon.motion

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.util.Log
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
            val still = top.type == DetectedActivity.STILL
            // Permanent instrumentation, not throwaway debug: this is the only window into why the
            // parked-GPS gate is or isn't pausing, since foldMotion() (BatterySaver.kt) only ever
            // sees the cached MotionReading below, never the raw classification that produced it.
            // Cheap — this fires at most once per INTERVAL_MS (~twice a minute).
            Log.d(TAG, "reading activity=${activityName(top.type)} confidence=${top.confidence} still=$still")
            cache.set(
                MotionReading(
                    still = still,
                    confidence = top.confidence,
                    atMs = System.currentTimeMillis(),
                    activity = activityName(top.type),
                ),
            )
        }
    }

    /**
     * `setPackage` makes the intent explicit — required on Android 14+ (targetSdk 34), which
     * throws `IllegalArgumentException` for a `FLAG_MUTABLE` PendingIntent wrapping an implicit
     * intent (bare action, no component/package). Mutable is still required: Play Services fills
     * the [ActivityRecognitionResult] extra into this intent before broadcasting it, which an
     * immutable PendingIntent cannot accept. Restricting to our own package is also strictly
     * safer, and does not affect matching: the dynamically-registered [receiver] still resolves
     * on [ACTION] via its [IntentFilter], same-app delivery is all [RECEIVER_NOT_EXPORTED] ever
     * allowed anyway.
     */
    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(ACTION).setPackage(context.packageName),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    /**
     * No-op when already requesting or the permission is missing. Registration and the update
     * request happen together: [registerReceiver] failing (defensive only — the [requesting]
     * guard above should prevent a duplicate registration from ever reaching it) skips the
     * request entirely, and a request that fails — synchronously or, in the realistic cases, via
     * the returned Task's own async failure listener (see [onSubscribeFailed]) — tears the
     * receiver back down. Either way [requesting] and "receiver is registered" never disagree —
     * if they could, a later [stop] would either skip a live receiver or crash unregistering one
     * that was never added.
     */
    @Synchronized
    @SuppressLint("MissingPermission") // guarded by hasPermission
    fun start() {
        if (requesting || !hasPermission(context)) return
        val registered = runCatching {
            ContextCompat.registerReceiver(
                context, receiver, IntentFilter(ACTION), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.isSuccess
        if (!registered) return
        requesting = true
        runCatching { client.requestActivityUpdates(INTERVAL_MS, pendingIntent()) }
            .onSuccess { task -> task.addOnFailureListener { e -> onSubscribeFailed(e) } }
            .onFailure { e -> onSubscribeFailed(e) }
    }

    /**
     * `requestActivityUpdates()` almost never throws synchronously — the realistic failure modes
     * (Play Services outdated or missing, activity recognition unsupported on this device, API
     * connection failure) surface later through the returned Task's own failure listener, well
     * after [start] has already returned with [requesting] set true. Without this rollback,
     * [requesting] would stay stuck true forever with no update ever going to arrive, and
     * [current] would silently starve with no way to tell "subscribed" from "silently never
     * subscribed" — this also covers the rare synchronous-throw path, so [start] only has one
     * failure handler to reason about.
     *
     * [Synchronized] against [start]/[stop]: Play Services normally delivers this on the main
     * thread, same as callers of [start]/[stop], so in practice this never contends — but nothing
     * here assumes that, and the [requesting] guard makes a late/duplicate callback (e.g. after an
     * explicit [stop] already tore things down) a safe no-op rather than a double-unregister.
     */
    @Synchronized
    private fun onSubscribeFailed(e: Throwable) {
        Log.w(TAG, "requestActivityUpdates failed: ${e.message}")
        if (!requesting) return
        requesting = false
        runCatching { context.unregisterReceiver(receiver) }
    }

    /** Latest reading, or null when none has arrived — null fails open to "not still". */
    fun current(): MotionReading? = cache.get()

    /**
     * Clears the cache along with the subscription. Without this, a reading cached before [stop]
     * would still feed the motion gate (`foldMotion`, BatterySaver.kt) after a later [start] —
     * read as fresh evidence the phone is stationary — even though it describes a session that
     * already ended and GNSS may have moved since.
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
        private const val TAG = "MotionSource"
        private const val ACTION = "dev.joely.bmsmon.MOTION_UPDATE"

        /** ~30 s. Fast enough to notice a van pulling away, slow enough to stay cheap. */
        private const val INTERVAL_MS = 30_000L

        fun hasPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
                PackageManager.PERMISSION_GRANTED
    }
}

/** Human-readable name for a [DetectedActivity] `type` constant, for the diagnostic log above. */
internal fun activityName(type: Int): String = when (type) {
    DetectedActivity.IN_VEHICLE -> "IN_VEHICLE"
    DetectedActivity.ON_BICYCLE -> "ON_BICYCLE"
    DetectedActivity.ON_FOOT -> "ON_FOOT"
    DetectedActivity.STILL -> "STILL"
    DetectedActivity.TILTING -> "TILTING"
    DetectedActivity.WALKING -> "WALKING"
    DetectedActivity.RUNNING -> "RUNNING"
    DetectedActivity.UNKNOWN -> "UNKNOWN"
    else -> "UNRECOGNIZED($type)"
}
