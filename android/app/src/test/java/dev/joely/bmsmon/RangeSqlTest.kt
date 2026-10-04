package dev.joely.bmsmon

import dev.joely.bmsmon.data.db.RANGE_PAGE_SQL
import dev.joely.bmsmon.data.db.RangeRowColumns
import dev.joely.bmsmon.data.db.SampleEntity
import dev.joely.bmsmon.data.forEachTsKeysetPage
import dev.joely.bmsmon.model.RangeAccumulator
import dev.joely.bmsmon.model.RangeRow
import dev.joely.bmsmon.model.learnRangeParams
import dev.joely.bmsmon.model.todayUsage
import java.sql.ResultSet
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** BLE-25: the range learner's page query on a JVM SQLite built from the checked-in Room schema. */
class RangeSqlTest {

    private fun row(rs: ResultSet) = RangeRowColumns(
        id = rs.getLong("id"),
        tsMs = rs.getLong("tsMs"),
        currentA = rs.getObject("currentA")?.let { (it as Number).toFloat() },
        powerW = rs.getObject("powerW")?.let { (it as Number).toFloat() },
        lat = rs.getObject("lat")?.let { (it as Number).toDouble() },
        lon = rs.getObject("lon")?.let { (it as Number).toDouble() },
        gpsAccuracyM = rs.getObject("gpsAccuracyM")?.let { (it as Number).toFloat() },
        regen = rs.getInt("regen") != 0,
    )

    /** Pack A with timestamp ties, a backward clock step and link rows, interleaved with pack B. */
    private fun seed(db: SqlTestDb): List<SampleEntity> {
        val base = randomPackSamples(seed = 21, rows = 3_000, address = "A")
        val t0 = base.first().tsMs
        val out = ArrayList<SampleEntity>(base.size)
        for ((k, s0) in base.withIndex()) {
            val ts = if (k < 2_000) t0 + (k / 3) * 1_500L else t0 + (k - 2_000) * 1_000L
            val gps = k % 4 == 0
            val s = s0.copy(
                tsMs = ts,
                lat = if (gps) 43.0 + k * 1e-6 else null,
                lon = if (gps) -87.9 else null,
                gpsAccuracyM = if (gps) 8f else null,
                regen = k % 97 == 0,
            )
            out += s.copy(id = db.insert(s))
            if (k % 3 == 0) db.insert(s.copy(address = "B"))
        }
        return out
    }

    private fun walk(db: SqlTestDb, since: Long, page: Int, consume: (RangeRowColumns) -> Unit) {
        forEachTsKeysetPage(page, since, { afterTs, afterId, limit ->
            db.query(
                RANGE_PAGE_SQL,
                mapOf("address" to "A", "afterTs" to afterTs, "afterId" to afterId, "limit" to limit),
                ::row,
            )
        }, RangeRowColumns::tsMs, RangeRowColumns::id, consume)
    }

    private fun walk(db: SqlTestDb, since: Long, page: Int): List<RangeRowColumns> =
        ArrayList<RangeRowColumns>().also { out -> walk(db, since, page) { out += it } }

    private fun expectedRows(a: List<SampleEntity>, since: Long) =
        a.filter { it.linkEvent == null && it.tsMs >= since }
            .sortedWith(compareBy({ it.tsMs }, { it.id }))
            .map { RangeRowColumns(it.id, it.tsMs, it.currentA, it.powerW, it.lat, it.lon, it.gpsAccuracyM, it.regen) }

    @Test fun thePagedWalkReturnsExactlyTheWindowInTimeThenIdOrder() {
        SqlTestDb().use { db ->
            val a = seed(db)
            val since = a[600].tsMs
            val expected = expectedRows(a, since)
            assertTrue("fixture must carry ties and a backward step", expected.size > 1_000)
            for (page in listOf(1, 7, 1_000, 10_000)) {
                assertEquals("page=$page", expected, walk(db, since, page))
            }
        }
    }

    @Test fun theRangePageSeeksThePackIndexAndNeverSorts() {
        SqlTestDb().use { db ->
            seed(db)
            val p = db.plan(RANGE_PAGE_SQL, mapOf("address" to "A", "afterTs" to 0L, "afterId" to 0L, "limit" to 100))
            assertTrue(p, p.contains("index_samples_address_tsMs"))
            assertFalse(p, p.contains("TEMP B-TREE"))
        }
    }

    @Test fun aPagedAccumulatorMatchesTheListPathAtEveryPageSize() {
        SqlTestDb().use { db ->
            val a = seed(db)
            val since = a[0].tsMs
            val zone = ZoneId.of("UTC")
            val now = a.maxOf { it.tsMs } + 60_000L
            fun toRow(r: RangeRowColumns) = RangeRow(r.tsMs, r.currentA, r.powerW, r.lat, r.lon, r.gpsAccuracyM, r.regen)
            val list = expectedRows(a, since).map(::toRow)
            for (page in listOf(1, 7, 1_000, 10_000)) {
                val acc = RangeAccumulator(zone)
                walk(db, since, page) { acc.add(toRow(it)) }
                assertEquals("page=$page rows", list.size, acc.rows)
                assertEquals("page=$page learn", learnRangeParams(list, zone, now), learnRangeParams(acc, now))
                assertEquals("page=$page today", todayUsage(list, zone, now), todayUsage(acc, now))
            }
        }
    }
}
