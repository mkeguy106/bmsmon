package dev.joely.bmsmon

import dev.joely.bmsmon.data.buildPackHealth
import dev.joely.bmsmon.data.cellImbalance
import dev.joely.bmsmon.data.computeRollup
import dev.joely.bmsmon.data.db.SampleEntity
import dev.joely.bmsmon.data.effectiveResistance
import dev.joely.bmsmon.data.fitOf
import dev.joely.bmsmon.data.healthInputsOf
import dev.joely.bmsmon.data.ivBinMomentsOf
import dev.joely.bmsmon.data.viScatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DATA-15: the moment-based analysis must reproduce the frozen list-based oracle. */
class HealthEquivalenceTest {

    private val pack = randomPackSamples(seed = 3, rows = 8_000)

    private fun sample(soc: Float?, i: Float, v: Float) = SampleEntity(
        address = "A", tsMs = 0L, sessionId = 1L, state = "X", soc = soc, currentA = i,
        powerW = kotlin.math.abs(i * v), voltageV = v, tempC = 25f, mosfetTempC = 26, soh = 100,
        fullChargeAh = 105f, remainingAh = 50f, cycles = 40, cellMinV = null, cellMaxV = null,
        regen = false, linkEvent = null,
    )

    @Test fun resistanceMatchesTheTwoPassReference() {
        val ref = referenceEffectiveResistance(pack)!!
        val got = effectiveResistance(pack)!!
        assertTrue("fixture must exercise several bins (got ${ref.bins})", ref.bins >= 5)
        assertEquals(ref.perBin.map { it.soc }, got.perBin.map { it.soc })
        for ((r, g) in ref.perBin.zip(got.perBin)) {
            assertEquals(r.rMohm, g.rMohm, 0.1001f)   // ≤ one round1 step
            assertEquals(r.r2, g.r2, 0.0011f)
            assertEquals(r.spreadA, g.spreadA, 0f)
        }
        assertEquals(ref.rMohm, got.rMohm, 0.1001f)
    }

    // The cancellation worst case for raw moments: 50k rows, ~99.6% idle at exactly 0 A (the BMS
    // deadband), V jittering ~1 mV around 13.3 V — Σv² ≈ 8.8e6 while the centred syy is ≈ 0.07.
    @Test fun momentFitAgreesWithTwoPassOnAHugeNarrowIdleBin() {
        val rnd = java.util.Random(17)
        val pts = ArrayList<Pair<Float, Float>>()
        repeat(49_800) { pts += 0f to (13.3 + rnd.nextGaussian() * 0.001).toFloat() }
        repeat(200) { pts += (-rnd.nextFloat() * 20f) to (13.29 + rnd.nextGaussian() * 0.001).toFloat() }
        val ref = referenceRegress(pts)!!
        val got = fitOf(ivBinMomentsOf(pts.map { (i, v) -> sample(80f, i, v) }).single())!!
        assertEquals(ref.slopeOhm, got.slopeOhm, 1e-9 * kotlin.math.abs(ref.slopeOhm) + 1e-15)
        assertEquals(ref.interceptV, got.interceptV, 1e-9)
        assertEquals(ref.r2, got.r2, 1e-5)
        assertEquals(ref.spreadA, got.spreadA, 0f)
    }

    @Test fun scatterPointsAreIdenticalAndTheFitMatches() {
        for (n in listOf(0, 1, 2, 699, 700, 1_399, 1_400, 2_099, 8_000)) {
            val s = randomPackSamples(seed = 4, rows = n)
            val ref = referenceViScatter(s, rMohm = null)
            val got = viScatter(s, rMohm = null)
            if (ref == null) {
                assertNull("n=$n", got)
                continue
            }
            assertNotNull("n=$n", got)
            assertEquals("n=$n", ref.pts, got!!.pts)
            assertEquals(ref.ocv, got.ocv, 0.0011f)
            assertEquals(ref.rMohm, got.rMohm, 0.1001f)
        }
    }

    @Test fun cellImbalanceMatchesReference() {
        val ref = referenceCellImbalance(pack)!!
        val got = cellImbalance(pack)!!
        assertEquals(ref.meanMv, got.meanMv, 0.1001f)
        assertEquals(ref.maxMv, got.maxMv, 0.1001f)
        assertEquals(ref.sessionsSampled, got.sessionsSampled)
    }

    @Test fun packHealthMatchesReference() {
        val sessions = pack.groupBy { it.sessionId }.map { (id, rows) -> computeRollup("A", id, rows) }
        val ref = referenceBuildPackHealth("A", "2012 · A", sessions, pack)
        val got = buildPackHealth("A", "2012 · A", sessions, healthInputsOf(pack))
        assertEquals(ref.sessions.map { it.copy(cellDeltaMv = null) }, got.sessions.map { it.copy(cellDeltaMv = null) })
        for ((r, g) in ref.sessions.zip(got.sessions)) {
            val expected = r.cellDeltaMv
            if (expected == null) assertNull(g.cellDeltaMv) else assertEquals(expected, g.cellDeltaMv!!, 0.1001f)
        }
        assertEquals(ref.copy(sessions = emptyList(), cell = null, resistance = null, scatter = null),
            got.copy(sessions = emptyList(), cell = null, resistance = null, scatter = null))
        assertEquals(ref.resistance!!.rMohm, got.resistance!!.rMohm, 0.1001f)
        assertEquals(ref.scatter!!.pts, got.scatter!!.pts)
    }

    // A pack with sessions but no usable I/V rows (a spare that only logged link events) must still
    // build, with every derived section null and nothing thrown.
    @Test fun packWithNoUsableRowsBuildsEmptyHealth() {
        val linkOnly = listOf(linkSample("A", 1_000, 1, "Connected"), linkSample("A", 2_000, 1, "Disconnected"))
        val h = buildPackHealth("A", "2016 · A", emptyList(), healthInputsOf(linkOnly))
        assertNull(h.resistance)
        assertNull(h.scatter)
        assertNull(h.cell)
        assertEquals(0, h.sessionCount)
    }

    // NULL-SOC rows (legacy CSV import) feed the scatter's global fit but no bin.
    @Test fun nullSocRowsJoinTheGlobalFitButNoBin() {
        val rows = (0..20).map { k -> sample(soc = null, i = -k.toFloat(), v = 13.3f - k * 0.006f) }
        assertEquals(listOf<Int?>(null), ivBinMomentsOf(rows).map { it.bin })
        assertNull(effectiveResistance(rows))
        assertNotNull(viScatter(rows, rMohm = null))
    }
}
