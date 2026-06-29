package dev.joely.bmsmon.monitor

import android.content.Context
import dev.joely.bmsmon.ble.BmsRepository
import dev.joely.bmsmon.data.TelemetryRepository
import dev.joely.bmsmon.data.classifyFrame
import dev.joely.bmsmon.data.db.BmsDatabase
import dev.joely.bmsmon.model.BatteryStatus
import dev.joely.bmsmon.model.DEFAULT_ROSTER
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
class MonitorEngine(appContext: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ble = BmsRepository(appContext)
    private val repository = TelemetryRepository(BmsDatabase.create(appContext))

    /** Exposed so the ViewModel can read session history for the graphs. */
    val history: TelemetryRepository get() = repository

    @Volatile private var importChecked = false
    @Volatile private var logging = false
    // The current roster drives the monitoring target set and group lookups (regen, last-discharge).
    // It's dynamic (the user can add/remove batteries) so the ViewModel pushes updates via setRoster.
    @Volatile private var roster: Roster = DEFAULT_ROSTER

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
        _state.value = MonitorState()
    }

    fun setStage(addresses: Set<String>) = ble.setStage(addresses)
    fun setDisabled(addresses: Set<String>) = ble.setDisabled(addresses)
    fun kickAll() = ble.kickAll()

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
        if (logging) repository.ingest(addr, t, raw, classifyFrame(raw, parsedOk = true), regen, now)
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
        if (reachable != was && logging) {
            repository.logLink(addr, reachable, now())
        }
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
