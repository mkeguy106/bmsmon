// Dev-only visual harness for screenshots (no backend). /preview.html?s=<safe|warn|crit|hot>&
// theme=<dark|light>&pin=1. Not part of the production bundle.
import { createRoot } from "react-dom/client";
import { MainStage } from "./components/MainStage";
import { AllBatteries } from "./components/AllBatteries";
import { BatteryProfilePanel } from "./components/BatteryProfilePanel";
import { DEFAULT_ENV, REDODO_DEFAULTS, type TempConfig } from "./temp";
import type { FleetItem } from "./types";
import "./theme.css";

const params = new URLSearchParams(location.search);
if (params.get("theme") === "light") document.documentElement.dataset.theme = "light";
const scenario = params.get("s") ?? "warn";
const featTemp = ({ safe: 22, warn: -6, crit: -14, hot: 55 } as Record<string, number>)[scenario] ?? -6;

const NOW = 1782900000000;
const mk = (address: string, alias: string, soc: number, cur: number, temp: number, agoMs = 0): FleetItem => ({
  address, alias, group_id: "2012", ts_ms: NOW - agoMs, soc, current_a: cur,
  power_w: cur * 13.2, voltage_v: 13.2, temp_c: temp,
});
// Pack A live (featured); pack B disconnected ~5m ago (shows last-known, muted).
const items = [mk("A", "2012 · A", 78, -8.4, featTemp), mk("B", "2012 · B", 64, 0, 24, 5 * 60_000)];
const stale = new Set(["B"]);
const config: TempConfig = {
  device_id: "x", profile_id: "redodo-beken-bk-ble-1.0", cold_caution_c: 5, hot_caution_c: 45,
  cold_crit_c: -12, hot_crit_c: 53, unit: "F", updated_at_ms: NOW - 120_000, received_at: "",
};
const pinnedMode = params.get("pin") != null;
const pinned = new Set<string>(pinnedMode ? ["A"] : []);
// In pinned mode the App shows only the pinned pack on the stage (mirror that here).
const stageItems = pinnedMode ? items.filter((i) => pinned.has(i.address)) : items;
const noop = () => { /* static preview */ };
const settings = params.get("view") === "settings";

createRoot(document.getElementById("root")!).render(
  <div style={{ maxWidth: 1400, margin: "0 auto", padding: 24 }}>
    {settings ? (
      <>
        <div className="mono" style={{ color: "var(--text3)", fontSize: 11, letterSpacing: 2, margin: "0 4px 24px" }}>SETTINGS</div>
        <BatteryProfilePanel thr={REDODO_DEFAULTS} env={DEFAULT_ENV} />
      </>
    ) : (
      <>
        <MainStage items={stageItems} staleAddrs={stale} thr={REDODO_DEFAULTS} env={DEFAULT_ENV}
          unit="F" config={config} now={NOW} pinned={pinned} onTogglePin={noop} rangeParams={new Map()} />
        <div style={{ height: 24 }} />
        <AllBatteries items={items} staleAddrs={stale} thr={REDODO_DEFAULTS} env={DEFAULT_ENV}
          unit="F" now={NOW} pinned={pinned} onTogglePin={noop} />
      </>
    )}
  </div>,
);
