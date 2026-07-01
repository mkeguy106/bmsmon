import type { FleetItem, Sample } from "./types";

// The server pushes a keepalive ({type:"ping"}) every ~25s when no telemetry is
// flowing, so any silence longer than this means the socket is dead (frozen tab,
// slept machine, dropped proxy) rather than merely an idle fleet.
const STALE_MS = 60_000;
const RECONNECT_MS = 1_500;

export function connectLive(
  onSnapshot: (f: FleetItem[]) => void,
  onSample: (s: Sample) => void,
  onStatus: (connected: boolean) => void,
) {
  let ws: WebSocket | null = null;
  let stop = false;
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  let watchdog: ReturnType<typeof setInterval> | null = null;
  let lastMsg = 0;

  const clearReconnect = () => {
    if (reconnectTimer) { clearTimeout(reconnectTimer); reconnectTimer = null; }
  };

  const scheduleReconnect = (delay = RECONNECT_MS) => {
    if (stop || reconnectTimer) return;
    reconnectTimer = setTimeout(() => { reconnectTimer = null; open(); }, delay);
  };

  const open = () => {
    if (stop) return;
    clearReconnect();
    const proto = location.protocol === "https:" ? "wss" : "ws";
    ws = new WebSocket(`${proto}://${location.host}/ws`);
    ws.onopen = () => { lastMsg = performance.now(); onStatus(true); };
    ws.onmessage = (e) => {
      lastMsg = performance.now();
      const msg = JSON.parse(e.data);
      if (msg.type === "snapshot") onSnapshot(msg.fleet);
      else if (msg.type === "sample") { const { type: _t, ...s } = msg; onSample(s); }
      // {type:"ping"} keepalives just refresh lastMsg above.
    };
    ws.onclose = () => { onStatus(false); scheduleReconnect(); };
    ws.onerror = () => ws?.close();
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
    if (dead) { clearReconnect(); ws?.close(); open(); }
  };
  document.addEventListener("visibilitychange", onVisible);

  open();
  return () => {
    stop = true;
    clearReconnect();
    if (watchdog) clearInterval(watchdog);
    document.removeEventListener("visibilitychange", onVisible);
    ws?.close();
  };
}
