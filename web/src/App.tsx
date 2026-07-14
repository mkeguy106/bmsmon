import { useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore } from "react";
import { AdminDevices } from "./components/AdminDevices";
import { AllBatteries } from "./components/AllBatteries";
import { MainStage } from "./components/MainStage";
import { BatteryProfilePanel } from "./components/BatteryProfilePanel";
import { getFleet, getTempConfig, getAlertConfig, getRangeConfig, DEFAULT_ALERT_CONFIG, type AlertConfig } from "./api";
import { connectLive } from "./ws";
import { createStore } from "./store";
import { selectStageItems } from "./stage";
import {
  envelopeFromConfig, selectActiveConfig, thresholdsFromConfig,
  type TempConfig, type TempUnit,
} from "./temp";
import { selectRangeParams, type RangeParams } from "./range";
import { readStored, useLocalStorage, type Codec } from "./useLocalStorage";
import { stableSet } from "./util";
import type { FleetItem } from "./types";

// The phone polls background packs slowly (a pack can go ~a minute between reports), so only
// treat a pack as disconnected after a generous gap — otherwise cards flap to DISCONNECTED.
const STALE_MS = 90_000;
// Staleness only needs coarse resolution against that 90 s threshold: re-check on this
// cadence (and on every fleet change). Visible age text ticks in <Ago>/useNow leaves.
const STALE_TICK_MS = 5_000;

// WEB-8: while the WS is down, fall back to REST snapshots at this cadence.
const REST_FALLBACK_MS = 10_000;

// Module-level codecs: useLocalStorage keys its setter identity on these.
const themeCodec: Codec<"dark" | "light"> = {
  decode: (raw) => (raw === "dark" || raw === "light" ? raw : null),
  encode: (v) => v,
};
const unitCodec: Codec<TempUnit> = {
  decode: (raw) => (raw === "C" || raw === "F" ? raw : null),
  encode: (v) => v,
};
const pinsCodec: Codec<Set<string>> = {
  decode: (raw) => {
    const a: unknown = JSON.parse(raw);
    return Array.isArray(a) ? new Set(a.filter((x): x is string => typeof x === "string")) : null;
  },
  encode: (s) => JSON.stringify([...s]),
};

