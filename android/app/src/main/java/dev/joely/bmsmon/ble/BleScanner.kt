package dev.joely.bmsmon.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log

/** BLE-advertised-name prefixes for compatible BMS modules (see bmsmon/CLAUDE.md). */
private val KNOWN_PREFIXES = listOf(
    "R-12", "R-24", "RO-12", "RO-24",
    "L-12", "L-24", "L-51", "LT-",
    "P-12", "P-24", "PQ-12", "PQ-24",
    "SS-", "S-",
)

/** True only for advertised names that match a known compatible BMS prefix. */
fun isCompatibleBmsName(name: String?): Boolean {
    val n = name?.trim().orEmpty()
    if (n.isEmpty()) return false
    return KNOWN_PREFIXES.any { n.startsWith(it, ignoreCase = true) }
}

/** A compatible device seen during a scan. */
data class DiscoveredDevice(val address: String, val name: String)

/**
 * Scans for compatible BMS devices only. SAFETY: surfaces nothing but [KNOWN_PREFIXES] matches,
 * and never connects here — discovery only.
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
                Log.d("BleScanner", "scan failed: $errorCode")
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
