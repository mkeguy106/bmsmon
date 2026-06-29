package dev.joely.bmsmon

import dev.joely.bmsmon.ble.BmsProtocol
import dev.joely.bmsmon.model.BatteryState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BmsProtocolTest {

    private fun hex(s: String): ByteArray =
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** A real 0x13 response captured from 2024-BATTERY-B (R-12100BNNA70-A02402). */
    private val realStatus =
        "000065019355aa007c3300000c340000030d030d030d030d00000000000000000000000000000000000000000000000000000000170018000000000000001027102700000000000000000000000000000000000000000000000063006400000000000000000000008b"

    @Test
    fun statusFrameHasCorrectChecksum() {
        // 00 00 04 01 13 55 AA -> sum = 0x117, &0xFF = 0x17
        val f = BmsProtocol.frame(BmsProtocol.ReadCommand.STATUS)
        assertArrayEquals(
            byteArrayOf(0x00, 0x00, 0x04, 0x01, 0x13, 0x55.toByte(), 0xAA.toByte(), 0x17),
            f,
        )
    }

    @Test
    fun allFramesChecksumToByteSum() {
        for (cmd in BmsProtocol.ReadCommand.values()) {
            val f = BmsProtocol.frame(cmd)
            var sum = 0
            for (i in 0 until 7) sum += f[i].toInt() and 0xFF
            assertEquals("checksum for $cmd", (sum and 0xFF).toByte(), f[7])
        }
    }

    @Test
    fun parsesRealStatusCapture() {
        val t = BmsProtocol.parseTelemetry(hex(realStatus), "test")!!
        assertEquals(13.324f, t.voltage, 0.001f)
        assertEquals(0f, t.current, 0.001f)
        assertEquals(99f, t.soc, 0.0f)
        assertEquals(100, t.soh)
        assertEquals(0, t.cycles)
        assertEquals(23f, t.temp, 0.0f)
        assertEquals(24, t.mosfetTemp)
        assertEquals(100f, t.capacityAh, 0.01f)
        assertEquals(100f, t.fullChargeAh, 0.01f)
        assertEquals(BatteryState.Idle, t.state)
        assertEquals(4, t.cells.size)
        assertEquals(3.331f, t.cellV, 0.001f)
        assertTrue(t.protections.isEmpty())
        assertEquals(0f, t.powerW, 0.001f) // 13.324 * |0|
    }

    @Test
    fun shortPacketReturnsNull() {
        assertEquals(null, BmsProtocol.parseTelemetry(ByteArray(40), "x"))
    }

    /**
     * Stale BLE notification fragments can prepend bytes so the real frame no longer starts at
     * offset 0. The parser must realign to the `01 93 55 AA` header and decode the true values —
     * not read garbage at the fixed offsets. (This is what produced the field bug: soc=0, 37.6 V.)
     */
    @Test
    fun realignsPrefixedStatusFrame() {
        val prefixed = hex("aabbccddee" + realStatus)  // 5 junk bytes, no false header
        val t = BmsProtocol.parseTelemetry(prefixed, "test")!!
        assertEquals(13.324f, t.voltage, 0.001f)
        assertEquals(99f, t.soc, 0.0f)
    }

    /** A long-enough buffer with no status header at all is junk, not a 0% reading -> null. */
    @Test
    fun rejectsFrameWithoutHeader() {
        val junk = ByteArray(110) { 0xAB.toByte() }
        assertEquals(null, BmsProtocol.parseTelemetry(junk, "x"))
    }

    /** A valid header but physically impossible SOC (>100%) is a corrupt frame -> null. */
    @Test
    fun rejectsImplausibleSoc() {
        val bad = hex(realStatus)
        bad[90] = 0xC8.toByte()  // SOC = 200%
        bad[91] = 0x00
        assertEquals(null, BmsProtocol.parseTelemetry(bad, "x"))
    }

    /** A valid header but impossible pack voltage (0 V) is a corrupt frame -> null, not a reading. */
    @Test
    fun rejectsImplausibleVoltage() {
        val bad = hex(realStatus)
        bad[12] = 0x00  // total voltage = 0.000 V
        bad[13] = 0x00
        assertEquals(null, BmsProtocol.parseTelemetry(bad, "x"))
    }

    /** Safety guard: the command whitelist must never contain a destructive opcode. */
    @Test
    fun commandWhitelistIsReadOnly() {
        val destructive = setOf(0x0A, 0x0B, 0x0C, 0x0D, 0x60) // MOSFET toggles + shutdown
        val allowed = setOf(0x13, 0x10, 0x15, 0x16, 0x41, 0x43)
        for (cmd in BmsProtocol.ReadCommand.values()) {
            assertFalse("destructive opcode ${cmd.opcode} in whitelist", cmd.opcode in destructive)
            assertTrue("opcode ${cmd.opcode} not in known-safe set", cmd.opcode in allowed)
        }
    }

    @Test
    fun parsesWithExplicitDefaultLayout() {
        // Same real frame the existing test uses, but passing the profile layout/header explicitly.
        val raw = hex(realStatus)
        val a = BmsProtocol.parseTelemetry(raw, "x")
        val b = BmsProtocol.parseTelemetry(
            raw, "x",
            dev.joely.bmsmon.ble.profile.RedodoBekenProfile.layout,
            dev.joely.bmsmon.ble.profile.RedodoBekenProfile.responseHeader,
        )
        assertEquals(a?.soc, b?.soc)
        assertEquals(a?.voltage, b?.voltage)
    }
}
