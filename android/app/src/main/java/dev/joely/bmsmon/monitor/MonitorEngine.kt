package dev.joely.bmsmon.monitor

import android.content.Context
import dev.joely.bmsmon.ble.BmsRepository
import dev.joely.bmsmon.location.LocationSource
import dev.joely.bmsmon.ble.profile.ProfileRegistry
import dev.joely.bmsmon.ble.profile.RedodoBekenProfile
import dev.joely.bmsmon.cloud.TelemetryReporter
import dev.joely.bmsmon.data.SettingsStore
import dev.joely.bmsmon.data.TelemetryRepository
import dev.joely.bmsmon.data.classifyFrame
import dev.joely.bmsmon.data.db.BmsDatabase
import dev.joely.bmsmon.model.AlertConfig
import dev.joely.bmsmon.model.BatteryState
import dev.joely.bmsmon.model.BatteryStatus
import dev.joely.bmsmon.model.ChargeSample
import dev.joely.bmsmon.model.DEFAULT_ROSTER
import dev.joely.bmsmon.model.PackSoc
import dev.joely.bmsmon.model.SEED_TAIL_MIN
import dev.joely.bmsmon.model.TARGET_SOC
import dev.joely.bmsmon.model.TempRank
import dev.joely.bmsmon.model.TempSide
import dev.joely.bmsmon.model.TempThresholds
import dev.joely.bmsmon.model.TempUnit
import dev.joely.bmsmon.model.estimateChargeMinutes
import dev.joely.bmsmon.model.evalStageAlert
import dev.joely.bmsmon.model.foldTailEma
import dev.joely.bmsmon.model.formatDelta
import dev.joely.bmsmon.model.observedChargeTailMinutes
import dev.joely.bmsmon.model.tempMarginToCutoffC
import dev.joely.bmsmon.model.tempZone
import dev.joely.bmsmon.model.batteryAt
import dev.joely.bmsmon.model.GroupActivity
import dev.joely.bmsmon.model.Roster
import dev.joely.bmsmon.model.Telemetry
import dev.joely.bmsmon.model.allTargets
import dev.joely.bmsmon.model.groupActivity
import dev.joely.bmsmon.model.groupOf
import dev.joely.bmsmon.model.groupViews
import dev.joely.bmsmon.model.isRegen
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The BLE-derived state the engine maintains, independent of any UI lifecycle. Mirrored into the
 * ViewModel's UiState while the app is foregrounded, and read by the foreground-service
 * notification while it isn't.
 */
data class MonitorState(
    val monitoring: Boolean = false,
    val fleet: Map<String, BatteryStatus> = emptyMap(),
    val regenAddrs: Set<String> = emptySet(),
    val lastDischargeAt: Map<String, Long> = emptyMap(),
    val peakPowerW: Float = 0f,
    val peakCurrentA: Float = 0f,
    val gpsActive: Boolean = false,
    val tailMinByAddress: Map<String, Float> = emptyMap(),
)

/**
 * Process-lifetime monitoring engine. Owns the [BmsRepository], its coroutine scope, and the
 * [TelemetryRepository] — none tied to an Activity/ViewModel — so BLE polling and DB logging
 * keep running while the app is backgrounded (kept alive by [MonitoringService]) and even if the
 * hosting Activity is destroyed. Held as a singleton by the Application ([dev.joely.bmsmon.BmsApp]).
 *
 * Telemetry processing that used to live in BatteryViewModel (regen detection, peak tracking,
 * per-sample logging, last-discharge tracking, connect/disconnect event logging) moved here so it
 * runs headless. Stage resolution and all settings/appearance state stay in the ViewModel, which
 * pushes the resolved stage down via [setStage].
 */
