package dev.joely.bmsmon.cloud

import java.util.ArrayDeque

/**
 * Smoothed upload throughput in KB/s over a rolling time window. Each successful POST records its
 * WIRE size (the gzipped bytes actually sent, not the plaintext JSON); [kbps] sums the bytes still
 * inside the window and divides by the window length, so a steady drip of small batches reads as a
 * steady rate instead of flickering to zero between posts.
 *
 * Single-threaded by contract — only the reporter's upload loop touches it, so no synchronization.
 */
class UploadRate(private val windowMs: Long = 5_000L) {

    // Each entry: [timestampMs, bytes]. Oldest at head.
    private val events = ArrayDeque<LongArray>()

    fun record(nowMs: Long, bytes: Int) {
        events.addLast(longArrayOf(nowMs, bytes.toLong()))
        prune(nowMs)
    }

    fun kbps(nowMs: Long): Double {
        prune(nowMs)
        val sum = events.sumOf { it[1] }
        return sum * 1000.0 / windowMs / 1024.0
    }

    private fun prune(nowMs: Long) {
        while (events.isNotEmpty() && nowMs - events.peekFirst()[0] > windowMs) events.removeFirst()
    }
}
