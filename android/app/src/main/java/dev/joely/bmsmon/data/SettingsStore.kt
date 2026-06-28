package dev.joely.bmsmon.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.joely.bmsmon.model.BatteryState
import dev.joely.bmsmon.model.Telemetry
import kotlinx.coroutines.flow.first
import org.json.JSONObject

private val Context.dataStore by preferencesDataStore("bms_settings")

data class Persisted(
    val accentArgb: Int?,
    val powerArgb: Int?,
    val manualMode: Boolean,
    val darkMode: Boolean,
    val dailyDriverId: String?,
    val dynamicStage: Boolean?,
    val stageHoldMinutes: Int?,
    val monitoring: Boolean,
    val logging: Boolean,
    val alertsOn: Boolean,
    val enabledThresholds: Set<Int>?,
    val keepScreenOn: Boolean,
    val sortKey: String?,
    val filters: Set<String>?,
    val filterBaseId: String?,
    val lastTelemetry: Map<String, Telemetry>,
    val tempFahrenheit: Boolean,
)

/** Persists user preferences (colors, appearance override, BMS addresses) via DataStore. */
class SettingsStore(private val context: Context) {

    private object K {
        val ACCENT = intPreferencesKey("accent")
        val POWER = intPreferencesKey("power")
        val MANUAL = booleanPreferencesKey("manual_mode")
        val DARK = booleanPreferencesKey("dark_mode")
        val DAILY_DRIVER = stringPreferencesKey("daily_driver")
        val DYNAMIC_STAGE = booleanPreferencesKey("dynamic_stage")
        val STAGE_HOLD = intPreferencesKey("stage_hold_min")
        val MONITORING = booleanPreferencesKey("monitoring")
        val LOGGING = booleanPreferencesKey("logging")
        val ALERTS_ON = booleanPreferencesKey("alerts_on")
        val THRESHOLDS = stringSetPreferencesKey("alert_thresholds")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val SORT_KEY = stringPreferencesKey("all_sort")
        val FILTERS = stringSetPreferencesKey("all_filters")
        val FILTER_BASE = stringPreferencesKey("all_filter_base")
        val LAST_TELEMETRY = stringPreferencesKey("last_telemetry")
        val TEMP_FAHRENHEIT = booleanPreferencesKey("temp_fahrenheit")
    }

    suspend fun load(): Persisted {
        val p = context.dataStore.data.first()
        return Persisted(
            accentArgb = p[K.ACCENT],
            powerArgb = p[K.POWER],
            manualMode = p[K.MANUAL] ?: false,
            darkMode = p[K.DARK] ?: false,
            dailyDriverId = p[K.DAILY_DRIVER],
            dynamicStage = p[K.DYNAMIC_STAGE],
            stageHoldMinutes = p[K.STAGE_HOLD],
            monitoring = p[K.MONITORING] ?: false,
            logging = p[K.LOGGING] ?: false,
            alertsOn = p[K.ALERTS_ON] ?: true,
            enabledThresholds = p[K.THRESHOLDS]?.mapNotNull { it.toIntOrNull() }?.toSet(),
            keepScreenOn = p[K.KEEP_SCREEN_ON] ?: true,
            sortKey = p[K.SORT_KEY],
            filters = p[K.FILTERS],
            filterBaseId = p[K.FILTER_BASE],
            lastTelemetry = p[K.LAST_TELEMETRY]?.let(::decodeTelemetry) ?: emptyMap(),
            tempFahrenheit = p[K.TEMP_FAHRENHEIT] ?: true,
        )
    }

