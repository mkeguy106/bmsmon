package dev.joely.bmsmon

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.joely.bmsmon.ble.hasBlePermissions
import dev.joely.bmsmon.monitor.MonitoringService
import dev.joely.bmsmon.sensor.AmbientLightSensor
import dev.joely.bmsmon.data.SettingsStore
import dev.joely.bmsmon.model.BatteryGroup
import dev.joely.bmsmon.model.BatteryState
import dev.joely.bmsmon.model.BatteryStatus
import dev.joely.bmsmon.model.DEFAULT_GROUP_ID
import dev.joely.bmsmon.model.DEFAULT_ROSTER
import dev.joely.bmsmon.model.DEFAULT_STAGE_HOLD_MIN
import dev.joely.bmsmon.model.GroupActivity
import dev.joely.bmsmon.model.PIN_HOLD_MS
import dev.joely.bmsmon.model.Roster
import dev.joely.bmsmon.model.StageInputs
import dev.joely.bmsmon.model.StageItem
import dev.joely.bmsmon.model.StageTarget
import dev.joely.bmsmon.model.Telemetry
import dev.joely.bmsmon.model.addBattery
import dev.joely.bmsmon.model.addGroup
import dev.joely.bmsmon.model.addresses
import dev.joely.bmsmon.model.allTargets
import dev.joely.bmsmon.model.assignGroup
import dev.joely.bmsmon.model.batteryAt
import dev.joely.bmsmon.model.demoFor
import dev.joely.bmsmon.model.groupActivity
import dev.joely.bmsmon.model.groupById
import dev.joely.bmsmon.model.groupViews
import dev.joely.bmsmon.model.removeBattery
import dev.joely.bmsmon.model.renameBattery
import dev.joely.bmsmon.model.renameGroup
import dev.joely.bmsmon.model.resolveStage
import dev.joely.bmsmon.model.targetFor
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

