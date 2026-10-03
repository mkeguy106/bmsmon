package dev.joely.bmsmon

import dev.joely.bmsmon.data.cellSessionStatsOf
import dev.joely.bmsmon.data.db.CELL_STATS_BY_SESSION_SQL
import dev.joely.bmsmon.data.db.CellSessionStats
import dev.joely.bmsmon.data.db.IV_MOMENTS_BY_SOC_BIN_SQL
import dev.joely.bmsmon.data.db.IV_ROW_AFTER_SQL
import dev.joely.bmsmon.data.db.IvBinMoments
import dev.joely.bmsmon.data.db.IvPoint
import dev.joely.bmsmon.data.db.SampleEntity
import dev.joely.bmsmon.data.effectiveResistanceFromBins
import dev.joely.bmsmon.data.healthInputsFrom
import dev.joely.bmsmon.data.ivBinMomentsOf
import dev.joely.bmsmon.data.scatterPointCap
import dev.joely.bmsmon.data.stridePoints
import dev.joely.bmsmon.data.stridedScatter
import java.sql.ResultSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** DATA-15: the real History SQL on a JVM SQLite, against the in-memory twins and the frozen oracle. */
class HealthSqlTest {

    private fun ivMoments(rs: ResultSet) = IvBinMoments(
        bin = rs.getObject("bin")?.let { (it as Number).toInt() }, n = rs.getLong("n"),
        sumI = rs.getDouble("sumI"), sumV = rs.getDouble("sumV"), sumII = rs.getDouble("sumII"),
        sumIV = rs.getDouble("sumIV"), sumVV = rs.getDouble("sumVV"),
        minI = rs.getDouble("minI"), maxI = rs.getDouble("maxI"),
        minV = rs.getDouble("minV"), maxV = rs.getDouble("maxV"),
    )

    private fun cellStats(rs: ResultSet) =
        CellSessionStats(rs.getLong("sessionId"), rs.getLong("n"), rs.getDouble("sumMv"), rs.getDouble("maxMv"))

    private fun ivPoint(rs: ResultSet) =
        IvPoint(rs.getLong("id"), rs.getLong("tsMs"), rs.getDouble("currentA").toFloat(), rs.getDouble("voltageV").toFloat())

    /** Pack A interleaved with pack B in one id sequence, as the live table is. Returns A's rows. */
    private fun seeded(db: SqlTestDb, rowsA: Int): List<SampleEntity> {
        val a = randomPackSamples(seed = 11, rows = rowsA, address = "A", firstSession = 1)
        val b = randomPackSamples(seed = 12, rows = rowsA / 2, address = "B", firstSession = 10_000)
        (a + b).sortedBy { it.tsMs }.forEach { db.insert(it) }
        return a
    }

    /** Pack A's V–I cloud as History builds it: step from the SQL moments, then the [rowAfterSql] walk. */
    private fun sqlStrideWalk(db: SqlTestDb, rowAfterSql: String = IV_ROW_AFTER_SQL) = healthInputsFrom(
        db.query(IV_MOMENTS_BY_SOC_BIN_SQL, mapOf("address" to "A"), ::ivMoments),
        emptyList(),
    ) { afterTs, afterId, skip ->
        db.query(
            rowAfterSql,
            mapOf("address" to "A", "afterTs" to afterTs, "afterId" to afterId, "skip" to skip),
            ::ivPoint,
        ).firstOrNull()
    }.scatter

    /** An I/V telemetry row whose point is unique to [k] (exact in Float), so a mismatch names its row. */
    private fun ivSample(address: String, ts: Long, k: Int) = SampleEntity(
        address = address, tsMs = ts, sessionId = 1, state = "X", soc = 50f,
        currentA = -(1f + k % 37), powerW = 10f, voltageV = 13f + k / 4096f,
        tempC = 25f, mosfetTempC = 26, soh = 100, fullChargeAh = 105f, remainingAh = 50f, cycles = 40,
        cellMinV = 3.3f, cellMaxV = 3.31f, regen = false, linkEvent = null,
    )

    /**
     * Pack A inserted in id order with runs of equal tsMs (4 per stamp), then a backward clock step
     * back into the middle of that span (3 per stamp), so later ids tie with and sort among earlier
     * timestamps. Link rows sit inside the runs and pack B interleaves, as in the live table.
     * Returns A's rows, in id order, with the ids SQLite assigned.
     */
    private fun tiedAndSteppedBack(db: SqlTestDb, before: Int, after: Int): List<SampleEntity> {
        val t0 = 1_700_000_000_000L
        val a = ArrayList<SampleEntity>(before + after)
        for (k in 0 until before + after) {
            val stamp = if (k < before) k / 4 else before / 8 + (k - before) / 3
            val ts = t0 + stamp * 1_500L
            val row = if (k % 50 == 49) linkSample("A", ts, 1, "Connected") else ivSample("A", ts, k)
            a += row.copy(id = db.insert(row))
            if (k % 3 == 0) db.insert(ivSample("B", ts, k))
        }
        return a
    }

