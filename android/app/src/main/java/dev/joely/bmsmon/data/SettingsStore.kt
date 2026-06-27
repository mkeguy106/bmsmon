package dev.joely.bmsmon.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

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
}
