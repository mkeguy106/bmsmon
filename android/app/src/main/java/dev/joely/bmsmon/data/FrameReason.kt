package dev.joely.bmsmon.data

import dev.joely.bmsmon.ble.BmsProtocol

/** Why a raw frame was stored, for debugging bad decodes. */
object FrameReason {
    const val PERIODIC = "periodic"
    const val REALIGN = "realign"
    const val DECODE_FAIL = "decode_fail"
}

/**
 * Classify a received frame: [DECODE_FAIL] if it didn't parse, [REALIGN] if it parsed but the
 * status header wasn't at offset 0 (stale bytes were prepended), else [PERIODIC].
 */
fun classifyFrame(raw: ByteArray, parsedOk: Boolean): String = when {
    !parsedOk -> FrameReason.DECODE_FAIL
    (BmsProtocol.statusFrameOffset(raw) ?: 0) != 0 -> FrameReason.REALIGN
    else -> FrameReason.PERIODIC
}
