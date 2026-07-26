package dev.joely.bmsmon.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.concurrent.atomic.AtomicReference

/**
 * A single cached GPS fix attached to outgoing telemetry. [timeMs] is the fix timestamp
 * ([android.location.Location.getTime], UTC epoch ms) — the upload path dedups on it so a fix
 * re-read between provider refreshes uploads once per pack (coordinates jitter while stationary,
 * so identity is the fix TIME, never coordinate equality).
 */
data class GpsFix(val lat: Double, val lon: Double, val accuracyM: Float?, val timeMs: Long)

/**
 * Thin wrapper over the fused location provider. Holds the latest fix in an atomic reference;
 * [current] is read on each telemetry upload. Safe to call [start]/[stop] repeatedly.
 */
class LocationSource(private val context: Context) {

    private val client = LocationServices.getFusedLocationProviderClient(context)
    private val cache = AtomicReference<GpsFix?>(null)
    private var requesting = false
    private var balanced = false

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let {
                cache.set(GpsFix(it.latitude, it.longitude, if (it.hasAccuracy()) it.accuracy else null, it.time))
            }
        }
    }

    @Synchronized
    @SuppressLint("MissingPermission") // guarded by hasLocationPermission
    fun start() {
        if (requesting || !hasLocationPermission(context)) return
        requesting = true
        client.lastLocation.addOnSuccessListener { loc ->
            loc?.let { cache.set(GpsFix(it.latitude, it.longitude, if (it.hasAccuracy()) it.accuracy else null, it.time)) }
        }
        requestUpdates()
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
        if (!requesting) return  // will pick up the new mode on the next start()
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
    fun stop() {
        if (!requesting) return
        requesting = false
        client.removeLocationUpdates(callback)
        cache.set(null)
    }

    fun current(): GpsFix? = cache.get()

    companion object {
        fun hasLocationPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
    }
}
