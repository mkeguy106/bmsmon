package dev.joely.bmsmon.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.joely.bmsmon.model.Battery
import dev.joely.bmsmon.model.BatteryState
import dev.joely.bmsmon.model.Group
import dev.joely.bmsmon.model.Roster
import dev.joely.bmsmon.model.StageTarget
import dev.joely.bmsmon.model.Telemetry
import dev.joely.bmsmon.model.TempThresholds
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore by preferencesDataStore("bms_settings")

data class Persisted(
    val accentArgb: Int?,
    val powerArgb: Int?,
    val manualMode: Boolean,
    val darkMode: Boolean,
    val dailyDriverId: String?,
    val lastStage: StageTarget?,
    val dynamicStage: Boolean?,
    val stageHoldMinutes: Int?,
    val monitoring: Boolean,
    val logging: Boolean,
    val alertsOn: Boolean,
    val enabledThresholds: Set<Int>?,
    val criticalThreshold: Int?,
    val keepScreenOn: Boolean,
    val sortKey: String?,
    val filters: Set<String>?,
    val filterBaseId: String?,
    val lastTelemetry: Map<String, Telemetry>,
    val tempFahrenheit: Boolean,
    val roster: Roster?,
    val appearance: String?,
    val autoLuxThreshold: Float?,
    val locked: Boolean,
    val csvImported: Boolean,
    val lockShowTime: Boolean,
    val lockShowWifi: Boolean,
    val lockShowBattery: Boolean,
    val disabledAddrs: Set<String>?,
    val cloudEnabled: Boolean,
    val apiBaseUrl: String?,
    val deviceId: String?,
    val enrolled: Boolean,
    val gpsEnabled: Boolean?,
    val importWatermark: Long,
    val importDone: Boolean,
    val tempThresholdsByProfile: Map<String, TempThresholds>,
    val tempAlertsEnabled: Boolean,
    val showTempGauge: Boolean,
    val tempGaugeSide: String?,
    val cloudSyncAlerts: Boolean,
    val pendingTempConfig: String?,
)

/** Persists user preferences (colors, appearance override, BMS addresses) via DataStore. */
class SettingsStore(private val context: Context) {

    private object K {
        val ACCENT = intPreferencesKey("accent")
        val POWER = intPreferencesKey("power")
        val MANUAL = booleanPreferencesKey("manual_mode")
        val DARK = booleanPreferencesKey("dark_mode")
        val DAILY_DRIVER = stringPreferencesKey("daily_driver")
        val LAST_STAGE = stringPreferencesKey("last_stage")
        val DYNAMIC_STAGE = booleanPreferencesKey("dynamic_stage")
        val STAGE_HOLD = intPreferencesKey("stage_hold_min")
        val MONITORING = booleanPreferencesKey("monitoring")
        val LOGGING = booleanPreferencesKey("logging")
        val ALERTS_ON = booleanPreferencesKey("alerts_on")
        val THRESHOLDS = stringSetPreferencesKey("alert_thresholds")
        val CRITICAL_THRESHOLD = intPreferencesKey("alert_critical_threshold")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val SORT_KEY = stringPreferencesKey("all_sort")
        val FILTERS = stringSetPreferencesKey("all_filters")
        val FILTER_BASE = stringPreferencesKey("all_filter_base")
        val LAST_TELEMETRY = stringPreferencesKey("last_telemetry")
        val TEMP_FAHRENHEIT = booleanPreferencesKey("temp_fahrenheit")
        val ROSTER = stringPreferencesKey("roster")
        val APPEARANCE = stringPreferencesKey("appearance")
        val AUTO_LUX = floatPreferencesKey("auto_lux_threshold")
        val LOCKED = booleanPreferencesKey("locked")
        val CSV_IMPORTED = booleanPreferencesKey("csv_imported")
        val LOCK_SHOW_TIME = booleanPreferencesKey("lock_show_time")
        val LOCK_SHOW_WIFI = booleanPreferencesKey("lock_show_wifi")
        val LOCK_SHOW_BATTERY = booleanPreferencesKey("lock_show_battery")
        val DISABLED = stringSetPreferencesKey("disabled_addrs")
        val CLOUD_ENABLED = booleanPreferencesKey("cloud_enabled")
        val API_BASE_URL = stringPreferencesKey("api_base_url")
        val DEVICE_ID = stringPreferencesKey("device_id")
        val ENROLLED = booleanPreferencesKey("enrolled")
        val GPS_ENABLED = booleanPreferencesKey("gps_enabled")
        val IMPORT_WATERMARK = longPreferencesKey("import_watermark")
        val IMPORT_DONE = booleanPreferencesKey("import_done")
        val INSTALL_UUID = stringPreferencesKey("install_uuid")
        val TEMP_THRESHOLDS = stringPreferencesKey("temp_thresholds_by_profile")
        val TEMP_ALERTS_ENABLED = booleanPreferencesKey("temp_alerts_enabled")
        val SHOW_TEMP_GAUGE = booleanPreferencesKey("show_temp_gauge")
        val TEMP_GAUGE_SIDE = stringPreferencesKey("temp_gauge_side")
        val CLOUD_SYNC_ALERTS = booleanPreferencesKey("cloud_sync_alerts")
        val PENDING_TEMP_CONFIG = stringPreferencesKey("pending_temp_config")
    }

