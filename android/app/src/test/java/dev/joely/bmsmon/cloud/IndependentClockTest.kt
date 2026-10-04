package dev.joely.bmsmon.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The clock that vouches for this phone's before a signing correction may start (DATA-20 review). */
class IndependentClockTest {

    private val elapsedNow = 5_000_000L

    // A satellite fix's time is the constellation's; carried forward on the monotonic clock it is
    // "now" by a clock this phone's wall clock cannot influence.
    @Test fun aSatelliteFixIsCarriedForwardOnTheMonotonicClock() {
        val fix = SatelliteTime(utcMs = 1_759_515_000_000L, elapsedRealtimeMs = elapsedNow - 42_000L)
        assertEquals(1_759_515_042_000L, satelliteNowMs(fix, elapsedNow))
        assertEquals(1_759_515_000_000L, satelliteNowMs(fix.copy(elapsedRealtimeMs = elapsedNow), elapsedNow))
    }

    @Test fun anOldOrFutureFixIsNoEvidence() {
        val fix = SatelliteTime(utcMs = 1_759_515_000_000L, elapsedRealtimeMs = elapsedNow - SATELLITE_TIME_MAX_AGE_MS)
        assertEquals(1_759_515_000_000L + SATELLITE_TIME_MAX_AGE_MS, satelliteNowMs(fix, elapsedNow))
        assertNull(satelliteNowMs(fix.copy(elapsedRealtimeMs = elapsedNow - SATELLITE_TIME_MAX_AGE_MS - 1), elapsedNow))
        // From before a reboot (the monotonic clock restarted), or otherwise nonsensical.
        assertNull(satelliteNowMs(fix.copy(elapsedRealtimeMs = elapsedNow + 1), elapsedNow))
        assertNull(satelliteNowMs(fix.copy(elapsedRealtimeMs = Long.MIN_VALUE), elapsedNow))
        assertNull(satelliteNowMs(null, elapsedNow))
    }

    @Test fun networkTimeIsPreferredOverASatelliteFix() {
        val fix = SatelliteTime(utcMs = 1_759_515_000_000L, elapsedRealtimeMs = elapsedNow)
        assertEquals(1_759_515_999_000L, independentNowMs(networkNowMs = 1_759_515_999_000L, fix = fix, nowElapsedMs = elapsedNow))
        assertEquals(1_759_515_000_000L, independentNowMs(networkNowMs = null, fix = fix, nowElapsedMs = elapsedNow))
        assertNull(independentNowMs(networkNowMs = null, fix = null, nowElapsedMs = elapsedNow))
    }

    @Test fun thePhoneClockErrorIsPhoneMinusIndependent() {
        assertEquals(700_000L, phoneClockErrorMs(phoneNowMs = 1_759_515_700_000L, independentNowMs = 1_759_515_000_000L))
        assertEquals(-2_000L, phoneClockErrorMs(phoneNowMs = 1_759_514_998_000L, independentNowMs = 1_759_515_000_000L))
        assertNull(phoneClockErrorMs(phoneNowMs = 1_759_515_000_000L, independentNowMs = null))
        // Never overflows into a value that would look like agreement.
        assertEquals(Long.MAX_VALUE, phoneClockErrorMs(phoneNowMs = 1_759_515_000_000L, independentNowMs = Long.MIN_VALUE))
    }
}
