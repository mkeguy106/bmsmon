package dev.joely.bmsmon.data

/** Full-resolution samples older than this many days are pruned. */
const val SAMPLE_RETENTION_DAYS = 14

/** Raw debug frames older than this many days are pruned. */
const val RAW_FRAME_RETENTION_DAYS = 7

/** Raw-frame table is also capped by total hex size (whichever limit hits first). */
const val RAW_FRAME_MAX_BYTES = 20L * 1024 * 1024

/** Timestamp [days] before [nowMs]. */
fun cutoffMs(nowMs: Long, days: Int): Long = nowMs - days * 86_400_000L
