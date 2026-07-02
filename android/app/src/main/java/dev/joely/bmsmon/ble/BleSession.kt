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
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import dev.joely.bmsmon.ble.profile.BatteryProfile
import dev.joely.bmsmon.ble.profile.RedodoBekenProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One reusable BLE GATT session against a single BMS: connect, read STATUS (0x13), close.
 * Held open by the persistent-fleet connection manager. The ONLY thing ever written is the
 * profile's prebuilt status frame ([dev.joely.bmsmon.ble.profile.BatteryProfile.statusFrame]) — read-only.
 */
@SuppressLint("MissingPermission")
class BleSession(
    private val context: Context,
    private val address: String,
    private val profile: BatteryProfile = RedodoBekenProfile,
    /** Stage packs poll every ~1.5s; a BALANCED connection interval keeps the round-trip clear of the
     *  poll timeout. Background packs stay LOW_POWER to spare the radio across many held links. */
    highPriority: Boolean = false,
) {

    // Live so a held link can be re-tiered when its stage membership changes (see setHighPriority).
    // @Volatile: written by the repository's control loop, read on the binder callback thread.
    @Volatile private var highPriority: Boolean = highPriority

    // @Volatile: written on the binder callback thread / close(), read by poll() and
    // setHighPriority() on repository coroutine threads.
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var ffe2: BluetoothGattCharacteristic? = null
    private val buffer = ArrayList<Byte>()

    @Volatile private var connectReady: CompletableDeferred<Boolean>? = null
    @Volatile private var response: CompletableDeferred<ByteArray>? = null

    /** Connect, discover services, and enable FFE1 notifications. Returns true once ready. */
    suspend fun connect(timeoutMs: Long): Boolean {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter ?: return false
        if (!adapter.isEnabled) return false
        val device = adapter.getRemoteDevice(address)
        val ready = CompletableDeferred<Boolean>()
        connectReady = ready
        synchronized(buffer) { buffer.clear() }
        // Full API-26+ overload (BLE-13): pin TRANSPORT_LE + 1M PHY and deliver every GATT
        // callback on our dedicated handler thread instead of an arbitrary binder thread —
        // deterministic callback threading, and field logs from all sessions serialize cleanly.
        gatt = device.connectGatt(
            context, false, callback,
            BluetoothDevice.TRANSPORT_LE,
            BluetoothDevice.PHY_LE_1M_MASK,
            callbackHandler,
        )
        return withTimeoutOrNull(timeoutMs) { ready.await() } ?: false
    }

    /** Send STATUS and collect the complete response frame, or null on timeout. */
    suspend fun poll(timeoutMs: Long): ByteArray? {
        val g = gatt ?: return null
        val ch = ffe2 ?: return null
        val resp = CompletableDeferred<ByteArray>()
        // Swap the deferred and clear leftovers atomically under the buffer lock: a late fragment
        // from the PREVIOUS response arriving between the two steps would otherwise be seen by
        // append() with the new deferred armed and could complete this poll with stale bytes.
        synchronized(buffer) {
            response = resp
            buffer.clear()
        }
        if (!writeStatus(g, ch)) return null  // refused unsafe frame → fail this poll (no write happened)
        return withTimeoutOrNull(timeoutMs) { resp.await() }
    }

    /**
     * Re-tier a held link when its stage membership changes (BLE-4): the poll cadence follows the
     * stage live, but the connection interval was only requested once at connect time — a pack
     * pinned to the stage would keep polling at 1.5 s over a LOW_POWER interval and miss.
     * Idempotent (no-op when unchanged) and safe on a closed/null gatt; if services aren't
     * discovered yet, onServicesDiscovered picks up the new value instead.
     */
    fun setHighPriority(high: Boolean) {
        if (high == highPriority) return
        highPriority = high
        val g = gatt ?: return
        if (ffe2 == null) return  // pre-discovery: onServicesDiscovered applies the updated value
        val priority = if (high) BluetoothGatt.CONNECTION_PRIORITY_BALANCED
                       else BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER
        runCatching { g.requestConnectionPriority(priority) }
    }

    fun close() {
        try { gatt?.disconnect() } catch (e: Exception) { Log.d(TAG, "disconnect: ${e.message}") }
        try { gatt?.close() } catch (e: Exception) { Log.d(TAG, "close: ${e.message}") }
        gatt = null
        ffe2 = null
    }

    /**
     * SAFETY (BLE-7): [BatteryProfile.statusFrame] is a shared mutable ByteArray. Take a defensive
     * copy and refuse the write unless the copy is verifiably the known-safe 0x13 status query
     * ([BmsProtocol.isSafeStatusFrame]: length 8, STATUS opcode, valid checksum) — so an accidental
     * in-place mutation anywhere can never change the bytes sent to a live battery. A plain runtime
     * `if` on the write path, structurally impossible to compile out (never `assert`).
     * @return false if the frame was refused (nothing written) — the caller fails the poll.
     */
    private fun writeStatus(g: BluetoothGatt, ch: BluetoothGattCharacteristic): Boolean {
        val frame = profile.statusFrame.copyOf()
        if (!BmsProtocol.isSafeStatusFrame(frame)) {
            Log.e(
                TAG,
                "REFUSED write to $address: status frame failed safety validation: " +
                    frame.joinToString(" ") { "%02X".format(it) },
            )
            return false
        }
        val type = if (profile.writeWithResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                   else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(ch, frame, type)
        } else {
            @Suppress("DEPRECATION")
            run {
                ch.writeType = type
                ch.value = frame
                g.writeCharacteristic(ch)
            }
        }
        return true
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            // Diagnosability (BLE-13): surface the GATT status (e.g. the classic 133 =
            // GATT_CONN_FAILED_ESTABLISHMENT) — behavior is unchanged, the retry/backoff
            // machinery still sees only connectReady=false / a dropped link.
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "gatt status $status on connect $address (newState=$newState)")
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> g.discoverServices()
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectReady?.complete(false)
                    response?.completeExceptionally(IllegalStateException("disconnected"))
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "gatt status $status on service discovery $address")
            }
            val service = g.getService(profile.serviceUuid)
            val ffe1 = service?.getCharacteristic(profile.notifyUuid)
            ffe2 = service?.getCharacteristic(profile.writeUuid)
            if (ffe1 == null || ffe2 == null) {
                connectReady?.complete(false)
                return
            }
            g.setCharacteristicNotification(ffe1, true)
            val priority = if (highPriority) BluetoothGatt.CONNECTION_PRIORITY_BALANCED
                           else BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER
            runCatching { g.requestConnectionPriority(priority) }
            val cccd = ffe1.getDescriptor(profile.cccdUuid)
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

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray,
        ) = append(value)

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            ch.value?.let(::append)
        }
    }

    private fun append(value: ByteArray) {
        synchronized(buffer) {
            value.forEach { buffer.add(it) }
            // Length-driven completion: the response header's payload-length byte tells us the
            // full frame size (~105 bytes across several notification fragments, possibly with
            // stale garbage prepended — statusFrameComplete realigns just like the parser). A
            // fixed byte threshold below the real length (the old >= 80) completed truncated
            // buffers on stacks chunking at 20-byte ATT fragments → permanent decode_fail on a
            // healthy link. A buffer that never completes is bounded by the poll timeout.
            // `response` is read under the same buffer lock poll() swaps it under.
            val snapshot = buffer.toByteArray()
            if (BmsProtocol.statusFrameComplete(snapshot, profile.responseHeader)) {
                response?.complete(snapshot)
            }
        }
    }

    private companion object {
        const val TAG = "BleSession"

        // One dedicated callback thread shared by every session (BLE-13): GATT callbacks are tiny
        // (complete a deferred / append to a buffer), so serializing all sessions onto one looper
        // is cheap, keeps callback threading deterministic, and avoids the binder-thread pool.
        // Process-lifetime, like the monitoring engine that owns the sessions — never stopped.
        val callbackHandler: Handler by lazy {
            Handler(HandlerThread("BleSessionCallbacks").apply { start() }.looper)
        }
    }
}
