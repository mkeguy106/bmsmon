package dev.joely.bmsmon.ble

import android.content.Context
import android.util.Log
import dev.joely.bmsmon.model.BatteryState
import dev.joely.bmsmon.model.BmsTarget
import dev.joely.bmsmon.model.SLOW_POLL_MS
import dev.joely.bmsmon.model.STAGE_POLL_MS
import dev.joely.bmsmon.model.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Fleet engine that stays within the phone's BLE link budget:
 *  - the STAGE base's batteries get a persistent connection, polled fast (1.65 s);
 *  - every other reachable battery is visited by a rotating sampler (connect → read one
 *    status → disconnect → next), in priority order discharging > charging > idle.
 * A shared gate caps how many connections are being established at once.
 */
class BmsRepository(private val context: Context) {

    // Cap on simultaneous *connection attempts* (the LE initiator can't pursue many).
    private val gate = Semaphore(2)

    private var scope: CoroutineScope? = null
    private var allTargets: List<BmsTarget> = emptyList()
    @Volatile private var running = false

    @Volatile private var stageAddrs: Set<String> = emptySet()
    @Volatile private var disabledAddrs: Set<String> = emptySet()
    private val stageJobs = ConcurrentHashMap<String, Job>()
    private var samplerJob: Job? = null

    private val lastState = ConcurrentHashMap<String, BatteryState>()
    private val sampleFailures = ConcurrentHashMap<String, Int>()
    @Volatile private var wakeSampler: CompletableDeferred<Unit>? = null

    private var onTelemetry: (String, Telemetry) -> Unit = { _, _ -> }
    private var onReachable: (String, Boolean) -> Unit = { _, _ -> }

    fun start(
        scope: CoroutineScope,
        targets: List<BmsTarget>,
        onTelemetry: (String, Telemetry) -> Unit,
        onReachable: (String, Boolean) -> Unit,
    ) {
        stop()
        this.scope = scope
        this.allTargets = targets.map { it.copy(address = it.address.trim().uppercase()) }
        this.onTelemetry = onTelemetry
        this.onReachable = onReachable
        running = true
        samplerJob = scope.launch(Dispatchers.IO) { samplerLoop() }
    }

    /** Set which batteries are on the stage (persistent, fast poll). The rest get sampled. */
    fun setStage(addresses: Set<String>) {
        val s = scope ?: return
        val want = addresses.map { it.uppercase() }.toSet()
        stageAddrs = want
        // Stop workers no longer on stage; their batteries return to the sampler pool.
        stageJobs.keys.filter { it !in want }.forEach { addr ->
            stageJobs.remove(addr)?.cancel()
        }
        // Start a persistent worker for each newly-staged battery.
        want.forEach { addr ->
            if (stageJobs[addr] == null) {
                val target = allTargets.firstOrNull { it.address == addr } ?: return@forEach
                stageJobs[addr] = s.launch(Dispatchers.IO) { stageWorker(target) }
            }
        }
        wakeSampler?.complete(Unit)  // pool changed
    }

    /** Update the full target set live (roster add/remove). Sampler picks it up next loop. */
    fun setTargets(targets: List<BmsTarget>) {
        allTargets = targets.map { it.copy(address = it.address.trim().uppercase()) }
        val present = allTargets.map { it.address }.toSet()
        stageJobs.keys.filter { it !in present }.forEach { addr -> stageJobs.remove(addr)?.cancel() }
        wakeSampler?.complete(Unit)
    }

    /** Reset/retry everything immediately (e.g. app returned to foreground). */
    fun kickAll() {
        wakeSampler?.complete(Unit)
    }

    fun stop() {
        running = false
        stageJobs.values.forEach { it.cancel() }
        stageJobs.clear()
        samplerJob?.cancel()
        samplerJob = null
        stageAddrs = emptySet()
        lastState.clear()
        sampleFailures.clear()
    }

