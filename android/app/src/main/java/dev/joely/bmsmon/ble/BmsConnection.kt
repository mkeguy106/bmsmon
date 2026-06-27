package dev.joely.bmsmon.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import dev.joely.bmsmon.model.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A persistent BLE connection to one BMS that polls read-only status on an interval.
 *
 * It connects directly by MAC, subscribes to FFE1 notifications, writes the 0x13 STATUS
 * frame to FFE2, collects response fragments until >= 80 bytes, parses, and emits telemetry.
 * Reconnects automatically on drop. The ONLY thing ever written is [BmsProtocol.STATUS_FRAME].
 */
@SuppressLint("MissingPermission")
class BmsConnection(
    private val context: Context,
    private val address: String,
    private val displayName: String,
    initialIntervalMs: Long,
    private val startDelayMs: Long,
    private val connectGate: Semaphore,
    private val onTelemetry: (Telemetry) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    /** Poll interval, adjustable at runtime (stage batteries poll faster). */
    @Volatile private var intervalMs: Long = initialIntervalMs
    fun setInterval(ms: Long) { intervalMs = ms }

    private var gatt: BluetoothGatt? = null
    private var ffe2: BluetoothGattCharacteristic? = null
    private val buffer = ArrayList<Byte>()

    @Volatile private var connectReady: CompletableDeferred<Boolean>? = null
    @Volatile private var response: CompletableDeferred<ByteArray>? = null
    @Volatile private var running = false
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        running = true
        job = scope.launch(Dispatchers.IO) { loop() }
    }

    fun stop() {
        running = false
        job?.cancel()
        close()
    }

    private suspend fun loop() {
        // Stagger startup so the whole fleet doesn't slam connectGatt at once.
        if (startDelayMs > 0) delay(startDelayMs)
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            onStatus("Bluetooth is off")
            return
        }
        var backoff = 2000L
        while (running) {
            var permitHeld = false
            try {
                // Only a few batteries may be in the "connecting" phase at once — the LE
                // initiator can't pursue many pending connections without thrashing.
                connectGate.acquire(); permitHeld = true
                onStatus("connecting")
                val device = adapter.getRemoteDevice(address)
                val ready = CompletableDeferred<Boolean>()
                connectReady = ready
                synchronized(buffer) { buffer.clear() }
                gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                val ok = withTimeoutOrNull(10000) { ready.await() } ?: false
                // Established links no longer use the initiator — free the gate either way.
                connectGate.release(); permitHeld = false
                if (!ok) throw IllegalStateException("connect/discover failed")
                onStatus("connected")
                backoff = 2000L  // reset on success

                while (running) {
                    val data = pollOnce()
                    if (data != null) {
                        BmsProtocol.parseTelemetry(data, displayName)?.let(onTelemetry)
                    }
                    delay(intervalMs)
                }
            } catch (e: CancellationException) {
                if (permitHeld) connectGate.release()
                throw e
            } catch (e: Exception) {
                if (permitHeld) connectGate.release()
                onStatus("retry: ${e.message}")
                close()
                if (running) delay(backoff)
                backoff = (backoff * 2).coerceAtMost(20000L)  // back off unreachable batteries
            }
        }
    }

    private suspend fun pollOnce(): ByteArray? {
        val g = gatt ?: return null
        val ch = ffe2 ?: return null
        val resp = CompletableDeferred<ByteArray>()
        response = resp
        synchronized(buffer) { buffer.clear() }
        writeStatus(g, ch)
        return withTimeoutOrNull(4000) { resp.await() }
    }

    private fun writeStatus(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        val frame = BmsProtocol.STATUS_FRAME
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(ch, frame, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        } else {
            @Suppress("DEPRECATION")
            run {
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                ch.value = frame
                g.writeCharacteristic(ch)
            }
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> g.discoverServices()
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectReady?.complete(false)
                    response?.completeExceptionally(IllegalStateException("disconnected"))
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(BmsProtocol.SERVICE_UUID)
            val ffe1 = service?.getCharacteristic(BmsProtocol.FFE1_NOTIFY)
            ffe2 = service?.getCharacteristic(BmsProtocol.FFE2_WRITE)
            if (ffe1 == null || ffe2 == null) {
                connectReady?.complete(false)
                return
            }
            g.setCharacteristicNotification(ffe1, true)
            val cccd = ffe1.getDescriptor(BmsProtocol.CCCD)
            if (cccd == null) {
                connectReady?.complete(false)
                return
            }
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                run {
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(cccd)
                }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            connectReady?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        // API 33+
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray,
        ) = append(value)

        // legacy (<= API 32)
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            ch.value?.let(::append)
        }
    }

    private fun append(value: ByteArray) {
        synchronized(buffer) {
            value.forEach { buffer.add(it) }
            if (buffer.size >= 80) response?.complete(buffer.toByteArray())
        }
    }

    private fun close() {
        try { gatt?.disconnect() } catch (e: Exception) { Log.d(TAG, "disconnect: ${e.message}") }
        try { gatt?.close() } catch (e: Exception) { Log.d(TAG, "close: ${e.message}") }
        gatt = null
        ffe2 = null
    }

    private companion object { const val TAG = "BmsConnection" }
}
