package dev.joely.bmsmon

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.joely.bmsmon.ble.hasBlePermissions
import dev.joely.bmsmon.monitor.MonitoringService
import dev.joely.bmsmon.sensor.AmbientLightSensor
import dev.joely.bmsmon.data.PackHealth
import dev.joely.bmsmon.data.SettingsStore
import dev.joely.bmsmon.data.buildPackHealth
import dev.joely.bmsmon.data.peakPool
import dev.joely.bmsmon.ble.profile.BatteryProfile
import dev.joely.bmsmon.ble.profile.ProfileRegistry
import dev.joely.bmsmon.cloud.CloudJson
import dev.joely.bmsmon.ble.profile.RedodoBekenProfile
import dev.joely.bmsmon.model.AlertConfig
import dev.joely.bmsmon.model.AlertKind
import dev.joely.bmsmon.model.BatteryGroup
import dev.joely.bmsmon.model.BatteryState
import dev.joely.bmsmon.model.GaugeSide
import dev.joely.bmsmon.model.TempRank
import dev.joely.bmsmon.model.TempSide
import dev.joely.bmsmon.model.TempThresholds
import dev.joely.bmsmon.model.TempZone
import dev.joely.bmsmon.model.pickStageAlert
import dev.joely.bmsmon.model.RangeParams
import dev.joely.bmsmon.model.CAP_SEVERITY_CRITICAL
import dev.joely.bmsmon.model.CAP_SEVERITY_WARNING
import dev.joely.bmsmon.model.SEVERITY_NONE
import dev.joely.bmsmon.model.nextChargeHold
import dev.joely.bmsmon.model.tempSeverity
import dev.joely.bmsmon.model.TempUnit
import dev.joely.bmsmon.model.formatDelta
import dev.joely.bmsmon.model.tempMarginToCutoffC
import dev.joely.bmsmon.model.tempZone
import dev.joely.bmsmon.model.BatteryStatus
import dev.joely.bmsmon.model.DEFAULT_GROUP_ID
import dev.joely.bmsmon.model.DEFAULT_ROSTER
import dev.joely.bmsmon.model.DEFAULT_STAGE_HOLD_MIN
import dev.joely.bmsmon.model.GroupActivity
import dev.joely.bmsmon.model.PIN_HOLD_MS
import dev.joely.bmsmon.model.PackSoc
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
import dev.joely.bmsmon.model.evalStageAlert
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

enum class Screen { Home, Settings, Detail, History, Review, Timeline }
enum class Mode { Dark, Light }
enum class SortKey { Activity, Soc, Base }
enum class FilterKey { ReachableOnly, ActiveOnly, ByBase, DailyDriverOnly }

/** Every selectable low-battery alert level, most severe last (full 5% ladder). */
val ALERT_THRESHOLDS =
    listOf(95, 90, 85, 80, 75, 70, 65, 60, 55, 50, 45, 40, 35, 30, 25, 20, 15, 10, 5)

/** Levels enabled on a fresh install (the original low set; high marks default OFF). */
val DEFAULT_THRESHOLDS = listOf(30, 25, 20, 15, 10, 5)

