package dev.joely.bmsmon.ble

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Admission to BLE connect attempts (BLE-16). At most [total] attempts run at once (the LE
 * initiator can't usefully pursue more), and background packs may hold at most [total] − 1 of
 * them, so one permit is always left for a stage pack. Before, absent spares — a burst of 10 s
 * attempts queued behind a resume kick — filled every permit, and a stage pack that dropped in that
 * window waited ~20 s for a turn while the stage read DISCONNECTED. Both stage packs can still
 * connect in parallel at launch. Both semaphores are fair (FIFO); a waiter cancelled in the queue
 * takes nothing with it.
 */
class ConnectGate(total: Int = 2) {
    init {
        require(total >= 2) { "reserving a stage permit needs total >= 2" }
    }

    private val all = Semaphore(total)
    private val background = Semaphore(total - 1)

    suspend fun <T> withPermit(stage: Boolean, block: suspend () -> T): T =
        if (stage) all.withPermit { block() }
        else background.withPermit { all.withPermit { block() } }
}
