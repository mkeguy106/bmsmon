package dev.joely.bmsmon

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.joely.bmsmon.ble.BmsRepository
import dev.joely.bmsmon.data.SettingsStore
import dev.joely.bmsmon.data.TelemetryLogger
import dev.joely.bmsmon.model.ALL_GROUPS
import dev.joely.bmsmon.model.BatteryGroup
import dev.joely.bmsmon.model.BatteryStatus
import dev.joely.bmsmon.model.DEFAULT_GROUP_ID
import dev.joely.bmsmon.model.GroupActivity
import dev.joely.bmsmon.model.PIN_HOLD_MS
import dev.joely.bmsmon.model.StageInputs
import dev.joely.bmsmon.model.StageTarget
import dev.joely.bmsmon.model.Telemetry
import dev.joely.bmsmon.model.addresses
import dev.joely.bmsmon.model.demoFor
import dev.joely.bmsmon.model.groupActivity
import dev.joely.bmsmon.model.groupById
import dev.joely.bmsmon.model.resolveStage
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
    val dynamicStage: Boolean = true,
    val manualStage: StageTarget? = null,
    val manualPinnedAt: Long = 0,
    val lastDischargeAt: Map<String, Long> = emptyMap(),
    val stageTarget: StageTarget = StageTarget.Base(DEFAULT_GROUP_ID),
    val pinned: Boolean = false,
    val disabled: Set<String> = emptySet(),
    val demo: List<Telemetry> = demoFor(groupById(DEFAULT_GROUP_ID)),
    val sortKey: SortKey = SortKey.Activity,
    val filters: Set<FilterKey> = emptySet(),
    val filterBaseId: String = DEFAULT_GROUP_ID,
    val logging: Boolean = false,
    val peakPowerW: Float = 0f,
    val peakCurrentA: Float = 0f,
    val logPath: String = "",
) {
    val isDark get() = mode == Mode.Dark
    val dailyDriver: BatteryGroup get() = groupById(dailyDriverId)
    val stageGroupId: String? get() = (stageTarget as? StageTarget.Base)?.groupId

    /** The 1–2 telemetry samples for the stage (last-known when monitoring, demo otherwise). */
    fun stageBatteries(): List<Telemetry> {
        if (!monitoring) return demo
        val targets = when (val t = stageTarget) {
            is StageTarget.Base -> groupById(t.groupId).targets
            is StageTarget.Single -> ALL_GROUPS.flatMap { it.targets }.filter { it.address == t.address }
        }
        return targets.map { tg ->
            fleet[tg.address]?.telemetry?.copy(name = tg.name)
                ?: Telemetry(tg.name, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        }
    }

    val stageLabel: String
        get() = when (val t = stageTarget) {
            is StageTarget.Base -> groupById(t.groupId).label
            is StageTarget.Single -> ALL_GROUPS.flatMap { it.targets }
                .firstOrNull { it.address == t.address }?.name ?: t.address
        }

    val stageActivity: GroupActivity
        get() = (stageTarget as? StageTarget.Base)?.let { groupActivity(groupById(it.groupId), fleet) }
            ?: GroupActivity.Unknown
}

class BatteryViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = BmsRepository(app)
    private val store = SettingsStore(app)
    private val logger = TelemetryLogger(app)

    private val _state = MutableStateFlow(UiState(logPath = logger.path))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private fun clockMs() = System.currentTimeMillis()

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
                    dynamicStage = p.dynamicStage ?: s.dynamicStage,
                    stageTarget = StageTarget.Base(dd.id),
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
        // Periodic re-resolve so sticky/pin holds can expire even with no new samples.
        viewModelScope.launch {
            while (true) {
                delay(30_000)
                if (_state.value.monitoring) refresh()
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
        _state.update {
            if (it.monitoring) it.copy(dailyDriverId = g.id)
            else it.copy(dailyDriverId = g.id, stageTarget = StageTarget.Base(g.id), demo = demoFor(g), filterBaseId = g.id)
        }
        viewModelScope.launch { store.setDailyDriver(g.id) }
        refresh()
    }

    // --- all-batteries page controls ---
    fun setSort(s: SortKey) = _state.update { it.copy(sortKey = s) }
    fun toggleFilter(f: FilterKey) = _state.update {
        it.copy(filters = if (f in it.filters) it.filters - f else it.filters + f)
    }
    fun setFilterBase(id: String) = _state.update { it.copy(filterBaseId = id) }

    // --- stage control ---
    fun pinStage(target: StageTarget) {
        _state.update { it.copy(manualStage = target, manualPinnedAt = clockMs()) }
        refresh()
    }
    fun setDynamicStage(enabled: Boolean) {
        _state.update { it.copy(dynamicStage = enabled) }
        viewModelScope.launch { store.setDynamicStage(enabled) }
        refresh()
    }

    // --- per-battery / all disconnect ---
    fun disconnectBattery(address: String) {
        val a = address.uppercase()
        _state.update { st ->
            st.copy(
                disabled = st.disabled + a,
                fleet = st.fleet + (a to (st.fleet[a] ?: BatteryStatus()).copy(reachable = false)),
            )
        }
        repository.setDisabled(_state.value.disabled)
        refresh()
    }
    fun reconnectBattery(address: String) {
        val a = address.uppercase()
        _state.update { it.copy(disabled = it.disabled - a) }
        repository.setDisabled(_state.value.disabled)
        repository.kickAll()
        refresh()
    }
    fun disconnectAll() = stopMonitoring()

    // --- fleet monitoring ---
    fun startMonitoring() {
        if (_state.value.monitoring) return
        _state.update { it.copy(monitoring = true, fleet = emptyMap()) }
        repository.start(
            scope = viewModelScope,
            targets = ALL_GROUPS.flatMap { it.targets },
            onTelemetry = { addr, t -> onTelemetry(addr, t) },
            onReachable = { addr, r -> onReachable(addr, r) },
        )
        repository.setDisabled(_state.value.disabled)
        repository.setStage(currentStageAddrs())
    }

    fun stopMonitoring() {
        repository.stop()
        _state.update { it.copy(monitoring = false, fleet = emptyMap(), demo = demoFor(it.dailyDriver)) }
    }

    fun toggleMonitoring() = if (_state.value.monitoring) stopMonitoring() else startMonitoring()

    fun onAppForeground() {
        if (_state.value.monitoring) repository.kickAll()
    }

    private fun onTelemetry(addr: String, t: Telemetry) {
        _state.update { st ->
            st.copy(fleet = st.fleet + (addr to (st.fleet[addr] ?: BatteryStatus()).copy(telemetry = t, reachable = true)))
        }
        if (_state.value.logging) {
            logger.log(addr, t, clockMs())
            if (t.current < -0.05f) {  // discharging — track peak draw
                _state.update {
                    it.copy(
                        peakPowerW = maxOf(it.peakPowerW, t.powerW),
                        peakCurrentA = maxOf(it.peakCurrentA, -t.current),
                    )
                }
            }
        }
        refresh()
    }

    // --- usage logging (to calibrate the power ring) ---
    fun setLogging(enabled: Boolean) {
        _state.update {
            if (enabled) it.copy(logging = true, peakPowerW = 0f, peakCurrentA = 0f) else it.copy(logging = false)
        }
    }

    fun clearLog() {
        logger.clear()
        _state.update { it.copy(peakPowerW = 0f, peakCurrentA = 0f) }
    }

    private fun onReachable(addr: String, r: Boolean) {
        _state.update { st ->
            st.copy(fleet = st.fleet + (addr to (st.fleet[addr] ?: BatteryStatus()).copy(reachable = r)))
        }
        refresh()
    }

    private fun currentStageAddrs(): Set<String> =
        _state.value.stageTarget.addresses() - _state.value.disabled

    /** Re-resolve the stage; if its battery set changed, tell the engine. */
    private fun refresh() {
        val now = clockMs()
        val before = currentStageAddrs()
        _state.update { st ->
            val newLast = st.lastDischargeAt.toMutableMap()
            ALL_GROUPS.forEach { g ->
                if (groupActivity(g, st.fleet) == GroupActivity.Discharging) newLast[g.id] = now
            }
            val resolved = resolveStage(
                StageInputs(st.fleet, st.dailyDriverId, st.dynamicStage, st.manualStage,
                    st.manualPinnedAt, newLast, st.stageTarget, now),
            )
            val isPinned = st.manualStage != null && resolved == st.manualStage &&
                (!st.dynamicStage || now - st.manualPinnedAt < PIN_HOLD_MS)
            st.copy(lastDischargeAt = newLast, stageTarget = resolved, pinned = isPinned)
        }
        val after = currentStageAddrs()
        if (after != before && _state.value.monitoring) repository.setStage(after)
    }

    override fun onCleared() {
        repository.stop()
        super.onCleared()
    }
}
