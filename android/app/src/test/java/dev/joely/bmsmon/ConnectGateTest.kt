package dev.joely.bmsmon

import dev.joely.bmsmon.ble.ConnectGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * BLE-16: absent spares used to fill both connect permits after every resume kick, so a stage pack
 * that dropped in that window waited ~20 s for a turn while the stage read DISCONNECTED.
 * runBlocking + real time (the offline Gradle cache has no kotlinx-coroutines-test); every wait is
 * bounded.
 */
class ConnectGateTest {

    /** Let every launched coroutine run until it suspends. */
    private suspend fun settle() = repeat(20) { yield() }

    // Review Focus 1.
    @Test fun aStageAttemptGetsInWhileBackgroundAttemptsQueue() = runBlocking {
        val gate = ConnectGate(total = 2)
        val release = CompletableDeferred<Unit>()
        var inside = 0
        var mostInside = 0
        val spares = List(4) {
            launch {
                gate.withPermit(stage = false) {
                    inside++
                    mostInside = maxOf(mostInside, inside)
                    release.await()
                    inside--
                }
            }
        }
        settle()
        val stageGotIn = withTimeoutOrNull(2_000L) { gate.withPermit(stage = true) { true } }
        assertEquals("a stage attempt must not wait behind queued spares", true, stageGotIn)
        assertEquals("background attempts hold at most one permit", 1, mostInside)
        release.complete(Unit)
        spares.forEach { it.join() }
        assertEquals("…and still run one at a time", 1, mostInside)
    }

    @Test fun twoStageAttemptsRunTogether() = runBlocking {
        val gate = ConnectGate(total = 2)
        val release = CompletableDeferred<Unit>()
        var inside = 0
        var mostInside = 0
        val stage = List(2) {
            launch {
                gate.withPermit(stage = true) {
                    inside++
                    mostInside = maxOf(mostInside, inside)
                    release.await()
                    inside--
                }
            }
        }
        settle()
        assertEquals("both stage packs connect in parallel, as at launch", 2, mostInside)
        release.complete(Unit)
        stage.forEach { it.join() }
    }

    @Test fun aCancelledWaiterGivesBackNothingItDidNotTake() = runBlocking {
        val gate = ConnectGate(total = 2)
        val release = CompletableDeferred<Unit>()
        val holder = launch { gate.withPermit(stage = false) { release.await() } }
        settle()
        val waiter = launch { gate.withPermit(stage = false) { fail("a cancelled waiter must never run") } }
        settle()
        waiter.cancel()
        release.complete(Unit)
        holder.join()
        waiter.join()
        // Every permit is back: two stage attempts get in together again.
        val both = CompletableDeferred<Unit>()
        var inside = 0
        val stage = List(2) { launch { gate.withPermit(stage = true) { inside++; both.await() } } }
        settle()
        assertEquals(2, inside)
        both.complete(Unit)
        stage.forEach { it.join() }
    }

    @Test fun aGateTooSmallToReserveAStagePermitIsRejected() {
        try {
            ConnectGate(total = 1)
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }
}