export default function App() {
  const store = useRef(createStore()).current;
  // WEB-8: subscribe via useSyncExternalStore — the store's version counter is
  // the snapshot, so a change re-renders exactly once (no manual force-counter).
  const v = useSyncExternalStore(store.subscribe, store.getVersion);
  const [live, setLive] = useState(false);
  // WEB-10: false until the first fleet snapshot (WS or REST fallback) lands —
  // distinguishes "no data yet" from a genuinely empty fleet.
  const [booted, setBooted] = useState(false);

  // The index.html pre-paint script applies the stored theme to <html> before
  // React mounts, so storage (validated) and the DOM fallback always agree.
  const [theme, setTheme] = useLocalStorage<"dark" | "light">("bmsmon-theme",
    () => (document.documentElement.dataset.theme === "light" ? "light" : "dark"), themeCodec);
  const toggleTheme = () => {
    const next = theme === "dark" ? "light" : "dark";
    document.documentElement.dataset.theme = next;
    setTheme(next);
  };

  // Temperature alert config synced from the phone (read-only). Poll periodically.
  // WEB-6: pick the active config per profile (newest per profile_id, newest
  // overall wins) instead of blindly taking configs[0].
  const [tempConfig, setTempConfig] = useState<TempConfig | null>(null);
  useEffect(() => {
    let alive = true;
    const load = () => getTempConfig()
      .then((r) => { if (alive) setTempConfig(selectActiveConfig(r.configs)); })
      .catch(() => { /* keep last */ });
    load();
    const t = setInterval(load, 60_000);
    return () => { alive = false; clearInterval(t); };
  }, []);
  const thr = useMemo(() => thresholdsFromConfig(tempConfig), [tempConfig]);
  const env = useMemo(() => envelopeFromConfig(tempConfig), [tempConfig]);

  // Low-SOC stage-seize config synced from the phone (read-only). Polls on the
  // same cadence as the temp config. On fetch failure keep the default
  // (threshold 30) rather than blanking the override.
  const [alertConfig, setAlertConfig] = useState<AlertConfig>(DEFAULT_ALERT_CONFIG);
  useEffect(() => {
    let alive = true;
    const load = () => getAlertConfig()
      .then((c) => { if (alive) setAlertConfig(c); })
      .catch(() => { if (alive) setAlertConfig(DEFAULT_ALERT_CONFIG); });
    load();
    const t = setInterval(load, 60_000);
    return () => { alive = false; clearInterval(t); };
  }, []);
  // Alerts off → no seize; otherwise the pushed value, default 30 when absent.
  const seizeThreshold: number | null =
    alertConfig.alerts_on === false ? null : (alertConfig.seize_soc ?? 30);

  // Learned discharge-range bands synced from the phone (read-only mirror).
  const [rangeParams, setRangeParams] = useState<Map<string, RangeParams>>(new Map());
  useEffect(() => {
    let alive = true;
    const load = () => getRangeConfig()
      .then((r) => { if (alive) setRangeParams(selectRangeParams(r.configs)); })
      .catch(() => { /* keep last */ });
    load();
    const t = setInterval(load, 60_000);
    return () => { alive = false; clearInterval(t); };
  }, []);

  const [unit, setUnit, setUnitLocal] = useLocalStorage<TempUnit>("bmsmon-temp-unit", () => "F", unitCodec);
  // WEB-3: default to the phone's synced unit. The lazy initializer above runs
  // before tempConfig has loaded, so apply the synced unit when it arrives —
  // but only while the user hasn't chosen one (a stored choice always wins);
  // setUnitLocal deliberately does NOT persist, so the synced default never
  // masquerades as a user choice.
  useEffect(() => {
    if (tempConfig == null || readStored("bmsmon-temp-unit", unitCodec.decode) != null) return;
    setUnitLocal(tempConfig.unit === "C" ? "C" : "F");
  }, [tempConfig, setUnitLocal]);
  const toggleUnit = () => setUnit(unit === "F" ? "C" : "F");

  useEffect(() => connectLive(
    (f) => { store.applySnapshot(f); setBooted(true); },
    store.applySample,
    setLive,
  ), [store]);

  // WEB-8: REST fallback — if the WS stays down for a fallback period, poll the
  // fleet snapshot over REST until it reconnects. applySnapshot merges through
  // the store's ts-guard, so a late/stale REST response can never regress
  // fresher WS data. First fetch fires REST_FALLBACK_MS after the WS drops.
  useEffect(() => {
    if (live) return;
    let alive = true;
    const t = setInterval(() => {
      getFleet()
        .then((r) => {
          if (!alive) return;
          store.applySnapshot(r.fleet);
          setBooted(true);
        })
        .catch(() => { /* still down — retry on the next tick */ });
    }, REST_FALLBACK_MS);
    return () => { alive = false; clearInterval(t); };
  }, [live, store]);

  // v (the store version) is the real dependency: the fleet only changes when it bumps.
  const items: FleetItem[] = useMemo(
    () => Object.values(store.getFleet()).sort((a, b) => (a.alias ?? "").localeCompare(b.alias ?? "")),
    [store, v],
  );
  // Identity-stable staleness: recompute on a coarse tick (and whenever the fleet
  // changes), but only publish a NEW Set when membership actually changed —
  // stableSet + the functional setState make React bail out entirely otherwise,
  // so the old 1 Hz whole-app re-render is gone.
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
  const gpsActive = useMemo(
    () => items.some((i) => !staleAddrs.has(i.address) && i.lat != null),
    [items, staleAddrs],
  );

  // Pinned packs (by address, persisted). If any are pinned, the main stage shows exactly
  // those; otherwise selectStageItems falls back to automatic base selection.
  const [pinned, setPinned] = useLocalStorage<Set<string>>("bmsmon-pins", () => new Set(), pinsCodec);
  const togglePin = useCallback((addr: string) => setPinned((prev) => {
    const next = new Set(prev);
    if (next.has(addr)) next.delete(addr); else next.add(addr);
    return next;
  }), [setPinned]);

  const stageItems = useMemo(
    () => selectStageItems(items, staleAddrs, pinned, seizeThreshold),
    [items, staleAddrs, pinned, seizeThreshold],
  );
  // True when the low-SOC seize override actually fired (a fresh pack at/below
  // the threshold is on stage) — drives the MainStage "LOW" marker.
  const lowSeized = useMemo(
    () => seizeThreshold != null &&
      items.some((i) => !staleAddrs.has(i.address) && i.soc != null && i.soc <= seizeThreshold),
    [items, staleAddrs, seizeThreshold],
  );

  const [view, setView] = useState<"dashboard" | "settings">("dashboard");

  return (
    <div style={{ maxWidth: 1400, margin: "0 auto", padding: 24 }}>
      <header style={{ display: "flex", alignItems: "center", gap: 12, marginBottom: 24 }}>
        <h1 style={{ fontSize: 22, margin: 0 }}>bmsmon</h1>
        <span style={{ marginLeft: "auto", display: "flex", alignItems: "center", gap: 8,
          color: live ? "var(--regen)" : "var(--text3)", fontSize: 13 }}>
          <span style={{ width: 9, height: 9, borderRadius: "50%",
            background: live ? "var(--regen)" : "var(--text3)" }} />
          {live ? "LIVE" : booted ? "RECONNECTING…" : "CONNECTING…"}
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
          <BatteryProfilePanel thr={thr} env={env} />
          <AdminDevices />
        </div>
      ) : !booted ? (
        // WEB-10: before the first snapshot we don't know the fleet yet — show a
        // connecting state instead of "No active base" over an empty grid.
        <div style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 10,
          padding: "96px 0", color: "var(--text3)" }}>
          <span className="mono" style={{ fontSize: 12, letterSpacing: 2 }}>CONNECTING…</span>
          <span style={{ fontSize: 13 }}>Waiting for the first fleet snapshot.</span>
        </div>
      ) : (
      <div style={{ display: "grid", gap: 24 }}>
        <MainStage items={stageItems} staleAddrs={staleAddrs}
          thr={thr} env={env} unit={unit} config={tempConfig}
          pinned={pinned} onTogglePin={togglePin} lowSeized={lowSeized} rangeParams={rangeParams} />
        <AllBatteries items={items} staleAddrs={staleAddrs} thr={thr} env={env} unit={unit}
          pinned={pinned} onTogglePin={togglePin} />
      </div>
      )}
    </div>
  );
}
