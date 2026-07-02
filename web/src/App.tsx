import { useEffect, useMemo, useRef, useState } from "react";
import { AdminDevices } from "./components/AdminDevices";
import { AllBatteries } from "./components/AllBatteries";
import { MainStage } from "./components/MainStage";
import { BatteryProfilePanel } from "./components/BatteryProfilePanel";
import { getTempConfig } from "./api";
import { connectLive } from "./ws";
import { createStore } from "./store";
import { thresholdsFromConfig, type TempConfig, type TempUnit } from "./temp";
import type { FleetItem } from "./types";

// The phone polls background packs slowly (a pack can go ~a minute between reports), so only
// treat a pack as disconnected after a generous gap — otherwise cards flap to DISCONNECTED.
const STALE_MS = 90_000;

// The user's explicit unit choice, if any. Garbage values are treated as absent.
const storedUnit = (): TempUnit | null => {
  try {
    const v = localStorage.getItem("bmsmon-temp-unit");
    return v === "C" || v === "F" ? v : null;
  } catch (e) { return null; }
};

export default function App() {
  const store = useRef(createStore()).current;
  const [v, force] = useState(0);
  const [live, setLive] = useState(false);
  const [now, setNow] = useState(Date.now());
  const [theme, setTheme] = useState<"dark" | "light">(
    () => (document.documentElement.dataset.theme === "light" ? "light" : "dark"),
  );
  const toggleTheme = () => {
    const next = theme === "dark" ? "light" : "dark";
    document.documentElement.dataset.theme = next;
    try { localStorage.setItem("bmsmon-theme", next); } catch (e) { /* not persisted */ }
    setTheme(next);
  };

  // Temperature alert config synced from the phone (read-only). Poll periodically.
  const [tempConfig, setTempConfig] = useState<TempConfig | null>(null);
  useEffect(() => {
    let alive = true;
    const load = () => getTempConfig()
      .then((r) => { if (alive) setTempConfig(r.configs[0] ?? null); })
      .catch(() => { /* keep last */ });
    load();
    const t = setInterval(load, 60_000);
    return () => { alive = false; clearInterval(t); };
  }, []);
  const thr = useMemo(() => thresholdsFromConfig(tempConfig), [tempConfig]);

  const [unit, setUnit] = useState<TempUnit>(() => storedUnit() ?? "F");
  // WEB-3: default to the phone's synced unit. The lazy initializer above runs
  // before tempConfig has loaded, so apply the synced unit when it arrives —
  // but only while the user hasn't chosen one (a stored choice always wins).
  useEffect(() => {
    if (tempConfig == null || storedUnit() != null) return;
    setUnit(tempConfig.unit === "C" ? "C" : "F");
  }, [tempConfig]);
  const toggleUnit = () => {
    const next: TempUnit = unit === "F" ? "C" : "F";
    try { localStorage.setItem("bmsmon-temp-unit", next); } catch (e) { /* not persisted */ }
    setUnit(next);
  };

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
  // Pinned packs (by address, persisted). If any are pinned, the main stage shows exactly those;
  // otherwise it falls back to automatic base selection.
  const [pinned, setPinned] = useState<Set<string>>(() => {
    try { return new Set(JSON.parse(localStorage.getItem("bmsmon-pins") || "[]") as string[]); }
    catch (e) { return new Set(); }
  });
  const togglePin = (addr: string) => setPinned((prev) => {
    const next = new Set(prev);
    if (next.has(addr)) next.delete(addr); else next.add(addr);
    try { localStorage.setItem("bmsmon-pins", JSON.stringify([...next])); } catch (e) { /* not persisted */ }
    return next;
  });

  const stageItems = useMemo(() => {
    const byAlias = (a: FleetItem, b: FleetItem) => (a.alias ?? "").localeCompare(b.alias ?? "");
    const pins = items.filter((i) => pinned.has(i.address));
    if (pins.length > 0) return pins.slice().sort(byAlias);
    const fresh = items.filter((i) => !staleAddrs.has(i.address));
    const byRecent = (a: FleetItem, b: FleetItem) => b.ts_ms - a.ts_ms;
    const lead =
      fresh.find((i) => (i.current_a ?? 0) < -0.1) ??
      fresh.slice().sort(byRecent)[0] ??
      items.slice().sort(byRecent)[0];
    if (!lead) return [];
    const gid = lead.group_id;
    if (!gid) return [lead];
    return items.filter((i) => i.group_id === gid).sort(byAlias);
  }, [items, staleAddrs, pinned]);

  const [view, setView] = useState<"dashboard" | "settings">("dashboard");

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
        <button
          onClick={toggleUnit}
          title="Toggle temperature unit"
          className="mono"
          style={{ background: "var(--input-bg)", border: "1px solid var(--input-border)", cursor: "pointer",
            color: "var(--text2)", fontSize: 12, letterSpacing: 1, padding: "6px 12px", borderRadius: 8 }}
        >
          °{unit}
        </button>
        <button
          onClick={toggleTheme}
          title={`Switch to ${theme === "dark" ? "light" : "dark"} theme`}
          aria-label={`Switch to ${theme === "dark" ? "light" : "dark"} theme`}
          style={{ background: "none", border: "none", cursor: "pointer", color: "var(--text2)",
            fontSize: 16, lineHeight: 1, padding: 4 }}
        >
          {theme === "dark" ? "☀" : "☾"}
        </button>
        <button
          onClick={() => setView((v) => (v === "settings" ? "dashboard" : "settings"))}
          title={view === "settings" ? "Back to dashboard" : "Settings"}
          aria-label={view === "settings" ? "Back to dashboard" : "Settings"}
          style={{ background: "none", border: "none", cursor: "pointer",
            color: view === "settings" ? "var(--accent)" : "var(--text2)",
            fontSize: 17, lineHeight: 1, padding: 4 }}
        >
          {view === "settings" ? "←" : "⚙"}
        </button>
      </header>
      {view === "settings" ? (
        <div style={{ display: "grid", gap: 24 }}>
          <div className="mono" style={{ color: "var(--text3)", fontSize: 11, letterSpacing: 2, margin: "0 4px" }}>
            SETTINGS
          </div>
          <BatteryProfilePanel thr={thr} unit={unit} />
          <AdminDevices />
        </div>
      ) : (
      <div style={{ display: "grid", gap: 24 }}>
        <MainStage items={stageItems} staleAddrs={staleAddrs}
          thr={thr} unit={unit} config={tempConfig} now={now}
          pinned={pinned} onTogglePin={togglePin} />
        <AllBatteries items={items} staleAddrs={staleAddrs} thr={thr} unit={unit}
          now={now} pinned={pinned} onTogglePin={togglePin} />
      </div>
      )}
    </div>
  );
}
