package dev.joely.bmsmon.ble

import dev.joely.bmsmon.ble.profile.BackoffSpec

/** Wait before reconnecting a pack whose healthy held link just dropped. */
const val RECONNECT_BACKOFF_MS = 2_000L

/**
 * Wait before reconnecting a pack whose held link just dropped. [garbageDrops] counts its drops in
 * a row whose session answered only with frames that would not decode (no decoded frame since).
 * Zero → the short [RECONNECT_BACKOFF_MS]. Otherwise the profile's connect-failure ladder: a pack
 * that connects fine but never sends a parsable frame used to cycle connect → 5 misses → drop →
 * 2 s every ~8–10 s on the stage, forever; now it backs off 5 → 10 → 20 s … up to the cap, and its
 * first decodable frame resets it.
 */
fun reconnectDelayMs(garbageDrops: Int, backoff: BackoffSpec): Long =
    if (garbageDrops <= 0) RECONNECT_BACKOFF_MS else backoff.delayFor(garbageDrops)

/** What the control loop does with a successful connect ([LinkLedger.connectSucceeded]). */
enum class ConnectVerdict {
    /** The current attempt, still wanted: hold it, start its poll loop, report the pack reachable. */
    HOLD,

    /** Not the current attempt (dropped while connecting, or superseded): close it, nothing else. */
    STALE,

    /** The current attempt, but the pack was disabled or removed meanwhile: close it. */
    UNWANTED,

    /** The current attempt, but a link is already held (the planner never asks — defensive): close it. */
    ALREADY_HELD,
}

/**
 * Per-address link bookkeeping for [BmsRepository]'s control loop — the BLE attempt model (T2.6,
 * BLE-15). Pure and single-threaded: only the control loop touches it, so it needs no locks, and it
 * holds no Android or coroutine types, so its rules are JVM-tested.
 *
 * Every connect attempt gets an id from [beginConnect]. Worker outcomes and poll events carry the
 * id of the attempt that produced them, and only the pack's CURRENT attempt changes state. A stale
 * one — its attempt was dropped while still connecting, or superseded by a newer one — changes
 * nothing, and a stale successful session is answered with "close it". That is what keeps a
 * Disconnect → Reconnect during a slow connect from holding (and polling) two GATT links to one
 * pack, an orphan that kept a disabled pack "live" and survived Stop.
 *
 * [S] is the session type ([BleSession] in production; a plain value in tests).
 */
class LinkLedger<S : Any> {

    private class Held<S>(val id: Long, val session: S, val since: Long) {
        var decoded = 0
        var undecoded = 0
    }

    private var nextId = 1L
    private val connecting = HashMap<String, Long>()
    private val held = HashMap<String, Held<S>>()
    private val failCount = HashMap<String, Int>()
    private val backoff = HashMap<String, Long>()
    private val garbageDrops = HashMap<String, Int>()

    val heldAddrs: Set<String> get() = held.keys.toSet()
    val connectingAddrs: Set<String> get() = connecting.keys.toSet()

    /** Earliest next connect time per pack (the planner's `backoffUntil`). */
    fun backoffSnapshot(): Map<String, Long> = HashMap(backoff)

    /** When each held link was established (the planner's rotation order). */
    fun heldSinceSnapshot(): Map<String, Long> = held.mapValues { it.value.since }

    fun heldSessions(): Map<String, S> = held.mapValues { it.value.session }

    /** Mint a new attempt for [addr] and make it the current one (an older one becomes stale). */
    fun beginConnect(addr: String): Long {
        val id = nextId++
        connecting[addr] = id
        return id
    }

    /** A worker's connect succeeded. [wanted] = the pack is still in the roster and not disabled. */
    fun connectSucceeded(addr: String, id: Long, session: S, wanted: Boolean, now: Long): ConnectVerdict {
        if (connecting[addr] != id) return ConnectVerdict.STALE
        connecting.remove(addr)
        if (addr in held) return ConnectVerdict.ALREADY_HELD
        if (!wanted) return ConnectVerdict.UNWANTED
        held[addr] = Held(id, session, now)
        failCount[addr] = 0
        backoff.remove(addr)
        return ConnectVerdict.HOLD
    }

    /**
     * A worker's connect failed. Null for a stale attempt — ignore it entirely: it must not clear
     * the CURRENT attempt's in-flight state, nor count a failure. Otherwise the failure is counted,
     * the profile's backoff applied, and the result says whether the pack has now failed
     * [failThreshold] times in a row (report it unreachable).
     */
    fun connectFailed(addr: String, id: Long, spec: BackoffSpec, failThreshold: Int, now: Long): Boolean? {
        if (connecting[addr] != id) return null
        connecting.remove(addr)
        val fc = (failCount[addr] ?: 0) + 1
        failCount[addr] = fc
        backoff[addr] = now + spec.delayFor(fc)
        return fc >= failThreshold
    }

    /** A frame from attempt [id]'s poll loop: true when it belongs to the held link (deliver it). */
    fun pollFrame(addr: String, id: Long, decoded: Boolean): Boolean {
        val h = held[addr]?.takeIf { it.id == id } ?: return false
        if (decoded) {
            h.decoded++
            garbageDrops.remove(addr)
        } else {
            h.undecoded++
        }
        return true
    }

    /**
     * Attempt [id]'s poll loop gave up on its link. Returns the session to close (and report the
     * pack unreachable), or null for a stale drop, which must not touch a newer link. A session that
     * answered only with undecodable frames extends the pack's garbage streak; a silent one leaves
     * it as is (see [reconnectDelayMs]).
     */
    fun pollDropped(addr: String, id: Long, spec: BackoffSpec, now: Long): S? {
        val h = held[addr]?.takeIf { it.id == id } ?: return null
        held.remove(addr)
        if (h.decoded == 0 && h.undecoded > 0) garbageDrops[addr] = (garbageDrops[addr] ?: 0) + 1
        backoff[addr] = now + reconnectDelayMs(garbageDrops[addr] ?: 0, spec)
        return h.session
    }

    /** The planner dropped [addr] (disabled, removed, or rotated out): forget its attempt — any
     *  outcome that attempt already posted becomes stale — and return the held session to close. */
    fun drop(addr: String): S? {
        connecting.remove(addr)
        return held.remove(addr)?.session
    }

    /** Retry everything now: clear every backoff, failure count and garbage streak. */
    fun kick() {
        backoff.clear()
        failCount.clear()
        garbageDrops.clear()
    }
}
