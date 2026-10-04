package dev.joely.bmsmon.cloud

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat

/** The oldest satellite fix still taken as a clock: carried forward on the monotonic clock, it drifts only ppm. */
internal const val SATELLITE_TIME_MAX_AGE_MS = 10 * 60_000L

/**
 * A satellite fix's own clock: its constellation UTC time ([utcMs]) and the monotonic
 * `SystemClock.elapsedRealtime()` at which it was taken ([elapsedRealtimeMs]). Only a GPS-provider fix
 * carries the constellation's time; a fused fix is commonly stamped with this phone's own clock, so it
 * can never vouch for it.
 */
internal data class SatelliteTime(val utcMs: Long, val elapsedRealtimeMs: Long)

/** "Now" by [fix]'s clock, carried forward to [nowElapsedMs]; null without a fix, or one older than [SATELLITE_TIME_MAX_AGE_MS] or from the future (a reboot). */
internal fun satelliteNowMs(fix: SatelliteTime?, nowElapsedMs: Long): Long? {
    if (fix == null || fix.elapsedRealtimeMs > nowElapsedMs) return null
    val ageMs = nowElapsedMs - fix.elapsedRealtimeMs
    if (ageMs < 0 || ageMs > SATELLITE_TIME_MAX_AGE_MS) return null
    return fix.utcMs + ageMs
}

/** The independent "now": the platform's network time when it has one, else a fresh satellite fix's. */
internal fun independentNowMs(networkNowMs: Long?, fix: SatelliteTime?, nowElapsedMs: Long): Long? =
    networkNowMs ?: satelliteNowMs(fix, nowElapsedMs)

/** This phone's wall clock minus [independentNowMs]; null without one. Saturates rather than overflow. */
internal fun phoneClockErrorMs(phoneNowMs: Long, independentNowMs: Long?): Long? =
    independentNowMs?.let { runCatching { Math.subtractExact(phoneNowMs, it) }.getOrDefault(Long.MAX_VALUE) }

/**
 * Reads the clocks that can vouch for this phone's (DATA-20): network time (`SystemClock
 * .currentNetworkTimeClock()`, API 33+), else the platform's last GPS-provider fix. Never throws; a
 * clock it cannot read is simply absent.
 */
internal class IndependentClock(private val context: Context) {

    /** This phone's wall clock minus the independent one, now, or null when there is none. */
    fun measurePhoneClockErrorMs(): Long? {
        val independent = independentNowMs(networkNowMs(), satelliteTime(), SystemClock.elapsedRealtime())
        return phoneClockErrorMs(System.currentTimeMillis(), independent)
    }

    private fun networkNowMs(): Long? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        // DateTimeException: no network time yet (e.g. since boot).
        return runCatching { SystemClock.currentNetworkTimeClock().millis() }.getOrNull()
    }

    private fun satelliteTime(): SatelliteTime? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        return runCatching {
            val lm = context.getSystemService(LocationManager::class.java) ?: return null
            val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: return null
            SatelliteTime(loc.time, loc.elapsedRealtimeNanos / 1_000_000L)
        }.getOrNull()
    }
}