    // --- persistent stage worker: hold the link, poll fast ---
    private suspend fun stageWorker(target: BmsTarget) {
        var backoff = 2000L
        while (running && target.address in stageAddrs) {
            val session = BleSession(context, target.address)
            try {
                val ok = gate.withPermit { session.connect(10_000) }
                if (!ok) throw IllegalStateException("connect failed")
                onReachable(target.address, true)
                backoff = 2000L
                while (running && target.address in stageAddrs) {
                    val data = session.poll(4000) ?: break  // a miss → reconnect
                    BmsProtocol.parseTelemetry(data, target.name)?.let {
                        lastState[target.address] = it.state
                        onTelemetry(target.address, it)
                    }
                    delay(STAGE_POLL_MS)
                }
                session.close()
            } catch (e: CancellationException) {
                session.close(); throw e
            } catch (e: Exception) {
                Log.d(TAG, "stage ${target.address}: ${e.message}")
                session.close()
                onReachable(target.address, false)
                if (running && target.address in stageAddrs) delay(backoff)
                backoff = (backoff * 2).coerceAtMost(12_000L)
            }
        }
    }

    /** User-disconnected batteries: drop their links and don't sample/stage them. */
    fun setDisabled(addresses: Set<String>) {
        disabledAddrs = addresses.map { it.uppercase() }.toSet()
        stageJobs.keys.filter { it in disabledAddrs }.forEach { addr ->
            stageJobs.remove(addr)?.cancel()
            onReachable(addr, false)
        }
        wakeSampler?.complete(Unit)
    }

    // --- rotating sampler: connect → read → disconnect, leisurely, priority-ordered ---
    private suspend fun samplerLoop() {
        while (running) {
            val pool = allTargets.filter { it.address !in stageAddrs && it.address !in disabledAddrs }
            if (pool.isEmpty()) { waitOrWake(1500); continue }
            // discharging first, then charging, then idle, then unknown
            val ordered = pool.sortedBy { rank(lastState[it.address]) }
            for (batch in ordered.chunked(SAMPLER_CONCURRENCY)) {
                if (!running) break
                coroutineScope {
                    batch.map { t -> launch { sampleOne(t) } }.joinAll()
                }
                waitOrWake(SAMPLER_GAP_MS)
            }
        }
    }

    private suspend fun sampleOne(t: BmsTarget) {
        if (t.address in stageAddrs) return
        val session = BleSession(context, t.address)
        try {
            val ok = gate.withPermit { session.connect(8000) }
            val tel = if (ok) session.poll(4000)?.let { BmsProtocol.parseTelemetry(it, t.name) } else null
            if (tel != null) {
                sampleFailures[t.address] = 0
                lastState[t.address] = tel.state
                onTelemetry(t.address, tel)
                onReachable(t.address, true)
            } else {
                markSampleFail(t.address)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            markSampleFail(t.address)
        } finally {
            session.close()
        }
    }

    /**
     * Only flag a sampled battery out-of-range after it misses [SAMPLE_FAIL_LIMIT] polls in a
     * row. The sampler connects → reads → disconnects on purpose, and a single connect attempt
     * often fails transiently on a flaky adapter; we don't want those deliberate cycles to read
     * as "disconnected". Each successful sample resets the counter.
     */
    private fun markSampleFail(address: String) {
        val f = (sampleFailures[address] ?: 0) + 1
        sampleFailures[address] = f
        if (f >= SAMPLE_FAIL_LIMIT) onReachable(address, false)
    }

    /** Sleep up to [ms], but wake early on kick()/stage change. */
    private suspend fun waitOrWake(ms: Long) {
        val w = CompletableDeferred<Unit>()
        wakeSampler = w
        withTimeoutOrNull(ms) { w.await() }
        wakeSampler = null
    }

    private fun rank(state: BatteryState?): Int = when (state) {
        BatteryState.Discharging -> 0
        BatteryState.Charging -> 1
        BatteryState.Idle -> 2
        else -> 3
    }

    private companion object {
        const val TAG = "BmsRepository"
        const val SAMPLER_CONCURRENCY = 2
        const val SAMPLER_GAP_MS = 3000L  // leisurely pacing between sample batches
        const val SAMPLE_FAIL_LIMIT = 3   // consecutive failed samples before "out of range"
    }
}
