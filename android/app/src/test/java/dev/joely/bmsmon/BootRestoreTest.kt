package dev.joely.bmsmon

import android.content.Intent
import dev.joely.bmsmon.monitor.ACTION_BOOT_COMPLETED
import dev.joely.bmsmon.monitor.ACTION_MY_PACKAGE_REPLACED
import dev.joely.bmsmon.monitor.shouldRestoreMonitoring
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * BLE-17: monitoring survived a process kill (START_STICKY) but not a reboot, an OTA or
 * `adb install -r` — nothing restarted it until someone opened the app. A headless FGS restore
 * from BOOT_COMPLETED / MY_PACKAGE_REPLACED needs no device-owner (connectedDevice isn't a type
 * Android 15 restricts from BOOT_COMPLETED).
 */
class BootRestoreTest {

    @Test fun bootOrUpdateRestoresWhenMonitoringWasOnAndBleIsGranted() {
        assertTrue(shouldRestoreMonitoring(ACTION_BOOT_COMPLETED, monitoringPersisted = true, blePermitted = true))
        assertTrue(shouldRestoreMonitoring(ACTION_MY_PACKAGE_REPLACED, monitoringPersisted = true, blePermitted = true))
    }

    @Test fun monitoringOffMeansNothingToRestore() {
        assertFalse(shouldRestoreMonitoring(ACTION_BOOT_COMPLETED, monitoringPersisted = false, blePermitted = true))
    }

    // A connectedDevice FGS needs the BLE grant (BLE-26).
    @Test fun revokedBlePermissionNeverStartsTheService() {
        assertFalse(shouldRestoreMonitoring(ACTION_BOOT_COMPLETED, monitoringPersisted = true, blePermitted = false))
    }

    @Test fun unreadableSettingsDoNotRestore() {
        assertFalse(shouldRestoreMonitoring(ACTION_BOOT_COMPLETED, monitoringPersisted = null, blePermitted = true))
    }

    @Test fun otherActionsAreIgnored() {
        // LOCKED_BOOT_COMPLETED: credential-encrypted storage (DataStore, Room) is still locked.
        assertFalse(shouldRestoreMonitoring("android.intent.action.LOCKED_BOOT_COMPLETED", true, true))
        assertFalse(shouldRestoreMonitoring("android.intent.action.PACKAGE_REPLACED", true, true))
        assertFalse(shouldRestoreMonitoring(null, true, true))
    }

    @Test fun actionLiteralsMatchThePlatform() {
        assertEquals(Intent.ACTION_BOOT_COMPLETED, ACTION_BOOT_COMPLETED)
        assertEquals(Intent.ACTION_MY_PACKAGE_REPLACED, ACTION_MY_PACKAGE_REPLACED)
    }

    @Test fun manifestDeclaresTheReceiverAndPermission() {
        val file = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml")
            .map(::File).first { it.isFile }
        val manifest = file.readText()
        assertTrue(manifest.contains("android.permission.RECEIVE_BOOT_COMPLETED"))
        // Credential-encrypted storage is still locked then; never registered, not even in a comment.
        assertFalse(manifest.contains("LOCKED_BOOT_COMPLETED"))

        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(file)
        val receivers = doc.getElementsByTagName("receiver").let { nl -> (0 until nl.length).map { nl.item(it) as Element } }
        val receiver = receivers.single { it.getAttributeNS(ANDROID_NS, "name") == ".monitor.BootRestoreReceiver" }
        // No other app may trigger a restore; the system's protected broadcasts still arrive.
        assertEquals("false", receiver.getAttributeNS(ANDROID_NS, "exported"))
        val actions = receiver.getElementsByTagName("action")
            .let { nl -> (0 until nl.length).map { (nl.item(it) as Element).getAttributeNS(ANDROID_NS, "name") } }
        assertEquals(
            listOf("android.intent.action.BOOT_COMPLETED", "android.intent.action.MY_PACKAGE_REPLACED"),
            actions.sorted(),
        )
    }

    // BLE-30: the restore trusts the persisted flag, so a user Stop must persist it or the next
    // reboot / update would silently undo it. A Recents swipe ("close the app") deliberately does
    // not. Pinned textually (EngineWiringTest pattern): exercising the service needs a device.
    @Test fun userStopPersistsMonitoringOffButASwipeDoesNot() {
        val src = listOf("src/main/java", "app/src/main/java")
            .map { File(it, "dev/joely/bmsmon/monitor/MonitoringService.kt") }
            .first { it.isFile }.readText()
        val stopBranch = src.substringAfter("if (intent?.action == ACTION_STOP) {", missingDelimiterValue = "").substringBefore("return START_NOT_STICKY")
        assertTrue(stopBranch.contains("engine.persistMonitoringOff()"))
        val swipe = src.substringAfter("override fun onTaskRemoved(", missingDelimiterValue = "").substringBefore("\n    }")
        assertTrue(swipe.contains("engine.stop()"))
        assertFalse(swipe.contains("persistMonitoringOff"))
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
