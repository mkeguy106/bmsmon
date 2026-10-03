package dev.joely.bmsmon.location

/** The fused-location request as the GPS gate drives it — [LocationSource] in production. */
interface LocationControl {
    /** Register the request if it isn't already; true when one is registered after the call. */
    fun start(): Boolean

    /** Remove the request if one is registered; a no-op otherwise. */
    fun stop()
}

/**
 * Apply one GPS-gate verdict ([run]) and return the `gpsActive` to publish (BLE-19): true only
 * while a request is actually registered. Called on EVERY gate evaluation, not only when the
 * verdict flips — both calls are idempotent — so a location permission granted after GPS was
 * switched on starts the request at the next evaluation; it used to start never, until the gate
 * happened to cycle, while gpsActive (and the FGS location type) already claimed it ran. A throw
 * is reported to [onError] and reads as not running; the next evaluation retries.
 */
fun driveLocation(run: Boolean, loc: LocationControl, onError: (Throwable) -> Unit): Boolean {
    if (!run) {
        runCatching { loc.stop() }.onFailure(onError)
        return false
    }
    return runCatching { loc.start() }.onFailure(onError).getOrDefault(false)
}
