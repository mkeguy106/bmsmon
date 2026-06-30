import { useEffect, useMemo, useRef, useState } from "react";
import { AdminDevices } from "./components/AdminDevices";
import { AllBatteries } from "./components/AllBatteries";
import { MainStage } from "./components/MainStage";
import { connectLive } from "./ws";
import { createStore } from "./store";
import type { FleetItem } from "./types";

// The phone polls background packs slowly (a pack can go ~a minute between reports), so only
// treat a pack as disconnected after a generous gap — otherwise cards flap to DISCONNECTED.
const STALE_MS = 90_000;

export default function App() {
  const store = useRef(createStore()).current;
  const [v, force] = useState(0);
  const [live, setLive] = useState(false);
  const [now, setNow] = useState(Date.now());

  useEffect(() => {
    const unsub = store.subscribe(() => force((n) => n + 1));
    return () => { unsub(); };
  }, [store]);
  useEffect(() => connectLive(store.applySnapshot, store.applySample, setLive), [store]);
  useEffect(() => { const t = setInterval(() => setNow(Date.now()), 1000); return () => clearInterval(t); }, []);

  const items: FleetItem[] = useMemo(
    () => Object.values(store.getFleet()).sort((a, b) => (a.alias ?? "").localeCompare(b.alias ?? "")),
    [store, now, v],
  );
  const staleAddrs = useMemo(
    () => new Set(items.filter((i) => now - i.ts_ms > STALE_MS).map((i) => i.address)),
    [items, now],
  );
  const gpsActive = useMemo(
    () => items.some((i) => !staleAddrs.has(i.address) && i.lat != null),
    [items, staleAddrs],
  );

  // Main stage = the WHOLE active base (group), not a single pack. The lead pack is a fresh
  // discharging pack, else the most-recently-updated pack; the stage then shows every pack in
  // that pack's group, stably ordered, so it doesn't jump as packs poll in and out.
  const stageItems = useMemo(() => {
    const fresh = items.filter((i) => !staleAddrs.has(i.address));
    const byRecent = (a: FleetItem, b: FleetItem) => b.ts_ms - a.ts_ms;
    const lead =
      fresh.find((i) => (i.current_a ?? 0) < -0.1) ??
      fresh.slice().sort(byRecent)[0] ??
      items.slice().sort(byRecent)[0];
    if (!lead) return [];
    const gid = lead.group_id;
    if (!gid) return [lead];
    return items.filter((i) => i.group_id === gid)
      .sort((a, b) => (a.alias ?? "").localeCompare(b.alias ?? ""));
  }, [items, staleAddrs]);

  return (
    <div style={{ maxWidth: 1400, margin: "0 auto", padding: 24 }}>
      <header style={{ display: "flex", alignItems: "center", gap: 12, marginBottom: 24 }}>
        <h1 style={{ fontSize: 22, margin: 0 }}>bmsmon</h1>
        <span style={{ marginLeft: "auto", display: "flex", alignItems: "center", gap: 8,
          color: live ? "var(--regen)" : "var(--text3)", fontSize: 13 }}>
          <span style={{ width: 9, height: 9, borderRadius: "50%",
            background: live ? "var(--regen)" : "var(--text3)" }} />
          {live ? "LIVE" : "RECONNECTING…"}
        </span>
        <span style={{ display: "flex", alignItems: "center", gap: 8,
          color: gpsActive ? "var(--regen)" : "var(--text3)", fontSize: 13 }}>
          <span style={{ width: 9, height: 9, borderRadius: "50%",
            background: gpsActive ? "var(--regen)" : "var(--text3)" }} />
          GPS
        </span>
      </header>
      <div style={{ display: "grid", gap: 24 }}>
        <MainStage items={stageItems} staleAddrs={staleAddrs} />
        <AllBatteries items={items} staleAddrs={staleAddrs} />
        <AdminDevices />
      </div>
    </div>
  );
}