    suspend fun load(): Persisted {
        val p = context.dataStore.data.first()
        return Persisted(
            accentArgb = p[K.ACCENT],
            powerArgb = p[K.POWER],
            manualMode = p[K.MANUAL] ?: false,
            darkMode = p[K.DARK] ?: false,
            dailyDriverId = p[K.DAILY_DRIVER],
            lastStage = decodeStage(p[K.LAST_STAGE]),
            dynamicStage = p[K.DYNAMIC_STAGE],
            stageHoldMinutes = p[K.STAGE_HOLD],
            monitoring = p[K.MONITORING] ?: false,
            logging = p[K.LOGGING] ?: false,
            alertsOn = p[K.ALERTS_ON] ?: true,
            enabledThresholds = p[K.THRESHOLDS]?.mapNotNull { it.toIntOrNull() }?.toSet(),
            criticalThreshold = p[K.CRITICAL_THRESHOLD],
            keepScreenOn = p[K.KEEP_SCREEN_ON] ?: true,
            sortKey = p[K.SORT_KEY],
            filters = p[K.FILTERS],
            filterBaseId = p[K.FILTER_BASE],
            lastTelemetry = p[K.LAST_TELEMETRY]?.let(::decodeTelemetry) ?: emptyMap(),
            tempFahrenheit = p[K.TEMP_FAHRENHEIT] ?: true,
            roster = p[K.ROSTER]?.let(::decodeRoster),
            appearance = p[K.APPEARANCE],
            autoLuxThreshold = p[K.AUTO_LUX],
            locked = p[K.LOCKED] ?: false,
            csvImported = p[K.CSV_IMPORTED] ?: false,
            lockShowTime = p[K.LOCK_SHOW_TIME] ?: true,
            lockShowWifi = p[K.LOCK_SHOW_WIFI] ?: true,
            lockShowBattery = p[K.LOCK_SHOW_BATTERY] ?: true,
            disabledAddrs = p[K.DISABLED],
            cloudEnabled = p[K.CLOUD_ENABLED] ?: false,
            apiBaseUrl = p[K.API_BASE_URL],
            deviceId = p[K.DEVICE_ID],
            enrolled = p[K.ENROLLED] ?: false,
            gpsEnabled = p[K.GPS_ENABLED],
            importWatermark = p[K.IMPORT_WATERMARK] ?: 0L,
            importDone = p[K.IMPORT_DONE] ?: false,
            tempThresholdsByProfile = p[K.TEMP_THRESHOLDS]?.let(::decodeTempThresholds) ?: emptyMap(),
            tempAlertsEnabled = p[K.TEMP_ALERTS_ENABLED] ?: true,
            showTempGauge = p[K.SHOW_TEMP_GAUGE] ?: true,
            tempGaugeSide = p[K.TEMP_GAUGE_SIDE],
            cloudSyncAlerts = p[K.CLOUD_SYNC_ALERTS] ?: true,
            pendingTempConfig = p[K.PENDING_TEMP_CONFIG],
        )
    }