enum class Screen { Home, Settings, Detail }
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
    val mode: Mode = Mode.Dark,                 // effective (rendered) theme
    val appearance: Appearance = Appearance.System,
    val autoLuxThreshold: Float = DEFAULT_AUTO_LUX,
    val currentLux: Float? = null,
    val hasLightSensor: Boolean = true,
    val accent: Color = DefaultAccent,
    val power: Color = DefaultPower,
    val monitoring: Boolean = false,
    val dailyDriverId: String = DEFAULT_GROUP_ID,
    val roster: Roster = DEFAULT_ROSTER,
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
    val demo: List<Telemetry> = demoFor(DEFAULT_ROSTER.groupById(DEFAULT_GROUP_ID)
        ?: BatteryGroup(DEFAULT_GROUP_ID, DEFAULT_GROUP_ID, emptyList())),
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
    val tempFahrenheit: Boolean = true,
    val detailAddress: String? = null,
    // Which Home pager page is showing (0 = stage, 1 = all batteries). Remembered so the detail
    // screen's back button returns to the page you came from.
    val homePage: Int = 0,
    val locked: Boolean = false,
) {
    val isDark get() = mode == Mode.Dark
    val dailyDriver: BatteryGroup
        get() = roster.groupById(dailyDriverId) ?: BatteryGroup(dailyDriverId, dailyDriverId, emptyList())
    val stageGroupId: String? get() = (stageTarget as? StageTarget.Base)?.groupId

    /** The 1–2 stage packs with their regen flags (last-known when monitoring, demo otherwise). */
    fun stageItems(): List<StageItem> {
        if (!monitoring) return demo.map { StageItem(it, false) }
        val targets = when (val t = stageTarget) {
            is StageTarget.Base -> roster.groupById(t.groupId)?.targets ?: emptyList()
            is StageTarget.Single -> roster.targetFor(t.address)?.let { listOf(it) } ?: emptyList()
        }
        return targets.map { tg ->
            val status = fleet[tg.address]
            // Connected only when the pack is reachable AND has actually reported telemetry.
            // A staged pack that drops BLE (or never connected) shows DISCONNECTED, not 0%.
            val connected = status?.reachable == true && status.telemetry != null
            val tel = status?.telemetry?.copy(name = tg.name)
                ?: Telemetry(tg.name, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
            StageItem(tel, regen = connected && tg.address in regenAddrs, connected = connected)
        }
    }

    /** True when any pack on the stage is currently dumping regen current. */
    val stageRegen: Boolean
        get() = when (val t = stageTarget) {
            is StageTarget.Base -> roster.groupById(t.groupId)?.targets?.any { it.address in regenAddrs } ?: false
            is StageTarget.Single -> t.address in regenAddrs
        }

    val stageLabel: String
        get() = when (val t = stageTarget) {
            is StageTarget.Base -> roster.groupById(t.groupId)?.label ?: t.groupId
            is StageTarget.Single -> roster.batteryAt(t.address)?.alias ?: t.address
        }

    val stageActivity: GroupActivity
        get() = (stageTarget as? StageTarget.Base)?.let { roster.groupById(it.groupId)?.let { g -> groupActivity(g, fleet) } }
            ?: GroupActivity.Unknown

    /**
     * Low-battery alert for the stage, driven off its reachable packs' real telemetry.
     * Mirrors the handoff state machine: the most-severe crossed (and un-acked) threshold
     * flashes; a charging low pack suppresses it; acks only count while still crossed.
     */
    fun stageAlert(): StageAlert {
        val none = StageAlert(false, false, 100, null, emptySet())
        if (!monitoring) return none
        val packs = stageTarget.addresses(roster)
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

    // Monitoring (BLE + logging) runs in the process-lifetime engine, kept alive in the background
    // by MonitoringService — not in viewModelScope, so it survives this ViewModel being cleared.
    private val engine = (app as BmsApp).engine
    private val store = SettingsStore(app)
    private val lightSensor = AmbientLightSensor(app)
    private var foreground = true
    private var lastSystemDark = true
    private var autoCandidate: Mode? = null
    private var autoCandidateSince = 0L

    private val _state = MutableStateFlow(UiState(logPath = engine.logPath))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private fun clockMs() = System.currentTimeMillis()

    init {
        viewModelScope.launch {
            val p = store.load()
            _state.update { s ->
                val roster = p.roster ?: DEFAULT_ROSTER
                val dd = roster.groupById(p.dailyDriverId ?: s.dailyDriverId)
                    ?: roster.groupViews().firstOrNull()
                    ?: BatteryGroup(DEFAULT_GROUP_ID, DEFAULT_GROUP_ID, emptyList())
                val appearance = p.appearance?.let { runCatching { Appearance.valueOf(it) }.getOrNull() }
                    ?: legacyAppearance(p.manualMode, p.darkMode)
                val resolvedAppearance =
                    if (appearance == Appearance.Auto && !lightSensor.hasSensor()) Appearance.System else appearance
                val effectiveMode = when (resolvedAppearance) {
                    Appearance.Dark -> Mode.Dark
                    Appearance.Light -> Mode.Light
                    Appearance.System, Appearance.Auto -> s.mode  // corrected once system/sensor reports
                }
                s.copy(
                    accent = p.accentArgb?.let { Color(it) } ?: s.accent,
                    power = p.powerArgb?.let { Color(it) } ?: s.power,
                    roster = roster,
                    appearance = resolvedAppearance,
                    autoLuxThreshold = p.autoLuxThreshold ?: s.autoLuxThreshold,
                    hasLightSensor = lightSensor.hasSensor(),
                    mode = effectiveMode,
                    dailyDriverId = dd.id,
                    dynamicStage = p.dynamicStage ?: s.dynamicStage,
                    stageHoldMinutes = p.stageHoldMinutes ?: s.stageHoldMinutes,
                    logging = p.logging,
                    alertsOn = p.alertsOn,
                    enabledThresholds = p.enabledThresholds ?: s.enabledThresholds,
                    keepScreenOn = p.keepScreenOn,
                    tempFahrenheit = p.tempFahrenheit,
                    locked = p.locked,
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
            updateSensor()
        }
        // Mirror the engine's BLE-derived state into the UI while we're alive, and re-resolve the
        // stage off each update. When monitoring stops, fall back to the demo. (The seed fleet set
        // above survives because the off-branch only clears it on the on->off edge.)
        viewModelScope.launch {
            engine.state.collect { es ->
                if (es.monitoring) {
                    _state.update {
                        it.copy(
                            monitoring = true,
                            fleet = es.fleet,
                            regenAddrs = es.regenAddrs,
                            lastDischargeAt = es.lastDischargeAt,
                            peakPowerW = es.peakPowerW,
                            peakCurrentA = es.peakCurrentA,
                        )
                    }
                    refresh()
                    val now = clockMs()
                    if (now - lastTeleSaveAt > TELE_SAVE_INTERVAL_MS) {
                        lastTeleSaveAt = now
                        persistLastTelemetry()
                    }
                } else {
                    _state.update {
                        if (it.monitoring) {
                            it.copy(monitoring = false, fleet = emptyMap(), demo = demoFor(it.dailyDriver))
                        } else {
                            it
                        }
                    }
                }
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

    /** User picked an explicit appearance (Dark/Light/System/Auto). */
    fun setAppearance(a: Appearance) {
        _state.update { it.copy(appearance = a) }
        viewModelScope.launch { store.setAppearance(a.name) }
        recomputeEffectiveMode()
        updateSensor()
    }

    /** Main-stage quick control: advance Dark → Light → System → Auto (skipping Auto if no sensor). */
    fun cycleAppearance() {
        val s = _state.value
        var next = when (s.appearance) {
            Appearance.Dark -> Appearance.Light
            Appearance.Light -> Appearance.System
            Appearance.System -> Appearance.Auto
            Appearance.Auto -> Appearance.Dark
        }
        if (next == Appearance.Auto && !s.hasLightSensor) next = Appearance.Dark
        setAppearance(next)
    }

    /** OS theme signal (from Compose). Only takes effect while appearance == System. */
    fun applySystemMode(dark: Boolean) {
        lastSystemDark = dark
        _state.update {
            if (it.appearance == Appearance.System) it.copy(mode = if (dark) Mode.Dark else Mode.Light) else it
        }
    }

    fun setAutoLuxThreshold(lux: Float) {
        _state.update { it.copy(autoLuxThreshold = lux) }
        viewModelScope.launch { store.setAutoLuxThreshold(lux) }
        if (_state.value.appearance == Appearance.Auto) recomputeEffectiveMode()
    }

    /** Recompute the effective [Mode] from the current appearance (no debounce — immediate). */
    private fun recomputeEffectiveMode() = _state.update { s ->
        val m = when (s.appearance) {
            Appearance.Dark -> Mode.Dark
            Appearance.Light -> Mode.Light
            Appearance.System -> if (lastSystemDark) Mode.Dark else Mode.Light
            Appearance.Auto -> s.currentLux?.let { resolveAutoMode(it, s.autoLuxThreshold, s.mode) } ?: s.mode
        }
        s.copy(mode = m)
    }

    /** Start the light sensor only while Auto is selected and the app is foregrounded. */
    private fun updateSensor() {
        val s = _state.value
        if (s.appearance == Appearance.Auto && foreground && s.hasLightSensor) {
            lightSensor.start(::onLux)
        } else {
            lightSensor.stop()
            autoCandidate = null
        }
    }

    /** Each lux sample: update the readout, then flip the theme through hysteresis + debounce. */
    private fun onLux(lux: Float) {
        val now = clockMs()
        _state.update { it.copy(currentLux = lux) }
        val s = _state.value
        if (s.appearance != Appearance.Auto) return
        val candidate = resolveAutoMode(lux, s.autoLuxThreshold, s.mode)
        if (candidate == s.mode) { autoCandidate = null; return }
        if (autoCandidate != candidate) { autoCandidate = candidate; autoCandidateSince = now }
        if (debouncedMode(s.mode, candidate, autoCandidateSince, now) != s.mode) {
            autoCandidate = null
            _state.update { it.copy(mode = candidate) }
        }
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
        val g = _state.value.roster.groupById(id) ?: return
        _state.update {
            if (it.monitoring) it.copy(dailyDriverId = g.id)
            else it.copy(dailyDriverId = g.id, stageTarget = StageTarget.Base(g.id), demo = demoFor(g), filterBaseId = g.id)
        }
        viewModelScope.launch { store.setDailyDriver(g.id) }
        refresh()
    }

    // --- roster editing ---
    private fun updateRoster(transform: (Roster) -> Roster) {
        _state.update { it.copy(roster = transform(it.roster)) }
        val r = _state.value.roster
        viewModelScope.launch { store.setRoster(r) }
        if (_state.value.monitoring) engine.setRoster(r)
        refresh()
    }

    fun addBattery(address: String, advertisedName: String) =
        updateRoster { it.addBattery(address, advertisedName) }

    fun removeBattery(address: String) {
        val a = address.uppercase()
        _state.update { st ->
            val ms = st.manualStage
            val newManualStage = if (ms is StageTarget.Single && ms.address.uppercase() == a) null else ms
            val tgt = st.stageTarget
            val newStageTarget = if (tgt is StageTarget.Single && tgt.address.uppercase() == a)
                StageTarget.Base(st.dailyDriverId) else tgt
            st.copy(
                disabled = st.disabled - a,
                fleet = st.fleet - a,
                manualStage = newManualStage,
                stageTarget = newStageTarget,
            )
        }
        engine.setDisabled(_state.value.disabled)
        updateRoster { it.removeBattery(a) }
    }

    fun renameBattery(address: String, alias: String) =
        updateRoster { it.renameBattery(address, alias) }

    fun setBatteryGroup(address: String, groupId: String?) =
        updateRoster { it.assignGroup(address, groupId) }

    fun createGroupForBattery(address: String, name: String) =
        updateRoster {
            val (r, id) = it.addGroup(name)
            r.assignGroup(address, id)
        }

    fun renameGroup(groupId: String, name: String) =
        updateRoster { it.renameGroup(groupId, name) }

    fun setHomePage(page: Int) = _state.update { if (it.homePage == page) it else it.copy(homePage = page) }
    fun openDetail(address: String) = _state.update { it.copy(screen = Screen.Detail, detailAddress = address) }
    fun closeDetail() = _state.update { it.copy(screen = Screen.Home, detailAddress = null) }

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
        engine.setDisabled(_state.value.disabled)
        refresh()
    }
    fun reconnectBattery(address: String) {
        val a = address.uppercase()
        _state.update { it.copy(disabled = it.disabled - a) }
        engine.setDisabled(_state.value.disabled)
        engine.kickAll()
        refresh()
    }
    fun disconnectAll() = stopMonitoring()

    // --- fleet monitoring (delegated to the process-lifetime engine + foreground service) ---
    fun startMonitoring() {
        // Seed the engine with the current roster + last-known readings (shown dimmed until live).
        engine.start(roster = _state.value.roster, seed = _state.value.fleet, loggingEnabled = _state.value.logging)
        engine.setDisabled(_state.value.disabled)
        engine.setStage(currentStageAddrs())
        MonitoringService.start(getApplication())
        viewModelScope.launch { store.setMonitoring(true) }
    }

    fun stopMonitoring() {
        persistLastTelemetry()  // keep the latest readings for next launch
        engine.stop()           // cancels BLE jobs -> each session closes its GATT cleanly
        MonitoringService.stop(getApplication())
        _state.update { it.copy(monitoring = false, fleet = emptyMap(), demo = demoFor(it.dailyDriver)) }
        viewModelScope.launch { store.setMonitoring(false) }
    }

    fun toggleMonitoring() = if (_state.value.monitoring) stopMonitoring() else startMonitoring()

    fun onAppForeground() {
        foreground = true
        updateSensor()
        if (_state.value.monitoring) engine.kickAll()
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
    fun onAppBackground() {
        foreground = false
        updateSensor()
        persistLastTelemetry()
    }

    // --- usage logging (to calibrate the power ring) ---
    fun setLogging(enabled: Boolean) {
        engine.setLogging(enabled)
        _state.update {
            if (enabled) it.copy(logging = true, peakPowerW = 0f, peakCurrentA = 0f) else it.copy(logging = false)
        }
        viewModelScope.launch { store.setLogging(enabled) }
    }

    fun clearLog() {
        engine.clearLog()
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
    fun setTempFahrenheit(enabled: Boolean) {
        _state.update { it.copy(tempFahrenheit = enabled) }
        viewModelScope.launch { store.setTempFahrenheit(enabled) }
    }

    fun setLocked(enabled: Boolean) {
        _state.update { it.copy(locked = enabled) }
        viewModelScope.launch { store.setLocked(enabled) }
    }

    /** Acknowledge the active alert: silence it until SOC drops past the next enabled level. */
    fun acknowledgeAlert() = _state.update { s ->
        val a = s.stageAlert()
        if (a.activeThreshold == null) s
        else s.copy(acknowledgedThresholds = a.ackEffective + a.activeThreshold)
    }

    private fun currentStageAddrs(): Set<String> =
        _state.value.stageTarget.addresses(_state.value.roster) - _state.value.disabled

    /** Re-resolve the stage; if its battery set changed, tell the engine. (lastDischargeAt is
     *  maintained by the engine and mirrored into UiState — we only read it here.) */
    private fun refresh() {
        val now = clockMs()
        val before = currentStageAddrs()
        _state.update { st ->
            // lastDischargeAt is maintained by the engine (mirrored into UiState); pass the roster's
            // current groups so stage resolution works against the dynamic roster.
            val resolved = resolveStage(
                StageInputs(st.fleet, st.dailyDriverId, st.dynamicStage, st.manualStage,
                    st.manualPinnedAt, st.lastDischargeAt, st.stageHoldMinutes * 60_000L, st.stageTarget, now,
                    st.roster.groupViews()),
            )
            val isPinned = st.manualStage != null && resolved == st.manualStage &&
                (!st.dynamicStage || now - st.manualPinnedAt < PIN_HOLD_MS)
            st.copy(stageTarget = resolved, pinned = isPinned)
        }
        val after = currentStageAddrs()
        if (after != before && _state.value.monitoring) engine.setStage(after)
    }

    override fun onCleared() {
        persistLastTelemetry()
        // Do NOT stop the engine: monitoring must keep running in the background (the foreground
        // service keeps the process alive). Clean shutdown happens on explicit stop or task removal.
        lightSensor.stop()
        super.onCleared()
    }
}
