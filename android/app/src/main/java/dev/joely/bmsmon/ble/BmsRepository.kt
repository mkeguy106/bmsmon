package dev.joely.bmsmon.ble

import android.content.Context
import dev.joely.bmsmon.model.BmsTarget
import dev.joely.bmsmon.model.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Semaphore

/**
 * Manages BLE connections to the whole fleet — every battery across all bases that the
 * phone can reach. Each connection polls independently; the poll interval per battery is
 * adjusted at runtime (stage batteries fast, the rest slow). Unreachable batteries keep
 * retrying in the background and report `reachable = false`.
 */
class BmsRepository(private val context: Context) {

    private val connections = mutableMapOf<String, BmsConnection>()

    // At most 2 batteries may be in the "connecting" phase at once (the rest wait their turn).
    private val connectGate = Semaphore(2)

    /**
     * Start monitoring all [targets].
     * @param onTelemetry (address, telemetry) as samples arrive.
     * @param onReachable (address, connected?) as connection state changes.
     */
    fun start(
        scope: CoroutineScope,
        targets: List<BmsTarget>,
        slowIntervalMs: Long,
        onTelemetry: (String, Telemetry) -> Unit,
        onReachable: (String, Boolean) -> Unit,
        staggerMs: Long = 250,
    ) {
        stop()
        targets.forEachIndexed { index, t ->
            val address = t.address.trim().uppercase()
            BmsConnection(
                context = context,
                address = address,
                displayName = t.name,
                initialIntervalMs = slowIntervalMs,
                startDelayMs = index * staggerMs,  // small fan-out; the gate does the real serialization
                connectGate = connectGate,
                onTelemetry = { onTelemetry(address, it) },
                onStatus = { msg -> onReachable(address, msg == "connected") },
            ).also {
                connections[address] = it
                it.start(scope)
            }
        }
    }

    /** Set the poll interval for one battery (e.g. promote a stage battery to fast polling). */
    fun setInterval(address: String, ms: Long) {
        connections[address.trim().uppercase()]?.setInterval(ms)
    }

    fun stop() {
        connections.values.forEach { it.stop() }
        connections.clear()
    }
}
