package dev.joely.bmsmon

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The BLE safety boundary: the ONLY characteristic write in the app is the 0x13 status frame in
 * BleSession.writeStatus (its API-33 and legacy branches), and the only descriptor write is the
 * FFE1 notification enable (same two branches). A new write site anywhere fails this test.
 *
 * "Anywhere" means every shipped source set (main, debug — the phone runs the debug APK — and any
 * other) and both Kotlin and Java sources; only test source sets are exempt. Occurrences are
 * counted, not lines, so two calls on one line, a call with a space before the parenthesis and a
 * `::writeCharacteristic` reference all count. A mention in a comment also counts: that fails
 * safe, so reword the comment rather than loosening the scan.
 * Gradle runs unit tests from the module directory.
 */
class SingleWriteSiteTest {

    private val writeToken = Regex("""\bwrite(Characteristic|Descriptor)\b""")

    /** source file (path relative to src/) -> its occurrences of each write token. */
    private fun scan(): Map<String, List<String>> {
        val root = listOf("src", "app/src").map(::File).first { File(it, "main").isDirectory }
        return root.walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "java") }
            .filter { f ->
                // First path component under src/ is the source set; test, testDebug and
                // androidTest are not shipped.
                val sourceSet = f.relativeTo(root).path.substringBefore(File.separatorChar)
                !(sourceSet.startsWith("test") || sourceSet.startsWith("androidTest"))
            }
            .associate { f ->
                f.relativeTo(root).path to writeToken.findAll(f.readText()).map { it.groupValues[1] }.toList()
            }
    }

    private fun sites(kind: String): Map<String, Int> =
        scan().mapValues { (_, tokens) -> tokens.count { it == kind } }.filterValues { it > 0 }

    @Test fun scanCoversEveryShippedSourceSet() {
        val files = scan().keys
        assertTrue("main sources scanned: $files", files.any { it.startsWith("main/") })
        assertTrue("debug sources scanned: $files", files.any { it.startsWith("debug/") })
        assertTrue("test sources must not be scanned: $files", files.none { it.startsWith("test/") })
    }

    @Test fun statusFrameIsTheOnlyCharacteristicWrite() {
        val h = sites("Characteristic")
        assertEquals("writeCharacteristic sites: $h", 2, h.values.sum())
        assertEquals("$h", setOf("main/java/dev/joely/bmsmon/ble/BleSession.kt"), h.keys)
    }

    @Test fun notificationEnableIsTheOnlyDescriptorWrite() {
        val h = sites("Descriptor")
        assertEquals("writeDescriptor sites: $h", 2, h.values.sum())
        assertEquals("$h", setOf("main/java/dev/joely/bmsmon/ble/BleSession.kt"), h.keys)
    }
}
