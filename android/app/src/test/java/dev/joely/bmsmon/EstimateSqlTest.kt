package dev.joely.bmsmon

import dev.joely.bmsmon.data.db.ESTIMATE_PAGE_SQL
import dev.joely.bmsmon.data.db.EstRowColumns
import dev.joely.bmsmon.data.db.SampleEntity
import dev.joely.bmsmon.data.forEachTsKeysetPage
import java.sql.ResultSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The SOC replay's page query (Task 4) on a JVM SQLite built from the checked-in Room schema. */
class EstimateSqlTest {

    private fun row(rs: ResultSet) = EstRowColumns(
        id = rs.getLong("id"),
        tsMs = rs.getLong("tsMs"),
        state = rs.getString("state"),
        regen = rs.getInt("regen") != 0,
        currentA = rs.getObject("currentA")?.let { (it as Number).toFloat() },
        voltageV = rs.getObject("voltageV")?.let { (it as Number).toFloat() },
        remainingAh = rs.getObject("remainingAh")?.let { (it as Number).toFloat() },
        fullChargeAh = rs.getObject("fullChargeAh")?.let { (it as Number).toFloat() },
        soc = rs.getObject("soc")?.let { (it as Number).toFloat() },
    )

    private fun sample(addr: String, ts: Long, k: Int, link: String? = null) = SampleEntity(
        address = addr, tsMs = ts, sessionId = 1L,
        state = if (link != null) null else if (k % 2 == 0) "Charging" else "Idle",
        soc = if (link != null) null else 90f + k, currentA = if (link != null) null else k * 0.5f,
        powerW = null, voltageV = if (link != null) null else 13.5f + k * 0.01f, tempC = null,
        mosfetTempC = null, soh = null, fullChargeAh = if (link != null) null else 105f,
        remainingAh = if (link != null) null else 100f + k, cycles = null, cellMinV = null, cellMaxV = null,
        regen = k == 3, linkEvent = link,
    )

    private fun walk(db: SqlTestDb, since: Long, page: Int): List<EstRowColumns> {
        val out = ArrayList<EstRowColumns>()
        forEachTsKeysetPage(page, since, { afterTs, afterId, limit ->
            db.query(
                ESTIMATE_PAGE_SQL,
                mapOf("address" to "A", "afterTs" to afterTs, "afterId" to afterId, "limit" to limit),
                ::row,
            )
        }, EstRowColumns::tsMs, EstRowColumns::id) { out += it }
        return out
    }

    @Test fun pageOrderAndExcludesLinks() {
        SqlTestDb().use { db ->
            val t0 = 1_794_900_000_000L
            // Inserted out of time order (a backward clock step) with a tie, a link row, an older row
            // before the window and another pack's row: none of those may leak or reorder.
            val ids = HashMap<Int, Long>()
            fun put(k: Int, ts: Long, link: String? = null, addr: String = "A") {
                val id = db.insert(sample(addr, ts, k, link))
                if (addr == "A" && link == null && ts >= t0) ids[k] = id
            }
            put(0, t0 - 1)                      // before the window
            put(1, t0 + 3_000)
            put(2, t0 + 1_500)
            put(3, t0 + 1_500)                  // tie on ts: id breaks it
            put(4, t0 + 2_000, link = "Disconnected")
            put(5, t0 + 4_500)
            put(6, t0 + 2_500, addr = "B")
            put(7, t0)

            val expected = listOf(7, 2, 3, 1, 5).map { k ->
                val s = sample("A", 0L, k)
                EstRowColumns(
                    ids.getValue(k), when (k) { 7 -> t0; 2, 3 -> t0 + 1_500; 1 -> t0 + 3_000; else -> t0 + 4_500 },
                    s.state, s.regen, s.currentA, s.voltageV, s.remainingAh, s.fullChargeAh, s.soc,
                )
            }
            for (page in listOf(1, 2, 3, 100)) assertEquals("page=$page", expected, walk(db, t0, page))
        }
    }

    @Test fun theEstimatePageSeeksThePackIndexAndNeverSorts() {
        SqlTestDb().use { db ->
            repeat(50) { db.insert(sample("A", 1_794_900_000_000L + it * 1_500L, it)) }
            val p = db.plan(ESTIMATE_PAGE_SQL, mapOf("address" to "A", "afterTs" to 0L, "afterId" to 0L, "limit" to 100))
            assertTrue(p, p.contains("index_samples_address_tsMs"))
            assertFalse(p, p.contains("TEMP B-TREE"))
        }
    }
}
