import { useCallback, useMemo, useState } from "react";
import { useLocalStorage, type Codec } from "../useLocalStorage";
import { useV2Settings } from "./useV2Settings";
import { useTheme, type ThemeMode } from "./useTheme";
import { useWinWidth } from "./useWinWidth";
import { resolveMobile } from "./settings";
import { Nav } from "./components/Nav";
import { TopBar } from "./components/TopBar";
import { BottomTabs } from "./components/BottomTabs";
import { Placeholder } from "./components/Placeholder";
import { CommandView } from "./views/CommandView";
import { HealthView } from "./views/HealthView";
import { HistoryView } from "./views/HistoryView";
import { AlertsView } from "./views/AlertsView";
import { SettingsView } from "./views/SettingsView";
import { JourneyView } from "./views/JourneyView";
import { useFleetData } from "./useFleetData";
import { useV2Configs } from "./useV2Configs";
import { useHistory } from "./useHistory";
import { deriveAlerts } from "./model/alerts";
import type { V2View } from "./nav";

const viewCodec: Codec<V2View> = {
  decode: (r) => (["command","health","journey","history","alerts","settings"].includes(r) ? (r as V2View) : null),
  encode: (v) => v,
};
const boolCodec: Codec<boolean> = { decode: (r) => (r === "1" ? true : r === "0" ? false : null), encode: (v) => (v ? "1" : "0") };
const THEME_CYCLE: ThemeMode[] = ["system", "light", "dark"];

export default function App() {
  const [settings, patch] = useV2Settings();
  const resolvedTheme = useTheme(settings.themeMode);
  const [view, setView] = useLocalStorage<V2View>("bmsmon-v2-view", () => "command", viewCodec);
  const [collapsed, setCollapsed] = useLocalStorage<boolean>("bmsmon-v2-nav", () => false, boolCodec);
  const mobile = resolveMobile(settings.deviceMode, useWinWidth());
  const cycleTheme = useCallback(() => patch({
    themeMode: THEME_CYCLE[(THEME_CYCLE.indexOf(settings.themeMode) + 1) % 3],
  }), [patch, settings.themeMode]);
  const toggleDevice = useCallback(() => patch({
    deviceMode: settings.deviceMode === "mobile" ? "desktop" : "mobile",
  }), [patch, settings.deviceMode]);

  // The single live data store for v2 — owned here and passed down. CommandView
  // must NOT call useFleetData itself or it would open a second store + WS.
  const data = useFleetData();
  const tempF = settings.tempUnitPref === "F";

  const { tempConfig } = useV2Configs();
  const history = useHistory();
  const alerts = useMemo(
    () => deriveAlerts(data.items, data.staleAddrs, tempConfig),
    [data.items, data.staleAddrs, tempConfig],
  );
  const [acked, setAcked] = useState<Set<string>>(new Set());
  const ack = useCallback((id: string) => setAcked((p) => new Set(p).add(id)), []);
  const unacked = alerts.filter((a) => !acked.has(a.id)).length;

  const content =
    view === "command" ? <CommandView data={data} mobile={mobile} onOpen={setView} tempF={tempF} /> :
    view === "health" ? <HealthView data={data} history={history} unit={settings.tempUnitPref} mobile={mobile} /> :
    view === "journey" ? <JourneyView data={data} theme={resolvedTheme} unit={settings.tempUnitPref} mobile={mobile} /> :
    view === "history" ? <HistoryView data={data} unit={settings.tempUnitPref} mobile={mobile} /> :
    view === "alerts" ? <AlertsView alerts={alerts} acked={acked} onAck={ack} now={data.now} /> :
    <SettingsView />;

  return (
    <div style={{ display: "flex", minHeight: "100vh" }}>
      {!mobile && (
        <Nav view={view} collapsed={collapsed} unackedCount={unacked}
          onSelect={setView} onToggleCollapse={() => setCollapsed((c) => !c)} />
      )}
      <div style={{ flex: 1, display: "flex", flexDirection: "column", minWidth: 0 }}>
        <TopBar view={view} live={data.live} gps={data.gps} synced={data.live}
          themeMode={settings.themeMode} mobile={mobile}
          onCycleTheme={cycleTheme} onToggleDevice={toggleDevice} onSelectView={setView} />
        <main style={{ padding: mobile ? "16px 14px 76px" : 18, flex: 1 }}>{content}</main>
      </div>
      {mobile && <BottomTabs view={view} unackedCount={unacked} onSelect={setView} />}
    </div>
  );
}
