package dev.joely.bmsmon

import dev.joely.bmsmon.data.decodeSocCorrections
import dev.joely.bmsmon.data.encodeSocCorrections
import dev.joely.bmsmon.model.BatteryState
import dev.joely.bmsmon.model.EstPackState
import dev.joely.bmsmon.model.EstReading
import dev.joely.bmsmon.model.EstSource
import dev.joely.bmsmon.model.DrainModel
import dev.joely.bmsmon.model.PersistedEst
import dev.joely.bmsmon.model.SocCorrection
import dev.joely.bmsmon.model.stepEstimate
import dev.joely.bmsmon.model.toPackState
import dev.joely.bmsmon.model.toPersisted
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SocCorrectionCodecTest {

    @Test fun codecRoundTrip() {
        val m = mapOf(
            "C8:47:80:15:67:44" to PersistedEst(SocCorrection(14.25f, EstSource.VOLTAGE, 1_700_000_000_123L), 100.4f),
            "C8:47:80:15:62:1B" to PersistedEst(SocCorrection(6.5f, EstSource.COUNTER, 42L), null),
        )
        assertEquals(m, decodeSocCorrections(encodeSocCorrections(m)))
    }

    @Test fun drainModelRoundTrips() {
        val m = mapOf(
            "A" to PersistedEst(SocCorrection(15f, EstSource.COUNTER, 5L), 120f, DrainModel(1_700_000_000_123L, 2.35f, 120f)),
            "B" to PersistedEst(SocCorrection(0f, null, 0L), 80f, DrainModel(anchorAtMs = 7L)),
            "C" to PersistedEst(SocCorrection(0f, null, 0L), null, DrainModel(ahPerDay = 3f)),
        )
        assertEquals(m, decodeSocCorrections(encodeSocCorrections(m)))
    }

    // Entries written before the drain model existed (four parts) decode with none.
    @Test fun fourPartEntriesDecodeWithNoDrainModel() {
        assertEquals(
            mapOf("A" to PersistedEst(SocCorrection(15f, EstSource.COUNTER, 5L), 120f)),
            decodeSocCorrections("A=15.0|COUNTER|5|120.0"),
        )
    }

    // A bad anchor or rate costs only itself, never the correction.
    @Test fun aBadDrainFieldKeepsTheRest() {
        assertEquals(
            mapOf("A" to PersistedEst(SocCorrection(15f, EstSource.COUNTER, 5L), 120f, DrainModel(ahPerDay = 2f))),
            decodeSocCorrections("A=15.0|COUNTER|5|120.0|x|2.0|"),
        )
        assertEquals(
            mapOf("A" to PersistedEst(SocCorrection(15f, EstSource.COUNTER, 5L), 120f, DrainModel(anchorAtMs = 9L))),
            decodeSocCorrections("A=15.0|COUNTER|5|120.0|9|NaN|x"),
        )
        assertEquals(
            mapOf("A" to PersistedEst(SocCorrection(15f, EstSource.COUNTER, 5L), 120f, DrainModel(anchorAtMs = 9L))),
            decodeSocCorrections("A=15.0|COUNTER|5|120.0|9|-1.0|NaN"),
        )
    }

    @Test fun nullSourceAndEmptyRoundTrip() {
        val m = mapOf("A" to PersistedEst(SocCorrection(0f, null, 0L), null))
        assertEquals(m, decodeSocCorrections(encodeSocCorrections(m)))
        assertTrue(decodeSocCorrections(encodeSocCorrections(emptyMap())).isEmpty())
    }

    // Entries written before lastRawAh was persisted (three parts) still decode, with no last raw.
    @Test fun threePartEntriesStillDecode() {
        assertEquals(
            mapOf("A" to PersistedEst(SocCorrection(15f, EstSource.COUNTER, 5L), null)),
            decodeSocCorrections("A=15.0|COUNTER|5"),
        )
    }

    // A bad last-raw field costs only the last raw, never the correction (losing the correction
    // would show the BMS's higher reading).
    @Test fun aBadLastRawKeepsTheCorrection() {
        val want = mapOf("A" to PersistedEst(SocCorrection(15f, EstSource.COUNTER, 5L), null))
        assertEquals(want, decodeSocCorrections("A=15.0|COUNTER|5|x"))
        assertEquals(want, decodeSocCorrections("A=15.0|COUNTER|5|NaN"))
        assertEquals(want, decodeSocCorrections("A=15.0|COUNTER|5|"))
        assertNull(decodeSocCorrections(encodeSocCorrections(
            mapOf("A" to PersistedEst(SocCorrection(1f, EstSource.COUNTER, 1L), Float.NaN))))["A"]!!.lastRawAh)
    }

    @Test fun codecDropsMalformed() {
        assertTrue(decodeSocCorrections("A=x|COUNTER|1").isEmpty())
        assertTrue(decodeSocCorrections("garbage").isEmpty())
        assertTrue(decodeSocCorrections("A=1.0|BOGUS|1").isEmpty())
        assertTrue(decodeSocCorrections("A=NaN|COUNTER|1").isEmpty())
        assertTrue(decodeSocCorrections("A=1.0|COUNTER|1|2|3").isEmpty())
        assertTrue(decodeSocCorrections("A=1.0|COUNTER|1|2|3|4").isEmpty())
        assertTrue(decodeSocCorrections("A=1.0|COUNTER|1|2|3|4|5|6").isEmpty())
        // One bad entry does not take the good ones with it.
        assertEquals(
            mapOf("B" to PersistedEst(SocCorrection(2f, EstSource.COUNTER, 5L), null)),
            decodeSocCorrections("A=x|COUNTER|1;B=2.0|COUNTER|5"),
        )
    }

    // Final review, Important 2: the unseen-charge rule (raw up > 2 Ah with no Charging seen, then
    // clamped to full -> clear) must survive a restart. A spare holding a 39 Ah VOLTAGE correction at
    // a raw 100.4 Ah is charged to a clamped full while the phone is off; the first reading after the
    // restart must clear the correction, not leave the pack showing ~60 % when it is full.
    @Test fun unseenChargeRuleSurvivesARestart() {
        val before = EstPackState(
            correction = SocCorrection(39f, EstSource.VOLTAGE, 1_000L),
            lastTsMs = 2_000L, lastRawAh = 100.4f,
        )
        val stored = encodeSocCorrections(mapOf("C8:47:80:15:25:01" to before.toPersisted()))
        val restored = decodeSocCorrections(stored).getValue("C8:47:80:15:25:01").toPackState()
        assertEquals(100.4f, restored.lastRawAh!!, 0f)
        val after = stepEstimate(
            restored,
            EstReading(9_000_000L, BatteryState.Idle, false, 0f, 13.4f, 105f, 105f, 100f),
        )
        assertEquals(0f, after.correction.correctionAh, 0f)
        assertNull(after.correction.source)
    }
}
