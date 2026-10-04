import { decodeSample, decodeSnapshot } from "./decode";
import {
  PROBE_AFTER_FAILS, probeSession, reconnectDelayMs, sessionFromClose, sessionFromProbe,
  type ProbeResult, type Session,
} from "./liveLink";
import type { FleetItem, Sample } from "./types";

// The server pushes a keepalive ({type:"ping"}) every ~25s when no telemetry is
// flowing, so any silence longer than this means the socket is dead (frozen tab,
// slept machine, dropped proxy) rather than merely an idle fleet.
const STALE_MS = 60_000;
/** A socket must stay up this long after its snapshot before the backoff resets. A server that
 *  crashes right after the snapshot would otherwise be retried every ~1 s forever. */
export const STABLE_MS = 30_000;

export interface LiveOptions {
  /** Called when the session verdict changes (liveLink.ts Session). It starts as "ok". */
  onSession?: (s: Session) => void;
  /** The HTTP session probe; injected by tests. Default: liveLink.probeSession. */
  probe?: () => Promise<ProbeResult>;
  /** The jitter source, in [0, 1]; injected by tests. Default: Math.random. */
  random?: () => number;
}

/**
 * The live WebSocket link (WEB-26).
 * - onStatus(true) fires on the first decoded SNAPSHOT, not on `open`. A server that accepts
 *   and then closes (an auth reject, an overload) has delivered nothing, and reporting it as
 *   live kept the REST fallback from ever running. onStatus(false) fires when the current
 *   socket closes after that.
 * - Reconnects back off exponentially with jitter (liveLink.reconnectDelayMs); a socket that
 *   delivers a snapshot and stays up STABLE_MS resets the backoff.
 * - A 4401/4403 close, or PROBE_AFTER_FAILS sockets in a row that delivered nothing followed
 *   by an HTTP probe, gives a session verdict for onSession, so an expired sign-in reads as
 *   "session expired" and not as a phone that went quiet. A probe verdict that lands after a
 *   socket has delivered a snapshot is dropped: the link recovered while it was in flight.
 */
