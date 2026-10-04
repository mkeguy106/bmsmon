package dev.joely.bmsmon

import dev.joely.bmsmon.location.FusedProvider
import dev.joely.bmsmon.location.GpsFix
import dev.joely.bmsmon.location.LocationSource
import dev.joely.bmsmon.location.REQUEST_RETRY_AFTER_REJECT_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * BLE-19, final wave: `start()` — and so `gpsActive` — reports a registered request only while one
 * really is. Two ways it could lie: a mode switch that removed the request and then failed to
 * re-register it, and a request Play Services fails after accepting it.
 */
class LocationSourceTest {

    /** The fused provider as a request log: each request can be rejected later, like a GMS Task. */
    private class FakeProvider : FusedProvider {
        val calls = ArrayList<String>()
        var throwOnRequest: Throwable? = null
        var lastKnown: GpsFix? = null
        private val rejectors = ArrayList<() -> Unit>()
        var registered: Boolean? = null   // null = nothing registered; else the mode

        override fun request(balanced: Boolean, onFix: (GpsFix) -> Unit, onRejected: () -> Unit) {
            calls += "request(${if (balanced) "balanced" else "high"})"
            throwOnRequest?.let { throw it }
            registered = balanced
            rejectors += onRejected
        }
        override fun remove() {
            calls += "remove"
            registered = null
        }
        override fun lastFix(onFix: (GpsFix) -> Unit) {
            lastKnown?.let(onFix)
        }
        /** Play Services fails request #[n] (0-based) after accepting it. */
        fun reject(n: Int) = rejectors[n]()
    }

    private var now = 0L
    private var lost = 0
    private val provider = FakeProvider()
    private val source = LocationSource(provider, permitted = { true }, onRequestLost = { lost++ }, elapsedNow = { now }, wallNow = { now })

    @Test fun aModeSwitchReplacesTheRequestInPlace() {
        assertTrue(source.start())
        source.setBalanced(true)
        assertEquals("never removed first: there is no window with nothing registered",
            listOf("request(high)", "request(balanced)"), provider.calls)
        assertEquals(true, provider.registered)
    }

    // Final-wave MUST-FIX: the switch used to remove the request, then re-request. A re-request that
    // threw left nothing registered while gpsActive still read true.
    @Test fun aModeSwitchThatThrowsLeavesTheOldRequestRegisteredAndIsRetried() {
        assertTrue(source.start())
        provider.throwOnRequest = IllegalStateException("Play Services is updating")
        try {
            source.setBalanced(true)
            fail("the failure is reported to the caller")
        } catch (_: IllegalStateException) {
        }
        assertEquals("the high-accuracy request is still the registered one", false, provider.registered)
        assertTrue("…and start() still truthfully says one is registered", source.start())
        provider.throwOnRequest = null
        source.setBalanced(true)                      // the next power reading retries the switch
        assertEquals(true, provider.registered)
    }

    @Test fun aModeSwitchWithNothingRegisteredOnlyRecordsTheMode() {
        source.setBalanced(true)
        assertTrue(provider.calls.isEmpty())
        assertTrue(source.start())
        assertEquals(listOf("request(balanced)"), provider.calls)
    }

    // Final-wave: Play Services fails the request after requestLocationUpdates returned. start() used
    // to keep answering true forever (the request "was" registered), and gpsActive with it.
    @Test fun aRequestRejectedLaterIsForgottenAndTheGateIsTold() {
        assertTrue(source.start())
        provider.reject(0)
        assertEquals("the gate is told once, to re-evaluate", 1, lost)
        assertNull("nothing is left registered", provider.registered)
        assertFalse("start() no longer claims a request", source.start())
    }

    @Test fun aRejectedRequestIsRetriedAfterTheHoldOffNotOnEveryFrame() {
        assertTrue(source.start())
        provider.reject(0)
        now += REQUEST_RETRY_AFTER_REJECT_MS - 1
        assertFalse(source.start())
        assertEquals(1, provider.calls.count { it.startsWith("request") })
        now += 1
        assertTrue(source.start())
        assertEquals(2, provider.calls.count { it.startsWith("request") })
    }

    @Test fun aRejectionOfAReplacedOrStoppedRequestChangesNothing() {
        assertTrue(source.start())
        source.setBalanced(true)                      // request #1 replaces #0
        provider.reject(0)
        assertEquals(0, lost)
        assertTrue(source.start())
        source.stop()
        provider.reject(1)                            // after stop(): moot
        assertEquals(0, lost)
    }

    @Test fun aStartThatThrowsAnythingLeavesNothingRegistered() {
        provider.throwOnRequest = LinkageError("Play Services client mismatch")
        try {
            source.start()
            fail("the failure is reported to the caller")
        } catch (_: LinkageError) {
        }
        provider.throwOnRequest = null
        assertTrue("the next evaluation starts it", source.start())
        assertEquals(2, provider.calls.count { it.startsWith("request") })
    }

    @Test fun noPermissionNoRequest() {
        val denied = LocationSource(provider, permitted = { false }, onRequestLost = {}, elapsedNow = { 0L }, wallNow = { 0L })
        assertFalse(denied.start())
        assertTrue(provider.calls.isEmpty())
    }

    @Test fun aFreshLastKnownFixSeedsTheCache() {
        provider.lastKnown = GpsFix(43.0, -87.9, 5f, timeMs = 1_000L)
        now = 2_000L
        assertTrue(source.start())
        assertEquals(provider.lastKnown, source.current(2_000L))
    }
}
