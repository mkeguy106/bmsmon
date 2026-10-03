package dev.joely.bmsmon

import dev.joely.bmsmon.ui.history.HistoryLoad
import dev.joely.bmsmon.ui.history.loadHistory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class HistoryLoadTest {

    @Test fun successIsReady() {
        runBlocking { assertEquals(HistoryLoad.Ready(42), loadHistory { 42 }) }
    }

    @Test fun anExceptionBecomesFailedInsteadOfEscaping() {
        runBlocking {
            val boom = IllegalStateException("database disk image is malformed")
            assertEquals(HistoryLoad.Failed(boom), loadHistory<Int> { throw boom })
        }
    }

    @Test fun evenAnErrorBecomesFailed() {
        runBlocking {
            val oom = OutOfMemoryError("synthetic")
            assertEquals(HistoryLoad.Failed(oom), loadHistory<Int> { throw oom })
        }
    }

    @Test fun cancellationStillPropagates() {
        runBlocking {
            try {
                loadHistory<Int> { throw CancellationException("navigated away") }
                fail("cancellation was swallowed")
            } catch (_: CancellationException) {
            }
        }
    }
}