    suspend fun setAccent(argb: Int) = context.dataStore.edit { it[K.ACCENT] = argb }.let {}
    suspend fun setPower(argb: Int) = context.dataStore.edit { it[K.POWER] = argb }.let {}
    suspend fun setAppearance(name: String) = context.dataStore.edit { it[K.APPEARANCE] = name }.let {}
    suspend fun setAutoLuxThreshold(lux: Float) = context.dataStore.edit { it[K.AUTO_LUX] = lux }.let {}
    suspend fun setDailyDriver(id: String) = context.dataStore.edit { it[K.DAILY_DRIVER] = id }.let {}
    suspend fun setLastStage(target: StageTarget) =
        context.dataStore.edit { it[K.LAST_STAGE] = encodeStage(target) }.let {}
    suspend fun setDynamicStage(enabled: Boolean) = context.dataStore.edit { it[K.DYNAMIC_STAGE] = enabled }.let {}
    suspend fun setStageHold(minutes: Int) = context.dataStore.edit { it[K.STAGE_HOLD] = minutes }.let {}
    suspend fun setMonitoring(on: Boolean) = context.dataStore.edit { it[K.MONITORING] = on }.let {}
    suspend fun setLogging(on: Boolean) = context.dataStore.edit { it[K.LOGGING] = on }.let {}
    suspend fun setAlertsOn(on: Boolean) = context.dataStore.edit { it[K.ALERTS_ON] = on }.let {}
    suspend fun setThresholds(values: Set<Int>) =
        context.dataStore.edit { it[K.THRESHOLDS] = values.map(Int::toString).toSet() }.let {}
    suspend fun setCriticalThreshold(t: Int) =
        context.dataStore.edit { it[K.CRITICAL_THRESHOLD] = t }.let {}
    suspend fun setKeepScreenOn(on: Boolean) = context.dataStore.edit { it[K.KEEP_SCREEN_ON] = on }.let {}
    suspend fun setSort(name: String) = context.dataStore.edit { it[K.SORT_KEY] = name }.let {}
    suspend fun setFilters(names: Set<String>) = context.dataStore.edit { it[K.FILTERS] = names }.let {}
    suspend fun setFilterBase(id: String) = context.dataStore.edit { it[K.FILTER_BASE] = id }.let {}
    suspend fun setLastTelemetry(map: Map<String, Telemetry>) =
        context.dataStore.edit { it[K.LAST_TELEMETRY] = encodeTelemetry(map) }.let {}
    suspend fun setTempFahrenheit(on: Boolean) = context.dataStore.edit { it[K.TEMP_FAHRENHEIT] = on }.let {}
    suspend fun setRoster(roster: Roster) =
        context.dataStore.edit { it[K.ROSTER] = encodeRoster(roster) }.let {}
    suspend fun setLocked(on: Boolean) = context.dataStore.edit { it[K.LOCKED] = on }.let {}
    suspend fun setCsvImported(on: Boolean) = context.dataStore.edit { it[K.CSV_IMPORTED] = on }.let {}
    suspend fun setLockShowTime(on: Boolean) = context.dataStore.edit { it[K.LOCK_SHOW_TIME] = on }.let {}
    suspend fun setLockShowWifi(on: Boolean) = context.dataStore.edit { it[K.LOCK_SHOW_WIFI] = on }.let {}
    suspend fun setLockShowBattery(on: Boolean) = context.dataStore.edit { it[K.LOCK_SHOW_BATTERY] = on }.let {}
    suspend fun setDisabled(addrs: Set<String>) = context.dataStore.edit { it[K.DISABLED] = addrs }.let {}
    suspend fun setCloudEnabled(on: Boolean) = context.dataStore.edit { it[K.CLOUD_ENABLED] = on }.let {}
    suspend fun setApiBaseUrl(url: String) = context.dataStore.edit { it[K.API_BASE_URL] = url }.let {}
    suspend fun setDeviceId(id: String) = context.dataStore.edit { it[K.DEVICE_ID] = id }.let {}
    suspend fun setEnrolled(on: Boolean) = context.dataStore.edit { it[K.ENROLLED] = on }.let {}
    suspend fun setGpsEnabled(on: Boolean) = context.dataStore.edit { it[K.GPS_ENABLED] = on }.let {}
    suspend fun setImportWatermark(v: Long) = context.dataStore.edit { it[K.IMPORT_WATERMARK] = v }.let {}
    suspend fun setImportDone(on: Boolean) = context.dataStore.edit { it[K.IMPORT_DONE] = on }.let {}
    suspend fun setTempThresholds(map: Map<String, TempThresholds>) =
        context.dataStore.edit { it[K.TEMP_THRESHOLDS] = encodeTempThresholds(map) }.let {}
    suspend fun setTempAlertsEnabled(on: Boolean) = context.dataStore.edit { it[K.TEMP_ALERTS_ENABLED] = on }.let {}
    suspend fun setShowTempGauge(on: Boolean) = context.dataStore.edit { it[K.SHOW_TEMP_GAUGE] = on }.let {}
    suspend fun setTempGaugeSide(side: String) = context.dataStore.edit { it[K.TEMP_GAUGE_SIDE] = side }.let {}
    suspend fun setCloudSyncAlerts(on: Boolean) = context.dataStore.edit { it[K.CLOUD_SYNC_ALERTS] = on }.let {}
    suspend fun setPendingTempConfig(json: String) =
        context.dataStore.edit { it[K.PENDING_TEMP_CONFIG] = json }.let {}
    suspend fun clearPendingTempConfig() =
        context.dataStore.edit { it.remove(K.PENDING_TEMP_CONFIG) }.let {}

