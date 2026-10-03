package dev.joely.bmsmon

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The BLE safety boundary: the ONLY characteristic write in the app is the 0x13 status frame in
 * BleSession.writeStatus (its API-33 and legacy branches), and the only descriptor write is the
 * FFE1 notification enable (same two branches). A new write site anywhere fails this test.
 * Gradle runs unit tests from the module directory.
 */
class SingleWriteSiteTest {

    private fun hits(token: String): List<String> {
        val root = listOf("src/main/java", "app/src/main/java").map(::File).first { it.isDirectory }
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .flatMap { f -> f.readLines().mapIndexedNotNull { i, line -> if (token in line) "${f.name}:${i + 1}" else null } }
            .toList()
    }

    @Test fun statusFrameIsTheOnlyCharacteristicWrite() {
        val h = hits("writeCharacteristic(")
        assertEquals("writeCharacteristic call sites: $h", 2, h.size)
        assertTrue("$h", h.all { it.startsWith("BleSession.kt:") })
    }

    @Test fun notificationEnableIsTheOnlyDescriptorWrite() {
        val h = hits("writeDescriptor(")
        assertEquals("writeDescriptor call sites: $h", 2, h.size)
        assertTrue("$h", h.all { it.startsWith("BleSession.kt:") })
    }
}
