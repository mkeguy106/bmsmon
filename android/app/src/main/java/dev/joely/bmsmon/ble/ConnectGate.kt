package dev.joely.bmsmon.ble

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Admission to BLE connect attempts (BLE-16). At most [total] attempts run at once (the LE
 * initiator can't usefully pursue more), and background packs may hold at most [total] − 1 of
 * them, so one permit is always left for a stage pack. Before, absent spares — a burst of 10 s
 * attempts queued behind a resume kick — filled every permit, and a stage pack that dropped in that
 * window waited ~20 s for a turn while the stage read DISCONNECTED. Both stage packs can still
 * connect in parallel at launch.
 *
 * An attempt's class is decided when it is ADMITTED, from the current stage set ([withPermit]'s
 * `isStage`), not when it queues: a pack promoted to the stage while its connect waits — the low
 * pack that just seized the stage, a manual pin — takes the stage permit as soon as one is free
 * instead of waiting out every spare queued ahead of it, ~10 s each. [stageChanged] re-runs
 * admission when the stage moves.
 *
 * Waiters are admitted first come, first served within what their class allows: a stage waiter
 * takes any free permit, a background waiter one that leaves a permit for the stage. Background
 * attempts therefore run one at a time, and a present spare can wait behind every absent spare
 * queued ahead of it — up to (absent spares ahead) × the 10 s connect timeout after a kick that
 * launches them all at once. A waiter cancelled in the queue takes nothing with it.
 */
class ConnectGate(private val total: Int = 2) {
    init {
        require(total >= 2) { "reserving a stage permit needs total >= 2" }
    }

    private class Waiter(val isStage: () -> Boolean) {
        /** Completed when admitted: true for a background permit. */
        val admitted = CompletableDeferred<Boolean>()
        /** Set under the gate's lock when admitted, so a cancelled waiter knows what to give back. */
        var background: Boolean? = null
    }

    private val lock = Any()
    private val queue = ArrayList<Waiter>()   // FIFO
    private var running = 0             // admitted attempts, both classes
    private var runningBackground = 0   // of which background

    /**
     * Run [block] holding a permit. [isStage] is read under the gate's lock each time admission is
     * decided, so it must be cheap and must not block (a volatile set lookup).
     */
    suspend fun <T> withPermit(isStage: () -> Boolean, block: suspend () -> T): T {
        val w = Waiter(isStage)
        synchronized(lock) {
            queue.add(w)
            admit()
        }
        val background = try {
            w.admitted.await()
        } catch (e: CancellationException) {
            // Cancelled while queued — or just as it was admitted: give back what it was granted.
            synchronized(lock) {
                if (!queue.remove(w)) w.background?.let { release(it) }
            }
            throw e
        }
        try {
            return block()
        } finally {
            synchronized(lock) { release(background) }
        }
    }

    /** The stage set changed: a queued attempt may have changed class, so re-run admission. */
    fun stageChanged() = synchronized(lock) { admit() }

    /** Lock held. */
    private fun release(background: Boolean) {
        running--
        if (background) runningBackground--
        admit()
    }

    /** Lock held: admit every waiter that now fits, oldest first, each in its current class. */
    private fun admit() {
        val it = queue.iterator()
        while (running < total && it.hasNext()) {
            val w = it.next()
            val background = !w.isStage()
            if (background && runningBackground >= total - 1) continue   // keep a permit for the stage
            it.remove()
            running++
            if (background) runningBackground++
            w.background = background
            w.admitted.complete(background)
        }
    }
}

/** How one connect attempt ended ([attemptConnect]). */
enum class ConnectOutcome { CONNECTED, FAILED, SKIPPED }

/**
 * One connect attempt through [gate]: queue in the class [isStage] gives at admission, then —
 * holding the permit, immediately before [connect] opens the GATT link — re-check [isWanted]. A pack
 * the user disconnected (or removed) after the control loop planned this attempt, or while it sat
 * queued behind the gate for N × 10 s, is never connected: SKIPPED, nothing opened. A cancelled
 * attempt (its pack dropped, or monitoring stopped) never reaches [connect] either.
 */
suspend fun attemptConnect(
    gate: ConnectGate,
    isStage: () -> Boolean,
    isWanted: () -> Boolean,
    connect: suspend () -> Boolean,
): ConnectOutcome = gate.withPermit(isStage) {
    currentCoroutineContext().ensureActive()
    when {
        !isWanted() -> ConnectOutcome.SKIPPED
        connect() -> ConnectOutcome.CONNECTED
        else -> ConnectOutcome.FAILED
    }
}
