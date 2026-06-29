package dev.joely.bmsmon.data

import dev.joely.bmsmon.data.db.SampleEntity

private val LINK_STATES = setOf("Connected", "Disconnected")

/**
 * Parse one line of the legacy usage_log.csv into a [SampleEntity] (sessionId = 0, filled in by the
 * importer). Columns: timestamp_ms,name,address,state,soc,current_a,power_w,voltage_v,regen.
 * Returns null for the header, blank lines, and malformed rows. Connected/Disconnected rows become
 * link-event rows with telemetry columns null.
 */
fun parseCsvLine(line: String): SampleEntity? {
    if (line.isBlank()) return null
    val f = line.split(",")
    if (f.size < 9) return null
    val ts = f[0].toLongOrNull() ?: return null      // header's "timestamp_ms" → null → skip
    val address = f[2].trim().uppercase()
    if (address.isEmpty()) return null
    val state = f[3].trim()
    val isLink = state in LINK_STATES
    val regen = f[8].trim() == "1"
    return SampleEntity(
        address = address,
        tsMs = ts,
        sessionId = 0,
        state = if (isLink) null else state.ifEmpty { null },
        soc = if (isLink) null else f[4].toFloatOrNull(),
        currentA = if (isLink) null else f[5].toFloatOrNull(),
        powerW = if (isLink) null else f[6].toFloatOrNull(),
        voltageV = if (isLink) null else f[7].toFloatOrNull(),
        tempC = null, mosfetTempC = null, soh = null, fullChargeAh = null,
        remainingAh = null, cycles = null, cellMinV = null, cellMaxV = null,
        regen = regen,
        linkEvent = if (isLink) state else null,
    )
}
