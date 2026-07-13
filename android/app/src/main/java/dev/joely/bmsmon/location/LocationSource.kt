package dev.joely.bmsmon.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.concurrent.atomic.AtomicReference

/** A single cached GPS fix attached to outgoing telemetry. */
data class GpsFix(val lat: Double, val lon: Double, val accuracyM: Float?)

/**
 * Thin wrapper over the fused location provider. Holds the latest fix in an atomic reference;
 * [current] is read on each telemetry upload. Safe to call [start]/[stop] repeatedly.
 */
class LocationSource(private val context: Context) {

    private val client = LocationServices.getFusedLocationProviderClient(context)
    private val cache = AtomicReference<GpsFix?>(null)
    private var requesting = false

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let {
                cache.set(GpsFix(it.latitude, it.longitude, if (it.hasAccuracy()) it.accuracy else null))
            }
        }
    }

    @SuppressLint("MissingPermission") // guarded by hasLocationPermission
    fun start() {
        if (requesting || !hasLocationPermission(context)) return
        requesting = true
        client.lastLocation.addOnSuccessListener { loc ->
            loc?.let { cache.set(GpsFix(it.latitude, it.longitude, if (it.hasAccuracy()) it.accuracy else null)) }
        }
        // Always-on GNSS (2026-07-13): balanced-power WiFi/cell fixes averaged ~90 m and
        // spawned the phantom map spikes; the phone rides the chair on constant USB power,
        // so there is no battery reason to accept coarse fixes — high accuracy, always.
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000L)
            .setMinUpdateIntervalMillis(2_000L)
            .build()
        client.requestLocationUpdates(req, callback, null)
    }

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
