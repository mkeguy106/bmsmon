import { useEffect, useMemo, useRef, useState } from "react";
import { AdminDevices } from "./components/AdminDevices";
import { AllBatteries } from "./components/AllBatteries";
import { MainStage } from "./components/MainStage";
import { connectLive } from "./ws";
import { createStore } from "./store";
import type { FleetItem } from "./types";

const STALE_MS = 10_000;

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
  // Main stage = discharging packs that are fresh; fall back to the most recently updated.
  const stage = items.filter((i) => !staleAddrs.has(i.address) && (i.current_a ?? 0) < -0.1);
  const stageItems = stage.length ? stage : items.slice().sort((a, b) => b.ts_ms - a.ts_ms).slice(0, 1);

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
      </header>
      <div style={{ display: "grid", gap: 24 }}>
        <MainStage items={stageItems} staleAddrs={staleAddrs} />
        <AllBatteries items={items} staleAddrs={staleAddrs} />
        <AdminDevices />
      </div>
    </div>
  );
}
