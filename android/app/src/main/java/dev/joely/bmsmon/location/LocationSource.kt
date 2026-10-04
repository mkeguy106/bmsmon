package dev.joely.bmsmon.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.os.SystemClock
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
 * The fused location provider as [LocationSource] drives it: Play Services in production
 * ([GmsFusedProvider]), a fake in JVM tests.
 */
interface FusedProvider {
    /**
     * Register the request for [balanced] (or high) accuracy, delivering fixes to [onFix]. It
     * REPLACES any request this provider already has registered — GMS replaces a request made with
     * the same callback in place — so a throw leaves the previous registration in force.
     * [onRejected] runs, on any thread, if the provider fails the request after accepting it (GMS
     * posts it to the main looper; one run before [request] returns is handled too).
     * It must NEVER run synchronously inside [request] on the mode-switch path: that path holds this
     * source's lock, and [onRejected] re-enters the engine's GPS gate, whose lock order is
     * engine-then-source — a synchronous call would invert it. Post it instead (as GMS does).
     */
    fun request(balanced: Boolean, onFix: (GpsFix) -> Unit, onRejected: () -> Unit)

    /** Remove the registered request, if any. */
    fun remove()

    /** Best-effort one-shot last-known fix. */
    fun lastFix(onFix: (GpsFix) -> Unit)
}

/** [FusedProvider] over Play Services' fused location client. Callers guard on location permission. */
@SuppressLint("MissingPermission")
private class GmsFusedProvider(context: Context) : FusedProvider {
    private val client = LocationServices.getFusedLocationProviderClient(context)
    @Volatile private var sink: (GpsFix) -> Unit = {}

    // ONE callback for every request: re-requesting with it replaces the registered request.
    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { sink(it.toFix()) }
        }
    }

    override fun request(balanced: Boolean, onFix: (GpsFix) -> Unit, onRejected: () -> Unit) {
        sink = onFix
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
            .addOnFailureListener { onRejected() }
    }

    override fun remove() {
        client.removeLocationUpdates(callback)
    }

    override fun lastFix(onFix: (GpsFix) -> Unit) {
        client.lastLocation.addOnSuccessListener { loc -> loc?.let { onFix(it.toFix()) } }
    }

    private fun Location.toFix() = GpsFix(latitude, longitude, if (hasAccuracy()) accuracy else null, time)
}

/** How long a request the provider rejected after accepting it keeps [LocationSource] from asking
 *  again — so a Play Services outage costs a retry a minute, not one on every BLE frame. */
internal const val REQUEST_RETRY_AFTER_REJECT_MS = 60_000L

/**
 * The fused request the GPS gate drives. Holds the latest fix in a [FixCache]; [current] is read on
 * each telemetry sample. Safe to call [start]/[stop] repeatedly.
 *
 * [start] reports true only while a request is registered (BLE-19), and that stays true to life:
 * a mode switch ([setBalanced]) replaces the request in place, so a failed switch leaves the old one
 * registered rather than none; and a request the provider fails AFTER accepting it (Play Services
 * rejecting it asynchronously) is forgotten, held off for [REQUEST_RETRY_AFTER_REJECT_MS], and
 * reported through [onRequestLost] so the gate re-evaluates and publishes `gpsActive = false`.
 */
class LocationSource internal constructor(
    private val provider: FusedProvider,
    private val permitted: () -> Boolean,
    /** Runs, outside this source's lock, when a registered request was lost (see above). */
    private val onRequestLost: () -> Unit,
    private val elapsedNow: () -> Long = { SystemClock.elapsedRealtime() },
    private val wallNow: () -> Long = { System.currentTimeMillis() },
) : LocationControl {

    constructor(context: Context, onRequestLost: () -> Unit) :
        this(GmsFusedProvider(context), { hasLocationPermission(context) }, onRequestLost)

    private val fixes = FixCache()
    private var balanced = false
    private var requestSeq = 0L          // the latest request handed to the provider
    private var retryAt: Long? = null    // elapsed clock: no new request before this (a rejection)

    /**
     * Register the fused request if it isn't already (idempotent). Returns whether one is registered
     * after the call: false without location permission, or while a rejected request is held off —
     * the GPS gate publishes gpsActive from this and calls again at its next evaluation, so a later
     * grant is picked up (BLE-19). A provider throw propagates with nothing registered.
     */
    @Synchronized
    override fun start(): Boolean {
        if (fixes.requesting) return true
        if (!permitted()) return false
        retryAt?.let { if (elapsedNow() < it) return false }
        retryAt = null
        val gen = fixes.begin()
        try {
            request(balanced)
        } catch (e: Throwable) {
            fixes.end()
            throw e
        }
        // Best-effort seed: the request above is what matters, so a failure here is not a failed start.
        runCatching { provider.lastFix { fixes.offerSeed(gen, it, wallNow()) } }
        // Not a bare `true`: a provider that fails the request before returning has already ended it.
        return fixes.requesting
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
     *
     * A registered request is REPLACED in place, never removed first: it used to be removed and then
     * re-requested, so a re-request that threw left nothing registered while gpsActive still read
     * true. Now a throw propagates with the old request — and the old mode — still in force, and the
     * next call retries the switch.
     */
    @Synchronized
    fun setBalanced(balanced: Boolean) {
        if (balanced == this.balanced) return
        if (fixes.requesting) request(balanced)   // not requesting: the next start() uses the new mode
        this.balanced = balanced
    }

    @Synchronized
    override fun stop() {
        if (!fixes.requesting) return
        provider.remove()
        fixes.end()
    }

    /** The cached fix, or null when none is fresher than 120 s (checked at read time, BLE-23). */
    fun current(nowMs: Long = wallNow()): GpsFix? = fixes.read(nowMs)

    /** Lock held: hand the provider a request, tagged so a late rejection of it can be told apart. */
    private fun request(balanced: Boolean) {
        val id = ++requestSeq
        provider.request(balanced, fixes::offerLive) { onRejected(id) }
    }

    /** The provider failed request [id] after accepting it. Only the latest request, still
     *  registered, counts: an older one was replaced, and one after stop() is moot. */
    private fun onRejected(id: Long) {
        val lost = synchronized(this) {
            if (id != requestSeq || !fixes.requesting) return@synchronized false
            // Whatever the provider still holds, nothing is registered from here on — make it so.
            runCatching { provider.remove() }
            fixes.end()
            retryAt = elapsedNow() + REQUEST_RETRY_AFTER_REJECT_MS
            true
        }
        // Outside this lock: the gate takes the engine's, and its order is engine -> this.
        if (lost) onRequestLost()
    }

    companion object {
        fun hasLocationPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
    }
}
