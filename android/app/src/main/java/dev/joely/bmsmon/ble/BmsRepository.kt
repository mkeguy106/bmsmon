package dev.joely.bmsmon.ble

import android.content.Context
import dev.joely.bmsmon.model.Telemetry
import kotlinx.coroutines.CoroutineScope

/** Manages the two BMS connections (one per pack). */
class BmsRepository(private val context: Context) {

    private val connections = mutableListOf<BmsConnection>()

    /**
     * @param targets list of (address, displayName) per pack, in display order.
     * @param onTelemetry (index, telemetry) as samples arrive.
     * @param onStatus (index, statusMessage) for connection state.
     */
    fun connect(
        scope: CoroutineScope,
        targets: List<Pair<String, String>>,
        onTelemetry: (Int, Telemetry) -> Unit,
        onStatus: (Int, String) -> Unit,
        intervalMs: Long = 1500,
    ) {
        disconnect()
        targets.forEachIndexed { index, (address, name) ->
            if (address.isNotBlank()) {
                BmsConnection(
                    context = context,
                    address = address.trim().uppercase(),
                    displayName = name,
                    intervalMs = intervalMs,
                    onTelemetry = { onTelemetry(index, it) },
                    onStatus = { onStatus(index, it) },
                ).also {
                    connections.add(it)
                    it.start(scope)
                }
            }
        }
    }

    fun disconnect() {
        connections.forEach { it.stop() }
        connections.clear()
    }
}