    suspend fun installUuid(): String {
        val existing = context.dataStore.data.first()[K.INSTALL_UUID]
        if (existing != null) return existing
        val fresh = java.util.UUID.randomUUID().toString()
        context.dataStore.edit { it[K.INSTALL_UUID] = fresh }
        return fresh
    }
}

/** Persist the last main-stage target so the next launch can restore + prioritize it. */
private fun encodeStage(target: StageTarget): String = when (target) {
    is StageTarget.Base -> "base:${target.groupId}"
    is StageTarget.Single -> "single:${target.address}"
}

private fun decodeStage(s: String?): StageTarget? = when {
    s == null -> null
    s.startsWith("base:") -> StageTarget.Base(s.removePrefix("base:"))
    s.startsWith("single:") -> StageTarget.Single(s.removePrefix("single:"))
    else -> null
}

/** Per-profile temperature thresholds (profileId -> the four °C values). */
private fun encodeTempThresholds(map: Map<String, TempThresholds>): String {
    val root = JSONObject()
    map.forEach { (id, t) ->
        root.put(id, JSONObject()
            .put("coldCautionC", t.coldCautionC).put("hotCautionC", t.hotCautionC)
            .put("coldCritC", t.coldCritC).put("hotCritC", t.hotCritC))
    }
    return root.toString()
}

private fun decodeTempThresholds(json: String): Map<String, TempThresholds> = runCatching {
    val root = JSONObject(json)
    buildMap {
        root.keys().forEach { id ->
            val o = root.getJSONObject(id)
            put(id, TempThresholds(
                coldCautionC = o.optInt("coldCautionC", 5),
                hotCautionC = o.optInt("hotCautionC", 45),
                coldCritC = o.optInt("coldCritC", -12),
                hotCritC = o.optInt("hotCritC", 53),
            ))
        }
    }
}.getOrDefault(emptyMap())

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

/** Compact JSON for the editable roster (groups + batteries). */
private fun encodeRoster(r: Roster): String {
    val groups = JSONArray()
    r.groups.forEach { g -> groups.put(JSONObject().put("id", g.id).put("name", g.name)) }
    val batteries = JSONArray()
    r.batteries.forEach { b ->
        batteries.put(JSONObject()
            .put("address", b.address)
            .put("advertisedName", b.advertisedName)
            .put("alias", b.alias)
            .put("groupId", b.groupId ?: JSONObject.NULL))
    }
    return JSONObject().put("groups", groups).put("batteries", batteries).toString()
}

private fun decodeRoster(json: String): Roster? = runCatching {
    val root = JSONObject(json)
    val ga = root.getJSONArray("groups")
    val groups = (0 until ga.length()).map { i ->
        val o = ga.getJSONObject(i)
        Group(o.getString("id"), o.optString("name"))
    }
    val ba = root.getJSONArray("batteries")
    val batteries = (0 until ba.length()).map { i ->
        val o = ba.getJSONObject(i)
        Battery(
            address = o.getString("address"),
            advertisedName = o.optString("advertisedName"),
            alias = o.optString("alias"),
            groupId = if (o.isNull("groupId")) null else o.optString("groupId"),
        )
    }
    Roster(batteries, groups)
}.getOrNull()

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
