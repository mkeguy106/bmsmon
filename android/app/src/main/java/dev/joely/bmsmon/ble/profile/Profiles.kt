package dev.joely.bmsmon.ble.profile

import dev.joely.bmsmon.ble.BmsProtocol
import dev.joely.bmsmon.model.TempEnvelope
import dev.joely.bmsmon.model.TempThresholds
import java.util.UUID

/** The one validated profile today: Redodo/LiTime/PowerQueen/Starry-Sea on the Beken BK-BLE-1.0. */
val RedodoBekenProfile = BatteryProfile(
    id = "redodo-beken-bk-ble-1.0",
    displayName = "Redodo / LiTime / PowerQueen (Beken BK-BLE-1.0)",
    namePrefixes = listOf(
        "R-12", "R-24", "RO-12", "RO-24",
        "L-12", "L-24", "L-51", "LT-",
        "P-12", "P-24", "PQ-12", "PQ-24",
        "SS-", "S-",
    ),
    validatedFirmware = "BMS app FW V1.4 (model T12100); BLE module BK-BLE-1.0 FW 6.1.2",
    serviceUuid = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb"),
    notifyUuid = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb"),
    writeUuid = UUID.fromString("0000ffe2-0000-1000-8000-00805f9b34fb"),
    cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
    writeWithResponse = true,  // official Redodo app uses ATT Write Request (with response)
    statusFrame = BmsProtocol.frame(BmsProtocol.ReadCommand.STATUS),
    firmwareFrame = BmsProtocol.frame(BmsProtocol.ReadCommand.FW_VERSION),
    responseHeader = byteArrayOf(0x01, 0x93.toByte(), 0x55, 0xAA.toByte()),
    layout = TelemetryLayout(),
    stagePollMs = 1500L,
    slowPollMs = 10_000L,
    maxHeldConnections = 8,
    connectTimeoutMs = 10_000L,
    failThreshold = 3,
    // Tolerate up to 4 missed status frames (retry in place); only drop the link on the 5th. A single
    // slow/missed notification used to tear the link down and reconnect. A genuinely dead link still
    // drops immediately via the STATE_DISCONNECTED error path, so this only governs the alive-but-silent
    // case — it lets a flaky pack (esp. slow-polled background ones like 2016-B) ride through a short
    // RF patch of a few consecutive misses instead of logging a disconnect + reconnect.
    maxPollMisses = 5,
    backoff = BackoffSpec(baseMs = 5_000L, factor = 2, capMs = 120_000L),
    // Verified Redodo LiFePO4 envelope: BMS cutoffs -20/60°C, charge lock 0°C (resume 5°C) / hot 50°C,
    // factory alert defaults cold caution 5 / hot 45 / cold crit -12 / hot crit 53 (fire before cutoff).
    tempEnvelope = TempEnvelope(
        coldCutoffC = -20, hotCutoffC = 60,
        chargeLockColdC = 0, chargeResumeColdC = 5, chargeLockHotC = 50,
        defaults = TempThresholds(coldCautionC = 5, hotCautionC = 45, coldCritC = -12, hotCritC = 53),
    ),
)

/** Selects a profile from a device's advertised name (by prefix). */
object ProfileRegistry {
    val all: List<BatteryProfile> = listOf(RedodoBekenProfile)
    fun profileFor(name: String?): BatteryProfile? = all.firstOrNull { it.matches(name) }
}
