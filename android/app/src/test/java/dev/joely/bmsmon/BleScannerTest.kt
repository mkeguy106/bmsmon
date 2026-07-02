package dev.joely.bmsmon

import dev.joely.bmsmon.ble.isCompatibleBmsName
import dev.joely.bmsmon.ble.scanFailureName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleScannerTest {
    @Test fun acceptsKnownRedodoAndFamilyPrefixes() {
        assertTrue(isCompatibleBmsName("R-12100BNNA70-A02402"))
        assertTrue(isCompatibleBmsName("RO-24100"))
        assertTrue(isCompatibleBmsName("L-51100"))
        assertTrue(isCompatibleBmsName("LT-12100"))
        assertTrue(isCompatibleBmsName("PQ-12100"))
        assertTrue(isCompatibleBmsName("SS-12100"))
        assertTrue(isCompatibleBmsName("S-12100"))
    }

    @Test fun rejectsUnknownOrNull() {
        assertFalse(isCompatibleBmsName(null))
        assertFalse(isCompatibleBmsName(""))
        assertFalse(isCompatibleBmsName("MyHeadphones"))
        assertFalse(isCompatibleBmsName("X-12100"))
    }

    /** BLE-13: scan-failure codes log with their human meaning (values per ScanCallback). */
    @Test fun scanFailureCodesMapToHumanMeaning() {
        assertEquals("already started", scanFailureName(1))
        assertEquals("app registration failed", scanFailureName(2))
        assertEquals("internal error", scanFailureName(3))
        assertEquals("feature unsupported", scanFailureName(4))
        assertEquals("out of hardware resources", scanFailureName(5))
        assertEquals("scanning too frequently", scanFailureName(6))
        assertEquals("unknown error", scanFailureName(42))
    }
}