class MonitorEngine(
    appContext: Context,
    db: BmsDatabase = BmsDatabase.create(appContext),
    private val reporter: TelemetryReporter? = null,
    private val settings: SettingsStore,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ble = BmsRepository(appContext)
    private val locationSource = LocationSource(appContext)
    private val repository = TelemetryRepository(db)
    private val alertNotifier = AlertNotifier(appContext)

    // Stage addresses + alert config, mirrored from the ViewModel so alerts can fire headless.
    @Volatile private var stageAddrs: Set<String> = emptySet()
    @Volatile private var alertConfig: AlertConfig? = null
    @Volatile private var tempAlertsEnabled: Boolean = true
    @Volatile private var tempThresholdsByProfile: Map<String, TempThresholds> = emptyMap()
    private val lastTailLearnAt = HashMap<String, Long>()

    init {
        reporter?.start()
    }

    /** Exposed so the ViewModel can read session history for the graphs. */
    val history: TelemetryRepository get() = repository

    @Volatile private var importChecked = false
    @Volatile private var logging = false
    // The current roster drives the monitoring target set and group lookups (regen, last-discharge).
    // It's dynamic (the user can add/remove batteries) so the ViewModel pushes updates via setRoster.
    @Volatile private var roster: Roster = DEFAULT_ROSTER

    init {
        // roster is now initialized — fire import resume on every process start while enrolled && !importDone.
        reporter?.startImportIfNeeded(roster)
    }

    private val _state = MutableStateFlow(MonitorState())
    val state: StateFlow<MonitorState> = _state.asStateFlow()

    private fun now() = System.currentTimeMillis()

    /** Begin monitoring every pack in [roster]. [seed] pre-populates the fleet (dimmed until live). */
    fun start(roster: Roster, seed: Map<String, BatteryStatus>, loggingEnabled: Boolean) {
        if (_state.value.monitoring) return
        this.roster = roster
        logging = loggingEnabled
        _state.update {
            MonitorState(
                monitoring = true,
                fleet = seed.mapValues { (_, s) -> s.copy(reachable = false) },
            )
        }
        ble.start(
            scope = scope,
            targets = roster.allTargets(),
            onPoll = ::onPoll,
            onReachable = ::onReachable,
        )
        scope.launch {
            runCatching {
                val saved = settings.load().chargeTailMinByAddress
                if (saved.isNotEmpty()) _state.update { it.copy(tailMinByAddress = saved) }
            }
        }
    }

    /** Roster edited while monitoring: update the live target set and group lookups. */
    fun setRoster(roster: Roster) {
        this.roster = roster
        ble.setTargets(roster.allTargets())
    }

    /** Stop monitoring: cancels all BLE jobs (each session closes its GATT cleanly) and clears state. */
    fun stop() {
        if (!_state.value.monitoring && _state.value.fleet.isEmpty()) return
        repository.finalizeOpenSessions()
        ble.stop()
        locationSource.stop()
        alertNotifier.clear()
        stageAddrs = emptySet()
        _state.value = MonitorState()
    }

    fun setStage(addresses: Set<String>) {
        stageAddrs = addresses.map { it.uppercase() }.toSet()
        ble.setStage(addresses)
        evaluateAlerts()
    }
    fun setDisabled(addresses: Set<String>) = ble.setDisabled(addresses)
    fun kickAll() = ble.kickAll()

    /** Mirror the alert settings from the ViewModel; re-evaluate so changes take effect at once. */
    fun setAlertConfig(cfg: AlertConfig) {
        alertConfig = cfg
        evaluateAlerts()
    }

    /** Mirror the temperature-alert settings from the ViewModel. */
    fun setTempAlertConfig(enabled: Boolean, thresholdsByProfile: Map<String, TempThresholds>) {
        tempAlertsEnabled = enabled
        tempThresholdsByProfile = thresholdsByProfile
        evaluateAlerts()
    }

    /** Evaluate the stage against the alert config and drive headless notifications. */
    private fun evaluateAlerts() {
        if (!_state.value.monitoring) return
        val fleet = _state.value.fleet
        val label = stageAddrs.firstNotNullOfOrNull { roster.groupOf(it)?.id }
        alertConfig?.let { cfg ->
            val packs = stageAddrs
                .mapNotNull { fleet[it]?.takeIf { s -> s.reachable }?.telemetry }
                .map { PackSoc(it.soc, it.state == BatteryState.Charging) }
            alertNotifier.update(evalStageAlert(packs, cfg), label)
        }
        evaluateTempAlerts(fleet, label)
    }

    /** Worst reachable stage pack's temperature zone → headless temperature notification. */
    private fun evaluateTempAlerts(fleet: Map<String, BatteryStatus>, label: String?) {
        if (!tempAlertsEnabled) { alertNotifier.updateTemp(TempRank.SAFE, TempSide.NONE, label, ""); return }
        val worst = stageAddrs
            .mapNotNull { a -> fleet[a]?.takeIf { it.reachable }?.telemetry?.let { a to it } }
            .map { (a, t) ->
                val profile = ProfileRegistry.profileFor(roster.batteryAt(a)?.advertisedName)
                    ?: RedodoBekenProfile
                val thr = tempThresholdsByProfile[profile.id] ?: profile.tempEnvelope.defaults
                Triple(a, t, tempZone(t.temp, thr, profile.tempEnvelope) to profile.tempEnvelope)
            }
            .maxByOrNull { it.third.first.rank.ordinal }
        if (worst == null) { alertNotifier.updateTemp(TempRank.SAFE, TempSide.NONE, label, ""); return }
        val (addr, tel, zoneEnv) = worst
        val (zone, env) = zoneEnv
        val name = roster.batteryAt(addr)?.alias ?: tel.name
        val side = if (zone.side == TempSide.COLD) "COLD" else "HOT"
        val detail = if (zone.rank == TempRank.CUTOFF) {
            "$side · $name · load disconnected"
        } else {
            // Headless notification text uses °F (the app-wide default unit).
            "$side · $name · ${formatDelta(tempMarginToCutoffC(tel.temp, zone.side, env), TempUnit.F)} to cutoff"
        }
        alertNotifier.updateTemp(zone.rank, zone.side, label, detail)
    }

    /** Backfill the legacy CSVs into the DB exactly once (guarded by a persisted flag). */
    fun importLegacyCsvIfNeeded(alreadyImported: Boolean, markImported: suspend () -> Unit, filesDir: File?) {
        if (importChecked || alreadyImported) { importChecked = true; return }
        importChecked = true
        scope.launch {
            val dir = filesDir ?: return@launch
            repository.importCsvOnce(listOf(File(dir, "usage_log.csv"), File(dir, "usage_log.1.csv")))
            markImported()
        }
    }

    /** Start/stop GPS capture; cached fixes are attached to each upload while active. */
    fun setGpsActive(active: Boolean) {
        _state.update { it.copy(gpsActive = active) }
        if (active) locationSource.start() else locationSource.stop()
    }

    fun setLogging(enabled: Boolean) {
        logging = enabled
        if (enabled) _state.update { it.copy(peakPowerW = 0f, peakCurrentA = 0f) }
    }

    fun clearLog() {
        repository.clearAll()
        _state.update { it.copy(peakPowerW = 0f, peakCurrentA = 0f) }
    }

    /** Last-known reading per battery, for the ViewModel to persist for next-launch seeding. */
    fun telemetrySnapshot(): Map<String, Telemetry> =
        _state.value.fleet.filterValues { it.telemetry != null }.mapValues { it.value.telemetry!! }

    private fun onPoll(addr: String, raw: ByteArray, t: Telemetry?) {
        val now = now()
        if (t == null) {
            if (logging) repository.ingestRawOnly(addr, raw, "decode_fail", now)
            return
        }
        val st0 = _state.value
        val group = roster.groupOf(addr)
        // Regen is judged against the group's last-discharge time BEFORE this sample updates it.
        val regen = isRegen(t, group?.let { st0.lastDischargeAt[it.id] }, now)
        _state.update { st ->
            val fleet = st.fleet + (addr to (st.fleet[addr] ?: BatteryStatus())
                .copy(telemetry = t, reachable = true))
            var peakP = st.peakPowerW
            var peakC = st.peakCurrentA
            if (logging && t.current < -0.05f) {  // discharging — track peak draw
                peakP = maxOf(peakP, t.powerW)
                peakC = maxOf(peakC, -t.current)
            }
            st.copy(
                fleet = fleet,
                regenAddrs = if (regen) st.regenAddrs + addr else st.regenAddrs - addr,
                lastDischargeAt = recomputeLastDischarge(fleet, st.lastDischargeAt, now),
                peakPowerW = peakP,
                peakCurrentA = peakC,
            )
        }
        val fix = if (_state.value.gpsActive) locationSource.current() else null
        val tailMin = st0.tailMinByAddress[addr] ?: SEED_TAIL_MIN
        val etaFullMin = estimateChargeMinutes(
            t.state, t.soc, t.current, t.fullChargeAh, t.capacityAh, regen, tailMin,
        )
        reporter?.report(
            addr, roster.batteryAt(addr)?.advertisedName, roster.batteryAt(addr)?.alias,
            group?.id, t, now, regen, fix?.lat, fix?.lon, fix?.accuracyM, etaFullMin,
        )
        if (logging) {
            val header = (ProfileRegistry.profileFor(roster.batteryAt(addr)?.advertisedName)
                ?: RedodoBekenProfile).responseHeader
            repository.ingest(addr, t, raw, classifyFrame(raw, parsedOk = true, header), regen, now)
        }
        if (addr.uppercase() in stageAddrs) evaluateAlerts()
        if (t.state == BatteryState.Charging && t.soc >= TARGET_SOC &&
            now - (lastTailLearnAt[addr] ?: 0L) > 30 * 60_000L
        ) {
            lastTailLearnAt[addr] = now
            scope.launch { runCatching { learnTail(addr, now) } }
        }
    }

    private fun onReachable(addr: String, reachable: Boolean) {
        val was = _state.value.fleet[addr]?.reachable == true
        _state.update { st ->
            val fleet = st.fleet + (addr to (st.fleet[addr] ?: BatteryStatus()).copy(reachable = reachable))
            st.copy(
                fleet = fleet,
                regenAddrs = if (reachable) st.regenAddrs else st.regenAddrs - addr,
                lastDischargeAt = recomputeLastDischarge(fleet, st.lastDischargeAt, now()),
            )
        }
        if (reachable != was) {
            val ts = now()
            if (logging) repository.logLink(addr, reachable, ts)
            reporter?.reportLink(addr, roster.batteryAt(addr)?.alias, roster.groupOf(addr)?.id, reachable, ts)
        }
        if (addr.uppercase() in stageAddrs) evaluateAlerts()
    }

    /** Fold the just-completed charge's observed 98->100 tail into the per-pack EMA and persist it. */
    private suspend fun learnTail(addr: String, now: Long) {
        val since = now - 6 * 60 * 60_000L   // look back 6h for the completed run
        val samples = repository.recentSamples(addr, since).map {
            ChargeSample(it.tsMs, it.soc ?: -1f, it.state == "Charging")
        }
        val observed = observedChargeTailMinutes(samples) ?: return
        val prev = _state.value.tailMinByAddress[addr] ?: SEED_TAIL_MIN
        val next = foldTailEma(prev, observed)
        _state.update { it.copy(tailMinByAddress = it.tailMinByAddress + (addr to next)) }
        settings.setChargeTailMin(addr, next)
    }

    /** Stamp each base's last-discharge time to [now] while it reads as discharging. */
    private fun recomputeLastDischarge(
        fleet: Map<String, BatteryStatus>,
        prev: Map<String, Long>,
        now: Long,
    ): Map<String, Long> {
        val next = prev.toMutableMap()
        roster.groupViews().forEach { g ->
            if (groupActivity(g, fleet) == GroupActivity.Discharging) next[g.id] = now
        }
        return next
    }
}
