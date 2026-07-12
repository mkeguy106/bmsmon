import { useEffect, useMemo, useRef, useState, useSyncExternalStore } from "react";
import { createStore } from "../store";
import { connectLive } from "../ws";
import { getFleet, getRangeConfig } from "../api";
import { selectRangeParams, type RangeParams } from "../range";
import type { FleetItem } from "../types";

// A pack is stale (treated as offline) if we haven't heard from it in 90 s —
// mirrors v1 App.tsx. The REST fallback polls the fleet snapshot every 10 s
// while the WS is down; applySnapshot merges through the store's ts-guard so a
// late/stale REST response can never regress fresher WS data.
const STALE_MS = 90_000;
const REST_FALLBACK_MS = 10_000;

export interface FleetData {
  items: FleetItem[];
  staleAddrs: Set<string>;
  live: boolean;
  gps: boolean;
  rangeParams: Map<string, RangeParams>;
  now: number;
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
  const [now, setNow] = useState(Date.now());
  const [rangeParams, setRangeParams] = useState<Map<string, RangeParams>>(new Map());

  useEffect(() => connectLive((f) => store.applySnapshot(f), store.applySample, setLive), [store]);
  useEffect(() => { const t = setInterval(() => setNow(Date.now()), 1000); return () => clearInterval(t); }, []);
  useEffect(() => {
    if (live) return;
    const t = setInterval(() => { getFleet().then((r) => store.applySnapshot(r.fleet)).catch(() => {}); }, REST_FALLBACK_MS);
    return () => clearInterval(t);
  }, [live, store]);
  useEffect(() => {
    let alive = true;
    const load = () => getRangeConfig().then((r) => { if (alive) setRangeParams(selectRangeParams(r.configs)); }).catch(() => {});
    load();
    const t = setInterval(load, 60_000);
    return () => { alive = false; clearInterval(t); };
  }, []);

  // v (the store version) is the real dependency: the fleet only changes when it bumps.
  const items = useMemo(
    () => Object.values(store.getFleet()).sort((a, b) => (a.alias ?? "").localeCompare(b.alias ?? "")),
    [store, v]);
  const staleAddrs = useMemo(
    () => new Set(items.filter((i) => now - i.ts_ms > STALE_MS).map((i) => i.address)), [items, now]);
  const gps = useMemo(
    () => items.some((i) => !staleAddrs.has(i.address) && i.lat != null), [items, staleAddrs]);

  return { items, staleAddrs, live, gps, rangeParams, now };
}
