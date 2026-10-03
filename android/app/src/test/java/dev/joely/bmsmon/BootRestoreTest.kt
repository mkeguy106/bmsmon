package dev.joely.bmsmon

import android.content.Intent
import dev.joely.bmsmon.monitor.ACTION_BOOT_COMPLETED
import dev.joely.bmsmon.monitor.ACTION_MY_PACKAGE_REPLACED
import dev.joely.bmsmon.monitor.shouldRestoreMonitoring
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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

    // Review Focus 5
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
        val manifest = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml")
            .map(::File).first { it.isFile }.readText()
        assertTrue(manifest.contains("android.permission.RECEIVE_BOOT_COMPLETED"))
        assertTrue(manifest.contains(".monitor.BootRestoreReceiver"))
        assertTrue(manifest.contains("android.intent.action.BOOT_COMPLETED"))
        assertTrue(manifest.contains("android.intent.action.MY_PACKAGE_REPLACED"))
    }
}
