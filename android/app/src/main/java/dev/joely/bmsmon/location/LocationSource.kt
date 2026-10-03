package dev.joely.bmsmon.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * A single cached GPS fix attached to outgoing telemetry. [timeMs] is the fix timestamp
 * ([android.location.Location.getTime], UTC epoch ms) — the upload path dedups on it so a fix
 * re-read between provider refreshes uploads once per pack (coordinates jitter while stationary,
 * so identity is the fix TIME, never coordinate equality).
 */
data class GpsFix(val lat: Double, val lon: Double, val accuracyM: Float?, val timeMs: Long)

/**
 * A fix older than this is never used as "now". Before the parked-GPS gate, [LocationSource.start]
 * ran once per monitoring session; now it runs at every park→drive transition, so `lastLocation`
 * can hand back an arbitrarily old pre-park fix — which would otherwise get attached to every
 * locally logged Room sample until a real GNSS fix arrives (up to the measured 292 s indoor TTFF),
 * writing known-wrong coordinates into field telemetry. 120 s comfortably exceeds normal fix
 * cadence (2-20 s) while staying well under that TTFF, so a genuinely fresh idle fix is never
 * rejected. Applied when a seed is cached AND when any fix is read (BLE-23).
 */
private const val MAX_CACHED_FIX_AGE_MS = 120_000L

/**
 * True when a fix timestamped [fixTimeMs] is too old to trust as "now". Both timestamps are
 * wall-clock epoch ms: [android.location.Location.getTime] returns UTC epoch ms — NOT
 * `SystemClock.elapsedRealtime()`, which is boot-relative — so it is compared against
 * `System.currentTimeMillis()`, the matching wall clock, not a monotonic one.
 */
internal fun isFixTooStale(fixTimeMs: Long, nowMs: Long, maxAgeMs: Long = MAX_CACHED_FIX_AGE_MS): Boolean =
    nowMs - fixTimeMs > maxAgeMs

/**
 * The fix cache behind [LocationSource], pure so its rules are JVM-tested (BLE-23). The staleness
 * guard used to apply only when the `lastLocation` seed was WRITTEN, so a seed that landed after
 * stop() (start → stop inside the Task's latency) sat in the cache until the next start() and was
 * then attached to every sample until GNSS got a fix — a phantom point on the map. Now the guard
 * also applies at READ time, and each start has a generation, so a late seed from an earlier start
 * is ignored. Synchronized: GMS callbacks arrive on the main looper, readers on the BLE threads.
 */
class FixCache(private val maxAgeMs: Long = MAX_CACHED_FIX_AGE_MS) {
    private var generation = 0L
    private var fix: GpsFix? = null

    /** True between [begin] and [end]. */
    @Volatile var requesting = false
        private set

    /** A request starts: returns its generation (pass it back with that start's seed). */
    @Synchronized
    fun begin(): Long {
        generation++
        requesting = true
        return generation
    }

    /** The request ends: forget the fix; anything still in flight from it is ignored. */
    @Synchronized
    fun end() {
        generation++
        requesting = false
        fix = null
    }

    /** A continuous-update result. Dropped once the request has ended (one dispatched just before
     *  the updates were removed can still arrive). */
    @Synchronized
    fun offerLive(f: GpsFix) {
        if (requesting) fix = f
    }

    /** The one-shot `lastLocation` seed of start [gen]: kept only while that start is current, when
     *  it isn't stale, and when no newer live fix has already landed. */
    @Synchronized
    fun offerSeed(gen: Long, f: GpsFix, nowMs: Long) {
        if (gen != generation || !requesting) return
        if (isFixTooStale(f.timeMs, nowMs, maxAgeMs)) return
        val cur = fix
        if (cur != null && cur.timeMs >= f.timeMs) return
        fix = f
    }

    /** The cached fix, or null once it is older than the limit — checked at READ time. */
    @Synchronized
    fun read(nowMs: Long): GpsFix? = fix?.takeIf { !isFixTooStale(it.timeMs, nowMs, maxAgeMs) }
}

/**
 * Thin wrapper over the fused location provider. Holds the latest fix in a [FixCache]; [current]
 * is read on each telemetry sample. Safe to call [start]/[stop] repeatedly.
 */
class LocationSource(private val context: Context) : LocationControl {

    private val client = LocationServices.getFusedLocationProviderClient(context)
    private val fixes = FixCache()
    private var balanced = false

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { fixes.offerLive(it.toFix()) }
        }
    }

    /**
     * Register the fused request if it isn't already (idempotent). Returns whether one is registered
     * after the call: false without location permission — the GPS gate publishes gpsActive from this
     * and calls again at its next evaluation, so a later grant is picked up (BLE-19). A GMS throw
     * propagates with nothing registered.
     */
    @Synchronized
    @SuppressLint("MissingPermission") // guarded by hasLocationPermission
    override fun start(): Boolean {
        if (fixes.requesting) return true
        if (!hasLocationPermission(context)) return false
        val gen = fixes.begin()
        try {
            requestUpdates()
        } catch (e: Exception) {
            fixes.end()
            throw e
        }
        // Best-effort seed: the request above is what matters, so a failure here is not a failed start.
        runCatching {
            client.lastLocation.addOnSuccessListener { loc ->
                loc?.let { fixes.offerSeed(gen, it.toFix(), System.currentTimeMillis()) }
            }
        }
        return true
    }

    /**
     * Switch between high-accuracy and balanced-power fixes.
     *
     * High accuracy is the norm (2026-07-13): balanced-power WiFi/cell fixes averaged ~90 m and
     * spawned the phantom map spikes, and the phone normally rides the chair on USB power. The
     * ONLY time coarse fixes are accepted is the low-battery window (2026-07-25) — entered below
     * 5%, held until 15% — where the phone must claw its way back to a safe charge. On a
     * charging chair-mounted phone that window can run 15-30 minutes, so it is not brief, but it
     * is rare, and the still-converging Wh/mile band is never fed a meaningful amount of coarse
     * data.
     */
    @Synchronized
    fun setBalanced(balanced: Boolean) {
        if (balanced == this.balanced) return
        this.balanced = balanced
        if (!fixes.requesting) return  // will pick up the new mode on the next start()
        client.removeLocationUpdates(callback)
        requestUpdates()
    }

    @SuppressLint("MissingPermission") // callers guard on hasLocationPermission
    private fun requestUpdates() {
        val req = if (balanced) {
            LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 20_000L)
                .setMinUpdateIntervalMillis(10_000L)
                .build()
        } else {
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000L)
                .setMinUpdateIntervalMillis(2_000L)
                .build()
        }
        client.requestLocationUpdates(req, callback, Looper.getMainLooper())
    }

    @Synchronized
    override fun stop() {
        if (!fixes.requesting) return
        client.removeLocationUpdates(callback)
        fixes.end()
    }

    /** The cached fix, or null when none is fresher than 120 s (checked at read time, BLE-23). */
    fun current(nowMs: Long = System.currentTimeMillis()): GpsFix? = fixes.read(nowMs)

    private fun Location.toFix() = GpsFix(latitude, longitude, if (hasAccuracy()) accuracy else null, time)

    companion object {
        fun hasLocationPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
    }
}