export function connectLive(
  onSnapshot: (f: FleetItem[]) => void,
  onSample: (s: Sample) => void,
  onStatus: (connected: boolean) => void,
  opts: LiveOptions = {},
) {
  const probe = opts.probe ?? probeSession;
  const random = opts.random ?? Math.random;
  let ws: WebSocket | null = null;
  let stop = false;
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  let watchdog: ReturnType<typeof setInterval> | null = null;
  let lastMsg = 0;
  /** The current socket has delivered a snapshot. */
  let healthy = false;
  /** performance.now() of the current socket's first snapshot. */
  let healthySince = 0;
  /** onStatus(true) was the last status reported. */
  let reportedLive = false;
  /** Consecutive sockets that closed without delivering a snapshot. */
  let failures = 0;
  let session: Session = "ok";
  let probing = false;
  /** Bumped each time a socket delivers its first snapshot. A probe verdict that lands after
   *  a snapshot arrived describes a link that has since recovered, so it is dropped; else a
   *  slow probe's "expired" or "unreachable" would stick for the healthy socket's whole life,
   *  since report("ok") fires only on a socket's first snapshot. */
  let snapshotEpoch = 0;

  const report = (s: Session) => {
    if (stop || s === session) return;
    session = s;
    opts.onSession?.(s);
  };

  const runProbe = () => {
    if (probing) return;
    probing = true;
    const epoch = snapshotEpoch;
    const verdict = (s: Session) => { if (epoch === snapshotEpoch) report(s); };
    probe()
      .then((p) => verdict(sessionFromProbe(p)), () => verdict("unreachable"))
      .finally(() => { probing = false; });
  };

  const clearReconnect = () => {
    if (reconnectTimer) { clearTimeout(reconnectTimer); reconnectTimer = null; }
  };

  const scheduleReconnect = () => {
    if (stop || reconnectTimer) return;
    const delay = reconnectDelayMs(failures, random());
    reconnectTimer = setTimeout(() => { reconnectTimer = null; open(); }, delay);
  };

  const open = () => {
    if (stop) return;
    clearReconnect();
    // Replacing an existing socket: detach its handlers before closing so its
    // async onclose can't schedule a spurious reconnect (which would orphan the
    // new socket) and its onmessage can't keep feeding the store.
    if (ws) {
      const old = ws;
      ws = null;
      old.onopen = null; old.onmessage = null; old.onclose = null; old.onerror = null;
      old.close();
    }
    const proto = location.protocol === "https:" ? "wss" : "ws";
    const sock = new WebSocket(`${proto}://${location.host}/ws`);
    ws = sock;
    healthy = false;
    // Every handler bails if this socket is no longer the current one — events
    // from a replaced socket (in-flight close/message) must never touch state.
    sock.onopen = () => {
      if (ws !== sock) return;
      lastMsg = performance.now();
    };
    sock.onmessage = (e) => {
      if (ws !== sock) return;
      lastMsg = performance.now();
      // WEB-4: a malformed frame must never kill the socket — warn + drop it.
      let msg: unknown;
      try { msg = JSON.parse(e.data); } catch (err) {
        console.warn("bmsmon: ignoring malformed WS frame", err);
        return;
      }
      if (typeof msg !== "object" || msg === null) return;
      const m = msg as { type?: unknown; fleet?: unknown };
      if (m.type === "snapshot") {
        const fleet = decodeSnapshot(m.fleet);
        if (fleet) {
          onSnapshot(fleet);
          if (!healthy) { healthy = true; healthySince = performance.now(); snapshotEpoch++; report("ok"); }
          if (!reportedLive) { reportedLive = true; onStatus(true); }
        }
      } else if (m.type === "sample") {
        // The whitelist decoder also strips the WS "type" tag.
        const s = decodeSample(msg);
        if (s) onSample(s);
      }
      // {type:"ping"} keepalives just refresh lastMsg above.
    };
    sock.onclose = (e) => {
      if (ws !== sock) return;
      if (reportedLive) { reportedLive = false; onStatus(false); }
      if (healthy) {
        // Only a socket that stayed up long enough proves the server is well; a quick
        // snapshot-then-die keeps counting, so the backoff keeps growing.
        if (performance.now() - healthySince >= STABLE_MS) failures = 0;
        else failures++;
      } else {
        failures++;
        const verdict = sessionFromClose(e?.code ?? 1006);
        if (verdict) report(verdict);
        else if (failures >= PROBE_AFTER_FAILS) runProbe();
      }
      scheduleReconnect();
    };
    sock.onerror = () => { if (ws === sock) sock.close(); };
  };

  // Watchdog: if we've heard nothing (not even a keepalive) for STALE_MS, the
  // socket is a zombie — force a reconnect.
  watchdog = setInterval(() => {
    if (stop || !ws || ws.readyState !== WebSocket.OPEN) return;
    if (performance.now() - lastMsg > STALE_MS) ws.close();
  }, STALE_MS / 2);

  // A backgrounded tab that got frozen/discarded (overnight, sleep) stops its
  // timers; on refocus, reconnect immediately if the socket isn't clearly live.
  const onVisible = () => {
    if (stop || document.visibilityState !== "visible") return;
    const dead = !ws || ws.readyState !== WebSocket.OPEN
      || performance.now() - lastMsg > STALE_MS;
    // open() detaches + closes the old socket itself, so this can't race the
    // old socket's async onclose against the fresh connection.
    if (dead) {
      // The replacement is still CONNECTING: LIVE must drop until it delivers a snapshot, and
      // the REST fallback must run meanwhile (open() detaches the old socket's onclose).
      if (reportedLive) { reportedLive = false; onStatus(false); }
      open();
    }
  };
  document.addEventListener("visibilitychange", onVisible);

  open();
  return () => {
    stop = true;
    clearReconnect();
    if (watchdog) clearInterval(watchdog);
    document.removeEventListener("visibilitychange", onVisible);
    if (ws) {
      const old = ws;
      ws = null;
      old.onopen = null; old.onmessage = null; old.onclose = null; old.onerror = null;
      old.close();
    }
  };
}
