package dev.joely.bmsmon.monitor

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import dev.joely.bmsmon.ble.BmsRepository
import dev.joely.bmsmon.ble.hasBlePermissions
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
import dev.joely.bmsmon.model.DEFAULT_ROSTER
import dev.joely.bmsmon.model.PackRange
import dev.joely.bmsmon.model.PackSoc
import dev.joely.bmsmon.model.RangeParams
import dev.joely.bmsmon.model.RangeRow
import dev.joely.bmsmon.model.SEED_RANGE_PARAMS
import dev.joely.bmsmon.model.SEED_TAIL_MIN
import dev.joely.bmsmon.model.TodayUsage
import dev.joely.bmsmon.model.TARGET_SOC
import dev.joely.bmsmon.model.TempRank
import dev.joely.bmsmon.model.TempSide
import dev.joely.bmsmon.model.TempThresholds
import dev.joely.bmsmon.model.TempUnit
import dev.joely.bmsmon.model.chargeSample
import dev.joely.bmsmon.model.estimateChargeMinutes
import dev.joely.bmsmon.model.estimatePackRange
import dev.joely.bmsmon.model.evalStageAlert
import dev.joely.bmsmon.model.foldTailEma
import dev.joely.bmsmon.model.learnRangeParams
import dev.joely.bmsmon.model.nextChargeHold
import dev.joely.bmsmon.model.formatDelta
import dev.joely.bmsmon.model.observedChargeTailMinutes
import dev.joely.bmsmon.model.todayUsage
import dev.joely.bmsmon.model.tempMarginToCutoffC
import dev.joely.bmsmon.model.tempZone
import dev.joely.bmsmon.model.batteryAt
import dev.joely.bmsmon.model.GroupActivity
import dev.joely.bmsmon.model.Roster
import dev.joely.bmsmon.model.Telemetry
import dev.joely.bmsmon.model.allTargets
import dev.joely.bmsmon.model.applyDisabled
import dev.joely.bmsmon.model.groupActivity
import dev.joely.bmsmon.model.groupOf
import dev.joely.bmsmon.model.groupViews
import dev.joely.bmsmon.model.isRegen
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
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
    val rangeParamsByAddress: Map<String, RangeParams> = emptyMap(),
    val todayUsageByAddress: Map<String, TodayUsage> = emptyMap(),
    // Cloud upload status, mirrored from the TelemetryReporter's onStatus hook. The engine owns
    // that process-lifetime hook (it already owns the reporter), so no ViewModel is ever captured
    // by it — the VM just mirrors these fields like the rest of the state.
    val cloudOutboxDepth: Int = 0,
    val cloudLastUploadMs: Long = 0,
    val cloudUploadKbps: Float = 0f,
    val cloudAuthFailed: Boolean = false,
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
    private val appContext: Context,
    // No default on purpose (DATA-9): a defaulted BmsDatabase.create(...) here silently opened a
    // second Room instance on bms.db whenever a caller forgot the argument. The shared instance
    // must be passed in (BmsApp owns it).
    db: BmsDatabase,
    private val reporter: TelemetryReporter? = null,
    private val settings: SettingsStore,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ble = BmsRepository(appContext)
    private val locationSource = LocationSource(appContext)
    private val repository = TelemetryRepository(db)
    private val alertNotifier = AlertNotifier(appContext)

    private val _state = MutableStateFlow(MonitorState())
    val state: StateFlow<MonitorState> = _state.asStateFlow()

    // Stage addresses + alert config, mirrored from the ViewModel so alerts can fire headless.
    @Volatile private var stageAddrs: Set<String> = emptySet()
    @Volatile private var alertConfig: AlertConfig? = null
    @Volatile private var tempAlertsEnabled: Boolean = true
    @Volatile private var tempThresholdsByProfile: Map<String, TempThresholds> = emptyMap()
    @Volatile private var tempUnit: TempUnit = TempUnit.F
    private val lastTailLearnAt = HashMap<String, Long>()
    private var rangeJob: Job? = null
    @Volatile private var lastRangeLearnAt = 0L

    // BLE-10: react to Bluetooth off→on. Without this, a BT toggle leaves every pack sitting out
    // its climbed backoff (up to 2 min each) before reconnecting — bad while backgrounded, where
    // nothing else calls kickAll(). ACTION_STATE_CHANGED is a protected system broadcast, so a
    // plain context-registered receiver is fine on all supported API levels.
    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            if (state == BluetoothAdapter.STATE_ON) ble.kickAll()
        }
    }
    @Volatile private var btReceiverRegistered = false

    private fun registerBtReceiver() {
        if (btReceiverRegistered) return
        runCatching {
            appContext.registerReceiver(btStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
            btReceiverRegistered = true
        }
    }

    private fun unregisterBtReceiver() {
        if (!btReceiverRegistered) return
        btReceiverRegistered = false
        // runCatching: unregistering an already-unregistered receiver throws IllegalArgumentException.
        runCatching { appContext.unregisterReceiver(btStateReceiver) }
    }

    init {
        // Surface upload status through MonitorState (UI-3). Registered before start() so the
        // uploader can never fire into an unset hook, and never re-registered by UI lifecycles.
        reporter?.onStatus = { depth, ts, kbps, authFailed ->
            _state.update {
                it.copy(
                    cloudOutboxDepth = depth.toInt(), cloudLastUploadMs = ts,
                    cloudUploadKbps = kbps.toFloat(), cloudAuthFailed = authFailed,
                )
            }
        }
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

    private fun now() = System.currentTimeMillis()

    /** Begin monitoring every pack in [roster]. [seed] pre-populates the fleet (dimmed until live). */
    fun start(roster: Roster, seed: Map<String, BatteryStatus>, loggingEnabled: Boolean) {
        if (_state.value.monitoring) return
        this.roster = roster
        logging = loggingEnabled
        _state.update { st ->
            st.copy(
                monitoring = true,
                fleet = seed.mapValues { (_, s) -> s.copy(reachable = false) },
                regenAddrs = emptySet(),
                lastDischargeAt = emptyMap(),
                peakPowerW = 0f,
                peakCurrentA = 0f,
                gpsActive = false,
                tailMinByAddress = emptyMap(),
            )
        }
        ble.start(
            scope = scope,
            targets = roster.allTargets(),
            onPoll = ::onPoll,
            onReachable = ::onReachable,
        )
        registerBtReceiver()  // BLE-10: BT off→on clears every backoff via kickAll
        scope.launch {
            runCatching {
                val saved = settings.load()
                if (saved.chargeTailMinByAddress.isNotEmpty())
                    _state.update { it.copy(tailMinByAddress = saved.chargeTailMinByAddress) }
                if (saved.rangeParamsByAddress.isNotEmpty())
                    _state.update { it.copy(rangeParamsByAddress = saved.rangeParamsByAddress) }
            }
            // Started only after the persisted-params load completes, so the first learn pass
            // can never race a stale load and get clobbered by it. Guarded by monitoring in case
            // stop() ran while the load was in flight.
            if (_state.value.monitoring) startRangeLoop()
        }
    }

    /**
     * Sticky-restart restore (BLE-11): the OS killed the process while monitoring was on and
     * restarted [MonitoringService] with a null intent — there is no ViewModel to drive us, so
     * resume headlessly from the persisted settings. Returns true when monitoring is (now)
     * running: already-running (raced with a normal in-app start — [start] is a no-op then, and
     * we must not clobber the ViewModel's config pushes) or freshly restored. Returns false when
     * monitoring was off at death or BLE permissions were revoked — nothing to restore.
     */
    suspend fun restoreFromPersisted(): Boolean {
        if (_state.value.monitoring) return true
        val plan = runCatching { settings.load() }.getOrNull()?.let(::restorePlan) ?: return false
        if (!hasBlePermissions(appContext)) return false
        if (_state.value.monitoring) return true  // raced with a normal start; leave it be
        start(plan.roster, plan.seed, plan.logging)
        setDisabled(plan.disabled)
        setStage(plan.stageAddrs)
        setAlertConfig(plan.alertConfig)
        setTempAlertConfig(plan.tempAlertsEnabled, plan.tempThresholdsByProfile, plan.tempUnit)
        setGpsActive(plan.gpsActive)
        return true
    }

    /** Roster edited while monitoring: update the live target set and group lookups. */
    fun setRoster(roster: Roster) {
        this.roster = roster
        ble.setTargets(roster.allTargets())
    }

    /**
     * Stop monitoring: cancels all BLE jobs (each session closes its GATT cleanly). The last-known
     * fleet is kept, marked unreachable — the engine's state is the single source of "monitoring
     * off → everything DISCONNECTED" and the ViewModel only mirrors it. Cloud upload status is
     * preserved (the reporter keeps running independently of monitoring).
     */
    fun stop() {
        if (!_state.value.monitoring && _state.value.fleet.isEmpty()) return
        repository.finalizeOpenSessions()
        unregisterBtReceiver()
        rangeJob?.cancel()
        rangeJob = null
        ble.stop()
        locationSource.stop()
        alertNotifier.clear()
        stageAddrs = emptySet()
        _state.update { st ->
            MonitorState(
                fleet = st.fleet.mapValues { (_, s) -> s.copy(reachable = false) },
                cloudOutboxDepth = st.cloudOutboxDepth,
                cloudLastUploadMs = st.cloudLastUploadMs,
                cloudUploadKbps = st.cloudUploadKbps,
                cloudAuthFailed = st.cloudAuthFailed,
                rangeParamsByAddress = st.rangeParamsByAddress,
            )
        }
    }

    fun setStage(addresses: Set<String>) {
        // Normalize once (UI-13d): everything downstream — stageAddrs comparisons AND the BLE
        // layer — sees the same uppercased set instead of whatever casing the caller had.
        val norm = addresses.map { it.uppercase() }.toSet()
        stageAddrs = norm
        ble.setStage(norm)
        evaluateAlerts()
    }

    /** Disable packs: mark them unreachable in the state FIRST (synchronously — reachability has a
     *  single writer, so a just-disconnected pack can never flash back to "connected" while its
     *  worker tears down), then cancel their BLE workers. */
    fun setDisabled(addresses: Set<String>) {
        _state.update { st ->
            val (fleet, regen) = applyDisabled(st.fleet, st.regenAddrs, addresses)
            st.copy(fleet = fleet, regenAddrs = regen)
        }
        ble.setDisabled(addresses)
    }
    fun kickAll() = ble.kickAll()

    /** Mirror the alert settings from the ViewModel; re-evaluate so changes take effect at once. */
    fun setAlertConfig(cfg: AlertConfig) {
        alertConfig = cfg
        evaluateAlerts()
    }

    /** Mirror the temperature-alert settings from the ViewModel. [unit] is the app-wide °C/°F
     *  preference, so headless notifications show margins in the user's unit (UI-4). */
    fun setTempAlertConfig(enabled: Boolean, thresholdsByProfile: Map<String, TempThresholds>, unit: TempUnit) {
        tempAlertsEnabled = enabled
        tempThresholdsByProfile = thresholdsByProfile
        tempUnit = unit
        evaluateAlerts()
    }

    // Per-pack charging-suppression latch for the headless notifier (UI-9) — same hysteresis as the
    // in-app overlay, so an Idle/Charging flap at the charger can't strobe notifications either. Keyed
    // by address now that notifications are fleet-wide (each low pack alerts independently).
    private val packChargeAt = mutableMapOf<String, Long>()

    /** Evaluate EVERY reachable pack against the alert config and drive per-pack headless
     *  notifications, so a low pack that isn't on the stage still raises the alarm. */
    private fun evaluateAlerts() {
        if (!_state.value.monitoring) return
        val fleet = _state.value.fleet
        val label = stageAddrs.firstNotNullOfOrNull { roster.groupOf(it)?.id }
        alertConfig?.let { cfg ->
            val now = now()
            val evals = fleet.mapNotNull { (addr, s) ->
                val tel = s.telemetry?.takeIf { s.reachable } ?: return@mapNotNull null
                val charging = tel.state == BatteryState.Charging
                val hold = nextChargeHold(
                    charging = charging,
                    discharging = tel.state == BatteryState.Discharging,
                    lastChargingAt = packChargeAt[addr] ?: 0L, now = now,
                )
                packChargeAt[addr] = hold.lastChargingAt
                val eval = evalStageAlert(listOf(PackSoc(tel.soc, charging)), cfg)
                // Present the latched hold as "charging" so the alert stays cancelled through an
                // Idle/Charging flap instead of cancel/re-notify churn.
                val held = if (hold.holdActive && !eval.charging) eval.copy(charging = true) else eval
                addr to PackAlert(held, roster.batteryAt(addr)?.alias ?: tel.name)
            }.toMap()
            alertNotifier.updateFleet(evals)
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
            // Margin formatted in the user's °C/°F preference (mirrored via setTempAlertConfig).
            "$side · $name · ${formatDelta(tempMarginToCutoffC(tel.temp, zone.side, env), tempUnit)} to cutoff"
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
        // Charge ETA is computed ONCE per pack per poll and carried on BatteryStatus — the same
        // value is uploaded (eta_full_min) and displayed on the stage, so they can never diverge.
        val tailMin = st0.tailMinByAddress[addr] ?: SEED_TAIL_MIN
        val etaFullMin = estimateChargeMinutes(
            t.state, t.soc, t.current, t.fullChargeAh, t.capacityAh, regen, tailMin,
        )
        // Discharge-range estimate — same single-writer pattern as the charge ETA: computed once
        // per poll here, carried on BatteryStatus, only displayed by the UI.
        val range = estimatePackRange(
            t.state, t.capacityAh,
            st0.rangeParamsByAddress[addr] ?: SEED_RANGE_PARAMS,
            st0.todayUsageByAddress[addr],
        )
        _state.update { st ->
            val fleet = st.fleet + (addr to (st.fleet[addr] ?: BatteryStatus())
                .copy(telemetry = t, reachable = true, etaFullMin = etaFullMin, range = range))
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
        reporter?.report(
            addr, roster.batteryAt(addr)?.advertisedName, roster.batteryAt(addr)?.alias,
            group?.id, t, now, regen, fix?.lat, fix?.lon, fix?.accuracyM, etaFullMin,
        )
        if (logging) {
            val header = (ProfileRegistry.profileFor(roster.batteryAt(addr)?.advertisedName)
                ?: RedodoBekenProfile).responseHeader
            repository.ingest(addr, t, raw, classifyFrame(raw, parsedOk = true, header), regen, now, fix)
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
        // chargeSample drops null-SOC rows (UI-10) — they must not count as "below 98%" evidence.
        val samples = repository.recentSamples(addr, since).mapNotNull {
            chargeSample(it.tsMs, it.soc, it.state == "Charging")
        }
        val observed = observedChargeTailMinutes(samples) ?: return
        val prev = _state.value.tailMinByAddress[addr] ?: SEED_TAIL_MIN
        val next = foldTailEma(prev, observed)
        _state.update { it.copy(tailMinByAddress = it.tailMinByAddress + (addr to next)) }
        settings.setChargeTailMin(addr, next)
    }

    /** Cadence of the range pass: today-usage refresh every pass, full re-learn every 6 h. */
    private fun startRangeLoop() {
        rangeJob?.cancel()
        rangeJob = scope.launch {
            while (isActive) {
                runCatching { rangePass() }
                delay(5 * 60_000L)
            }
        }
    }

    private suspend fun rangePass() {
        val now = now()
        val zone = java.time.ZoneId.systemDefault()
        val learn = now - lastRangeLearnAt > 6 * 60 * 60_000L
        if (learn) lastRangeLearnAt = now
        // Non-learn passes only feed todayUsage() (rows since local midnight) — scope the query
        // to that instead of always dragging in the full 14-day window (up to ~800k rows/pack).
        val midnight = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            .atStartOfDay(zone).toInstant().toEpochMilli()
        val since = if (learn) now - 14L * 86_400_000L else midnight
        for (b in roster.batteries) {
            val addr = b.address
            val rows = repository.rangeRows(addr, since).map {
                RangeRow(it.tsMs, it.state, it.powerW, it.lat, it.lon, it.gpsAccuracyM, it.regen)
            }
            if (rows.isEmpty()) continue
            if (learn) {
                val params = learnRangeParams(rows, zone, now)
                _state.update { it.copy(rangeParamsByAddress = it.rangeParamsByAddress + (addr to params)) }
            }
            val today = todayUsage(rows, zone, now)
            _state.update { it.copy(todayUsageByAddress = it.todayUsageByAddress + (addr to today)) }
        }
        if (learn) runCatching { settings.setRangeParams(_state.value.rangeParamsByAddress) }
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
