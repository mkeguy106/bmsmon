package dev.joely.bmsmon.data

import dev.joely.bmsmon.ble.BmsProtocol
import dev.joely.bmsmon.ble.profile.RedodoBekenProfile

/** Why a raw frame was stored, for debugging bad decodes. */
object FrameReason {
    const val PERIODIC = "periodic"
    const val REALIGN = "realign"
    const val DECODE_FAIL = "decode_fail"
}

/**
 * Classify a received frame against [header]: [DECODE_FAIL] if it didn't parse, [REALIGN] if it
 * parsed but the status header wasn't at offset 0 (stale bytes were prepended), else [PERIODIC].
 */
fun classifyFrame(
    raw: ByteArray,
    parsedOk: Boolean,
    header: ByteArray = RedodoBekenProfile.responseHeader,
): String = when {
    !parsedOk -> FrameReason.DECODE_FAIL
    (BmsProtocol.statusFrameOffset(raw, header) ?: 0) != 0 -> FrameReason.REALIGN
    else -> FrameReason.PERIODIC
}