/** Default critical tier (red / fast pulse). User-configurable. */
const val DEFAULT_CRITICAL_THRESHOLD = 15

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
    val kind: AlertKind = AlertKind.CAPACITY,
    val headline: String = "BATTERY CAPACITY",
    val detail: String = "",
    val tempAckKey: String? = null,
    val tempActive: Boolean = false,
    /** A condition is present (flashing OR acknowledged). Drives the persistent ack'd strip. */
    val present: Boolean = false,
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
    val sortKey: SortKey = SortKey.Activity,
    val filters: Set<FilterKey> = emptySet(),
    val filterBaseId: String = DEFAULT_GROUP_ID,
    val logging: Boolean = false,
    val peakPowerW: Float = 0f,
    val peakCurrentA: Float = 0f,
    val dbSize: String = "",
    // low-battery alerts
    val alertsOn: Boolean = true,
    val enabledThresholds: Set<Int> = DEFAULT_THRESHOLDS.toSet(),
    val acknowledgedThresholds: Set<Int> = emptySet(),
    val criticalThreshold: Int = DEFAULT_CRITICAL_THRESHOLD,
    // When on (and alerts on), any reachable pack at/below the highest enabled threshold seizes the
    // main stage — over the active chair and a manual pin — so a too-low pack can't hide off-stage.
    val seizeLowToStage: Boolean = true,
    // temperature alerts (per-profile thresholds; unit reuses tempFahrenheit below)
    val tempAlertsEnabled: Boolean = true,
    val tempThresholdsByProfile: Map<String, TempThresholds> = emptyMap(),
    val acknowledgedTempKeys: Set<String> = emptySet(),
    // Charging-suppression hysteresis (UI-9): when the stage's lowest pack last read Charging,
    // and whether the latched hold is active (advanced in refresh() via withChargeHold).
    val stageChargeLastAt: Long = 0,
    val stageChargeHold: Boolean = false,
    val showTempGauge: Boolean = true,
    val tempGaugeSide: GaugeSide = GaugeSide.LEFT,
    val cloudSyncAlerts: Boolean = true,
    val keepScreenOn: Boolean = true,
    val tempFahrenheit: Boolean = true,
    val detailAddress: String? = null,
    val reviewAddress: String? = null,     // pack open in the Health & Usage Review screen
    val timelineSession: Long? = null,     // session open in the Session timeline drill-down
    // Which Home pager page is showing (0 = stage, 1 = all batteries). Remembered so the detail
    // screen's back button returns to the page you came from.
    val homePage: Int = 0,
    val locked: Boolean = false,
    val csvImported: Boolean = false,
    // Which system-info items to replicate at the top while locked (screen pinning hides the real
    // status bar). Per-item, all on by default.
    val lockShowTime: Boolean = true,
    val lockShowWifi: Boolean = true,
    val lockShowBattery: Boolean = true,
    val cloudEnabled: Boolean = false,
    val apiBaseUrl: String? = null,
    val enrolled: Boolean = false,
    val gpsEnabled: Boolean = false,
    val cloudOutboxDepth: Int = 0,
    val cloudLastUploadMs: Long = 0,
    val cloudUploadKbps: Float = 0f,
    val cloudAuthFailed: Boolean = false,
    val importDone: Boolean = false,
    val importTotal: Int = 0,
    val importSent: Int = 0,
) {
    val isDark get() = mode == Mode.Dark
    val dailyDriver: BatteryGroup
        get() = roster.groupById(dailyDriverId) ?: BatteryGroup(dailyDriverId, dailyDriverId, emptyList())
    val stageGroupId: String? get() = (stageTarget as? StageTarget.Base)?.groupId

    /** SOC at/below which a reachable pack seizes the stage on the phone, or null when disabled
     *  (alerts off, the seize toggle off, or no thresholds enabled). */
    val seizeThreshold: Int? get() =
        if (alertsOn && seizeLowToStage && enabledThresholds.isNotEmpty()) enabledThresholds.max() else null

    /** Highest enabled capacity threshold, pushed to the cloud so the WebUI drives its own low-pack
     *  stage seize (defaults to 30 when the ladder is empty, matching the web fallback). */
    val cloudSeizeSoc: Int get() = enabledThresholds.maxOrNull() ?: 30

    /** App-wide temperature unit (reuses the existing °C/°F preference). */
    val tempUnit: TempUnit get() = if (tempFahrenheit) TempUnit.F else TempUnit.C

    /** Battery profile backing the current stage (Redodo today); carries the temp envelope/defaults. */
    fun stageProfile(): BatteryProfile {
        val name = stageTarget.addresses(roster).firstOrNull()?.let { roster.batteryAt(it)?.advertisedName }
        return ProfileRegistry.profileFor(name) ?: RedodoBekenProfile
    }

    /** Resolved temperature thresholds for [profileId] — stored overrides, else the profile defaults. */
    fun tempThresholdsFor(profileId: String): TempThresholds =
        tempThresholdsByProfile[profileId]
            ?: (ProfileRegistry.all.firstOrNull { it.id == profileId } ?: RedodoBekenProfile)
                .tempEnvelope.defaults

    /** The 1–2 stage packs with their regen flags. A pack without live data reads DISCONNECTED. */
    fun stageItems(): List<StageItem> {
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
            val regenFlag = connected && tg.address in regenAddrs
            // The ETA is computed by the engine (once per poll — the exact value that is uploaded)
            // and carried on BatteryStatus; the stage only displays it.
            val eta = if (connected) status?.etaFullMin else null
            val range = if (connected) status?.range else null
            StageItem(tel, regen = regenFlag, connected = connected, etaFullMin = eta, range = range)
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
        val packs = stagePacks()
        if (packs.isEmpty()) return none

        // --- capacity (existing low-battery behavior) ---
        val capEval = evalStageAlert(
            packs.map { PackSoc(it.second.soc, it.second.state == BatteryState.Charging) },
            AlertConfig(alertsOn, enabledThresholds, criticalThreshold),
        )
        // Suppressed while charging OR within the latched hold after charging (UI-9) — the
        // Idle/Charging flap at the charger must not strobe the overlay.
        val suppressed = capEval.charging || stageChargeHold
        val capAck = acknowledgedThresholds.intersect(capEval.crossed)
        val capFlashing = !suppressed &&
            capEval.activeThreshold != null && capEval.activeThreshold !in capAck
        val capSeverity = when {
            capEval.activeThreshold == null || suppressed -> SEVERITY_NONE
            capEval.critical -> CAP_SEVERITY_CRITICAL
            else -> CAP_SEVERITY_WARNING
        }

        // --- temperature (worst reachable stage pack; flashes only at rank >= CRITICAL) ---
        val worst = stageWorstTemp()
        val tempRank = worst?.third?.rank ?: TempRank.SAFE
        val tempPresent = tempAlertsEnabled && tempRank.ordinal >= TempRank.CRITICAL.ordinal
        val tempSide = if (worst?.third?.side == TempSide.COLD) "COLD" else "HOT"
        val tempRankStr = if (tempRank == TempRank.CUTOFF) "CUTOFF" else "CRITICAL"
        val tempAckKey = "temp:$tempSide:$tempRankStr"
        val tempFlashing = tempPresent && tempAckKey !in acknowledgedTempKeys

        // Worst *un-acked* alert wins (a flashing alert beats an acked one, so an acked temp
        // CRITICAL can't suppress an un-acked capacity flash); ties go to temperature.
        val pick = pickStageAlert(
            capSeverity, capFlashing,
            if (tempPresent) tempSeverity(tempRank) else SEVERITY_NONE, tempFlashing,
        )
        if (pick == AlertKind.TEMPERATURE && worst != null) {
            val (addr, tel, zone) = worst
            val name = roster.batteryAt(addr)?.alias ?: tel.name
            val detail = if (zone.rank == TempRank.CUTOFF) {
                "$tempRankStr · $tempSide · $name · LOAD DISCONNECTED"
            } else {
                val margin = tempMarginToCutoffC(tel.temp, zone.side, stageProfile().tempEnvelope)
                "$tempRankStr · $tempSide · $name · ${formatDelta(margin, tempUnit)} TO CUTOFF"
            }
            return StageAlert(
                flashing = tempFlashing, critical = true,
                lowSoc = tel.soc.roundToInt(), activeThreshold = null, ackEffective = emptySet(),
                kind = AlertKind.TEMPERATURE, headline = "TEMPERATURE", detail = detail,
                tempAckKey = tempAckKey, tempActive = true, present = true,
            )
        }

        return StageAlert(
            flashing = capFlashing, critical = capEval.critical, lowSoc = capEval.lowSoc,
            activeThreshold = capEval.activeThreshold, ackEffective = capAck,
            kind = AlertKind.CAPACITY, headline = "BATTERY CAPACITY",
            detail = "LOW BATTERY · ${capEval.lowSoc}% · BELOW ${capEval.activeThreshold ?: 0}%",
            present = capEval.activeThreshold != null && !suppressed,
        )
    }

    /**
     * Reachable stage packs that have reported telemetry, with their addresses.
     *
     * The `reachable`-only filter is also what excludes *disabled* packs from alerting (UI-8):
     * `engine.setDisabled` marks a disconnected pack unreachable synchronously in MonitorState
     * (see `applyDisabled`), so a user-disconnected stage pack can never drive an alert.
     * Deliberate consequence (accepted, documented): an UNREACHABLE low pack raises no alert at
     * all — the DISCONNECTED stage rendering is the only signal. We alert on data, not absence.
     */
    private fun stagePacks(): List<Pair<String, Telemetry>> =
        stageTarget.addresses(roster)
            .mapNotNull { a -> fleet[a]?.takeIf { it.reachable }?.telemetry?.let { a to it } }

    /** Advance the charging-suppression latch (UI-9) off the stage's lowest reachable pack. */
    fun withChargeHold(now: Long): UiState {
        val lowest = stagePacks().minByOrNull { it.second.soc }?.second
        val hold = nextChargeHold(
            charging = lowest?.state == BatteryState.Charging,
            discharging = lowest?.state == BatteryState.Discharging,
            lastChargingAt = stageChargeLastAt, now = now,
        )
        return if (hold.lastChargingAt == stageChargeLastAt && hold.holdActive == stageChargeHold) this
        else copy(stageChargeLastAt = hold.lastChargingAt, stageChargeHold = hold.holdActive)
    }

    /** Worst temperature zone among the reachable stage packs (null when none are reporting). */
    fun stageWorstTemp(): Triple<String, Telemetry, TempZone>? {
        val profile = stageProfile()
        val thr = tempThresholdsFor(profile.id)
        return stagePacks()
            .map { (addr, t) -> Triple(addr, t, tempZone(t.temp, thr, profile.tempEnvelope)) }
            .maxByOrNull { it.third.rank.ordinal }
    }

    /**
     * Re-arm temperature acks: once the stage's worst reachable pack recovers below CRITICAL, any
     * acknowledged temp keys are cleared so the same condition flashes again if it recurs later
     * (mirrors the headless AlertNotifier, which resets its dedup key when the rank drops below
     * CRITICAL). Requires a live reading — a transient BLE dropout does not clear acks.
     */
    fun withTempAcksPruned(): UiState {
        if (acknowledgedTempKeys.isEmpty()) return this
        val worst = stageWorstTemp() ?: return this
        return if (worst.third.rank.ordinal < TempRank.CRITICAL.ordinal)
            copy(acknowledgedTempKeys = emptySet()) else this
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

    // Last range-params map pushed to the cloud (updatedMs stripped) — dedups the config enqueue
    // (the engine re-learns every 6 h; identical results must not re-POST just because the
    // timestamp moved).
    private var lastPushedRangeParams: Map<String, RangeParams> = emptyMap()

    private val _state = MutableStateFlow(UiState())
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
                    csvImported = p.csvImported,
                    alertsOn = p.alertsOn,
                    enabledThresholds = p.enabledThresholds ?: s.enabledThresholds,
                    criticalThreshold = p.criticalThreshold ?: s.criticalThreshold,
                    seizeLowToStage = p.seizeLowToStage,
                    keepScreenOn = p.keepScreenOn,
                    tempFahrenheit = p.tempFahrenheit,
                    tempAlertsEnabled = p.tempAlertsEnabled,
                    tempThresholdsByProfile = p.tempThresholdsByProfile,
                    showTempGauge = p.showTempGauge,
                    tempGaugeSide = runCatching { GaugeSide.valueOf(p.tempGaugeSide ?: "LEFT") }
                        .getOrDefault(GaugeSide.LEFT),
                    cloudSyncAlerts = p.cloudSyncAlerts,
                    locked = p.locked,
                    lockShowTime = p.lockShowTime,
                    lockShowWifi = p.lockShowWifi,
                    lockShowBattery = p.lockShowBattery,
                    // Per-battery disconnects persist across restarts.
                    disabled = p.disabledAddrs ?: emptySet(),
                    cloudEnabled = p.cloudEnabled,
                    gpsEnabled = p.gpsEnabled ?: p.cloudEnabled,
                    apiBaseUrl = p.apiBaseUrl,
                    enrolled = p.enrolled,
                    importDone = p.importDone,
                    sortKey = p.sortKey?.let { runCatching { SortKey.valueOf(it) }.getOrNull() } ?: s.sortKey,
                    filters = p.filters?.mapNotNull { runCatching { FilterKey.valueOf(it) }.getOrNull() }?.toSet()
                        ?: s.filters,
                    // Restore the *actual* main stage from last run (validated against the current
                    // roster) so launch reconnects to what was on stage, not just the daily driver.
                    stageTarget = p.lastStage?.let { ls ->
                        when (ls) {
                            is StageTarget.Base -> ls.takeIf { roster.groupById(it.groupId) != null }
                            is StageTarget.Single -> ls.takeIf { roster.targetFor(it.address) != null }
                        }
                    } ?: StageTarget.Base(dd.id),
                    filterBaseId = p.filterBaseId ?: dd.id,
                    // Seed the fleet with the last-known reading per battery (dimmed/not reachable
                    // until the first live connect), so All Batteries isn't blank on launch.
                    fleet = p.lastTelemetry.mapValues { (_, tel) -> BatteryStatus(telemetry = tel, reachable = false) },
                )
            }
            // Resume monitoring if it was on before the app was killed/restarted.
            if (p.monitoring && hasBlePermissions(getApplication())) startMonitoring()
            updateSensor()
        }
        // Mirror the engine's state into the UI while we're alive — the engine is the single
        // source of truth for the fleet (reachability included) and the cloud upload status; the
        // VM never mutates the fleet itself. While monitoring is off the engine keeps the
        // last-known fleet marked unreachable, so packs render dimmed as DISCONNECTED (no demo
        // data); on a fresh launch (engine fleet empty) the VM's persisted seed is kept instead.
        viewModelScope.launch {
            engine.state.collect { es ->
                _state.update { s ->
                    val mirrored = s.copy(
                        monitoring = es.monitoring,
                        cloudOutboxDepth = es.cloudOutboxDepth,
                        cloudLastUploadMs = es.cloudLastUploadMs,
                        cloudUploadKbps = es.cloudUploadKbps,
                        cloudAuthFailed = es.cloudAuthFailed,
                    )
                    if (es.monitoring) {
                        mirrored.copy(
                            fleet = es.fleet,
                            regenAddrs = es.regenAddrs,
                            lastDischargeAt = es.lastDischargeAt,
                            peakPowerW = es.peakPowerW,
                            peakCurrentA = es.peakCurrentA,
                        )
                    } else if (es.fleet.isNotEmpty()) {
                        mirrored.copy(fleet = es.fleet, regenAddrs = es.regenAddrs)
                    } else {
                        // Engine has no fleet (fresh process): keep the persisted seed, dimmed.
                        mirrored.copy(fleet = s.fleet.mapValues { (_, st) -> st.copy(reachable = false) })
                    }
                }
                if (es.monitoring) {
                    refresh()
                    val now = clockMs()
                    if (now - lastTeleSaveAt > TELE_SAVE_INTERVAL_MS) {
                        lastTeleSaveAt = now
                        persistLastTelemetry()
                    }
                }
                // Learned range params changed → ride the one-way config push so the WebUI's
                // formula twin uses the same bands (latest-wins per address server-side).
                val strippedNew = es.rangeParamsByAddress.mapValues { it.value.copy(updatedMs = 0L) }
                if (strippedNew.isNotEmpty() && strippedNew != lastPushedRangeParams) {
                    lastPushedRangeParams = strippedNew
                    enqueueTempConfig(_state.value.stageProfile().id)
                }
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

    // --- navigation / theme ---
    fun goSettings() {
        _state.update { it.copy(screen = Screen.Settings) }
        refreshDbSize()
    }
    fun refreshDbSize() = viewModelScope.launch {
        val bytes = engine.history.approxSizeBytes()
        val mb = bytes / (1024f * 1024f)
        _state.update { it.copy(dbSize = "%.1f MB".format(mb)) }
    }
    fun goHome() = _state.update { it.copy(screen = Screen.Home) }
    fun goHistory() = _state.update { it.copy(screen = Screen.History) }

    fun sessionsFor(address: String) = engine.history.sessions(address)
    fun allSessions() = engine.history.allSessions()

    // --- history redesign: navigation + derived-health loaders ---
    fun openReview(address: String) = _state.update { it.copy(screen = Screen.Review, reviewAddress = address) }
    fun closeReview() = _state.update { it.copy(screen = Screen.Detail) }   // back to the live detail
    fun openTimeline(sessionId: Long) = _state.update { it.copy(screen = Screen.Timeline, timelineSession = sessionId) }
    fun closeTimeline() = _state.update { it.copy(screen = Screen.Review) }

    /** Build one pack's full derived health (resistance, V–I cloud, cell Δ, usage) off Room. */
    suspend fun loadPackHealth(address: String): PackHealth {
        val alias = _state.value.roster.batteryAt(address)?.alias ?: address
        val sessions = engine.history.sessions(address).first()
        val samples = engine.history.telemetry(address)
        return buildPackHealth(address, alias, sessions, samples)
    }

    /** Build derived health for every roster pack that has recorded sessions (Group health). */
    suspend fun loadFleetHealth(): List<PackHealth> =
        _state.value.roster.allTargets()
            .map { loadPackHealth(it.address) }
            .filter { it.sessionCount > 0 }

    /** Load one session's rollups + peak-pooled timeline buckets for the drill-down. */
    suspend fun loadTimeline(sessionId: Long): Triple<String, dev.joely.bmsmon.data.db.SessionEntity, List<dev.joely.bmsmon.data.TimelineBucket>>? {
        val session = engine.history.session(sessionId) ?: return null
        val alias = _state.value.roster.batteryAt(session.address)?.alias ?: session.address
        val buckets = peakPool(engine.history.samplesForSession(sessionId))
        return Triple(alias, session, buckets)
    }

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
    /** Restore both the theme accent and the power-ring color to their app defaults. */
    fun resetColors() {
        setAccent(DefaultAccent)
        setPower(DefaultPower)
    }

    fun setDailyDriver(id: String) {
        val g = _state.value.roster.groupById(id) ?: return
        _state.update {
            if (it.monitoring) it.copy(dailyDriverId = g.id)
            else it.copy(dailyDriverId = g.id, stageTarget = StageTarget.Base(g.id), filterBaseId = g.id)
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
        persistDisabled()
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
    /** Persist the current disconnect set so it survives an app restart. */
    private fun persistDisabled() = viewModelScope.launch { store.setDisabled(_state.value.disabled) }

    fun disconnectBattery(address: String) {
        val a = address.uppercase()
        _state.update { it.copy(disabled = it.disabled + a) }
        // The engine synchronously marks the pack unreachable in its state (the single fleet
        // writer) before tearing the worker down; we only mirror the result.
        engine.setDisabled(_state.value.disabled)
        persistDisabled()
        refresh()
    }
    fun reconnectBattery(address: String) {
        val a = address.uppercase()
        _state.update { it.copy(disabled = it.disabled - a) }
        engine.setDisabled(_state.value.disabled)
        persistDisabled()
        engine.kickAll()
        refresh()
    }
    /** Drop the BLE link to every pack (closing each GATT) but keep the engine running, so each
     *  row shows DISCONNECTED with a reconnect icon — distinct from stopping monitoring entirely. */
    fun disconnectAll() {
        val all = _state.value.roster.allTargets().map { it.address.uppercase() }.toSet()
        _state.update { it.copy(disabled = it.disabled + all) }
        // As in disconnectBattery: the engine marks them unreachable, the VM only mirrors.
        engine.setDisabled(_state.value.disabled)
        persistDisabled()
        refresh()
    }

    /** Re-enable every pack the user had disconnected and kick an immediate retry. */
    fun reconnectAll() {
        _state.update { it.copy(disabled = emptySet()) }
        engine.setDisabled(emptySet())
        persistDisabled()
        engine.kickAll()
        refresh()
    }

    // --- fleet monitoring (delegated to the process-lifetime engine + foreground service) ---
    fun startMonitoring() {
        // Seed the engine with the current roster + last-known readings (shown dimmed until live).
        engine.start(roster = _state.value.roster, seed = _state.value.fleet, loggingEnabled = _state.value.logging)
        engine.setDisabled(_state.value.disabled)
        engine.setStage(currentStageAddrs())
        pushAlertConfig()
        pushTempConfig()
        engine.setGpsActive(_state.value.gpsEnabled && _state.value.enrolled && _state.value.cloudEnabled)
        engine.importLegacyCsvIfNeeded(
            alreadyImported = _state.value.csvImported,
            markImported = { store.setCsvImported(true) },
            filesDir = getApplication<Application>().getExternalFilesDir(null),
        )
        MonitoringService.start(getApplication())
        viewModelScope.launch { store.setMonitoring(true) }
    }

    fun stopMonitoring() {
        persistLastTelemetry()  // keep the latest readings for next launch
        // Cancels BLE jobs (each session closes its GATT cleanly) and marks the engine's fleet
        // unreachable — the state mirror renders every pack DISCONNECTED; no VM-side fleet write.
        engine.stop()
        MonitoringService.stop(getApplication())
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

    // --- cloud sync settings ---
    fun setCloudEnabled(on: Boolean) {
        viewModelScope.launch { store.setCloudEnabled(on) }
        _state.update { it.copy(cloudEnabled = on) }
        engine.setGpsActive(_state.value.gpsEnabled && _state.value.enrolled && _state.value.monitoring && on)
        if (on) getApplication<BmsApp>().reporter.start()
    }
    fun setGpsEnabled(on: Boolean) {
        viewModelScope.launch { store.setGpsEnabled(on) }
        _state.update { it.copy(gpsEnabled = on) }
        engine.setGpsActive(on && _state.value.enrolled && _state.value.monitoring && _state.value.cloudEnabled)
    }
    fun setApiBaseUrl(url: String) {
        val base = dev.joely.bmsmon.data.normalizeApiBaseUrl(url)   // https-only (DATA-11)
        viewModelScope.launch { store.setApiBaseUrl(base) }
        _state.update { it.copy(apiBaseUrl = base) }
    }

    fun enroll(baseUrl: String, code: String) {
        // HTTPS-only at the entry point (DATA-11): a manually typed http:// or bare host is
        // normalized before ever being used or persisted.
        val base = dev.joely.bmsmon.data.normalizeApiBaseUrl(baseUrl)
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            dev.joely.bmsmon.cloud.DeviceKeys.ensureKeyPair()
            val installUuid = store.installUuid()
            val res = dev.joely.bmsmon.cloud.EnrollClient(okhttp3.OkHttpClient())
                .enroll(base, code, installUuid, dev.joely.bmsmon.cloud.DeviceKeys.publicKeySpkiB64())
            res.onSuccess { id ->
                store.setApiBaseUrl(base)
                store.setDeviceId(id)
                store.setEnrolled(true)
                store.setCloudEnabled(true)
                store.setGpsEnabled(true)
                _state.update { it.copy(apiBaseUrl = base, enrolled = true, cloudEnabled = true, gpsEnabled = true) }
                val app = getApplication<BmsApp>()
                app.reporter.start()
                app.reporter.startImportIfNeeded(_state.value.roster)
            }
        }
    }

    fun forgetDevice() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            dev.joely.bmsmon.cloud.DeviceKeys.deleteKey()
            store.setEnrolled(false)
            store.setCloudEnabled(false)
            store.setDeviceId("")
            store.setImportDone(false)
            store.setImportWatermark(0)
            store.setGpsEnabled(false)
            _state.update { it.copy(enrolled = false, cloudEnabled = false, gpsEnabled = false) }
            engine.setGpsActive(false)
        }
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
    /** Mirror the current alert settings down to the headless engine so it can notify off-screen. */
    private fun pushAlertConfig() = engine.setAlertConfig(
        AlertConfig(_state.value.alertsOn, _state.value.enabledThresholds, _state.value.criticalThreshold),
    )
    fun setAlertsOn(enabled: Boolean) {
        _state.update { it.copy(alertsOn = enabled) }
        viewModelScope.launch { store.setAlertsOn(enabled) }
        pushAlertConfig()
        enqueueCapacityConfig()
    }
    fun toggleThreshold(t: Int) {
        _state.update {
            val next = if (t in it.enabledThresholds) it.enabledThresholds - t else it.enabledThresholds + t
            it.copy(enabledThresholds = next)
        }
        viewModelScope.launch { store.setThresholds(_state.value.enabledThresholds) }
        pushAlertConfig()
        enqueueCapacityConfig()
    }
    fun setCriticalThreshold(t: Int) {
        // Auto-arm: choosing a critical level also enables that threshold, so it can't silently
        // fail to trigger (the trap where "Critical level" looked like — but wasn't — a trigger).
        _state.update { it.copy(criticalThreshold = t, enabledThresholds = it.enabledThresholds + t) }
        viewModelScope.launch {
            store.setCriticalThreshold(t)
            store.setThresholds(_state.value.enabledThresholds)
        }
        pushAlertConfig()
        enqueueCapacityConfig()
    }
    /** Toggle the low-pack stage seize (phone-side visual override). Alerts still fire fleet-wide
     *  when off; refresh() picks up the change on its next tick (engine update / timer). */
    fun setSeizeLowToStage(enabled: Boolean) {
        _state.update { it.copy(seizeLowToStage = enabled) }
        viewModelScope.launch { store.setSeizeLowToStage(enabled) }
    }
    /** Restore every alert setting (toggle, thresholds, critical level, seize) to its default. */
    fun resetAlertsToDefaults() {
        _state.update {
            it.copy(
                alertsOn = true,
                enabledThresholds = DEFAULT_THRESHOLDS.toSet(),
                criticalThreshold = DEFAULT_CRITICAL_THRESHOLD,
                acknowledgedThresholds = emptySet(),
                seizeLowToStage = true,
            )
        }
        viewModelScope.launch {
            store.setAlertsOn(true)
            store.setThresholds(DEFAULT_THRESHOLDS.toSet())
            store.setCriticalThreshold(DEFAULT_CRITICAL_THRESHOLD)
            store.setSeizeLowToStage(true)
        }
        pushAlertConfig()
        enqueueCapacityConfig()
    }
    fun setKeepScreenOn(enabled: Boolean) {
        _state.update { it.copy(keepScreenOn = enabled) }
        viewModelScope.launch { store.setKeepScreenOn(enabled) }
    }
    fun setTempFahrenheit(enabled: Boolean) {
        _state.update { it.copy(tempFahrenheit = enabled) }
        viewModelScope.launch { store.setTempFahrenheit(enabled) }
        pushTempConfig()   // headless temp notifications format margins in the user's unit
    }
    fun toggleTempUnit() = setTempFahrenheit(!_state.value.tempFahrenheit)

    // --- temperature alerts ---
    /** Mirror temp settings to the headless engine so it can flash/notify off-screen. */
    private fun pushTempConfig() = engine.setTempAlertConfig(
        _state.value.tempAlertsEnabled, _state.value.tempThresholdsByProfile, _state.value.tempUnit,
    )

    fun setTempAlertsEnabled(on: Boolean) {
        _state.update { it.copy(tempAlertsEnabled = on) }
        viewModelScope.launch { store.setTempAlertsEnabled(on) }
        pushTempConfig()
    }
    fun setShowTempGauge(on: Boolean) {
        _state.update { it.copy(showTempGauge = on) }
        viewModelScope.launch { store.setShowTempGauge(on) }
    }
    fun setTempGaugeSide(side: GaugeSide) {
        _state.update { it.copy(tempGaugeSide = side) }
        viewModelScope.launch { store.setTempGaugeSide(side.name) }
    }
    fun setTempThresholds(profileId: String, t: TempThresholds) {
        _state.update { it.copy(tempThresholdsByProfile = it.tempThresholdsByProfile + (profileId to t)) }
        viewModelScope.launch { store.setTempThresholds(_state.value.tempThresholdsByProfile) }
        pushTempConfig()
        enqueueTempConfig(profileId)
    }
    fun resetTempThresholds(profileId: String) {
        val defaults = (ProfileRegistry.all.firstOrNull { it.id == profileId } ?: RedodoBekenProfile)
            .tempEnvelope.defaults
        setTempThresholds(profileId, defaults)
    }
    fun setCloudSyncAlerts(on: Boolean) {
        _state.update { it.copy(cloudSyncAlerts = on) }
        viewModelScope.launch { store.setCloudSyncAlerts(on) }
        if (on) enqueueTempConfig(_state.value.stageProfile().id)
    }

    /** Queue this profile's threshold config for the one-way cloud push (drained by the uploader).
     *  The device-level capacity seize threshold + alerts-on flag ride along so the WebUI can drive
     *  its own low-pack stage seize (latest-wins server-side). */
    private fun enqueueTempConfig(profileId: String) {
        if (!_state.value.cloudSyncAlerts || !_state.value.enrolled) return
        val t = _state.value.tempThresholdsFor(profileId)
        val env = (ProfileRegistry.all.firstOrNull { it.id == profileId } ?: RedodoBekenProfile).tempEnvelope
        val unit = if (_state.value.tempFahrenheit) "F" else "C"
        val seizeSoc = _state.value.cloudSeizeSoc
        val alertsOn = _state.value.alertsOn
        val ranges = engine.state.value.rangeParamsByAddress
        viewModelScope.launch {
            store.setPendingTempConfig(
                CloudJson.encodeTempConfig(profileId, t, env, unit, clockMs(), seizeSoc, alertsOn, ranges),
            )
        }
    }

    /** Push the capacity seize threshold / alerts-on to the cloud after a low-battery-alert change,
     *  reusing the per-profile temp-config channel (temp values unchanged, latest-wins). */
    private fun enqueueCapacityConfig() = enqueueTempConfig(_state.value.stageProfile().id)

    fun setLocked(enabled: Boolean) {
        _state.update { it.copy(locked = enabled) }
        viewModelScope.launch { store.setLocked(enabled) }
    }

    fun setLockShowTime(on: Boolean) {
        _state.update { it.copy(lockShowTime = on) }
        viewModelScope.launch { store.setLockShowTime(on) }
    }
    fun setLockShowWifi(on: Boolean) {
        _state.update { it.copy(lockShowWifi = on) }
        viewModelScope.launch { store.setLockShowWifi(on) }
    }
    fun setLockShowBattery(on: Boolean) {
        _state.update { it.copy(lockShowBattery = on) }
        viewModelScope.launch { store.setLockShowBattery(on) }
    }

    /** Acknowledge the active alert: silence it until the condition changes/resolves. */
    fun acknowledgeAlert() = _state.update { s ->
        val a = s.stageAlert()
        when {
            a.kind == AlertKind.TEMPERATURE && a.tempAckKey != null ->
                s.copy(acknowledgedTempKeys = s.acknowledgedTempKeys + a.tempAckKey)
            a.activeThreshold != null ->
                s.copy(acknowledgedThresholds = a.ackEffective + a.activeThreshold)
            else -> s
        }
    }

    private fun currentStageAddrs(): Set<String> =
        _state.value.stageTarget.addresses(_state.value.roster) - _state.value.disabled

    /** Re-resolve the stage; if its battery set changed, tell the engine. (lastDischargeAt is
     *  maintained by the engine and mirrored into UiState — we only read it here.) */
    private fun refresh() {
        val now = clockMs()
        val before = currentStageAddrs()
        val prevTarget = _state.value.stageTarget
        _state.update { st ->
            // lastDischargeAt is maintained by the engine (mirrored into UiState); pass the roster's
            // current groups so stage resolution works against the dynamic roster.
            val resolved = resolveStage(
                StageInputs(st.fleet, st.dailyDriverId, st.dynamicStage, st.manualStage,
                    st.manualPinnedAt, st.lastDischargeAt, st.stageHoldMinutes * 60_000L, st.stageTarget, now,
                    st.roster.groupViews(), seizeThreshold = st.seizeThreshold),
            )
            val isPinned = st.manualStage != null && resolved == st.manualStage &&
                (!st.dynamicStage || now - st.manualPinnedAt < PIN_HOLD_MS)
            // Re-arm temp acks whenever the stage's worst pack has recovered below CRITICAL, so
            // a recurring condition flashes again (refresh runs on every engine update + timer);
            // advance the charging-suppression latch (UI-9) off the same fresh stage.
            st.copy(stageTarget = resolved, pinned = isPinned).withChargeHold(now).withTempAcksPruned()
        }
        // Persist the resolved stage whenever it changes, so the next launch restores + prioritizes it.
        val newTarget = _state.value.stageTarget
        if (newTarget != prevTarget) viewModelScope.launch { store.setLastStage(newTarget) }
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
