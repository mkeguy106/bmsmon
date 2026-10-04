package dev.joely.bmsmon

import dev.joely.bmsmon.ble.ConnectGate
import dev.joely.bmsmon.ble.ConnectOutcome
import dev.joely.bmsmon.ble.attemptConnect
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

/**
 * BLE-16: absent spares used to fill both connect permits after every resume kick, so a stage pack
 * that dropped in that window waited ~20 s for a turn while the stage read DISCONNECTED.
 * runBlocking + real time (the offline Gradle cache has no kotlinx-coroutines-test); every wait is
 * bounded, so a leaked permit fails a test instead of hanging the suite.
 */
class ConnectGateTest {

    private val stage = { true }
    private val spare = { false }

    /** Let every launched coroutine run until it suspends. */
    private suspend fun settle() = repeat(20) { yield() }

    private suspend fun Job.joinWithin() = withTimeout(5_000L) { join() }

    /** Both lanes admit again: a background attempt, then two stage attempts together. */
    private suspend fun CoroutineScope.assertEveryPermitIsBack(gate: ConnectGate) {
        assertEquals("the background lane admits", true, withTimeoutOrNull(2_000L) { gate.withPermit(spare) { true } })
        val both = CompletableDeferred<Unit>()
        var inside = 0
        val jobs = List(2) { launch { gate.withPermit(stage) { inside++; both.await() } } }
        settle()
        assertEquals("…and both permits admit two stage attempts together", 2, inside)
        both.complete(Unit)
        jobs.forEach { it.joinWithin() }
    }

    // Review Focus 1.
    @Test fun aStageAttemptGetsInWhileBackgroundAttemptsQueue() = runBlocking {
        val gate = ConnectGate(total = 2)
        val release = CompletableDeferred<Unit>()
        var inside = 0
        var mostInside = 0
        val spares = List(4) {
            launch {
                gate.withPermit(spare) {
                    inside++
                    mostInside = maxOf(mostInside, inside)
                    release.await()
                    inside--
                }
            }
        }
        settle()
        val stageGotIn = withTimeoutOrNull(2_000L) { gate.withPermit(stage) { true } }
        assertEquals("a stage attempt must not wait behind queued spares", true, stageGotIn)
        assertEquals("background attempts hold at most one permit", 1, mostInside)
        release.complete(Unit)
        spares.forEach { it.joinWithin() }
        assertEquals("…and still run one at a time", 1, mostInside)
    }

