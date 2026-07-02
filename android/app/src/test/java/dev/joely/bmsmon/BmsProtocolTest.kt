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

    // --- write-time safety gate (BLE-7): BleSession refuses any frame this predicate rejects ---

    @Test
    fun safeStatusFrameAcceptsTheRealStatusQuery() {
        assertTrue(BmsProtocol.isSafeStatusFrame(BmsProtocol.frame(BmsProtocol.ReadCommand.STATUS)))
        assertTrue(BmsProtocol.isSafeStatusFrame(BmsProtocol.STATUS_FRAME))
        // The exact bytes from the protocol spec, built by hand.
        assertTrue(BmsProtocol.isSafeStatusFrame(
            byteArrayOf(0x00, 0x00, 0x04, 0x01, 0x13, 0x55.toByte(), 0xAA.toByte(), 0x17)))
    }

    @Test
    fun safeStatusFrameRejectsShutdownOpcode() {
        // 0x60 = BMS shutdown (the battery-bricking command) — even with a VALID checksum this
        // must never pass the gate. 00 00 04 01 60 55 AA -> sum & 0xFF = 0x64.
        val shutdown = byteArrayOf(0x00, 0x00, 0x04, 0x01, 0x60, 0x55.toByte(), 0xAA.toByte(), 0x64)
        assertFalse(BmsProtocol.isSafeStatusFrame(shutdown))
        // And every other destructive/unknown opcode mutation of the status frame.
        for (opcode in intArrayOf(0x0A, 0x0B, 0x0C, 0x0D, 0x30, 0x80, 0xFF)) {
            val f = BmsProtocol.frame(BmsProtocol.ReadCommand.STATUS)
            f[4] = opcode.toByte()
            var sum = 0
            for (i in 0 until 7) sum += f[i].toInt() and 0xFF
            f[7] = (sum and 0xFF).toByte()  // re-checksum so only the opcode check can reject it
            assertFalse("opcode 0x%02X must be refused".format(opcode), BmsProtocol.isSafeStatusFrame(f))
        }
    }

    @Test
    fun safeStatusFrameRejectsWrongChecksum() {
        val f = BmsProtocol.frame(BmsProtocol.ReadCommand.STATUS)
        f[7] = (f[7] + 1).toByte()
        assertFalse(BmsProtocol.isSafeStatusFrame(f))
        // A corrupted non-opcode byte also fails (checksum and byte-identity both break).
        val g = BmsProtocol.frame(BmsProtocol.ReadCommand.STATUS)
        g[2] = 0x05
        assertFalse(BmsProtocol.isSafeStatusFrame(g))
    }

    @Test
    fun safeStatusFrameRejectsWrongLength() {
        assertFalse(BmsProtocol.isSafeStatusFrame(ByteArray(0)))
        assertFalse(BmsProtocol.isSafeStatusFrame(BmsProtocol.frame(BmsProtocol.ReadCommand.STATUS).copyOfRange(0, 7)))
        assertFalse(BmsProtocol.isSafeStatusFrame(BmsProtocol.frame(BmsProtocol.ReadCommand.STATUS) + 0x00))
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

    // --- length-driven frame completion (BLE-1): the reassembly buffer must complete only when
    // the realigned frame's declared length (payload_len at frame offset 2, total = len + 4) has
    // fully arrived — never on a fixed byte count like the old >= 80. ---

    @Test
    fun completeRealFrameReportsExpectedLenAndCompletes() {
        val raw = hex(realStatus)
        assertEquals(105, raw.size)                                     // real capture is 105 bytes
        assertEquals(105, BmsProtocol.expectedStatusResponseLen(raw))   // 0x65 payload + 4
        assertTrue(BmsProtocol.statusFrameComplete(raw))
        // And what completes must parse: completion and the parser agree on alignment.
        assertTrue(BmsProtocol.parseTelemetry(raw, "x") != null)
    }

    /** The old bug: a stack chunking at 20-byte ATT fragments lands exactly on 80 — that is NOT
     *  a complete frame (parser needs the full 105) and must not complete the poll. */
    @Test
    fun exactly80ByteTruncationDoesNotComplete() {
        val truncated = hex(realStatus).copyOfRange(0, 80)
        assertFalse(BmsProtocol.statusFrameComplete(truncated))
        // Nor at any other partial length below the declared total.
        assertFalse(BmsProtocol.statusFrameComplete(hex(realStatus).copyOfRange(0, 100)))
        assertFalse(BmsProtocol.statusFrameComplete(hex(realStatus).copyOfRange(0, 104)))
    }

    /** Reassembly in 20-byte ATT fragments: incomplete at every step until all 105 bytes landed. */
    @Test
    fun fragmentedArrivalCompletesOnlyAtFullLength() {
        val raw = hex(realStatus)
        val buffer = ArrayList<Byte>()
        for (chunkStart in raw.indices step 20) {
            val chunk = raw.copyOfRange(chunkStart, minOf(chunkStart + 20, raw.size))
            chunk.forEach { buffer.add(it) }
            val complete = BmsProtocol.statusFrameComplete(buffer.toByteArray())
            assertEquals("at ${buffer.size} bytes", buffer.size >= raw.size, complete)
        }
    }

    /** Stale garbage prepended (same case the parser realigns for): the expected total shifts by
     *  the prefix, so prefix + 80 real bytes must not complete; the full frame must. */
    @Test
    fun garbagePrefixedFrameCompletesAtShiftedLength() {
        val full = hex("aabbccddee" + realStatus)  // 5 junk bytes, no false header
        assertEquals(110, BmsProtocol.expectedStatusResponseLen(full))
        assertFalse(BmsProtocol.statusFrameComplete(full.copyOfRange(0, 85)))   // prefix + 80
        assertFalse(BmsProtocol.statusFrameComplete(full.copyOfRange(0, 105)))  // prefix ate the tail
        assertTrue(BmsProtocol.statusFrameComplete(full))
        assertTrue(BmsProtocol.parseTelemetry(full, "x") != null)
    }

    /** No status header visible → no expected length, never complete early (poll timeout covers
     *  it), regardless of how many garbage bytes piled up below the safety cap. */
    @Test
    fun garbageOnlyBufferNeverCompletesEarly() {
        assertFalse(BmsProtocol.statusFrameComplete(ByteArray(0)))
        assertEquals(null, BmsProtocol.expectedStatusResponseLen(ByteArray(0)))
        assertFalse(BmsProtocol.statusFrameComplete(ByteArray(40) { 0xAB.toByte() }))
        assertFalse(BmsProtocol.statusFrameComplete(ByteArray(200) { 0xAB.toByte() }))
        assertEquals(null, BmsProtocol.expectedStatusResponseLen(ByteArray(200) { 0xAB.toByte() }))
    }

    /** Safety cap: a runaway buffer completes (and is then rejected by the parser) instead of
     *  growing unbounded until the poll timeout. */
    @Test
    fun runawayBufferCompletesAtSafetyCap() {
        val runaway = ByteArray(BmsProtocol.STATUS_RESPONSE_MAX_BYTES) { 0xAB.toByte() }
        assertTrue(BmsProtocol.statusFrameComplete(runaway))
        assertEquals(null, BmsProtocol.parseTelemetry(runaway, "x"))  // still junk, not a reading
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
