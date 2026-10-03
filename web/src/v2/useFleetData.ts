import { useEffect, useMemo, useRef, useState, useSyncExternalStore } from "react";
import { createStore } from "../store";
import { connectLive } from "../ws";
import { getFleet, getRangeConfig } from "../api";
import { selectRangeParams, type RangeParams } from "../range";
import { stableSet } from "../util";
import { anyFresh, staleAddresses } from "../freshness";
import type { Session } from "../liveLink";
import { visibleInterval } from "../visiblePoll";
import type { FleetItem } from "../types";

// Pack staleness (90 s without telemetry, or a reported disconnect) is defined once in
// ../freshness.ts and shared with v1. The REST fallback polls the fleet snapshot every
// 10 s while the WS is down; applySnapshot merges through the store's ts-guard so a
// late/stale REST response can never regress fresher WS data.
const REST_FALLBACK_MS = 10_000;
// Staleness only needs coarse resolution against the 90 s threshold. It is
// re-checked on this cadence (and on every fleet change), NOT every second —
// components that render live age text subscribe to useNow(1000) themselves.
const STALE_TICK_MS = 5_000;

export interface FleetData {
  items: FleetItem[];
  staleAddrs: Set<string>;
  /** The live socket is delivering (ws.ts: from its first snapshot until it closes). */
  live: boolean;
  gps: boolean;
  /** At least one pack has fresh telemetry, i.e. the phone is uploading (WEB-27). */
  synced: boolean;
  /** The web session as the live link last judged it (liveLink.ts). */
  session: Session;
  rangeParams: Map<string, RangeParams>;
}

/**
 * The single live-data hook for v2. Owns the store + WS subscription + REST
 * fallback + the read-only learned-range config poll. Call ONCE at the top of
 * the v2 App and pass the result down — a second call would open a second store
 * and a second WS.
 */
export function useFleetData(): FleetData {
  const store = useRef(createStore()).current;
  // The store's version counter is the snapshot; useSyncExternalStore re-renders
  // exactly once per change (no manual force-counter).
  const v = useSyncExternalStore(store.subscribe, store.getVersion);
  const [live, setLive] = useState(false);
  const [session, setSession] = useState<Session>("ok");
  const [rangeParams, setRangeParams] = useState<Map<string, RangeParams>>(new Map());

  useEffect(() => connectLive(
    (f) => store.applySnapshot(f), store.applySample, setLive, { onSession: setSession }), [store]);
  useEffect(() => {
    if (live) return;
    // Visibility-gated: a hidden tab skips the fallback poll and catches up on refocus
    // (the WS reconnect path in ws.ts handles its own visibilitychange).
    return visibleInterval(() => { getFleet().then((r) => store.applySnapshot(r.fleet)).catch(() => {}); }, REST_FALLBACK_MS);
  }, [live, store]);
  useEffect(() => {
    let alive = true;
    const load = () => getRangeConfig().then((r) => { if (alive) setRangeParams(selectRangeParams(r.configs)); }).catch(() => {});
    load();
    const stop = visibleInterval(load, 60_000);
    return () => { alive = false; stop(); };
  }, []);

  // v (the store version) is the real dependency: the fleet only changes when it bumps.
  const items = useMemo(
    () => Object.values(store.getFleet()).sort((a, b) => (a.alias ?? "").localeCompare(b.alias ?? "")),
    [store, v]);

  // Identity-stable staleness: recompute on a coarse tick (and whenever the
  // fleet changes), but only publish a NEW Set when membership actually
  // changed — stableSet + the functional setState make React bail out
  // entirely otherwise, so nothing downstream re-renders on the tick.
  const [staleAddrs, setStaleAddrs] = useState<Set<string>>(() => new Set());
  useEffect(() => {
    const check = () => {
      const next = staleAddresses(items, Date.now());
      setStaleAddrs((prev) => stableSet(prev, next));
    };
    check();
    const t = setInterval(check, STALE_TICK_MS);
    return () => clearInterval(t);
  }, [items]);

  const gps = useMemo(
    () => items.some((i) => !staleAddrs.has(i.address) && i.lat != null), [items, staleAddrs]);
  const synced = useMemo(() => anyFresh(items, staleAddrs), [items, staleAddrs]);

  // Stable data-object identity: consumers (and their effects) only see a new
  // object when one of the fields actually changed.
  return useMemo(
    () => ({ items, staleAddrs, live, gps, synced, session, rangeParams }),
    [items, staleAddrs, live, gps, synced, session, rangeParams]);
}
