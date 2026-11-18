package dev.joely.bmsmon

import dev.joely.bmsmon.data.decodeSocCorrections
import dev.joely.bmsmon.data.encodeSocCorrections
import dev.joely.bmsmon.model.EstSource
import dev.joely.bmsmon.model.SocCorrection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SocCorrectionCodecTest {

    @Test fun codecRoundTrip() {
        val m = mapOf(
            "C8:47:80:15:67:44" to SocCorrection(14.25f, EstSource.VOLTAGE, 1_700_000_000_123L),
            "C8:47:80:15:62:1B" to SocCorrection(6.5f, EstSource.COUNTER, 42L),
        )
        assertEquals(m, decodeSocCorrections(encodeSocCorrections(m)))
    }

    @Test fun nullSourceAndEmptyRoundTrip() {
        val m = mapOf("A" to SocCorrection(0f, null, 0L))
        assertEquals(m, decodeSocCorrections(encodeSocCorrections(m)))
        assertTrue(decodeSocCorrections(encodeSocCorrections(emptyMap())).isEmpty())
    }

    @Test fun codecDropsMalformed() {
        assertTrue(decodeSocCorrections("A=x|COUNTER|1").isEmpty())
        assertTrue(decodeSocCorrections("garbage").isEmpty())
        assertTrue(decodeSocCorrections("A=1.0|BOGUS|1").isEmpty())
        assertTrue(decodeSocCorrections("A=NaN|COUNTER|1").isEmpty())
        // One bad entry does not take the good ones with it.
        assertEquals(
            mapOf("B" to SocCorrection(2f, EstSource.COUNTER, 5L)),
            decodeSocCorrections("A=x|COUNTER|1;B=2.0|COUNTER|5"),
        )
    }
}
