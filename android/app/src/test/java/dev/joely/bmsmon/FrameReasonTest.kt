package dev.joely.bmsmon

import dev.joely.bmsmon.data.FrameReason
import dev.joely.bmsmon.data.classifyFrame
import org.junit.Assert.assertEquals
import org.junit.Test

class FrameReasonTest {
    // A frame whose status header (01 93 55 AA) sits at offset 3 → aligned (00 00 LEN at 0..2).
    private fun aligned() = ByteArray(110).also {
        it[3] = 0x01; it[4] = 0x93.toByte(); it[5] = 0x55; it[6] = 0xAA.toByte()
    }
    // Two stale bytes prepended → header now at offset 5 → realigned.
    private fun misaligned() = ByteArray(110).also {
        it[5] = 0x01; it[6] = 0x93.toByte(); it[7] = 0x55; it[8] = 0xAA.toByte()
    }

    @Test fun alignedSuccessIsPeriodic() {
        assertEquals(FrameReason.PERIODIC, classifyFrame(aligned(), parsedOk = true))
    }

    @Test fun misalignedSuccessIsRealign() {
        assertEquals(FrameReason.REALIGN, classifyFrame(misaligned(), parsedOk = true))
    }

    @Test fun parseFailureIsDecodeFail() {
        assertEquals(FrameReason.DECODE_FAIL, classifyFrame(ByteArray(110), parsedOk = false))
    }
}
