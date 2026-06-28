package dev.joely.bmsmon

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.joely.bmsmon.ble.BmsRepository
import dev.joely.bmsmon.ble.hasBlePermissions
import dev.joely.bmsmon.data.SettingsStore
import dev.joely.bmsmon.data.TelemetryLogger
import dev.joely.bmsmon.model.ALL_GROUPS
import dev.joely.bmsmon.model.BatteryGroup
import dev.joely.bmsmon.model.BatteryState
import dev.joely.bmsmon.model.BatteryStatus
import dev.joely.bmsmon.model.DEFAULT_GROUP_ID
import dev.joely.bmsmon.model.DEFAULT_STAGE_HOLD_MIN
import dev.joely.bmsmon.model.GroupActivity
import dev.joely.bmsmon.model.PIN_HOLD_MS
import dev.joely.bmsmon.model.StageInputs
import dev.joely.bmsmon.model.StageItem
import dev.joely.bmsmon.model.StageTarget
import dev.joely.bmsmon.model.Telemetry
import dev.joely.bmsmon.model.addresses
import dev.joely.bmsmon.model.demoFor
import dev.joely.bmsmon.model.groupActivity
import dev.joely.bmsmon.model.groupById
import dev.joely.bmsmon.model.groupForAddress
import dev.joely.bmsmon.model.isRegen
import dev.joely.bmsmon.model.resolveStage
import dev.joely.bmsmon.ui.theme.DefaultAccent
import dev.joely.bmsmon.ui.theme.DefaultPower
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.random.Random

enum class Screen { Home, Settings }
enum class Mode { Dark, Light }
enum class SortKey { Activity, Soc, Base }
enum class FilterKey { ReachableOnly, ActiveOnly, ByBase, DailyDriverOnly }

/** All low-battery alert thresholds, most severe last (matches the prototype + handoff). */
val ALERT_THRESHOLDS = listOf(30, 25, 20, 15, 10, 5)

/** Throttle for writing the last-known telemetry snapshot to disk while monitoring. */
private const val TELE_SAVE_INTERVAL_MS = 15_000L

/**
 * The resolved low-battery alert for the current stage. [flashing] drives the screen wash;
 * [activeThreshold] is the most-severe crossed level; [ackEffective] are acks still in force.
 */
