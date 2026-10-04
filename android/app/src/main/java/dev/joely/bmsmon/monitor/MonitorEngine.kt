package dev.joely.bmsmon.monitor

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.util.Log
import dev.joely.bmsmon.ble.BmsRepository
import dev.joely.bmsmon.ble.hasBlePermissions
import dev.joely.bmsmon.location.LocationSource
import dev.joely.bmsmon.location.driveLocation
import dev.joely.bmsmon.motion.MotionSource
import dev.joely.bmsmon.ble.profile.ProfileRegistry
import dev.joely.bmsmon.ble.profile.RedodoBekenProfile
import dev.joely.bmsmon.cloud.TelemetryReporter
import dev.joely.bmsmon.data.FailureLogThrottle
import dev.joely.bmsmon.data.SettingsStore
import dev.joely.bmsmon.data.TelemetryRepository
import dev.joely.bmsmon.data.classifyFrame
import dev.joely.bmsmon.data.db.BmsDatabase
import dev.joely.bmsmon.model.AlertConfig
import dev.joely.bmsmon.model.BatteryState
import dev.joely.bmsmon.model.BatteryStatus
import dev.joely.bmsmon.model.DEFAULT_GROUP_ID
import dev.joely.bmsmon.model.DEFAULT_ROSTER
import dev.joely.bmsmon.model.EngineDecision
import dev.joely.bmsmon.model.PackRange
import dev.joely.bmsmon.model.RangeAccumulator
import dev.joely.bmsmon.model.RangeParams
import dev.joely.bmsmon.model.RangeRow
import dev.joely.bmsmon.model.SEED_RANGE_PARAMS
import dev.joely.bmsmon.model.SEED_TAIL_MIN
import dev.joely.bmsmon.model.SLOW_POLL_MS
import dev.joely.bmsmon.model.StageConfig
import dev.joely.bmsmon.model.StageTarget
import dev.joely.bmsmon.model.TodayUsage
import dev.joely.bmsmon.model.TAIL_START_SOC
import dev.joely.bmsmon.model.TempRank
import dev.joely.bmsmon.model.TempSide
import dev.joely.bmsmon.model.TempThresholds
import dev.joely.bmsmon.model.TempUnit
import dev.joely.bmsmon.model.chargeSample
import dev.joely.bmsmon.model.decisionView
import dev.joely.bmsmon.model.engineDecision
import dev.joely.bmsmon.model.estimateChargeMinutes
import dev.joely.bmsmon.model.estimatePackRange
import dev.joely.bmsmon.model.learnRangeParams
import dev.joely.bmsmon.model.learnTailFold
import dev.joely.bmsmon.model.formatDelta
import dev.joely.bmsmon.model.todayUsage
import dev.joely.bmsmon.model.tempMarginToCutoffC
import dev.joely.bmsmon.model.batteryAt
import dev.joely.bmsmon.model.GroupActivity
import dev.joely.bmsmon.model.Roster
import dev.joely.bmsmon.model.Telemetry
import dev.joely.bmsmon.model.allTargets
import dev.joely.bmsmon.model.applyDisabled
import dev.joely.bmsmon.model.groupActivity
import dev.joely.bmsmon.model.groupOf
import dev.joely.bmsmon.model.groupViews
import dev.joely.bmsmon.model.hasDesiredLinks
import dev.joely.bmsmon.model.pruneToRoster
import dev.joely.bmsmon.model.wantedAddrs
import dev.joely.bmsmon.model.packTemps
import dev.joely.bmsmon.model.MotionGate
import dev.joely.bmsmon.model.MotionReading
import dev.joely.bmsmon.model.foldMotion
import dev.joely.bmsmon.model.gpsShouldRun
import dev.joely.bmsmon.model.isRegen
import dev.joely.bmsmon.model.powerDecision
import dev.joely.bmsmon.model.seedLowPower
import dev.joely.bmsmon.power.PowerMonitor
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
 * Upload-path GPS dedup (bandwidth): attach lat/lon to a pack's uploaded sample only when the
 * cached fix is NEW for that pack, keyed on the fix timestamp ([lastUploadedFixMs] vs
 * [fixTimeMs] = [dev.joely.bmsmon.location.GpsFix.timeMs]). Never key on coordinate equality —
 * the fused provider re-fires with jittering coordinates while stationary. Staged packs poll at
 * 1.5 s vs the ~5 s fix cadence, so ~1-in-3 samples carry GPS; every pack still uploads every fix.
 */
internal fun isNewFixForPack(lastUploadedFixMs: Long?, fixTimeMs: Long): Boolean =
    lastUploadedFixMs == null || fixTimeMs > lastUploadedFixMs

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
    // BLE-27: some roster pack is not user-disconnected, i.e. BLE has a link to want. Single writer:
    // the engine (start / setDisabled / setRoster). The service holds its wakelock only while
    // monitoring && linksWanted, and the GPS gate needs it too (fixes only ride BLE samples).
    // Defaults true so a state built without it (stop()'s reset, tests) never reads "nothing to poll"
    // — so it reads true whenever monitoring is off: gate any use of it on [monitoring].
    val linksWanted: Boolean = true,
    // The roster has no battery at all — the ongoing notification says so rather than "All packs
    // disconnected". Single writer: the engine (start / setRoster).
    val rosterEmpty: Boolean = false,
    // Stage (T1.2, 2026-10-02 review) — single writer: the engine. Resolved (low-pack seize
    // included) on every BLE event, every config push and a 10 s tick, with or without a
    // ViewModel; the VM pushes StageConfig and mirrors these two fields.
    val stageTarget: StageTarget = StageTarget.Base(DEFAULT_GROUP_ID),
    val stagePinned: Boolean = false,
    // Phone power policy (2026-07-25), single-writer: only the engine sets these. holdScreen gates
    // FLAG_KEEP_SCREEN_ON in the UI; gpsBalanced downgrades GPS during the low-battery window
    // (entered below 5%, held until 15%); lowPower is the hysteretic latch, fed back into
    // powerDecision on the next reading.
    val holdScreen: Boolean = false,
    val gpsBalanced: Boolean = false,
    val lowPower: Boolean = false,
    val tailMinByAddress: Map<String, Float> = emptyMap(),
    // End ts of the last charge run each pack's tail EMA folded — run-identity dedup for the tail
    // learner (persisted, so neither a blip's 6-h re-scan nor an engine restart re-folds a run).
    val tailRunEndByAddress: Map<String, Long> = emptyMap(),
    val rangeParamsByAddress: Map<String, RangeParams> = emptyMap(),
    val todayUsageByAddress: Map<String, TodayUsage> = emptyMap(),
)

