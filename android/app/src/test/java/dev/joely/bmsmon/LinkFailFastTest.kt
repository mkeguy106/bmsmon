package dev.joely.bmsmon

import android.bluetooth.BluetoothStatusCodes
import dev.joely.bmsmon.ble.GATT_WRITE_REQUEST_BUSY
import dev.joely.bmsmon.ble.GATT_WRITE_SUCCESS
import dev.joely.bmsmon.ble.WriteResult
import dev.joely.bmsmon.ble.classifyLegacyWrite
import dev.joely.bmsmon.ble.classifyWriteStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * BLE-18: the return of writeCharacteristic was ignored, so a status write onto a link that had
 * dropped between polls waited out the full 4 s timeout — five times (~22 s) — while the stage
 * showed the last reading as live.
 */
class LinkFailFastTest {

    @Test fun successIsWritten() {
        assertEquals(WriteResult.WRITTEN, classifyWriteStatus(GATT_WRITE_SUCCESS))
    }

    @Test fun busyIsAMissNotALinkLoss() {
        // A GATT op still queued: the link is alive — tearing it down would churn the Beken module.
        assertEquals(WriteResult.BUSY, classifyWriteStatus(GATT_WRITE_REQUEST_BUSY))
    }

    @Test fun stackUnavailableIsALinkError() {
        assertEquals(
            WriteResult.LINK_ERROR,
            classifyWriteStatus(BluetoothStatusCodes.ERROR_PROFILE_SERVICE_NOT_BOUND),
        )
        // The platform's "device not connected" status (documented, but hidden from the SDK's
        // constants): the literal must also read as a dead link.
        assertEquals(WriteResult.LINK_ERROR, classifyWriteStatus(4))
    }

    @Test fun unknownFailureIsALinkError() {
        assertEquals(WriteResult.LINK_ERROR, classifyWriteStatus(Int.MAX_VALUE))
    }

    @Test fun legacyRefusalIsAMiss() {
        assertEquals(WriteResult.WRITTEN, classifyLegacyWrite(true))
        assertEquals(WriteResult.BUSY, classifyLegacyWrite(false))
    }

    // The literals exist so the classifier stays JVM-pure and lint-clean on minSdk 26; pin them
    // to the platform's own constants (compile-time inlined from android.jar).
    @Test fun literalsMatchThePlatform() {
        assertEquals(BluetoothStatusCodes.SUCCESS, GATT_WRITE_SUCCESS)
        assertEquals(BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY, GATT_WRITE_REQUEST_BUSY)
    }
}