data class StageAlert(
    val flashing: Boolean,
    val critical: Boolean,
    val lowSoc: Int,
    val activeThreshold: Int?,
    val ackEffective: Set<Int>,
)

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
    val stageHoldMinutes: Int = DEFAULT_STAGE_HOLD_MIN,
    val manualStage: StageTarget? = null,
    val manualPinnedAt: Long = 0,
    val lastDischargeAt: Map<String, Long> = emptyMap(),
    val stageTarget: StageTarget = StageTarget.Base(DEFAULT_GROUP_ID),
    val pinned: Boolean = false,
    val regenAddrs: Set<String> = emptySet(),
    val disabled: Set<String> = emptySet(),
    val demo: List<Telemetry> = demoFor(groupById(DEFAULT_GROUP_ID)),
    val sortKey: SortKey = SortKey.Activity,
    val filters: Set<FilterKey> = emptySet(),
    val filterBaseId: String = DEFAULT_GROUP_ID,
    val logging: Boolean = false,
    val peakPowerW: Float = 0f,
    val peakCurrentA: Float = 0f,
    val logPath: String = "",
    // low-battery alerts
    val alertsOn: Boolean = true,
    val enabledThresholds: Set<Int> = ALERT_THRESHOLDS.toSet(),
    val acknowledgedThresholds: Set<Int> = emptySet(),
    val keepScreenOn: Boolean = true,
) {
    val isDark get() = mode == Mode.Dark
    val dailyDriver: BatteryGroup get() = groupById(dailyDriverId)
    val stageGroupId: String? get() = (stageTarget as? StageTarget.Base)?.groupId

    /** The 1–2 stage packs with their regen flags (last-known when monitoring, demo otherwise). */
    fun stageItems(): List<StageItem> {
        if (!monitoring) return demo.map { StageItem(it, false) }
        val targets = when (val t = stageTarget) {
            is StageTarget.Base -> groupById(t.groupId).targets
            is StageTarget.Single -> ALL_GROUPS.flatMap { it.targets }.filter { it.address == t.address }
        }
        return targets.map { tg ->
            val tel = fleet[tg.address]?.telemetry?.copy(name = tg.name)
                ?: Telemetry(tg.name, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
            StageItem(tel, tg.address in regenAddrs)
        }
    }

    /** True when any pack on the stage is currently dumping regen current. */
    val stageRegen: Boolean
        get() = when (val t = stageTarget) {
            is StageTarget.Base -> groupById(t.groupId).targets.any { it.address in regenAddrs }
            is StageTarget.Single -> t.address in regenAddrs
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

    /**
     * Low-battery alert for the stage, driven off its reachable packs' real telemetry.
     * Mirrors the handoff state machine: the most-severe crossed (and un-acked) threshold
     * flashes; a charging low pack suppresses it; acks only count while still crossed.
     */
    fun stageAlert(): StageAlert {
        val none = StageAlert(false, false, 100, null, emptySet())
        if (!monitoring) return none
        val packs = stageTarget.addresses()
            .mapNotNull { fleet[it]?.takeIf { s -> s.reachable }?.telemetry }
        val lowPack = packs.minByOrNull { it.soc } ?: return none
        val lowSoc = lowPack.soc
        val lowCharging = lowPack.state == BatteryState.Charging
        val crossed = enabledThresholds.filter { lowSoc < it }.toSet()
        val activeThreshold = crossed.minOrNull()
        val ackEffective = acknowledgedThresholds.intersect(crossed)
        val flashing = alertsOn && !lowCharging &&
            activeThreshold != null && activeThreshold !in ackEffective
        val critical = activeThreshold != null && activeThreshold <= 15
        return StageAlert(flashing, critical, lowSoc.roundToInt(), activeThreshold, ackEffective)
    }
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
                    stageHoldMinutes = p.stageHoldMinutes ?: s.stageHoldMinutes,
                    logging = p.logging,
                    alertsOn = p.alertsOn,
                    enabledThresholds = p.enabledThresholds ?: s.enabledThresholds,
                    keepScreenOn = p.keepScreenOn,
                    sortKey = p.sortKey?.let { runCatching { SortKey.valueOf(it) }.getOrNull() } ?: s.sortKey,
                    filters = p.filters?.mapNotNull { runCatching { FilterKey.valueOf(it) }.getOrNull() }?.toSet()
                        ?: s.filters,
                    stageTarget = StageTarget.Base(dd.id),
                    filterBaseId = p.filterBaseId ?: dd.id,
                    demo = demoFor(dd),
                    // Seed the fleet with the last-known reading per battery (dimmed/not reachable
                    // until the first live connect), so All Batteries isn't blank on launch.
                    fleet = p.lastTelemetry.mapValues { (_, tel) -> BatteryStatus(telemetry = tel, reachable = false) },
                )
            }
            // Resume monitoring if it was on before the app was killed/restarted.
            if (p.monitoring && hasBlePermissions(getApplication())) startMonitoring()
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

    // --- all-batteries page controls (persisted) ---
    fun setSort(s: SortKey) {
        _state.update { it.copy(sortKey = s) }
        viewModelScope.launch { store.setSort(s.name) }
    }
    fun toggleFilter(f: FilterKey) {
        _state.update { it.copy(filters = if (f in it.filters) it.filters - f else it.filters + f) }
        viewModelScope.launch { store.setFilters(_state.value.filters.map { it.name }.toSet()) }
    }
    fun setFilterBase(id: String) {
        _state.update { it.copy(filterBaseId = id) }
        viewModelScope.launch { store.setFilterBase(id) }
    }

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
    fun setStageHold(minutes: Int) {
        _state.update { it.copy(stageHoldMinutes = minutes) }
        viewModelScope.launch { store.setStageHold(minutes) }
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
        // Keep last-known readings (marked not-reachable) so rows show stale data until live samples.
        _state.update { it.copy(monitoring = true, fleet = it.fleet.mapValues { (_, st) -> st.copy(reachable = false) }) }
        repository.start(
            scope = viewModelScope,
            targets = ALL_GROUPS.flatMap { it.targets },
            onTelemetry = { addr, t -> onTelemetry(addr, t) },
            onReachable = { addr, r -> onReachable(addr, r) },
        )
        repository.setDisabled(_state.value.disabled)
        repository.setStage(currentStageAddrs())
        viewModelScope.launch { store.setMonitoring(true) }
    }

    fun stopMonitoring() {
        persistLastTelemetry()  // keep the latest readings for next launch
        repository.stop()
        _state.update { it.copy(monitoring = false, fleet = emptyMap(), demo = demoFor(it.dailyDriver)) }
        viewModelScope.launch { store.setMonitoring(false) }
    }

    fun toggleMonitoring() = if (_state.value.monitoring) stopMonitoring() else startMonitoring()

    fun onAppForeground() {
        if (_state.value.monitoring) repository.kickAll()
    }

    private fun onTelemetry(addr: String, t: Telemetry) {
        val now = clockMs()
        val group = groupForAddress(addr)
        val regen = isRegen(t, group?.let { _state.value.lastDischargeAt[it.id] }, now)
        _state.update { st ->
            st.copy(
                fleet = st.fleet + (addr to (st.fleet[addr] ?: BatteryStatus()).copy(telemetry = t, reachable = true)),
                regenAddrs = if (regen) st.regenAddrs + addr else st.regenAddrs - addr,
            )
        }
        if (_state.value.logging) {
            logger.log(addr, t, now, regen)
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
        if (now - lastTeleSaveAt > TELE_SAVE_INTERVAL_MS) {
            lastTeleSaveAt = now
            persistLastTelemetry()
        }
    }

    private var lastTeleSaveAt = 0L

    /** Snapshot the current per-battery readings to disk for restore on the next launch. */
    private fun persistLastTelemetry() {
        val snapshot = _state.value.fleet
            .filterValues { it.telemetry != null }
            .mapValues { it.value.telemetry!! }
        if (snapshot.isEmpty()) return
        viewModelScope.launch { store.setLastTelemetry(snapshot) }
    }

    /** App backgrounded: flush the latest readings so they survive a process kill. */
    fun onAppBackground() = persistLastTelemetry()

    // --- usage logging (to calibrate the power ring) ---
    fun setLogging(enabled: Boolean) {
        _state.update {
            if (enabled) it.copy(logging = true, peakPowerW = 0f, peakCurrentA = 0f) else it.copy(logging = false)
        }
        viewModelScope.launch { store.setLogging(enabled) }
    }

    fun clearLog() {
        logger.clear()
        _state.update { it.copy(peakPowerW = 0f, peakCurrentA = 0f) }
    }

    // --- low-battery alerts ---
    fun setAlertsOn(enabled: Boolean) {
        _state.update { it.copy(alertsOn = enabled) }
        viewModelScope.launch { store.setAlertsOn(enabled) }
    }
    fun toggleThreshold(t: Int) {
        _state.update {
            val next = if (t in it.enabledThresholds) it.enabledThresholds - t else it.enabledThresholds + t
            it.copy(enabledThresholds = next)
        }
        viewModelScope.launch { store.setThresholds(_state.value.enabledThresholds) }
    }
    fun setKeepScreenOn(enabled: Boolean) {
        _state.update { it.copy(keepScreenOn = enabled) }
        viewModelScope.launch { store.setKeepScreenOn(enabled) }
    }

    /** Acknowledge the active alert: silence it until SOC drops past the next enabled level. */
    fun acknowledgeAlert() = _state.update { s ->
        val a = s.stageAlert()
        if (a.activeThreshold == null) s
        else s.copy(acknowledgedThresholds = a.ackEffective + a.activeThreshold)
    }

    private fun onReachable(addr: String, r: Boolean) {
        _state.update { st ->
            st.copy(
                fleet = st.fleet + (addr to (st.fleet[addr] ?: BatteryStatus()).copy(reachable = r)),
                regenAddrs = if (r) st.regenAddrs else st.regenAddrs - addr,
            )
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
                    st.manualPinnedAt, newLast, st.stageHoldMinutes * 60_000L, st.stageTarget, now),
            )
            val isPinned = st.manualStage != null && resolved == st.manualStage &&
                (!st.dynamicStage || now - st.manualPinnedAt < PIN_HOLD_MS)
            st.copy(lastDischargeAt = newLast, stageTarget = resolved, pinned = isPinned)
        }
        val after = currentStageAddrs()
        if (after != before && _state.value.monitoring) repository.setStage(after)
    }

    override fun onCleared() {
        persistLastTelemetry()
        repository.stop()
        super.onCleared()
    }
}
