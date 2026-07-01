// Dev-only visual harness for screenshots of the temperature mirror (no backend). Served at
// /preview.html?s=<safe|warn|crit|hot>&theme=<dark|light>. Not part of the production bundle.
import { createRoot } from "react-dom/client";
import { MainStage } from "./components/MainStage";
import { BatteryProfilePanel } from "./components/BatteryProfilePanel";
import { REDODO_DEFAULTS, type TempConfig } from "./temp";
import type { FleetItem } from "./types";
import "./theme.css";

const params = new URLSearchParams(location.search);
if (params.get("theme") === "light") document.documentElement.dataset.theme = "light";
const scenario = params.get("s") ?? "warn";
const featTemp = ({ safe: 22, warn: -6, crit: -14, hot: 55 } as Record<string, number>)[scenario] ?? -6;

const NOW = 1782900000000;
const mk = (address: string, alias: string, soc: number, cur: number, temp: number): FleetItem => ({
  address, alias, group_id: "2012", ts_ms: NOW, soc, current_a: cur,
  power_w: cur * 13.2, voltage_v: 13.2, temp_c: temp,
});
const items = [mk("A", "2012 · A", 78, -8.4, featTemp), mk("B", "2012 · B", 64, 12.1, 24)];
const config: TempConfig = {
  device_id: "x", profile_id: "redodo-beken-bk-ble-1.0", cold_caution_c: 5, hot_caution_c: 45,
  cold_crit_c: -12, hot_crit_c: 53, unit: "F", updated_at_ms: NOW - 120_000, received_at: "",
};

createRoot(document.getElementById("root")!).render(
  <div style={{ maxWidth: 1040, margin: "0 auto", padding: 24 }}>
    <MainStage items={items} staleAddrs={new Set()} thr={REDODO_DEFAULTS} unit="F" config={config} now={NOW} />
    <div style={{ height: 24 }} />
    <BatteryProfilePanel thr={REDODO_DEFAULTS} unit="F" />
  </div>,
);
