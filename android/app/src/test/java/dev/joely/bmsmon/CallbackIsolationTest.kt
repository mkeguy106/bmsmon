package dev.joely.bmsmon

import dev.joely.bmsmon.ble.isolateCallback
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * BLE-22: one bad frame must be a dropped frame, not a process crash. The engine callbacks run
 * inside BmsRepository's control loop; an escaping throw killed the loop, then the process (no
 * CoroutineExceptionHandler) — and the foreground service with it — with a crash loop if the
 * same frame recurred after START_STICKY.
 */
class CallbackIsolationTest {

    @Test fun aHealthyCallbackRuns() {
        var ran = false
        isolateCallback({ fail("no error expected: $it") }) { ran = true }
        assertTrue(ran)
    }

    @Test fun aThrowingCallbackIsDroppedAndReported() {
        var reported: Exception? = null
        isolateCallback({ reported = it }) { throw IllegalStateException("bad frame") }
        assertEquals("bad frame", reported?.message)
    }

    @Test fun cancellationIsNeverSwallowed() {
        var swallowed = false
        try {
            isolateCallback({ swallowed = true }) { throw CancellationException("stopping") }
            fail("cancellation must propagate")
        } catch (e: CancellationException) {
            assertFalse(swallowed)
        }
    }

    @Test fun errorsAreNotIsolated() {
        // An Error (OOM, stack overflow) means the process state is unknown: crash, don't limp on.
        try {
            isolateCallback({ fail("an Error must not be reported as a dropped event") }) { throw StackOverflowError() }
            fail("an Error must propagate")
        } catch (e: StackOverflowError) {
            // expected
        }
    }
}
