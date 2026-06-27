package dev.joely.bmsmon

import android.app.Application
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.joely.bmsmon.ble.BmsRepository
import dev.joely.bmsmon.data.SettingsStore
import dev.joely.bmsmon.model.ALL_GROUPS
import dev.joely.bmsmon.model.BatteryGroup
import dev.joely.bmsmon.model.BatteryStatus
import dev.joely.bmsmon.model.DEFAULT_GROUP_ID
import dev.joely.bmsmon.model.SLOW_POLL_MS
import dev.joely.bmsmon.model.STAGE_POLL_MS
import dev.joely.bmsmon.model.Telemetry
import dev.joely.bmsmon.model.computeStageGroup
import dev.joely.bmsmon.model.demoFor
import dev.joely.bmsmon.model.groupActivity
import dev.joely.bmsmon.model.groupById
import dev.joely.bmsmon.model.isActive
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
enum class SortKey { Activity, Soc, Base }
enum class FilterKey { ReachableOnly, ActiveOnly, ByBase, DailyDriverOnly }

data class UiState(
    val screen: Screen = Screen.Home,
    val mode: Mode = Mode.Dark,
    val manualMode: Boolean = false,
    val accent: Color = DefaultAccent,
    val power: Color = DefaultPower,
    val monitoring: Boolean = false,
    val dailyDriverId: String = DEFAULT_GROUP_ID,
    val fleet: Map<String, BatteryStatus> = emptyMap(),
    val lastActiveGroupId: String = DEFAULT_GROUP_ID,
    val stageGroupId: String = DEFAULT_GROUP_ID,
    val demo: List<Telemetry> = demoFor(groupById(DEFAULT_GROUP_ID)),
    val sortKey: SortKey = SortKey.Activity,
    val filters: Set<FilterKey> = emptySet(),
    val filterBaseId: String = DEFAULT_GROUP_ID,
) {
    val isDark get() = mode == Mode.Dark
    val stageGroup: BatteryGroup get() = groupById(stageGroupId)
    val dailyDriver: BatteryGroup get() = groupById(dailyDriverId)

    /** The two telemetry samples for the stage (last-known when monitoring, demo otherwise). */
    fun stageBatteries(): List<Telemetry> =
        if (!monitoring) demo
        else stageGroup.targets.map { t ->
            fleet[t.address]?.telemetry?.copy(name = t.name)
                ?: Telemetry(t.name, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        }
}

class BatteryViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = BmsRepository(app)
    private val store = SettingsStore(app)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val p = store.load()
            _state.update { s ->
                val dd = groupById(p.dailyDriverId ?: s.dailyDriverId)
                s.copy(
                    accent = p.accentArgb?.let { Color(it) } ?: s.accent,
                    power = p.powerArgb?.let { Color(it) } ?: s.power,
                    manualMode = p.manualMode,
                    mode = if (p.manualMode) (if (p.darkMode) Mode.Dark else Mode.Light) else s.mode,
                    dailyDriverId = dd.id,
                    lastActiveGroupId = dd.id,
                    stageGroupId = dd.id,
                    filterBaseId = dd.id,
                    demo = demoFor(dd),
                )
            }
        }
        // Demo drift while not monitoring.
        viewModelScope.launch {
            while (true) {
                delay(1800)
                if (!_state.value.monitoring) tickDemo()
            }
        }
    }

    private fun clamp(v: Float, lo: Float, hi: Float) = v.coerceIn(lo, hi)

    private fun tickDemo() {
        _state.update { s ->
            s.copy(demo = s.demo.map { b ->
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

    // --- navigation / theme ---
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

    fun setDailyDriver(id: String) {
        val g = groupById(id)
        _state.update { st ->
            val base = st.copy(dailyDriverId = g.id)
            if (st.monitoring) recompute(base)
            else base.copy(lastActiveGroupId = g.id, stageGroupId = g.id, demo = demoFor(g), filterBaseId = g.id)
        }
        viewModelScope.launch { store.setDailyDriver(g.id) }
        if (_state.value.monitoring) applyStageIntervals(_state.value.stageGroupId)
    }

    // --- all-batteries page controls ---
    fun setSort(s: SortKey) = _state.update { it.copy(sortKey = s) }
    fun toggleFilter(f: FilterKey) = _state.update {
        it.copy(filters = if (f in it.filters) it.filters - f else it.filters + f)
    }
    fun setFilterBase(id: String) = _state.update { it.copy(filterBaseId = id) }

    // --- fleet monitoring ---
    fun startMonitoring() {
        if (_state.value.monitoring) return
        _state.update { it.copy(monitoring = true, fleet = emptyMap()) }
        // Connect the daily driver first so it gets a link before the radio fills up.
        val dd = _state.value.dailyDriverId
        val orderedTargets = ALL_GROUPS.sortedByDescending { it.id == dd }.flatMap { it.targets }
        repository.start(
            scope = viewModelScope,
            targets = orderedTargets,
            slowIntervalMs = SLOW_POLL_MS,
            onTelemetry = { addr, t -> updateFleet(addr) { it.copy(telemetry = t, reachable = true) } },
            onReachable = { addr, r ->
                Log.d("BMS", "$addr reachable=$r")
                updateFleet(addr) { it.copy(reachable = r) }
            },
        )
        applyStageIntervals(_state.value.stageGroupId)
    }

    fun stopMonitoring() {
        repository.stop()
        _state.update { it.copy(monitoring = false, fleet = emptyMap(), demo = demoFor(it.stageGroup)) }
    }

    fun toggleMonitoring() = if (_state.value.monitoring) stopMonitoring() else startMonitoring()

    private fun updateFleet(address: String, transform: (BatteryStatus) -> BatteryStatus) {
        val before = _state.value.stageGroupId
        _state.update { st ->
            val cur = st.fleet[address] ?: BatteryStatus()
            recompute(st.copy(fleet = st.fleet + (address to transform(cur))))
        }
        val after = _state.value.stageGroupId
        if (before != after && _state.value.monitoring) applyStageIntervals(after)
    }

    /** Recompute the stage base and remember the last active pair. */
    private fun recompute(st: UiState): UiState {
        val stage = computeStageGroup(ALL_GROUPS, st.fleet, st.dailyDriverId, st.lastActiveGroupId)
        val stageActive = groupActivity(groupById(stage), st.fleet).isActive()
        return st.copy(
            stageGroupId = stage,
            lastActiveGroupId = if (stageActive) stage else st.lastActiveGroupId,
        )
    }

    private fun applyStageIntervals(stageId: String) {
        val stageAddrs = groupById(stageId).targets.map { it.address.uppercase() }.toSet()
        ALL_GROUPS.flatMap { it.targets }.forEach { t ->
            val fast = t.address.uppercase() in stageAddrs
            repository.setInterval(t.address, if (fast) STAGE_POLL_MS else SLOW_POLL_MS)
        }
    }

    override fun onCleared() {
        repository.stop()
        super.onCleared()
    }
}