    @Test fun twoStageAttemptsRunTogether() = runBlocking {
        val gate = ConnectGate(total = 2)
        val release = CompletableDeferred<Unit>()
        var inside = 0
        var mostInside = 0
        val jobs = List(2) {
            launch {
                gate.withPermit(stage) {
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
        jobs.forEach { it.joinWithin() }
    }

    @Test fun aCancelledWaiterGivesBackNothingItDidNotTake() = runBlocking {
        val gate = ConnectGate(total = 2)
        val release = CompletableDeferred<Unit>()
        val holder = launch { gate.withPermit(spare) { release.await() } }
        settle()
        val waiter = launch { gate.withPermit(spare) { fail("a cancelled waiter must never run") } }
        settle()
        waiter.cancel()
        release.complete(Unit)
        holder.joinWithin()
        waiter.joinWithin()
        assertEveryPermitIsBack(gate)
    }

    // Both permits busy: a background attempt waits for the last one. Cancelled there, it must give
    // back nothing — the background lane above all, which a stage-only check would never notice.
    @Test fun aBackgroundAttemptCancelledWhileTheStageHoldsEveryPermitLeaksNothing() = runBlocking {
        val gate = ConnectGate(total = 2)
        val release = CompletableDeferred<Unit>()
        val holders = List(2) { launch { gate.withPermit(stage) { release.await() } } }
        settle()
        val waiter = launch { gate.withPermit(spare) { fail("a cancelled waiter must never run") } }
        settle()
        waiter.cancel()
        waiter.joinWithin()
        release.complete(Unit)
        holders.forEach { it.joinWithin() }
        assertEveryPermitIsBack(gate)
    }

    @Test fun anAttemptThatThrowsGivesItsPermitBack() = runBlocking {
        val gate = ConnectGate(total = 2)
        for (isStage in listOf(spare, stage)) {
            try {
                gate.withPermit(isStage) { throw IllegalStateException("connect blew up") }
            } catch (_: IllegalStateException) {
            }
        }
        assertEveryPermitIsBack(gate)
    }

    // Final-wave MUST-FIX: the class is decided at ADMISSION. A pack promoted to the stage while its
    // connect sits queued behind spares — the low pack that just seized the stage — used to keep its
    // background place and wait ~10 s per spare ahead of it, showing DISCONNECTED meanwhile.
    @Test fun aPackPromotedWhileQueuedBehindSparesTakesTheStagePermitAtOnce() = runBlocking {
        val gate = ConnectGate(total = 2)
        val release = CompletableDeferred<Unit>()
        val busy = launch { gate.withPermit(spare) { release.await() } }   // one spare connecting
        settle()
        val queued = List(2) { launch { gate.withPermit(spare) { release.await() } } }   // ≥ 2 spares waiting
        settle()
        var promoted = false
        val got = CompletableDeferred<Unit>()
        val pack = launch { gate.withPermit({ promoted }) { got.complete(Unit) } }
        settle()
        assertFalse("still a spare: it waits its turn", got.isCompleted)
        promoted = true
        gate.stageChanged()
        assertEquals(
            "promoted: in on the stage permit while every spare is still connecting or queued",
            Unit, withTimeoutOrNull(2_000L) { got.await() },
        )
        release.complete(Unit)
        (queued + busy + pack).forEach { it.joinWithin() }
        assertEveryPermitIsBack(gate)
    }

    // The converse: a pack that leaves the stage before it is admitted waits in the background lane.
    @Test fun aPackDemotedWhileQueuedWaitsInTheBackgroundLane() = runBlocking {
        val gate = ConnectGate(total = 2)
        val releaseSpare = CompletableDeferred<Unit>()
        val releaseStage = CompletableDeferred<Unit>()
        val spareBusy = launch { gate.withPermit(spare) { releaseSpare.await() } }
        val stageBusy = launch { gate.withPermit(stage) { releaseStage.await() } }
        settle()
        var onStage = true
        val got = CompletableDeferred<Unit>()
        val pack = launch { gate.withPermit({ onStage }) { got.complete(Unit) } }
        settle()
        onStage = false
        gate.stageChanged()
        releaseStage.complete(Unit)                   // a permit frees, but it is the stage's
        stageBusy.joinWithin()
        settle()
        assertFalse("demoted: it must not take the permit kept for the stage", got.isCompleted)
        releaseSpare.complete(Unit)
        assertEquals(Unit, withTimeoutOrNull(2_000L) { got.await() })
        (listOf(spareBusy, pack)).forEach { it.joinWithin() }
        assertEveryPermitIsBack(gate)
    }

    // Final-wave MUST-FIX (hard rule: a disabled pack is never connected). The user disconnects a
    // spare while its attempt is queued behind the gate; when its turn comes it must not connect.
    @Test fun aPackDisconnectedWhileQueuedIsNeverConnected() = runBlocking {
        val gate = ConnectGate(total = 2)
        val release = CompletableDeferred<Unit>()
        val busy = launch { gate.withPermit(spare) { release.await() } }
        settle()
        var wanted = true
        var connects = 0
        val outcome = async { attemptConnect(gate, spare, { wanted }) { connects++; true } }
        settle()
        wanted = false                                // setDisabled lands while it waits
        release.complete(Unit)
        assertEquals(ConnectOutcome.SKIPPED, withTimeout(5_000L) { outcome.await() })
        assertEquals("nothing was opened", 0, connects)
        busy.joinWithin()
        assertEveryPermitIsBack(gate)
    }

    @Test fun aWantedPackConnectsAndAFailedConnectSaysSo() = runBlocking {
        val gate = ConnectGate(total = 2)
        assertEquals(ConnectOutcome.CONNECTED, attemptConnect(gate, stage, { true }) { true })
        assertEquals(ConnectOutcome.FAILED, attemptConnect(gate, spare, { true }) { false })
        assertEveryPermitIsBack(gate)
    }

    @Test fun aCancelledAttemptNeverConnects() = runBlocking {
        val gate = ConnectGate(total = 2)
        val release = CompletableDeferred<Unit>()
        val busy = launch { gate.withPermit(spare) { release.await() } }
        settle()
        var connects = 0
        val attempt = launch { attemptConnect(gate, spare, { true }) { connects++; true } }
        settle()
        attempt.cancel()                              // its pack dropped while it was queued
        release.complete(Unit)
        attempt.joinWithin()
        busy.joinWithin()
        assertEquals(0, connects)
        assertEveryPermitIsBack(gate)
    }

    @Test fun aGateTooSmallToReserveAStagePermitIsRejected() {
        try {
            ConnectGate(total = 1)
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }
}
