package dev.joely.bmsmon.ble

import dev.joely.bmsmon.model.BatteryState
import dev.joely.bmsmon.model.Telemetry
import java.util.UUID
import kotlin.math.abs

/**
 * Beken BK-BLE-1.0 BMS protocol — pure logic, no Android dependencies (unit-testable).
 *
 * SAFETY: this object frames ONLY the read-only commands in [ReadCommand]. No destructive
 * opcode exists anywhere in this codebase — not the charge/discharge MOSFET toggles
 * (0x0A/0x0B/0x0C/0x0D) nor shutdown (0x60). The UI has no surface that can write anything
 * but a [ReadCommand]. See bmsmon/CLAUDE.md "Safe vs Destructive Commands".
 */
object BmsProtocol {

    val SERVICE_UUID: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
    val FFE1_NOTIFY: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
    val FFE2_WRITE: UUID = UUID.fromString("0000ffe2-0000-1000-8000-00805f9b34fb")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** The complete set of commands this app may ever send. All are read-only/confirmed safe. */
    enum class ReadCommand(val opcode: Int) {
        STATUS(0x13),       // main telemetry
        SERIAL(0x10),
        CONFIG(0x15),
        FW_VERSION(0x16),
        SOH_SOC(0x41),
        CAPACITY(0x43),
    }

    /** Build the 8-byte command frame: 00 00 04 01 CMD 55 AA CHECKSUM (checksum = sum & 0xFF). */
    fun frame(cmd: ReadCommand): ByteArray {
        val b = byteArrayOf(0x00, 0x00, 0x04, 0x01, cmd.opcode.toByte(), 0x55.toByte(), 0xAA.toByte(), 0x00)
        var sum = 0
        for (i in 0 until 7) sum += b[i].toInt() and 0xFF
        b[7] = (sum and 0xFF).toByte()
        return b
    }

    val STATUS_FRAME: ByteArray get() = frame(ReadCommand.STATUS)

    // --- little-endian readers ---
    private fun u16(d: ByteArray, o: Int) = (d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8)
    private fun i16(d: ByteArray, o: Int): Int { val v = u16(d, o); return if (v >= 0x8000) v - 0x10000 else v }
    private fun u32(d: ByteArray, o: Int): Long =
        (d[o].toLong() and 0xFF) or
            ((d[o + 1].toLong() and 0xFF) shl 8) or
            ((d[o + 2].toLong() and 0xFF) shl 16) or
            ((d[o + 3].toLong() and 0xFF) shl 24)
    private fun i32(d: ByteArray, o: Int): Int = u32(d, o).toInt()

    val PROTECTION_FLAGS: Map<Int, String> = linkedMapOf(
        0x00000004 to "Over Charge",
        0x00000020 to "Over-discharge",
        0x00000040 to "Charge Over Current",
        0x00000080 to "Discharge Over Current",
        0x00000100 to "High Temp (charge)",
        0x00000200 to "High Temp (discharge)",
        0x00000400 to "Low Temp (charge)",
        0x00000800 to "Low Temp (discharge)",
        0x00004000 to "Short Circuit",
    )

    /** Frame offset of the status header (00 00 LEN 01 93 55 AA), or null if none. 0 = aligned. */
    fun statusFrameOffset(raw: ByteArray): Int? = statusFrameStart(raw)

    /**
     * Offset of the status frame start (the leading `00` of `00 00 LEN 01 93 55 AA`), or null if
     * no status-response header is present. The marker `01 93 55 AA` (response cmd = 0x13|0x80,
     * then the 55 AA tag) sits at frame offset 3, so the frame begins 3 bytes before it. We scan
     * because stale notification fragments can prepend bytes; the first match is the real header.
     */
    private fun statusFrameStart(d: ByteArray): Int? {
        var i = 3
        while (i <= d.size - 4) {
            if ((d[i].toInt() and 0xFF) == 0x01 &&
                (d[i + 1].toInt() and 0xFF) == 0x93 &&
                (d[i + 2].toInt() and 0xFF) == 0x55 &&
                (d[i + 3].toInt() and 0xFF) == 0xAA
            ) return i - 3
            i++
        }
        return null
    }

    /**
     * Parse a 0x13 status response (~105 bytes, little-endian) into [Telemetry], or null if the
     * frame is too short, isn't a status response, or is implausible.
     *
     * IMPORTANT: BLE notification fragments can leave stale bytes in front of the real frame, so
     * the status payload doesn't always start at offset 0. Parsing those misaligned bytes at the
     * fixed offsets below produces garbage (a real capture seen in the field: soc=0, voltage=37.6 V
     * — the header marker `01 93` shifted into the voltage field). Such garbage was being shown on
     * the stage as a genuine 0% reading and tripping the critical low-battery alarm. We therefore
     * (1) realign to the `00 00 LEN 01 93 55 AA` response header before reading any field, and
     * (2) reject readings outside physically possible bounds. Anything we can't trust returns null,
     * which the engine treats as a missed poll (keep last-known / mark unreachable), never a 0%.
     */
    fun parseTelemetry(raw: ByteArray, name: String): Telemetry? {
        val base = statusFrameStart(raw) ?: return null
        val d = if (base == 0) raw else raw.copyOfRange(base, raw.size)
        if (d.size < 100) return null
        val totalV = u16(d, 12) / 1000f
        val current = i32(d, 48) / 1000f
        val cellTemp = i16(d, 52)
        val mosfetTemp = i16(d, 54)
        val remainingAh = u16(d, 62) / 100f
        val fullAh = u32(d, 64) / 100f
        val stateRaw = u16(d, 88)
        val soc = u16(d, 90).toFloat()
        val soh = u32(d, 92).toInt()
        val cycles = u32(d, 96).toInt()
        // Sanity gate: a real pack is 0–100% SOC and (12/24/48 V LiFePO4) 4–70 V. Out-of-range
        // means a corrupt/misframed packet slipped through — drop it rather than display it.
        if (soc < 0f || soc > 100f) return null
        if (totalV < 4f || totalV > 70f) return null
        val cells = (0 until 16).map { u16(d, 16 + it * 2) / 1000f }.filter { it > 0.1f }
        val maxCell = cells.maxOrNull() ?: (totalV / 4f)
        val state = when (stateRaw) {
            1 -> BatteryState.Charging
            2 -> BatteryState.Discharging
            4 -> BatteryState.Disabled
            else -> BatteryState.Idle
        }
        val prot = u32(d, 76).toInt()
        val protections = PROTECTION_FLAGS.filter { (prot and it.key) != 0 }.values.toList()

        return Telemetry(
            name = name,
            soc = soc,
            powerW = totalV * abs(current),
            current = current,
            voltage = totalV,
            capacityAh = remainingAh,
            cellV = maxCell,
            temp = cellTemp.toFloat(),
            soh = soh,
            cycles = cycles,
            state = state,
            fullChargeAh = fullAh,
            mosfetTemp = mosfetTemp,
            cells = cells,
            protections = protections,
        )
    }
}
