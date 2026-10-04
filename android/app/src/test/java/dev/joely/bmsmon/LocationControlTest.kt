package dev.joely.bmsmon

import dev.joely.bmsmon.location.LocationControl
import dev.joely.bmsmon.location.driveLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** BLE-19: gpsActive is true only while a location request is actually registered. */
class LocationControlTest {

    private class FakeLocation(
        var permitted: Boolean = true,
        var throwsLeft: Int = 0,
        var stopThrows: Boolean = false,
    ) : LocationControl {
        var requesting = false
        var starts = 0
        override fun start(): Boolean {
            starts++
            if (throwsLeft > 0) {
                throwsLeft--
                throw SecurityException("permission revoked between the check and the request")
            }
            if (!requesting && permitted) requesting = true
            return requesting
        }
        override fun stop() {
            if (stopThrows) throw SecurityException("removeLocationUpdates refused")
            requesting = false
        }
    }

    // Review Focus 4: GPS switched on, the permission dialog answered afterwards.
    @Test fun aGrantAfterTheGateOpenedStartsTheRequestOnTheNextEvaluation() {
        val loc = FakeLocation(permitted = false)
        assertFalse(driveLocation(true, loc) { throw AssertionError(it) })
        assertFalse(loc.requesting)
        loc.permitted = true                                          // the user grants it
        assertTrue(driveLocation(true, loc) { throw AssertionError(it) })   // the next BLE frame
        assertTrue(loc.requesting)
    }

    @Test fun gpsIsNeverActiveWithoutARegisteredRequest() {
        val loc = FakeLocation(permitted = false)
        repeat(3) { assertFalse(driveLocation(true, loc) {}) }
        assertEquals("retried on every evaluation, never claimed", 3, loc.starts)
    }

    @Test fun aStartThatThrowsReadsInactiveAndIsRetried() {
        val loc = FakeLocation(throwsLeft = 1)
        val errors = ArrayList<Throwable>()
        assertFalse(driveLocation(true, loc) { errors += it })
        assertEquals(1, errors.size)
        assertTrue(driveLocation(true, loc) { errors += it })
        assertEquals(1, errors.size)
    }

    // A stop that throws is reported and reads as not running — never a crash — and the next
    // closed-gate evaluation retries it.
    @Test fun aStopThatThrowsIsReportedAndReadsInactive() {
        val loc = FakeLocation(stopThrows = true)
        assertTrue(driveLocation(true, loc) {})
        val errors = ArrayList<Throwable>()
        assertFalse(driveLocation(false, loc) { errors += it })
        assertEquals(1, errors.size)
        loc.stopThrows = false
        assertFalse(driveLocation(false, loc) { errors += it })
        assertFalse(loc.requesting)
        assertEquals(1, errors.size)
    }

    @Test fun aClosedGateStopsTheRequest() {
        val loc = FakeLocation()
        assertTrue(driveLocation(true, loc) {})
        assertFalse(driveLocation(false, loc) {})
        assertFalse(loc.requesting)
    }
}