    suspend fun setAccent(argb: Int) = context.dataStore.edit { it[K.ACCENT] = argb }.let {}
    suspend fun setPower(argb: Int) = context.dataStore.edit { it[K.POWER] = argb }.let {}
    suspend fun setMode(dark: Boolean) = context.dataStore.edit {
        it[K.MANUAL] = true
        it[K.DARK] = dark
    }.let {}
    suspend fun setDailyDriver(id: String) = context.dataStore.edit { it[K.DAILY_DRIVER] = id }.let {}
    suspend fun setDynamicStage(enabled: Boolean) = context.dataStore.edit { it[K.DYNAMIC_STAGE] = enabled }.let {}
    suspend fun setStageHold(minutes: Int) = context.dataStore.edit { it[K.STAGE_HOLD] = minutes }.let {}
    suspend fun setMonitoring(on: Boolean) = context.dataStore.edit { it[K.MONITORING] = on }.let {}
    suspend fun setLogging(on: Boolean) = context.dataStore.edit { it[K.LOGGING] = on }.let {}
    suspend fun setAlertsOn(on: Boolean) = context.dataStore.edit { it[K.ALERTS_ON] = on }.let {}
    suspend fun setThresholds(values: Set<Int>) =
        context.dataStore.edit { it[K.THRESHOLDS] = values.map(Int::toString).toSet() }.let {}
    suspend fun setKeepScreenOn(on: Boolean) = context.dataStore.edit { it[K.KEEP_SCREEN_ON] = on }.let {}
    suspend fun setSort(name: String) = context.dataStore.edit { it[K.SORT_KEY] = name }.let {}
    suspend fun setFilters(names: Set<String>) = context.dataStore.edit { it[K.FILTERS] = names }.let {}
    suspend fun setFilterBase(id: String) = context.dataStore.edit { it[K.FILTER_BASE] = id }.let {}
    suspend fun setLastTelemetry(map: Map<String, Telemetry>) =
        context.dataStore.edit { it[K.LAST_TELEMETRY] = encodeTelemetry(map) }.let {}
    suspend fun setTempFahrenheit(on: Boolean) = context.dataStore.edit { it[K.TEMP_FAHRENHEIT] = on }.let {}
}

/** JSON forbids NaN/Infinity; coerce any non-finite reading to 0 before writing. */
private fun Float.jsonSafe(): Double = if (isFinite()) toDouble() else 0.0

/** Compact JSON for the last-known per-battery telemetry (address -> the row's display fields). */
private fun encodeTelemetry(map: Map<String, Telemetry>): String {
    val root = JSONObject()
    map.forEach { (addr, t) ->
        root.put(addr, JSONObject().apply {
            put("name", t.name)
            put("soc", t.soc.jsonSafe())
            put("powerW", t.powerW.jsonSafe())
            put("current", t.current.jsonSafe())
            put("voltage", t.voltage.jsonSafe())
            put("capacityAh", t.capacityAh.jsonSafe())
            put("fullChargeAh", t.fullChargeAh.jsonSafe())
            put("cellV", t.cellV.jsonSafe())
            put("temp", t.temp.jsonSafe())
            put("soh", t.soh)
            put("cycles", t.cycles)
            put("state", t.state.name)
        })
    }
    return root.toString()
}

private fun decodeTelemetry(json: String): Map<String, Telemetry> = runCatching {
    val root = JSONObject(json)
    buildMap {
        root.keys().forEach { addr ->
            val o = root.getJSONObject(addr)
            // optDouble defaults to NaN for absent keys — always pass an explicit 0.0 fallback so
            // older blobs (missing newly-added fields) never decode to NaN and crash on re-encode.
            put(addr, Telemetry(
                name = o.optString("name"),
                soc = o.optDouble("soc", 0.0).toFloat(),
                powerW = o.optDouble("powerW", 0.0).toFloat(),
                current = o.optDouble("current", 0.0).toFloat(),
                voltage = o.optDouble("voltage", 0.0).toFloat(),
                capacityAh = o.optDouble("capacityAh", 0.0).toFloat(),
                fullChargeAh = o.optDouble("fullChargeAh", 0.0).toFloat(),
                cellV = o.optDouble("cellV", 0.0).toFloat(),
                temp = o.optDouble("temp", 0.0).toFloat(),
                soh = o.optInt("soh", 100),
                cycles = o.optInt("cycles", 0),
                state = runCatching { BatteryState.valueOf(o.optString("state")) }
                    .getOrDefault(BatteryState.Idle),
            ))
        }
    }
}.getOrDefault(emptyMap())
