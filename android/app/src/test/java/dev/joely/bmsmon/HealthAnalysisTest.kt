package dev.joely.bmsmon

import dev.joely.bmsmon.data.Verdict
import dev.joely.bmsmon.data.cellImbalance
import dev.joely.bmsmon.data.effectiveResistance
import dev.joely.bmsmon.data.peakPool
import dev.joely.bmsmon.data.verdictFor
import dev.joely.bmsmon.data.viScatter
import dev.joely.bmsmon.data.db.SampleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthAnalysisTest {

    /** A telemetry sample. powerW is the magnitude V·|I| as the BMS reports it. */
    private fun s(
        soc: Int, cur: Float, v: Float, ts: Long = 0L, session: Long = 1L,
        cellMin: Float? = null, cellMax: Float? = null, link: String? = null,
    ) = SampleEntity(
        address = "A", tsMs = ts, sessionId = session, state = "Discharging", soc = soc.toFloat(),
        currentA = if (link == null) cur else null, powerW = if (link == null) v * kotlin.math.abs(cur) else null,
        voltageV = if (link == null) v else null, tempC = 25f, mosfetTempC = 26, soh = 0,
        fullChargeAh = 0f, remainingAh = 0f, cycles = 0, cellMinV = cellMin, cellMaxV = cellMax,
        regen = cur > 0f, linkEvent = link,
    )

    /** A perfectly-linear SOC bin: V = ocv + I·(rMohm/1000), I swept across [spread] A. */
    private fun bin(soc: Int, rMohm: Float, ocv: Float, spread: Float): List<SampleEntity> {
        val r = rMohm / 1000f
        return (0..4).map { k ->
            val i = -spread + k * (spread / 4f)   // -spread..0
            s(soc, i, ocv + i * r)
        }
    }

    // --- effective resistance ---

    @Test fun medianOfSurvivingBins() {
        // Three clean bins at 4 / 5 / 6 mΩ → median 5, three bins.
        val samples = bin(80, 4f, 13.3f, 20f) + bin(81, 5f, 13.2f, 20f) + bin(82, 6f, 13.1f, 20f)
        val r = effectiveResistance(samples)!!
        assertEquals(3, r.bins)
        assertEquals(5f, r.rMohm, 0.2f)
    }

    @Test fun rejectsLowSpreadLowR2AndOutOfRangeBins() {
        val good = bin(80, 5f, 13.3f, 20f)              // kept
        val lowSpread = bin(81, 5f, 13.2f, 4f)          // spread < 8 A → dropped
        val tooHigh = bin(82, 90f, 13.1f, 20f)          // R ≥ 60 mΩ → dropped
        // Noisy bin: voltages scattered so r² is poor.
        val noisy = listOf(s(83, -20f, 13.0f), s(83, -10f, 13.4f), s(83, 0f, 13.0f), s(83, -15f, 13.5f))
        val r = effectiveResistance(good + lowSpread + tooHigh + noisy)!!
        assertEquals(1, r.bins)
        assertEquals(5f, r.rMohm, 0.2f)
    }

    @Test fun noUsableBinsReturnsNull() {
        // Every bin too narrow to trust.
        assertNull(effectiveResistance(bin(80, 5f, 13.3f, 3f) + bin(81, 5f, 13.2f, 2f)))
    }

    // --- V–I scatter / OCV ---

    @Test fun scatterRecoversOcvIntercept() {
        val samples = bin(80, 6f, 13.33f, 30f) + bin(81, 6f, 13.33f, 30f)
        val sc = viScatter(samples, rMohm = 6f)!!
        assertEquals(6f, sc.rMohm, 0.01f)              // headline R passed through
        assertEquals(13.33f, sc.ocv, 0.02f)           // intercept ≈ OCV at I=0
        assertTrue(sc.pts.isNotEmpty())
    }

    // --- cell imbalance ---

    @Test fun cellImbalanceMeanAndMax() {
        val samples = listOf(
            s(80, -5f, 13.3f, cellMin = 3.300f, cellMax = 3.310f),   // 10 mV
            s(80, -5f, 13.3f, cellMin = 3.300f, cellMax = 3.320f),   // 20 mV
            s(80, -5f, 13.3f),                                       // unsampled → ignored
        )
        val cd = cellImbalance(samples)!!
        assertEquals(15f, cd.meanMv, 0.1f)
        assertEquals(20f, cd.maxMv, 0.1f)
        assertEquals(1, cd.sessionsSampled)
    }

    @Test fun cellImbalanceNullWhenUnsampled() {
        assertNull(cellImbalance(listOf(s(80, -5f, 13.3f))))
    }

    // --- peak pooling ---

    @Test fun peakPoolKeepsSpikeAndDeepestSag() {
        // Two buckets over the time span; bucket 0 hides an 800 W spike + a 13.0 V sag among calm rows.
        val samples = listOf(
            s(80, -5f, 13.30f, ts = 0),
            s(80, -60f, 13.00f, ts = 100),     // the spike: |power| = 60·13 = 780 W, deepest sag
            s(80, -5f, 13.29f, ts = 200),
            s(80, -5f, 13.28f, ts = 1000),     // bucket 1
            s(80, -5f, 13.27f, ts = 1100),
        )
        val pooled = peakPool(samples, buckets = 2)
        assertEquals(2, pooled.size)
        assertEquals(780f, pooled[0].dischargeW, 1f)   // spike survived max-pool
        assertEquals(13.00f, pooled[0].voltageV!!, 0.001f)  // deepest sag survived min-pool
    }

    @Test fun peakPoolFlagsLinkEvents() {
        val samples = listOf(
            s(80, -5f, 13.30f, ts = 0),
            s(80, 0f, 0f, ts = 50, link = "Disconnected"),
            s(80, -5f, 13.29f, ts = 100),
        )
        val pooled = peakPool(samples, buckets = 1)
        assertEquals(1, pooled.size)
        assertTrue(pooled[0].link)
    }

    // --- verdict ---

    @Test fun verdictThresholds() {
        assertEquals(Verdict.Healthy, verdictFor(5.3f, 2f))
        assertEquals(Verdict.Healthy, verdictFor(null, null))
        assertEquals(Verdict.Watch, verdictFor(16f, null))
        assertEquals(Verdict.Watch, verdictFor(null, 25f))
        assertEquals(Verdict.Service, verdictFor(31f, null))
        assertEquals(Verdict.Service, verdictFor(null, 45f))
    }

    // --- oracle: reproduces the design handoff's headline numbers from the published per-bin slopes ---

    @Test fun reproducesA02214Reference() {
        // A02214 (67:44): 6 surviving bins → median 5.3 mΩ (data/health.json).
        val slopes = listOf(83 to 5.5f, 84 to 11.8f, 85 to 4.3f, 87 to 5.1f, 88 to 5.5f, 90 to 3.9f)
        val samples = slopes.flatMap { (soc, r) -> bin(soc, r, 13.33f, 30f) }
        val res = effectiveResistance(samples)!!
        assertEquals(6, res.bins)
        assertEquals(5.3f, res.rMohm, 0.05f)
    }

    @Test fun reproducesA02345Reference() {
        // A02345 (62:1B): 7 surviving bins → median 5.6 mΩ (data/health.json).
        val slopes = listOf(83 to 6.1f, 84 to 12.5f, 85 to 4.4f, 86 to 8.7f, 87 to 5.0f, 88 to 5.6f, 90 to 4.5f)
        val samples = slopes.flatMap { (soc, r) -> bin(soc, r, 13.30f, 30f) }
        val res = effectiveResistance(samples)!!
        assertEquals(7, res.bins)
        assertEquals(5.6f, res.rMohm, 0.05f)
    }

    @Test fun packHealthScatterPresentForLoadedPack() {
        val samples = bin(80, 6f, 13.33f, 30f)
        assertNotNull(viScatter(samples, 6f))
    }
}
