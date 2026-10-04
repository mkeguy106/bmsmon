package dev.joely.bmsmon

import dev.joely.bmsmon.data.db.SampleEntity
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * In-memory SQLite for executing the app's DAO SQL text on the JVM. The schema is built from the
 * CHECKED-IN Room export (highest version under app/schemas/), so tables and indices are exactly
 * what ships; the driver is xerial sqlite-jdbc 3.41.2.2 — the artifact Room's compile-time verifier
 * uses. This runs the SQL *strings* (shared with the @Query annotations as consts), not Room's
 * generated code, and it is not Room test infra.
 */
class SqlTestDb : AutoCloseable {
    val conn: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    init {
        val db = Json.parseToJsonElement(schemaFile().readText()).jsonObject.getValue("database").jsonObject
        conn.createStatement().use { st ->
            for (e in db.getValue("entities").jsonArray) {
                val o = e.jsonObject
                val table = o.getValue("tableName").jsonPrimitive.content
                fun ddl(sql: String) = sql.replace("\${TABLE_NAME}", table)
                st.execute(ddl(o.getValue("createSql").jsonPrimitive.content))
                o["indices"]?.jsonArray?.forEach { st.execute(ddl(it.jsonObject.getValue("createSql").jsonPrimitive.content)) }
            }
        }
    }

    /** Insert like Room's generated adapter (Float → REAL via toDouble, Boolean → 0/1, null → NULL). Returns the rowid. */
    fun insert(s: SampleEntity): Long {
        conn.prepareStatement(
            "INSERT INTO samples (address, tsMs, sessionId, state, soc, currentA, powerW, voltageV, tempC, " +
                "mosfetTempC, soh, fullChargeAh, remainingAh, cycles, cellMinV, cellMaxV, regen, lat, lon, " +
                "gpsAccuracyM, linkEvent) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        ).use { ps ->
            listOf<Any?>(
                s.address, s.tsMs, s.sessionId, s.state, s.soc, s.currentA, s.powerW, s.voltageV, s.tempC,
                s.mosfetTempC, s.soh, s.fullChargeAh, s.remainingAh, s.cycles, s.cellMinV, s.cellMaxV, s.regen,
                s.lat, s.lon, s.gpsAccuracyM, s.linkEvent,
            ).forEachIndexed { i, v -> bind(ps, i + 1, v) }
            ps.executeUpdate()
        }
        conn.createStatement().use { st ->
            st.executeQuery("SELECT last_insert_rowid()").use { rs ->
                rs.next()
                return rs.getLong(1)
            }
        }
    }

    /** Run [sql], binding its `:name` parameters from [args]; map each row with [row]. */
    fun <T> query(sql: String, args: Map<String, Any?>, row: (ResultSet) -> T): List<T> =
        prepare(sql, args).use { ps ->
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(row(rs)) } }
        }

    /** Run a DELETE/UPDATE [sql] with `:name` parameters; returns the affected row count. */
    fun update(sql: String, args: Map<String, Any?>): Int = prepare(sql, args).use { it.executeUpdate() }

    /** Insert one outbox row the way Room's adapter does; returns its id. */
    fun insertOutbox(payload: String, enqueuedAt: Long): Long {
        conn.prepareStatement("INSERT INTO outbox (payload, enqueuedAt) VALUES (?, ?)").use { ps ->
            ps.setString(1, payload)
            ps.setLong(2, enqueuedAt)
            ps.executeUpdate()
        }
        conn.createStatement().use { st ->
            st.executeQuery("SELECT last_insert_rowid()").use { rs ->
                rs.next()
                return rs.getLong(1)
            }
        }
    }

    /** EXPLAIN QUERY PLAN detail lines for [sql], joined — pins index use and absence of sorts. */
    fun plan(sql: String, args: Map<String, Any?>): String =
        query("EXPLAIN QUERY PLAN $sql", args) { it.getString("detail") }.joinToString("\n")

    private fun prepare(sql: String, args: Map<String, Any?>): PreparedStatement {
        // SQLite numbers named parameters by first appearance; a repeated name shares its index.
        val names = Regex(":(\\w+)").findAll(sql).map { it.groupValues[1] }.distinct().toList()
        val ps = conn.prepareStatement(sql)
        names.forEachIndexed { i, n ->
            require(n in args) { "missing bind arg :$n" }
            bind(ps, i + 1, args[n])
        }
        return ps
    }

    private fun bind(ps: PreparedStatement, i: Int, v: Any?) {
        when (v) {
            null -> ps.setNull(i, Types.NULL)
            is Float -> ps.setDouble(i, v.toDouble())
            is Double -> ps.setDouble(i, v)
            is Int -> ps.setLong(i, v.toLong())
            is Long -> ps.setLong(i, v)
            is Boolean -> ps.setLong(i, if (v) 1L else 0L)
            is String -> ps.setString(i, v)
            else -> error("unsupported bind type ${v::class}")
        }
    }

    override fun close() = conn.close()

    private companion object {
        fun schemaFile(): File {
            val dir = listOf("schemas", "app/schemas", "android/app/schemas")
                .map { File(it, "dev.joely.bmsmon.data.db.BmsDatabase") }
                .firstOrNull { it.isDirectory }
                ?: error("Room schema export not found from ${File(".").absolutePath}")
            return dir.listFiles { f -> f.name.endsWith(".json") }!!
                .maxByOrNull { it.nameWithoutExtension.toInt() }!!
        }
    }
}

/** Nullable column reads (JDBC getters return 0 for NULL). */
fun ResultSet.floatOrNull(col: String): Float? = getDouble(col).let { if (wasNull()) null else it.toFloat() }
fun ResultSet.intOrNull(col: String): Int? = getLong(col).let { if (wasNull()) null else it.toInt() }
fun ResultSet.longOrNull(col: String): Long? = getLong(col).let { if (wasNull()) null else it }