/**
 * Process-lifetime monitoring engine. Owns the [BmsRepository], its coroutine scope, and the
 * [TelemetryRepository] — none tied to an Activity/ViewModel — so BLE polling and DB logging
 * keep running while the app is backgrounded (kept alive by [MonitoringService]) and even if the
 * hosting Activity is destroyed. Held as a singleton by the Application ([dev.joely.bmsmon.BmsApp]).
 *
 * Telemetry processing that used to live in BatteryViewModel (regen detection, peak tracking,
 * per-sample logging, last-discharge tracking, connect/disconnect event logging) moved here so it
 * runs headless. Since T1.2 the engine also owns stage resolution (incl. the low-pack seize) — see
 * [reevaluate]; settings/appearance state stays in the ViewModel, which pushes [StageConfig] down
 * via [setStageConfig].
 */
class MonitorEngine(
    private val appContext: Context,
    // No default on purpose (DATA-9): a defaulted BmsDatabase.create(...) here silently opened a
    // second Room instance on bms.db whenever a caller forgot the argument. The shared instance
    // must be passed in (BmsApp owns it).
    db: BmsDatabase,
    private val reporter: TelemetryReporter? = null,
    private val settings: SettingsStore,
    /** Monotonic clock for frame freshness (UI-16). Injectable; the same clock the UI reads. */
    private val elapsedNow: () -> Long = { SystemClock.elapsedRealtime() },
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ble = BmsRepository(appContext)
    // A request Play Services fails after accepting it re-runs the GPS gate (see onLocationRequestLost).
    private val locationSource = LocationSource(appContext) { onLocationRequestLost() }
    private val motionSource = MotionSource(appContext)
    private val powerMonitor = PowerMonitor(appContext)
    private var powerJob: Job? = null
    private val repository = TelemetryRepository(db)
    private val alertNotifier = AlertNotifier(appContext)

    private val _state = MutableStateFlow(MonitorState())
    val state: StateFlow<MonitorState> = _state.asStateFlow()

    // Alert config, mirrored from the ViewModel (or the restore plan) so alerts fire headless.
    @Volatile private var alertConfig: AlertConfig? = null
    @Volatile private var tempAlertsEnabled: Boolean = true
    @Volatile private var tempThresholdsByProfile: Map<String, TempThresholds> = emptyMap()
    @Volatile private var tempUnit: TempUnit = TempUnit.F

    // --- Stage ownership (T1.2). Synchronization: fields marked "lock" are read and written ONLY
    // inside @Synchronized engine methods (reevaluate, seedStage, forceStage, markStageAuthoritative
    // — and persistStage, called only from those) or stop()'s synchronized(this) block — the same
    // monitor as applyGpsGate/shutdownGps. The @Volatile ones are written from the main thread by
    // start() and by setters that then call reevaluate().
    @Volatile private var stageConfig: StageConfig = StageConfig()
    @Volatile private var disabledAddrs: Set<String> = emptySet()
    /** Resolved stage addresses (minus disabled), as last pushed to BLE. Written under the lock;
     *  read lock-free in onPoll for the frame-cadence stamp (a stale read costs one frame's window). */
    @Volatile private var stageAddrs: Set<String> = emptySet()
    private var stageInitialized = false        // lock — false until seeded/forced/started
    private var forceStagePush = true           // lock — push the BLE stage set on a session's first pass
    private var persistStageJob: Job? = null    // lock
    // Per-pack charging-suppression latch for the headless notifier (UI-9). Lock (BLE-20): it used
    // to be a HashMap mutated from both the main and the control-loop thread.
    private var packChargeAt: Map<String, Long> = emptyMap()
    private var stageTickJob: Job? = null       // main thread only (start/stop), like rangeJob
    private val lastTailLearnAt = HashMap<String, Long>()
    // Last-uploaded GPS fix time per address (see isNewFixForPack). Upload path only — local Room
    // logging keeps attaching the full-precision fix to every sample.
    private val lastGpsFixUploaded = HashMap<String, Long>()
    private var rangeJob: Job? = null
    @Volatile private var lastRangeLearnAt = 0L
    // BLE-21: the monitoring session BLE callbacks belong to. start() mints one and captures it in
    // the callbacks it hands to BLE; stop() clears it FIRST, so a callback already running on the
    // control loop when the session ended (cancellation can't interrupt one) changes nothing.
    // Written on the main thread (start/stop — the VM, or the service's Main.immediate restore);
    // read on the control loop, also inside the state-update lambdas.
    @Volatile private var currentSession = 0L
    private var sessionSeq = 0L   // main thread only (start)

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
        // The uploader is process-lifetime like the engine. The UI reads its status straight from
        // TelemetryReporter.status, so nothing is mirrored here; start() also queues the history
        // import when one is due.
        reporter?.start()
    }

    /** Exposed so the ViewModel can read session history for the graphs. */
    val history: TelemetryRepository get() = repository

    @Volatile private var importChecked = false
    @Volatile private var logging = false
    // The current roster drives the monitoring target set and group lookups (regen, last-discharge).
    // It's dynamic (the user can add/remove batteries) so the ViewModel pushes updates via setRoster.
    @Volatile private var roster: Roster = DEFAULT_ROSTER

    private fun now() = System.currentTimeMillis()

    /** Begin monitoring every pack in [roster]. [seed] pre-populates the fleet (dimmed until live).
     *  [disabled] — the user's disconnected packs — is part of the session's starting state, not a
     *  follow-up push: it must be in force on BLE before this method's first stage push releases
     *  BmsRepository's launch barrier (T1.2 fix round 1; see BmsRepository.start). */
    fun start(roster: Roster, seed: Map<String, BatteryStatus>, loggingEnabled: Boolean, disabled: Set<String>) {
        if (_state.value.monitoring) return
        // BLE-21: this session's id, captured by the callbacks handed to BLE below.
        val session = ++sessionSeq
        currentSession = session
        // Reset GPS intent/state/request together through shutdownGps() rather than folding a
        // bare `gpsActive = false` into the state copy below. A bare state reset only touches
        // MonitorState — if `gpsActive` were ever false while LocationSource was still
        // `requesting` (unreachable today), GNSS would run with the state claiming it doesn't
        // until the gate's next evaluation stopped it. shutdownGps() is safe to
        // call before a session begins: gpsWanted is already false pre-start, gpsActive is
        // already false (a fresh engine's default MonitorState(), or stop()'s explicit reset),
        // and LocationSource.stop() no-ops when it isn't requesting — so this is a genuine no-op
        // on the common path and a real fix on the desynced one.
        shutdownGps()
        this.roster = roster
        logging = loggingEnabled
        disabledAddrs = disabled.map { it.uppercase() }.toSet()
        _state.update { st ->
            st.copy(
                monitoring = true,
                // A new session starts with NO session data (UI-16): the seed renders as
                // last-known and can never read LIVE or drive an alert until its pack's first
                // parsed frame. Removed packs are pruned so they can't return as ghosts (UI-29).
                fleet = pruneToRoster(seed, roster).mapValues { (_, s) ->
                    s.copy(reachable = false, lastFrameAtElapsedMs = null, frameIntervalMs = SLOW_POLL_MS)
                },
                regenAddrs = emptySet(),
                lastDischargeAt = emptyMap(),
                peakPowerW = 0f,
                peakCurrentA = 0f,
                tailMinByAddress = emptyMap(),
                tailRunEndByAddress = emptyMap(),
                linksWanted = hasDesiredLinks(roster, disabledAddrs),
                rosterEmpty = roster.batteries.isEmpty(),
            )
        }
        ble.start(
            scope = scope,
            targets = roster.allTargets(),
            // Installed by ble.start() before its control loop runs — so before the first stage
            // push below, the one that releases the launch barrier. A later setDisabled() would
            // let that first plan connect packs the user disconnected (BmsRepository.start wipes
            // the previous set).
            disabled = disabledAddrs,
            // Every BLE event re-runs the decision step (BLE-14/UI-20) — never only stage-pack
            // ones, so fleet-wide alerts and the seize keep working when the stage is dark.
            // A callback from an ended session (BLE-21) changes nothing, so it decides nothing either.
            onPoll = { addr, raw, t -> onPoll(session, addr, raw, t); if (session == currentSession) reevaluate() },
            onReachable = { addr, reachable ->
                onReachable(session, addr, reachable); if (session == currentSession) reevaluate()
            },
        )
        markStageAuthoritative()
        reevaluate()       // resolve + push the launch stage (releases BmsRepository's launch barrier)
        startStageTick()
        registerBtReceiver()  // BLE-10: BT off→on clears every backoff via kickAll
        startPowerLoop()  // phone power → screen-hold + GPS priority policy
        scope.launch {
            runCatching {
                val saved = settings.load()
                if (saved.chargeTailMinByAddress.isNotEmpty())
                    _state.update { it.copy(tailMinByAddress = saved.chargeTailMinByAddress) }
                if (saved.chargeTailRunEndByAddress.isNotEmpty())
                    _state.update { it.copy(tailRunEndByAddress = saved.chargeTailRunEndByAddress) }
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
        setRoster(plan.roster)           // the stage below resolves against the restored roster
        seedStage(plan.stage)
        setStageConfig(plan.stageConfig)
        start(plan.roster, plan.seed, plan.logging, plan.disabled)
        setAlertConfig(plan.alertConfig)
        setTempAlertConfig(plan.tempAlertsEnabled, plan.tempThresholdsByProfile, plan.tempUnit)
        // Pause setting first, so the gate's first evaluation is already correct and GPS is never
        // started only to be stopped again (which would also churn the service's FGS type).
        setGpsPauseParked(plan.gpsPauseParked)
        setGpsActive(plan.gpsActive)
        return true
    }

    /** Roster edited (monitoring or not): update the target set and group lookups, prune removed
     *  packs from the fleet so they can't linger as ghosts or seize the stage (UI-29), and
     *  re-resolve — a target that lost every member falls back to the daily driver. */
    fun setRoster(roster: Roster) {
        this.roster = roster
        _state.update { st ->
            st.copy(
                fleet = pruneToRoster(st.fleet, roster),
                regenAddrs = st.regenAddrs.filter { roster.batteryAt(it) != null }.toSet(),
                linksWanted = hasDesiredLinks(roster, disabledAddrs),
                rosterEmpty = roster.batteries.isEmpty(),
            )
        }
        if (_state.value.monitoring) {
            ble.setTargets(roster.allTargets())
            applyGpsGate(now())   // BLE-27: a roster left with no wanted pack stops GPS too
        }
        reevaluate()
    }

    /** Hand the engine its starting stage (the validated persisted lastStage). Ignored once the
     *  engine has an authoritative stage — e.g. a headless restore already started it and the
     *  Activity opens later: the VM must adopt the engine's stage, never reset it. */
    @Synchronized
    fun seedStage(target: StageTarget) {
        if (stageInitialized) return
        stageInitialized = true
        _state.update { it.copy(stageTarget = target) }
    }

    /** An explicit user choice of stage outside pin semantics (a daily driver picked while not
     *  monitoring). Re-resolved at once, so the seize and an active pin still win. */
    @Synchronized
    fun forceStage(target: StageTarget) {
        stageInitialized = true
        _state.update { it.copy(stageTarget = target) }
        // Persist here: reevaluate() compares against the target just written, so applyStage sees
        // no change and would never save it — a later headless restore would start on an older
        // stage. If the re-resolve moves the stage (seize, pin), applyStage's newer write wins.
        persistStage(target)
        reevaluate()
    }

    /** Stage inputs from the ViewModel (pin, dynamic, hold, daily driver, seize threshold). */
    fun setStageConfig(cfg: StageConfig) {
        stageConfig = cfg
        reevaluate()
    }

    @Synchronized
    private fun markStageAuthoritative() {
        stageInitialized = true
        forceStagePush = true
    }

    /**
     * Stop monitoring: cancels all BLE jobs (each session closes its GATT cleanly). The last-known
     * fleet is kept, marked unreachable — the engine's state is the single source of "monitoring
     * off → everything DISCONNECTED" and the ViewModel only mirrors it. Cloud upload status is
     * preserved (the reporter keeps running independently of monitoring).
     */
    fun stop() {
        if (!_state.value.monitoring && _state.value.fleet.isEmpty()) return
        // BLE-21: end the session FIRST, so a BLE callback still in flight from it is refused from
        // here on (onPoll/onReachable re-check inside their state update, too).
        currentSession = 0L
        repository.finalizeOpenSessions()
        unregisterBtReceiver()
        stopPowerLoop()
        rangeJob?.cancel()
        rangeJob = null
        stageTickJob?.cancel()
        stageTickJob = null
        ble.stop()
        shutdownGps()
        // One step under the engine lock (BLE-20): flip monitoring off and clear the notifier
        // together, so a reevaluate() racing in from the control loop either finished before (and
        // is cleared here) or starts after (reads monitoring = false and posts nothing).
        synchronized(this) {
            _state.update { st ->
                MonitorState(
                    fleet = st.fleet.mapValues { (_, s) -> s.copy(reachable = false) },
                    stageTarget = st.stageTarget,
                    stagePinned = st.stagePinned,
                    rangeParamsByAddress = st.rangeParamsByAddress,
                )
            }
            alertNotifier.clear()
            packChargeAt = emptyMap()
            stageAddrs = emptySet()
            forceStagePush = true
        }
    }

    /**
     * Record a user Stop (BLE-30): persist `monitoring = false`. The boot / app-update restore
     * (BLE-17) trusts that flag, so without this a reboot would silently undo the user's Stop and
     * re-take every BLE link the Redodo app needs. Runs on the engine's process-lifetime scope so
     * the write outlives the service that asks for it. UNDISPATCHED: the edit is handed to
     * DataStore on the caller's thread before this returns, so it queues ahead of a later Start's
     * `true` instead of racing it.
     */
    fun persistMonitoringOff() {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            runCatching { settings.setMonitoring(false) }
                .onFailure { Log.w(TAG, "persisting monitoring = false failed", it) }
        }
    }

    /** Disable packs: record them, then mark them unreachable in the state (synchronously), then
     *  cancel their BLE workers. A worker's cancellation is cooperative, so a frame or connect
     *  already in flight can still reach [onPoll]/[onReachable] after this returns; both ignore a
     *  disabled address — re-checked inside their state update, so one racing this call can't
     *  commit after it either — which is what keeps a just-disconnected pack from flashing back
     *  to "connected" (or driving an alert or the seize) while its worker tears down. */
    fun setDisabled(addresses: Set<String>) {
        disabledAddrs = addresses.map { it.uppercase() }.toSet()
        _state.update { st ->
            val (fleet, regen) = applyDisabled(st.fleet, st.regenAddrs, addresses)
            st.copy(fleet = fleet, regenAddrs = regen, linksWanted = hasDesiredLinks(roster, disabledAddrs))
        }
        ble.setDisabled(addresses)
        // "Disconnect all" cancels every worker, so onPoll — the gate's primary driver — may never
        // fire again while monitoring stays on. Re-evaluate here rather than wait for the range
        // loop's 5-minute tick.
        applyGpsGate(now())
        reevaluate()   // the stage's BLE address set excludes disabled packs
    }
    fun kickAll() = ble.kickAll()
    /** App resume (BLE-16): retries the stage packs every time and every pack at most once per
     *  5 min; see BmsRepository.kickOnResume. */
    fun kickOnResume() = ble.kickOnResume()

    /** Mirror the alert settings from the ViewModel; re-evaluate so changes take effect at once. */
    fun setAlertConfig(cfg: AlertConfig) {
        alertConfig = cfg
        reevaluate()
    }

    /** Mirror the temperature-alert settings from the ViewModel. [unit] is the app-wide °C/°F
     *  preference, so headless notifications show margins in the user's unit (UI-4). */
    fun setTempAlertConfig(enabled: Boolean, thresholdsByProfile: Map<String, TempThresholds>, unit: TempUnit) {
        tempAlertsEnabled = enabled
        tempThresholdsByProfile = thresholdsByProfile
        tempUnit = unit
        reevaluate()
    }

    /**
     * The engine's decision step (T1.2 — BLE-14/UI-20, UI-29, UI-16): resolve the stage (low-pack
     * seize included) and evaluate fleet-wide capacity + stage temperature alerts, via the pure
     * [engineDecision]. Runs on EVERY BLE event (wired once in [start]), on every config push and
     * on the [STAGE_TICK_MS] tick, so headless operation decides exactly like foreground operation.
     *
     * @Synchronized on the engine — the same monitor as [applyGpsGate]/[shutdownGps]: it is entered
     * from the BLE control loop (IO), the ViewModel (main) and the tick (Default); the stage
     * read-decide-act and AlertNotifier's HashMaps (BLE-20) must not interleave. Nothing it calls
     * re-enters the engine from another thread or waits on one — [BmsRepository.setStage] only
     * posts a wake and the lastStage write is launched — but it does make NotificationManager
     * binder calls (notify / cancel / areNotificationsEnabled) while holding the lock. Those are
     * bounded (milliseconds), the same trade [applyGpsGate] already makes for its GMS calls; a
     * main-thread setter that arrives meanwhile waits that long.
     */
    @Synchronized
    private fun reevaluate() {
        val st = _state.value
        val nowE = elapsedNow()
        val cfg = alertConfig
        val d = engineDecision(
            roster = roster,
            fleet = st.fleet,
            nowElapsedMs = nowE,
            nowMs = now(),
            lastDischargeAt = st.lastDischargeAt,
            stageCfg = stageConfig,
            current = st.stageTarget,
            disabled = disabledAddrs,
            alertCfg = cfg,
            chargeAt = packChargeAt,
            regenAddrs = st.regenAddrs,
        )
        if (stageInitialized) applyStage(st, d)
        if (!st.monitoring) return
        packChargeAt = d.chargeAt
        d.capacity?.let { caps ->
            alertNotifier.updateFleet(
                caps.mapValues { (addr, eval) ->
                    PackAlert(eval, roster.batteryAt(addr)?.alias ?: st.fleet[addr]?.telemetry?.name)
                },
                // BLE-24: a low pack that drops out of the view keeps its notification through a
                // short flap — never one the user disconnected or removed, nor with alerts off.
                holdable = if (cfg?.alertsOn == true) wantedAddrs(roster, disabledAddrs) else emptySet(),
                nowElapsedMs = nowE,
            )
        }
        evaluateTempAlerts(d.view, nowE)
    }

    /** Publish a resolved stage (lock held by [reevaluate]): mirror it into [MonitorState], persist
     *  a changed target for the next (possibly headless) restore, and push the BLE stage set. The
     *  push is forced on a session's first pass ([forceStagePush]) because that first setStage is
     *  what releases BmsRepository's launch barrier — which is safe only because [start] hands the
     *  disabled set to ble.start() first, so the planner it releases never wants a pack the user
     *  disconnected. */
    private fun applyStage(st: MonitorState, d: EngineDecision) {
        val target = d.stage.target
        if (target != st.stageTarget || d.stage.pinned != st.stagePinned) {
            _state.update { it.copy(stageTarget = target, stagePinned = d.stage.pinned) }
        }
        if (target != st.stageTarget) persistStage(target)
        if (st.monitoring && (forceStagePush || d.stageAddrs != stageAddrs)) {
            forceStagePush = false
            ble.setStage(d.stageAddrs)
        }
        stageAddrs = d.stageAddrs
    }

    /** Save [target] as lastStage for the next (possibly headless) restore. Lock held (callers:
     *  [applyStage], [forceStage]); the newest call wins — an older in-flight write is cancelled. */
    private fun persistStage(target: StageTarget) {
        persistStageJob?.cancel()
        persistStageJob = scope.launch {
            runCatching { settings.setLastStage(target) }.onFailure {
                // A cancellation is this write being superseded by a newer target — not a failure.
                if (it !is CancellationException) {
                    Log.w(TAG, "lastStage write failed; the next restore may use an older stage", it)
                }
            }
        }
    }

    /** Clock-driven re-resolution: pin and stage-hold expiry and the STALE_MAX_MS backstop happen
     *  with no BLE event to trigger them. Frames already run [reevaluate]; this bounds the lag of
     *  the purely time-driven transitions to [STAGE_TICK_MS], headless or not. */
    private fun startStageTick() {
        stageTickJob?.cancel()
        stageTickJob = scope.launch {
            while (isActive) {
                delay(STAGE_TICK_MS)
                // runCatching: no CoroutineExceptionHandler on this scope — a throw here would kill
                // the process, and the foreground service with it.
                runCatching { reevaluate() }.onFailure { Log.e(TAG, "stage tick failed", it) }
            }
        }
    }

    /** Stage packs' temperature zones → headless temperature notifications, one per alarming pack
     *  (BLE-24: deduped and held per pack — `reconcileTempNotifications`). [view] is the freshness
     *  decision view, so a seed or a silent pack never raises or holds an alarm. */
    private fun evaluateTempAlerts(view: Map<String, BatteryStatus>, nowE: Long) {
        val on = tempAlertsEnabled
        val temps = if (on) {
            packTemps(view) { a ->
                val profile = ProfileRegistry.profileFor(roster.batteryAt(a)?.advertisedName) ?: RedodoBekenProfile
                (tempThresholdsByProfile[profile.id] ?: profile.tempEnvelope.defaults) to profile.tempEnvelope
            }
        } else {
            emptyMap()
        }
        alertNotifier.updateTemp(
            temps,
            stageAddrs,
            // Temperature alerts off: no readings AND nothing holdable, which is what cancels every
            // temperature notification on show at once — holdable packs would be held for the grace.
            // Otherwise only packs the user hasn't disconnected or removed are held through a flap.
            holdable = if (on) wantedAddrs(roster, disabledAddrs) else emptySet(),
            nowElapsedMs = nowE,
            label = { a -> roster.groupOf(a)?.id },
            detail = { t ->
                val name = roster.batteryAt(t.addr)?.alias ?: t.telemetry.name
                val side = if (t.zone.side == TempSide.COLD) "COLD" else "HOT"
                if (t.zone.rank == TempRank.CUTOFF) {
                    "$side · $name · load disconnected"
                } else {
                    // Margin formatted in the user's °C/°F preference (mirrored via setTempAlertConfig).
                    "$side · $name · ${formatDelta(tempMarginToCutoffC(t.telemetry.temp, t.zone.side, t.env), tempUnit)} to cutoff"
                }
            },
        )
    }

    /** Backfill the legacy CSVs into the DB exactly once (guarded by a persisted flag). */
    fun importLegacyCsvIfNeeded(alreadyImported: Boolean, markImported: suspend () -> Unit, filesDir: File?) {
        if (importChecked || alreadyImported) { importChecked = true; return }
        importChecked = true
        scope.launch {
            val dir = filesDir ?: return@launch
            // runCatching: no CoroutineExceptionHandler on this scope — an IOException or
            // SQLiteException here used to kill the process (and monitoring with it), again on every
            // app open while the flag stayed false. A failure leaves the flag unset, so the next
            // process start retries.
            runCatching {
                repository.importCsvOnce(listOf(File(dir, "usage_log.csv"), File(dir, "usage_log.1.csv")))
                markImported()
            }.onFailure {
                if (it !is CancellationException) Log.w(TAG, "legacy CSV import failed; retried next process start", it)
            }
        }
    }

    // What the cloud settings WANT (monitoring && gpsEnabled && enrolled && cloudEnabled), before
    // the parked gate subtracts from it. Kept separate so the gate can flip GPS off and back on
    // without losing the user's intent.
    @Volatile private var gpsWanted = false
    @Volatile private var gpsPauseParked = true

    // Silence-as-stillness motion-gate state (see foldMotion). Only ever read/written inside
    // applyGpsGate and shutdownGps, both @Synchronized on this engine, so this does not need
    // @Volatile the way gpsWanted/gpsPauseParked do (those are written from outside the lock
    // too). Callers that need the folded verdict (e.g. the upload path in onPoll) must use
    // applyGpsGate's return value rather than reading this field directly — a direct read is
    // unsynchronized and was exactly the bug a prior review caught here.
    private var motionGate = MotionGate()

    // Rate limit for a GPS start/stop that throws on every gate evaluation (~100/min while it
    // persists — driveLocation retries each one, BLE-19). Only touched inside @Synchronized
    // applyGpsGate, so its single-consumer contract holds.
    private val gpsFailures = FailureLogThrottle()

    private fun logGpsFailure(run: Boolean, e: Throwable) {
        val what = "location ${if (run) "start" else "stop"} failed"
        when (val line = gpsFailures.onFailure(elapsedNow())) {
            is FailureLogThrottle.Action.Full ->
                Log.w(TAG, if (line.unreported == 0) what else "$what (${line.unreported} earlier failures went unreported)", e)
            is FailureLogThrottle.Action.Summary ->
                Log.w(TAG, "${line.count} more location failures since the last report; latest: $e")
            FailureLogThrottle.Action.Suppress -> Unit
        }
    }

    /** Record whether GPS capture is wanted at all; the parked gate decides if it actually runs. */
    fun setGpsActive(active: Boolean) {
        gpsWanted = active
        applyGpsGate(now())
    }

    /** Settings › Battery saver: pause GNSS while no base has discharged recently. */
    fun setGpsPauseParked(on: Boolean) {
        gpsPauseParked = on
        applyGpsGate(now())
    }

    /**
     * Fold intent + parked state into the actual GPS run state, and start/stop [MotionSource]
     * alongside it. The engine stays the single writer of [MonitorState.gpsActive], which is true
     * only while a fused request is actually registered (BLE-19).
     *
     * The chair cannot move without discharging a pack, so a parked chair's fixes teach the range
     * learner nothing (its discharge gate discards them) while GNSS costs ~22 mA. Full stop rather
     * than a drop to balanced accuracy: coarse fixes are what produced the 2026-07-13 phantom map
     * spikes, so we would rather capture nothing than capture noise.
     *
     * [motionSource] is started/stopped **here**, driven off [gpsWanted] **and** [gpsPauseParked],
     * rather than by its callers. It is an *input* to the pause decision below (via [motionGate],
     * folded by [foldMotion] — once per reading, not once per call; see below), not something the
     * decision's own output can gate, and it must run for the whole window the parked gate could
     * apply within: whenever GPS is wanted at all *and* the pause is enabled. With the pause
     * toggle off, [gpsShouldRun] ignores the motion verdict entirely, so an Activity Recognition
     * subscription would be pure waste in exactly the configuration a user picks to keep their
     * track — the toggle is off precisely because they want GNSS to stay on.
     *
     * Doing this under the same lock as [locationSource] is what lets [setGpsActive] stay a bare,
     * unsynchronized volatile write to [gpsWanted]: even if it
     * races [shutdownGps] between that write and reaching this method, whichever write is still
     * current when this method acquires the lock is the one both [motionSource] and
     * [locationSource] end up obeying, because both are driven from the live field here, never
     * from a value a caller captured earlier. (Previously `setGpsActive` called
     * `motionSource.start()/stop()` directly, outside any lock — a thread could set
     * `gpsWanted = true` and be about to call `start()` when [shutdownGps] ran on another thread,
     * set `gpsWanted = false`, and called `motionSource.stop()`; the first thread's `start()`
     * would then land *after* that stop, leaving [motionSource] subscribed with nothing consuming
     * it — a live subscription burning battery for no reason, the same class of leak this
     * synchronization already prevented for [locationSource].)
     *
     * Synchronized because there are several callers on different threads — the ViewModel (main),
     * the BLE poll callback (Dispatchers.IO) and the range loop — and the read-decide-act has to be
     * atomic or an interleaving could leave `gpsActive = false` with the fused request still
     * registered, i.e. exactly the silent GNSS drain this gate exists to remove. [shutdownGps]
     * takes the same lock for the same reason. Lock order is always engine -> LocationSource /
     * MotionSource, and neither ever calls back in, so this cannot deadlock.
     *
     * **This method is called far more often than motion readings arrive** — once per BLE frame
     * per pack (~80–115×/min across the fleet at [STAGE_POLL_MS]/[SLOW_POLL_MS]) against ~10
     * Activity Recognition broadcasts a minute, and [MotionSource.current] returns the same cached
     * reading until a new broadcast lands. [foldMotion] therefore dedupes by reading identity
     * (`MotionGate.lastConfidentAtMs`), so one reading advances the hold clock once rather than
     * ~11 times per evaluation; it still re-derives the verdict from the clock on every call, so
     * the `STILL_CLOSE_HOLD_MS` close fires off the wall clock even when no new readings arrive —
     * silence closes the gate; only a null reading or confident non-STILL can reopen it.
     *
     * Under the OLD counted debounce (`STILL_DEBOUNCE_N` fresh readings inside a stale window)
     * this dedup was correctness machinery: folding once per evaluation instead of once per
     * reading silently collapsed `N=3` to an effective `N=1`, so a single spurious STILL closed
     * the gate on the very next re-evaluation and the next confident reading reopened it — a real
     * flap (see CLAUDE.md, "Folds are deduped by reading identity"). That hazard does not carry
     * over to the current hold-based rules: a re-folded confident-STILL reading still lands on
     * `foldMotion`'s `reading.still` branch, `stillSinceMs = prev.stillSinceMs ?: reading.atMs`
     * preserves the run's original start, and the gate still needs the full `STILL_CLOSE_HOLD_MS`
     * no matter how many times one reading gets refolded. The dedup is therefore no longer
     * correctness machinery here — it's a fast-path idempotence guard that spares ~11 redundant
     * `foldMotion` calls per evaluation for the same cached reading.
     */
    @Synchronized
    private fun applyGpsGate(now: Long): Pair<MotionReading?, MotionGate> {
        // BLE-27: GPS needs a pack to attach fixes to — fixes only ever ride BLE samples (onPoll), so
        // with every pack disconnected GNSS would cost ~22 mA for nothing, and with the service's
        // wakelock released this gate's 5-min tick may not run to stop it.
        val wanted = gpsWanted && _state.value.linksWanted
        if (wanted && gpsPauseParked) {
            motionSource.start()
            motionSource.maybeResubscribe(now)
        } else {
            motionSource.stop()
        }
        val reading = motionSource.current()
        motionGate = foldMotion(motionGate, reading, now)
        val run = gpsShouldRun(
            wanted = wanted,
            pauseEnabled = gpsPauseParked,
            lastDischargeMs = _state.value.lastDischargeAt.values.maxOrNull(),
            nowMs = now,
            confidentlyStill = motionGate.still,
        )
        // BLE-19: drive the request on EVERY evaluation, not only when the verdict flips, and publish
        // gpsActive only for a request that is actually registered — start() used to no-op silently
        // without permission while gpsActive still went true, and a later grant re-pushed nothing.
        // Both calls are idempotent, so a grant is picked up at the next BLE frame (or the 5-min
        // range tick). BLE-22: a throw (a permission revoked between the check and the GMS call) is
        // logged, rate-limited, and reads as inactive — it never takes the battery monitor down.
        val active = driveLocation(run, locationSource) { e -> logGpsFailure(run, e) }
        if (_state.value.gpsActive != active) _state.update { it.copy(gpsActive = active) }
        return reading to motionGate
    }

    /**
     * Monitoring is ending: drop the GPS intent and the request together, under the gate's lock.
     *
     * This has to be one atomic step, not three loose ones in [stop]. `ble.stop()` cancels the
     * control-loop job, but `onPoll` is a plain call from the loop body and cancellation cannot
     * preempt it — so a gate call can already be mid-flight, having decided `active = true`. Doing
     * the teardown unsynchronized lets that call's `locationSource.start()` land *after* [stop],
     * leaving `gpsActive = false` in the state with the fused request still registered: a silent
     * high-accuracy GNSS drain with monitoring off. Holding the lock forces the two to order —
     * either the gate runs first (starts, and is stopped here a moment later) or after (reads
     * `gpsWanted = false` and no-ops).
     *
     * Clearing the intent also matters on its own: a later `setGpsPauseParked(false)` (the setting
     * is reachable with monitoring off) would otherwise re-evaluate a stale `wanted = true` and
     * start GNSS with nothing monitoring. [start] has the ViewModel push both again.
     *
     * [motionSource] is stopped here for the same reason as [locationSource]: it is the other
     * resource [applyGpsGate] owns under this lock, and leaving it subscribed with monitoring
     * torn down would be a live Activity Recognition subscription nothing ever reads again.
     * [motionGate] is reset alongside it so a stale stillness run cannot survive a stop and carry
     * over into the next monitoring session — a fresh session must start with no holdover from
     * before the phone last moved or monitoring was off.
     */
    @Synchronized
    private fun shutdownGps() {
        gpsWanted = false
        _state.update { it.copy(gpsActive = false) }
        runCatching { locationSource.stop() }.onFailure { Log.w(TAG, "location stop failed", it) }
        motionSource.stop()
        motionGate = MotionGate()
    }

    /**
     * Fold each phone power reading into the screen/GPS policy. Started with monitoring so the
     * receiver's lifetime matches the engine's, like the Bluetooth one.
     *
     * The screen is the phone's dominant drain by a wide margin, so it is held only on external
     * power and out of the low-battery latch (see PowerPolicy for why the latch exists). Note the
     * GPS half is applied unconditionally — LocationSource.setBalanced only records the mode while GPS
     * is inactive, for the next start(), so this never fights setGpsActive.
     */
    private fun startPowerLoop() {
        powerJob?.cancel()
        powerMonitor.start()
        powerJob = scope.launch {
            var first = true
            powerMonitor.status.collect { ps ->
                // runCatching: an uncaught throw here would kill the whole process (no
                // CoroutineExceptionHandler on this scope) — and with it the foreground service,
                // which ActivityManager may then not reschedule for up to an hour. A null-Looper
                // NPE from this exact spot has done that once on-device; never let this collector
                // escape.
                runCatching {
                    // A fresh loop (app restart, or a reboot after the phone died at 0%) has no
                    // real latch value to carry — seed conservatively instead of trusting the
                    // MonitorState default of false, which would let a low-but-recovering reading
                    // hold the screen in exactly the window the latch protects.
                    val was = if (first) seedLowPower(ps.levelPct) else _state.value.lowPower
                    first = false
                    val d = powerDecision(
                        onExternal = ps.onExternal,
                        levelPct = ps.levelPct,
                        wasLowPower = was,
                    )
                    _state.update {
                        it.copy(holdScreen = d.holdScreen, gpsBalanced = d.gpsBalanced, lowPower = d.lowPower)
                    }
                    applyLocationMode(d.gpsBalanced)
                }
            }
        }
    }

    private fun stopPowerLoop() {
        powerJob?.cancel()
        powerJob = null
        powerMonitor.stop()
        applyLocationMode(false)
    }

    /**
     * The ONE place the location mode is switched, guarded: a GMS throw is logged and reads as "mode
     * unchanged" ([LocationSource.setBalanced] keeps the old request registered and the next call
     * retries). Unguarded, the switch in [stopPowerLoop] aborted [stop] right after it had ended the
     * session — BLE left running with every frame refused, the throw crashing the main-thread caller,
     * and the user's Stop lost to the sticky restart.
     */
    private fun applyLocationMode(balanced: Boolean) {
        runCatching { locationSource.setBalanced(balanced) }
            .onFailure { Log.w(TAG, "location mode switch failed; the current request stays", it) }
    }

    /**
     * The provider failed a registered request after accepting it (Play Services rejecting it
     * asynchronously). [LocationSource] has already forgotten it; re-run the gate — under its lock,
     * as the single writer of `gpsActive` — so the state stops claiming a request that doesn't exist.
     * Runs on the main thread (GMS listeners), holding no lock.
     */
    private fun onLocationRequestLost() {
        runCatching { applyGpsGate(now()) }.onFailure { Log.w(TAG, "GPS gate re-evaluation failed", it) }
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

    /** A user-disconnected pack ([setDisabled]); [disabledAddrs] is stored uppercased. */
    private fun isDisabled(addr: String) = addr.uppercase() in disabledAddrs

    private fun onPoll(session: Long, addr: String, raw: ByteArray, t: Telemetry?) {
        // BLE-21: a frame from an ended session — stop() ran while this callback was already in
        // flight on the control loop — changes nothing: no pack marked live under MONITORING OFF,
        // nothing logged or uploaded, nothing carried into the next session. Re-checked inside the
        // state update below, like the disabled check.
        if (session != currentSession) return
        // UI-29: a frame already in flight when its pack was removed from the roster must not
        // re-add it to the fleet as a ghost (setRoster pruned it).
        if (roster.batteryAt(addr) == null) return
        // M1: a frame already in flight when the user disconnected this pack must not revive it
        // (reachable + a fresh stamp = LIVE, able to alert or seize, for up to 60 s) — nor be logged,
        // decodable or not. Re-checked inside the state update below for a setDisabled that lands
        // between here and there.
        if (isDisabled(addr)) return
        val now = now()
        if (t == null) {
            // An undecodable frame commits no state, so this check, right before the write, is where
            // it is accepted or refused — the decoded path's CAS below does the same job: a frame
            // refused because its pack was disconnected or its session ended is never logged.
            if (logging && session == currentSession && _state.value.monitoring && !isDisabled(addr)) {
                repository.ingestRawOnly(addr, raw, "decode_fail", now)
            }
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
        val profile = ProfileRegistry.profileFor(roster.batteryAt(addr)?.advertisedName) ?: RedodoBekenProfile
        // UI-16: stamp the parsed frame on the monotonic clock with the cadence it was polled at —
        // the window freshness() judges it by. Decode failures returned above, so they never stamp.
        val frameAt = elapsedNow()
        val cadence = if (addr.uppercase() in stageAddrs) profile.stagePollMs else profile.slowPollMs
        // Whether the update below committed this frame (a CAS lambda may re-run; the last run decides).
        var accepted = false
        _state.update { st ->
            accepted = false
            // Read inside the CAS loop: if setDisabled's own update commits first, this retries,
            // sees the pack disabled, and leaves it unreachable.
            if (isDisabled(addr)) return@update st
            if (session != currentSession || !st.monitoring) return@update st
            accepted = true
            val fleet = st.fleet + (addr to (st.fleet[addr] ?: BatteryStatus()).copy(
                telemetry = t, reachable = true, etaFullMin = etaFullMin, range = range,
                lastFrameAtElapsedMs = frameAt, frameIntervalMs = cadence,
            ))
            var peakP = st.peakPowerW
            var peakC = st.peakCurrentA
            if (logging && t.current < -0.05f) {  // discharging — track peak draw
                peakP = maxOf(peakP, t.powerW)
                peakC = maxOf(peakC, -t.current)
            }
            st.copy(
                fleet = fleet,
                regenAddrs = if (regen) st.regenAddrs + addr else st.regenAddrs - addr,
                // Decision view (UI-16): a carried seed can't stamp a base as "driving".
                lastDischargeAt = recomputeLastDischarge(decisionView(fleet, frameAt), st.lastDischargeAt, now),
                peakPowerW = peakP,
                peakCurrentA = peakC,
            )
        }
        // A frame the update refused (its pack disabled, or its session ended, meanwhile) isn't
        // logged or uploaded either.
        if (!accepted) return
        // lastDischargeAt just moved; re-derive whether the chair still counts as driving. The
        // reading and the gate verdict come from this ONE call, both computed under the same
        // lock — never two independent motionSource.current() calls, which could pair a fresh
        // reading with a stale verdict (or vice versa) if a broadcast landed in between.
        val (motion, gate) = applyGpsGate(now)
        val fix = if (_state.value.gpsActive) locationSource.current(now) else null
        // Upload GPS only when the fix is new for this pack (bandwidth — see isNewFixForPack).
        val uploadFix = fix?.takeIf { isNewFixForPack(lastGpsFixUploaded[addr], it.timeMs) }
        if (uploadFix != null) lastGpsFixUploaded[addr] = uploadFix.timeMs
        reporter?.report(
            addr, roster.batteryAt(addr)?.advertisedName, roster.batteryAt(addr)?.alias,
            group?.id, t, now, regen, uploadFix?.lat, uploadFix?.lon, uploadFix?.accuracyM, etaFullMin,
            motion?.activity, motion?.confidence, gate.still, motion?.atMs,
        )
        if (logging) {
            val header = profile.responseHeader
            repository.ingest(addr, t, raw, classifyFrame(raw, parsedOk = true, header), regen, now, fix)
        }
        // Tail learn fires at charger CUTOFF: this BMS never reports SOC 100 while Charging
        // (it caps at 99; 100 appears only after the state flips) — so the old "100 while
        // Charging" trigger never fired and every pack sat on the seed. The Charging→other
        // transition of a pack that had climbed into the tail is the completed-charge signal.
        // The 30-min wall-clock guard is only a cheap pre-filter against rapid-blip Room scans;
        // correctness (never folding the same run twice — a regen blip hours later, or an
        // engine restart, re-finds the same run in the 6-h lookback) comes from the persisted
        // run-identity dedup inside learnTail (shouldFoldTail on tailRunEndByAddress).
        val prevTel = st0.fleet[addr]?.telemetry
        if (prevTel?.state == BatteryState.Charging && t.state != BatteryState.Charging &&
            prevTel.soc >= TAIL_START_SOC &&
            now - (lastTailLearnAt[addr] ?: 0L) > 30 * 60_000L
        ) {
            lastTailLearnAt[addr] = now
            scope.launch { runCatching { learnTail(addr, now) } }
        }
    }

    private fun onReachable(session: Long, addr: String, reachable: Boolean) {
        // BLE-21: see onPoll — a link event from an ended session changes nothing.
        if (session != currentSession) return
        val was = _state.value.fleet[addr]?.reachable == true
        // The value actually committed: M1 — a user-disconnected pack is never marked reachable,
        // not even by a connect that completed just as the user disconnected it. Read inside the
        // CAS loop, like onPoll, so a racing setDisabled can't be overtaken.
        var up = reachable
        var committed = false
        _state.update { st ->
            committed = false
            if (session != currentSession || !st.monitoring) return@update st
            committed = true
            if (roster.batteryAt(addr) == null) {
                // UI-29: a removed pack's final link-down must not keep it in the fleet. A frame
                // in flight while setRoster pruned it can still have re-added it after the prune,
                // so REMOVE it here rather than merely skipping (that would leave the ghost
                // reading "reachable" — counted as connected and persisted with the snapshot).
                up = reachable
                st.copy(fleet = st.fleet - addr, regenAddrs = st.regenAddrs - addr)
            } else {
                up = reachable && !isDisabled(addr)
                val fleet = st.fleet + (addr to (st.fleet[addr] ?: BatteryStatus()).copy(reachable = up))
                st.copy(
                    fleet = fleet,
                    regenAddrs = if (up) st.regenAddrs else st.regenAddrs - addr,
                    lastDischargeAt = recomputeLastDischarge(
                        decisionView(fleet, elapsedNow()), st.lastDischargeAt, now(),
                    ),
                )
            }
        }
        if (committed && up != was) {
            val ts = now()
            if (logging) repository.logLink(addr, up, ts)
            reporter?.reportLink(addr, roster.batteryAt(addr)?.alias, roster.groupOf(addr)?.id, up, ts)
        }
        // Stage + alert evaluation runs right after this returns — see the BLE wiring in start().
    }

    /** Fold the just-completed charge's observed 98->100 tail into the per-pack EMA and persist it.
     *  learnTailFold's run-identity dedup (against the persisted last-learned run end) makes a
     *  re-scan that finds an already-folded run a no-op — the prod double-fold bug was a regen
     *  blip 5 h after cutoff re-finding the same overnight run in this 6-h window. */
    private suspend fun learnTail(addr: String, now: Long) {
        val since = now - 6 * 60 * 60_000L   // look back 6h for the completed run
        // chargeSample drops null-SOC rows (UI-10) — they must not count as "below 98%" evidence.
        val samples = repository.recentSamples(addr, since).mapNotNull {
            chargeSample(it.tsMs, it.soc, it.state == "Charging")
        }
        val st = _state.value
        val result = learnTailFold(
            samples, st.tailMinByAddress[addr] ?: SEED_TAIL_MIN, st.tailRunEndByAddress[addr],
        ) ?: return
        _state.update {
            it.copy(
                tailMinByAddress = it.tailMinByAddress + (addr to result.tailMin),
                tailRunEndByAddress = it.tailRunEndByAddress + (addr to result.runEndMs),
            )
        }
        settings.setChargeTailLearned(addr, result.tailMin, result.runEndMs)
    }

    /** Cadence of the range pass: today-usage refresh every pass, full re-learn every 6 h. */
    private fun startRangeLoop() {
        rangeJob?.cancel()
        rangeJob = scope.launch {
            while (isActive) {
                // Second driver for the parked-GPS gate, run FIRST — before rangePass(), not
                // after. onPoll is the primary driver, but it only fires when a frame arrives —
                // "Disconnect all", Bluetooth off, or every pack out of range (the phone leaves
                // the chair) stall it indefinitely with monitoring still on, freezing
                // lastDischargeAt and pinning GNSS on. rangePass() pulls a 14-day window for
                // every pack on its 6-hourly learn pass (up to ~800k rows/pack), so gating after
                // it would tack that pass's duration onto this loop's period on exactly those
                // passes; gating first keeps this loop's period equal to PARKED_HOLD_MS.
                // This driver can also CLOSE the motion gate: foldMotion re-derives the verdict
                // from the clock on every fold, so a tick past STILL_CLOSE_HOLD_MS closes it even
                // with onPoll stalled (worst case one tick of lag, erring toward GPS-on). It also
                // drives MotionSource.maybeResubscribe via applyGpsGate.
                runCatching { applyGpsGate(now()) }
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
            // BLE-25: stream the window through the accumulator in bounded keyset pages — the learn
            // pass used to hold a stage pack's whole 14 days (~800 k rows, ~140 MB at peak) in the
            // monitor's own process. One accumulator serves both the learn and today's usage.
            val acc = RangeAccumulator(zone)
            repository.forEachRangeRow(addr, since) {
                acc.add(RangeRow(it.tsMs, it.currentA, it.powerW, it.lat, it.lon, it.gpsAccuracyM, it.regen))
            }
            if (acc.rows == 0) continue
            if (learn) {
                val params = learnRangeParams(acc, now)
                _state.update { it.copy(rangeParamsByAddress = it.rangeParamsByAddress + (addr to params)) }
            }
            val today = todayUsage(acc, now)
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

    private companion object {
        const val TAG = "MonitorEngine"
        /** Period of the engine's clock-driven stage re-resolution (see [startStageTick]). */
        const val STAGE_TICK_MS = 10_000L
    }
}
