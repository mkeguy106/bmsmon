package dev.joely.bmsmon.ble.profile

import java.util.UUID

/** Field offsets (bytes, little-endian, from the realigned status frame) + plausibility bounds. */
data class TelemetryLayout(
    val minBytes: Int = 100,
    val voltageOff: Int = 12,
    val currentOff: Int = 48,
    val cellTempOff: Int = 52,
    val mosfetTempOff: Int = 54,
    val remainingAhOff: Int = 62,
    val fullAhOff: Int = 64,
    val protOff: Int = 76,
    val stateOff: Int = 88,
    val socOff: Int = 90,
    val sohOff: Int = 92,
    val cyclesOff: Int = 96,
    val cellsBaseOff: Int = 16,
    val cellCount: Int = 16,
    val socMin: Float = 0f,
    val socMax: Float = 100f,
    val voltMin: Float = 4f,
    val voltMax: Float = 70f,
)

/** Exponential backoff schedule for connect-retry. */
data class BackoffSpec(val baseMs: Long, val factor: Int, val capMs: Long)

/**
 * Everything brand/firmware-specific for one battery family. Selected by [namePrefixes]. SAFETY:
 * only prebuilt read-command frames are exposed ([statusFrame], [firmwareFrame]) — there is no
 * open opcode API, so no destructive command can ever be emitted.
 */
data class BatteryProfile(
    val id: String,
    val displayName: String,
    val namePrefixes: List<String>,
    val validatedFirmware: String,
    val serviceUuid: UUID,
    val notifyUuid: UUID,
    val writeUuid: UUID,
    val cccdUuid: UUID,
    val writeWithResponse: Boolean,
    val statusFrame: ByteArray,
    val firmwareFrame: ByteArray,
    val responseHeader: ByteArray,
    val layout: TelemetryLayout,
    val stagePollMs: Long,
    val slowPollMs: Long,
    val maxHeldConnections: Int,
    val connectTimeoutMs: Long,
    val failThreshold: Int,
    val backoff: BackoffSpec,
) {
    fun matches(name: String?): Boolean {
        val n = name?.trim().orEmpty()
        return n.isNotEmpty() && namePrefixes.any { n.startsWith(it, ignoreCase = true) }
    }
}
