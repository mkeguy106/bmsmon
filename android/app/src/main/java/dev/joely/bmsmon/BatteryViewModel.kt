package dev.joely.bmsmon

import android.app.Application
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.joely.bmsmon.ble.BmsRepository
import dev.joely.bmsmon.data.SettingsStore
import dev.joely.bmsmon.model.Telemetry
import dev.joely.bmsmon.model.demoBatteries
import dev.joely.bmsmon.ui.theme.DefaultAccent
import dev.joely.bmsmon.ui.theme.DefaultPower
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

enum class Screen { Home, Settings }
enum class Mode { Dark, Light }

data class UiState(
    val screen: Screen = Screen.Home,
    val mode: Mode = Mode.Dark,
    val manualMode: Boolean = false,
    val connected: Boolean = false,
    val accent: Color = DefaultAccent,
    val power: Color = DefaultPower,
    val bms1: String = DEFAULT_BMS1,
    val bms2: String = DEFAULT_BMS2,
    val batteries: List<Telemetry> = demoBatteries,
    val statuses: List<String> = listOf("", ""),
) {
    val isDark get() = mode == Mode.Dark
}

// Pre-filled with the 2024 test packs for convenience (read-only target addresses).
private const val DEFAULT_BMS1 = "C8:47:80:15:07:DE"  // 2024-BATTERY-A (A02285)
private const val DEFAULT_BMS2 = "C8:47:80:15:25:01"  // 2024-BATTERY-B (A02402)
private const val NAME1 = "Battery 1"
private const val NAME2 = "Battery 2"

class BatteryViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = BmsRepository(app)
    private val store = SettingsStore(app)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        // Load persisted preferences (colors, appearance override, BMS addresses).
        viewModelScope.launch {
            val p = store.load()
            _state.update { s ->
                s.copy(
                    accent = p.accentArgb?.let { Color(it) } ?: s.accent,
                    power = p.powerArgb?.let { Color(it) } ?: s.power,
                    manualMode = p.manualMode,
                    mode = if (p.manualMode) (if (p.darkMode) Mode.Dark else Mode.Light) else s.mode,
                    bms1 = p.bms1 ?: s.bms1,
                    bms2 = p.bms2 ?: s.bms2,
                )
            }
        }
        // Demo simulation: drift values while not connected to a real BMS.
        viewModelScope.launch {
            while (true) {
                delay(1800)
                if (!_state.value.connected) tick()
            }
        }
    }

    private fun clamp(v: Float, lo: Float, hi: Float) = v.coerceIn(lo, hi)

    private fun tick() {
        _state.update { s ->
            s.copy(batteries = s.batteries.map { b ->
                b.copy(
                    current = clamp(b.current + (Random.nextFloat() - 0.5f) * 0.12f, 0f, 14f),
                    voltage = clamp(b.voltage + (Random.nextFloat() - 0.5f) * 0.04f, 20f, 29f),
                    temp = clamp(b.temp + (Random.nextFloat() - 0.5f) * 0.08f, 5f, 50f),
                    cellV = clamp(b.cellV + (Random.nextFloat() - 0.5f) * 0.004f, 2.9f, 3.65f),
                    powerW = clamp(b.powerW + (Random.nextFloat() - 0.5f) * 0.9f, 0f, 120f),
                )
            })
        }
    }

    // --- navigation / theme / settings intents ---
    fun goSettings() = _state.update { it.copy(screen = Screen.Settings) }
    fun goHome() = _state.update { it.copy(screen = Screen.Home) }

    fun setMode(mode: Mode) {
        _state.update { it.copy(mode = mode, manualMode = true) }
        viewModelScope.launch { store.setMode(mode == Mode.Dark) }
    }
    fun toggleMode() = setMode(if (_state.value.isDark) Mode.Light else Mode.Dark)

    fun applySystemMode(dark: Boolean) = _state.update {
        if (it.manualMode) it else it.copy(mode = if (dark) Mode.Dark else Mode.Light)
    }

    fun setAccent(c: Color) {
        _state.update { it.copy(accent = c) }
        viewModelScope.launch { store.setAccent(c.toArgb()) }
    }
    fun setPower(c: Color) {
        _state.update { it.copy(power = c) }
        viewModelScope.launch { store.setPower(c.toArgb()) }
    }
    fun setBms1(v: String) {
        _state.update { it.copy(bms1 = v) }
        viewModelScope.launch { store.setBms1(v) }
    }
    fun setBms2(v: String) {
        _state.update { it.copy(bms2 = v) }
        viewModelScope.launch { store.setBms2(v) }
    }

    // --- BLE connect / disconnect ---
    fun startConnect() {
        val s = _state.value
        if (s.connected) return
        _state.update { it.copy(connected = true, statuses = listOf("connecting", "connecting")) }
        repository.connect(
            scope = viewModelScope,
            targets = listOf(s.bms1 to NAME1, s.bms2 to NAME2),
            onTelemetry = { index, t ->
                val labelled = t.copy(name = if (index == 0) NAME1 else NAME2)
                _state.update { st ->
                    st.copy(batteries = st.batteries.toMutableList().also {
                        if (index < it.size) it[index] = labelled
                    })
                }
            },
            onStatus = { index, msg ->
                Log.d("BMS", "[$index] $msg")
                _state.update { st ->
                    st.copy(statuses = st.statuses.toMutableList().also {
                        if (index < it.size) it[index] = msg
                    })
                }
            },
        )
    }

    fun stopConnect() {
        repository.disconnect()
        _state.update { it.copy(connected = false, statuses = listOf("", "")) }
    }

    override fun onCleared() {
        repository.disconnect()
        super.onCleared()
    }
}
