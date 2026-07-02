package dev.joely.bmsmon.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import dev.joely.bmsmon.ble.profile.ProfileRegistry

/** True only for advertised names that match a known battery profile (see ble/profile). */
fun isCompatibleBmsName(name: String?): Boolean = ProfileRegistry.profileFor(name) != null

/** Human meaning of a [ScanCallback] `SCAN_FAILED_*` code, for field-diagnosable logs (BLE-13). */
fun scanFailureName(errorCode: Int): String = when (errorCode) {
    ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "already started"
    ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "app registration failed"
    ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "internal error"
    ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "feature unsupported"
    ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> "out of hardware resources"
    ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> "scanning too frequently"
    else -> "unknown error"
}

/** A compatible device seen during a scan. */
data class DiscoveredDevice(val address: String, val name: String)

/**
 * Scans for compatible BMS devices only. SAFETY: surfaces nothing but names matching a known
 * [dev.joely.bmsmon.ble.profile.BatteryProfile], and never connects here — discovery only.
 */
@SuppressLint("MissingPermission")
class BleScanner(private val context: Context) {

    private var scanner: BluetoothLeScanner? = null
    private var callback: ScanCallback? = null

    fun start(onFound: (DiscoveredDevice) -> Unit) {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return
        val adapter = manager.adapter ?: return
        if (!adapter.isEnabled) return
        val s = adapter.bluetoothLeScanner ?: return
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: result.scanRecord?.deviceName
                if (isCompatibleBmsName(name)) {
                    onFound(DiscoveredDevice(result.device.address.uppercase(), name!!.trim()))
                }
            }
            override fun onScanFailed(errorCode: Int) {
                // Log-only (BLE-13): start(onFound) has no error path to surface this through, and
                // threading one in would ripple to every caller for a failure the user already
                // experiences as "no devices found". Warn level + human-readable meaning so a
                // field logcat pull explains WHY discovery went quiet.
                Log.w("BleScanner", "scan failed: ${scanFailureName(errorCode)} ($errorCode)")
            }
        }
        scanner = s
        callback = cb
        s.startScan(
            null,
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            cb,
        )
    }

    fun stop() {
        runCatching { callback?.let { scanner?.stopScan(it) } }
        callback = null
        scanner = null
    }
}