    @Test fun momentsQueryMatchesTheKotlinTwinPerBin() {
        SqlTestDb().use { db ->
            val a = seeded(db, 6_000)
            val sql = db.query(IV_MOMENTS_BY_SOC_BIN_SQL, mapOf("address" to "A"), ::ivMoments).associateBy { it.bin }
            val kt = ivBinMomentsOf(a).associateBy { it.bin }
            assertEquals(kt.keys, sql.keys)
            assertTrue("NULL-SOC rows must form their own group", null in sql.keys)
            for ((bin, k) in kt) {
                val s = sql.getValue(bin)
                assertEquals("bin $bin", k.n, s.n)
                assertEquals(k.minI, s.minI, 0.0); assertEquals(k.maxI, s.maxI, 0.0)
                assertEquals(k.minV, s.minV, 0.0); assertEquals(k.maxV, s.maxV, 0.0)
                for ((x, y) in listOf(k.sumI to s.sumI, k.sumV to s.sumV, k.sumII to s.sumII, k.sumIV to s.sumIV, k.sumVV to s.sumVV)) {
                    assertEquals("bin $bin", x, y, 1e-9 * maxOf(1.0, kotlin.math.abs(x)))
                }
            }
        }
    }

    @Test fun resistanceFromSqlMomentsMatchesTheReference() {
        SqlTestDb().use { db ->
            val a = seeded(db, 6_000)
            val got = effectiveResistanceFromBins(db.query(IV_MOMENTS_BY_SOC_BIN_SQL, mapOf("address" to "A"), ::ivMoments))!!
            val ref = referenceEffectiveResistance(a)!!
            assertEquals(ref.perBin.map { it.soc }, got.perBin.map { it.soc })
            assertEquals(ref.rMohm, got.rMohm, 0.1001f)
        }
    }

    @Test fun cellStatsQueryMatchesTheKotlinTwin() {
        SqlTestDb().use { db ->
            val a = seeded(db, 6_000)
            val sql = db.query(CELL_STATS_BY_SESSION_SQL, mapOf("address" to "A"), ::cellStats).associateBy { it.sessionId }
            val kt = cellSessionStatsOf(a).associateBy { it.sessionId }
            assertEquals(kt.keys, sql.keys)
            for ((id, k) in kt) {
                val s = sql.getValue(id)
                assertEquals(k.n, s.n)
                assertEquals(k.sumMv, s.sumMv, 1e-9 * maxOf(1.0, k.sumMv))
                assertEquals(k.maxMv, s.maxMv, 1e-12)
            }
        }
    }

    @Test fun strideWalkPicksExactlyTheOldScatterRows() {
        for (n in listOf(0, 1, 2, 699, 700, 1_399, 1_400, 2_099, 5_000)) {
            SqlTestDb().use { db ->
                val a = seeded(db, n)
                assertEquals("n=$n", stridedScatter(a), sqlStrideWalk(db))
            }
        }
    }

    // The keyset's tie-break: rows sharing a tsMs are walked in id order, and after a backward clock
    // step later ids sort among earlier timestamps. The SQL walk must pick exactly what the list
    // stride picks from the rows sorted by (tsMs, id) — the order the index is walked in. Sizes
    // cover step 1, 2 and 4, against runs of 3 and 4 equal stamps.
    @Test fun strideWalkBreaksTimestampTiesById() {
        val noTieBreak = IV_ROW_AFTER_SQL.replace("(tsMs > :afterTs OR id > :afterId)", "tsMs > :afterTs")
        for ((before, after) in listOf(300 to 200, 1_000 to 800, 2_000 to 1_500)) {
            SqlTestDb().use { db ->
                val a = tiedAndSteppedBack(db, before, after)
                val byTsThenId = a.sortedWith(compareBy({ it.tsMs }, { it.id }))
                val label = "rows=${a.size}"
                assertEquals(label, stridedScatter(byTsThenId), sqlStrideWalk(db))
                // The fixture must exercise both: id order is not (tsMs, id) order here, and the
                // walk depends on the tie-break (without it, the rest of each equal-stamp run is skipped).
                assertNotEquals(label, stridedScatter(a), stridedScatter(byTsThenId))
                assertNotEquals("the tie-break clause must be present to drop", IV_ROW_AFTER_SQL, noTieBreak)
                assertNotEquals(label, stridedScatter(byTsThenId), sqlStrideWalk(db, noTieBreak))
            }
        }
    }

    @Test fun historyQueriesUseThePackIndexAndTheStrideNeverSorts() {
        SqlTestDb().use { db ->
            seeded(db, 2_000)
            val s = db.plan(IV_ROW_AFTER_SQL, mapOf("address" to "A", "afterTs" to 0L, "afterId" to 0L, "skip" to 5))
            assertTrue(s, s.contains("index_samples_address_tsMs"))
            assertFalse(s, s.contains("TEMP B-TREE"))
            for (q in listOf(IV_MOMENTS_BY_SOC_BIN_SQL, CELL_STATS_BY_SESSION_SQL)) {
                val p = db.plan(q, mapOf("address" to "A"))
                assertTrue(p, p.contains("index_samples_address_tsMs"))
            }
        }
    }

    // The writer keeps inserting while History walks, so the moments' row count can be stale. The
    // walk must still terminate, bounded by the exact point cap.
    @Test fun strideWalkStopsAtTheCapEvenIfRowsKeepArriving() {
        var id = 0L
        val pts = stridePoints(step = 3) { _, _, _ -> id++; IvPoint(id, id, -1f, 13f) }
        assertEquals(scatterPointCap(), pts.size)
    }

    @Test fun strideWalkRejectsANonPositiveStep() {
        try {
            stridePoints(step = 0) { _, _, _ -> null }
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }
}
