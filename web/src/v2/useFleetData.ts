import { useEffect, useMemo, useRef, useState, useSyncExternalStore } from "react";
import { createStore } from "../store";
import { connectLive } from "../ws";
import { getFleet, getRangeConfig } from "../api";
import { selectRangeParams, type RangeParams } from "../range";
import { stableSet } from "../util";
import { visibleInterval } from "../visiblePoll";
import type { FleetItem } from "../types";

// A pack is stale (treated as offline) if we haven't heard from it in 90 s —
// mirrors v1 App.tsx. The REST fallback polls the fleet snapshot every 10 s
// while the WS is down; applySnapshot merges through the store's ts-guard so a
// late/stale REST response can never regress fresher WS data.
const STALE_MS = 90_000;
const REST_FALLBACK_MS = 10_000;
// Staleness only needs coarse resolution against the 90 s threshold. It is
// re-checked on this cadence (and on every fleet change), NOT every second —
// components that render live age text subscribe to useNow(1000) themselves.
const STALE_TICK_MS = 5_000;

export interface FleetData {
  items: FleetItem[];
  staleAddrs: Set<string>;
  live: boolean;
  gps: boolean;
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
  const [rangeParams, setRangeParams] = useState<Map<string, RangeParams>>(new Map());

  useEffect(() => connectLive((f) => store.applySnapshot(f), store.applySample, setLive), [store]);
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
      const nowMs = Date.now();
      const next = new Set(items.filter((i) => nowMs - i.ts_ms > STALE_MS).map((i) => i.address));
      setStaleAddrs((prev) => stableSet(prev, next));
    };
    check();
    const t = setInterval(check, STALE_TICK_MS);
    return () => clearInterval(t);
  }, [items]);

  const gps = useMemo(
    () => items.some((i) => !staleAddrs.has(i.address) && i.lat != null), [items, staleAddrs]);

  // Stable data-object identity: consumers (and their effects) only see a new
  // object when one of the fields actually changed.
  return useMemo(
    () => ({ items, staleAddrs, live, gps, rangeParams }),
    [items, staleAddrs, live, gps, rangeParams]);
}
