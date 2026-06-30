package dev.joely.bmsmon.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

class UploadRateTest {

    @Test fun emptyWindowIsZero() {
        val r = UploadRate(windowMs = 5_000)
        assertEquals(0.0, r.kbps(1_000), 1e-9)
    }

    @Test fun sumsBytesInWindowToKbps() {
        val r = UploadRate(windowMs = 5_000)
        r.record(1_000, 1024)
        r.record(2_000, 1024)
        // 2048 bytes over a 5 s window = 2048 / 5 / 1024 = 0.4 KB/s
        assertEquals(0.4, r.kbps(2_000), 1e-9)
    }

    @Test fun prunesEntriesOlderThanWindow() {
        val r = UploadRate(windowMs = 5_000)
        r.record(1_000, 5120)
        // at t=7000 the t=1000 entry is 6000 ms old (> 5000) -> pruned -> 0
        assertEquals(0.0, r.kbps(7_000), 1e-9)
    }

    @Test fun partialWindowKeepsRecentDropsOld() {
        val r = UploadRate(windowMs = 5_000)
        r.record(1_000, 10240)  // becomes stale
        r.record(6_500, 1024)   // recent
        // at t=6500 the t=1000 entry is 5500 ms old -> pruned; only 1024 remains -> 0.2 KB/s
        assertEquals(0.2, r.kbps(6_500), 1e-9)
    }
}
