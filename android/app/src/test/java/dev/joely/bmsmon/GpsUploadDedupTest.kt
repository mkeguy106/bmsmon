package dev.joely.bmsmon

import dev.joely.bmsmon.monitor.isNewFixForPack
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Upload-path GPS dedup (bandwidth): a pack's sample carries lat/lon only when the cached fix's
 * timestamp ([android.location.Location.getTime]) is NEW for that pack. Keyed on fix time — never
 * coordinate equality, because the fused provider re-fires with jittering coordinates while
 * stationary, which would defeat the dedup entirely.
 */
class GpsUploadDedupTest {

    @Test fun firstFixForAPackAlwaysAttaches() {
        assertTrue(isNewFixForPack(lastUploadedFixMs = null, fixTimeMs = 1_000L))
    }

    @Test fun sameFixTimeIsDeduped() {
        // Staged packs poll at 1.5 s vs the ~5 s fix cadence: repeats of one fix upload once.
        assertFalse(isNewFixForPack(lastUploadedFixMs = 5_000L, fixTimeMs = 5_000L))
    }

    @Test fun newerFixAttaches() {
        assertTrue(isNewFixForPack(lastUploadedFixMs = 5_000L, fixTimeMs = 10_000L))
    }

    @Test fun staleOutOfOrderFixIsDropped() {
        assertFalse(isNewFixForPack(lastUploadedFixMs = 10_000L, fixTimeMs = 5_000L))
    }
}
