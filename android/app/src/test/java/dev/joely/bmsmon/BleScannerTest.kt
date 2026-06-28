package dev.joely.bmsmon

import dev.joely.bmsmon.ble.isCompatibleBmsName
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
}
