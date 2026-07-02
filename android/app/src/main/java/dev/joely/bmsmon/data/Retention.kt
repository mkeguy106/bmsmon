package dev.joely.bmsmon.data

/** Full-resolution samples older than this many days are pruned. */
const val SAMPLE_RETENTION_DAYS = 14

/** Raw debug frames older than this many days are pruned. */
const val RAW_FRAME_RETENTION_DAYS = 7

/** Raw-frame table is also capped by total raw-frame bytes (whichever limit hits first). */
const val RAW_FRAME_MAX_BYTES = 20L * 1024 * 1024

/**
 * Raw-frame BYTES represented by [hexChars] hex characters (2 chars encode 1 byte). The DAO can
 * only cheaply sum `LENGTH(hex)` — comparing that directly against [RAW_FRAME_MAX_BYTES] counted
 * every frame twice (DATA-6), halving the effective cap. 20 MB now means 20 MB of frame data.
 */
fun rawFrameBytes(hexChars: Long): Long = hexChars / 2

/** Timestamp [days] before [nowMs]. */
fun cutoffMs(nowMs: Long, days: Int): Long = nowMs - days * 86_400_000L
