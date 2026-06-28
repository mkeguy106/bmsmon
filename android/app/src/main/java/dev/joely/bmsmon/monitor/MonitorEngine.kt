package dev.joely.bmsmon.monitor

import android.content.Context
import dev.joely.bmsmon.ble.BmsRepository
import dev.joely.bmsmon.data.TelemetryLogger
import dev.joely.bmsmon.model.ALL_GROUPS
import dev.joely.bmsmon.model.BatteryStatus
import dev.joely.bmsmon.model.GroupActivity
import dev.joely.bmsmon.model.Telemetry
import dev.joely.bmsmon.model.groupActivity
import dev.joely.bmsmon.model.groupForAddress
import dev.joely.bmsmon.model.isRegen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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
 * usage [TelemetryLogger] — none tied to an Activity/ViewModel — so BLE polling and CSV logging
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
    private val repository = BmsRepository(appContext)
    private val logger = TelemetryLogger(appContext)

    @Volatile private var logging = false

    private val _state = MutableStateFlow(MonitorState())
    val state: StateFlow<MonitorState> = _state.asStateFlow()

    val logPath: String get() = logger.path

    private fun now() = System.currentTimeMillis()

    /** Begin monitoring all known packs. [seed] pre-populates the fleet (shown dimmed until live). */
    fun start(seed: Map<String, BatteryStatus>, loggingEnabled: Boolean) {
        if (_state.value.monitoring) return
        logging = loggingEnabled
        _state.update {
            MonitorState(
                monitoring = true,
                fleet = seed.mapValues { (_, s) -> s.copy(reachable = false) },
            )
        }
        repository.start(
            scope = scope,
            targets = ALL_GROUPS.flatMap { it.targets },
            onTelemetry = ::onTelemetry,
            onReachable = ::onReachable,
        )
    }

    /** Stop monitoring: cancels all BLE jobs (each session closes its GATT cleanly) and clears state. */
    fun stop() {
        if (!_state.value.monitoring && _state.value.fleet.isEmpty()) return
        repository.stop()
        _state.value = MonitorState()
    }

    fun setStage(addresses: Set<String>) = repository.setStage(addresses)
    fun setDisabled(addresses: Set<String>) = repository.setDisabled(addresses)
    fun kickAll() = repository.kickAll()

    fun setLogging(enabled: Boolean) {
        logging = enabled
        if (enabled) _state.update { it.copy(peakPowerW = 0f, peakCurrentA = 0f) }
    }

    fun clearLog() {
        logger.clear()
        _state.update { it.copy(peakPowerW = 0f, peakCurrentA = 0f) }
    }

    /** Last-known reading per battery, for the ViewModel to persist for next-launch seeding. */
    fun telemetrySnapshot(): Map<String, Telemetry> =
        _state.value.fleet.filterValues { it.telemetry != null }.mapValues { it.value.telemetry!! }

    private fun onTelemetry(addr: String, t: Telemetry) {
        val now = now()
        val st0 = _state.value
        val group = groupForAddress(addr)
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
        if (logging) logger.log(addr, t, now, regen)
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
        // Log link transitions so a BLE drop/glitch is visible in the CSV (and tellable apart from
        // a genuine low/idle reading). Only on an actual edge, to avoid spam.
        if (reachable != was && logging) {
            val name = groupForAddress(addr)?.targets
                ?.firstOrNull { it.address.equals(addr, ignoreCase = true) }?.name ?: addr
            logger.event(addr, name, if (reachable) "Connected" else "Disconnected", now())
        }
    }

    /** Stamp each base's last-discharge time to [now] while it reads as discharging. */
    private fun recomputeLastDischarge(
        fleet: Map<String, BatteryStatus>,
        prev: Map<String, Long>,
        now: Long,
    ): Map<String, Long> {
        val next = prev.toMutableMap()
        ALL_GROUPS.forEach { g ->
            if (groupActivity(g, fleet) == GroupActivity.Discharging) next[g.id] = now
        }
        return next
    }
}
