import { Suspense, lazy, useCallback, useEffect, useMemo, useState } from "react";
import { useLocalStorage, type Codec } from "../useLocalStorage";
import { useV2Settings } from "./useV2Settings";
import { useTheme, type ThemeMode } from "./useTheme";
import { useWinWidth } from "./useWinWidth";
import { resolveMobile } from "./settings";
import { Nav } from "./components/Nav";
import { TopBar } from "./components/TopBar";
import { ConnectionBanner } from "./components/ConnectionBanner";
import { connectionBanner } from "./model/connection";
import { BAR_H, BottomTabs } from "./components/BottomTabs";
import { CommandView } from "./views/CommandView";
import { HealthView } from "./views/HealthView";
import { HistoryView } from "./views/HistoryView";
import { AlertsView } from "./views/AlertsView";
import { SettingsView } from "./views/SettingsView";
import { useFleetData } from "./useFleetData";
import { useV2Configs } from "./useV2Configs";
import { useStageBase } from "./useStageBase";
import { seizeThresholdFrom } from "./model/stageBase";
import { ackAlert, deriveAlerts, pruneAcks, unackedCount, type AckMap, type V2Alert } from "./model/alerts";
import type { V2View } from "./nav";

const viewCodec: Codec<V2View> = {
  decode: (r) => (["command","health","journey","history","alerts","settings"].includes(r) ? (r as V2View) : null),
  encode: (v) => v,
};
const boolCodec: Codec<boolean> = { decode: (r) => (r === "1" ? true : r === "0" ? false : null), encode: (v) => (v ? "1" : "0") };
const THEME_CYCLE: ThemeMode[] = ["system", "light", "dark"];

// Journey is the only view that pulls in Leaflet (~150 kB min) — load it on demand so a
// Command-only session never downloads it. JourneyMap is imported ONLY by JourneyView
// (the share page has its own build with a static import), so the whole map stack splits
// cleanly into this lazy chunk.
const JourneyView = lazy(() =>
  import("./views/JourneyView").then((m) => ({ default: m.JourneyView })));

/** Suspense fallback while the Journey chunk loads — mirrors the app's CONNECTING style. */
function ViewLoading() {
  return (
    <div style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 10,
      padding: "96px 0", color: "var(--text-3)" }}>
      <span className="mono" style={{ fontSize: 12, letterSpacing: 2 }}>LOADING…</span>
    </div>
  );
}

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

  const { tempConfig, alertConfig } = useV2Configs();
  // One stage selection for every view (WEB-12): seize → pin → in use → hold → parked →
  // daily driver. Command, Journey and the Health hero all read this same answer. No seize
  // until the seize config is known (seizeThresholdFrom(null) → null).
  const stage = useStageBase(data, seizeThresholdFrom(alertConfig));
  const alerts = useMemo(
    () => deriveAlerts(data.items, data.staleAddrs, tempConfig),
    [data.items, data.staleAddrs, tempConfig],
  );
  // Acks live for the tab's lifetime (in memory). Each records the rank it was given at,
  // so a worse reading re-arms it, and it is dropped once its condition clears (WEB-17).
  const [acked, setAcked] = useState<AckMap>(() => new Map());
  useEffect(() => {
    setAcked((p) => pruneAcks(p, alerts, data.staleAddrs));
  }, [alerts, data.staleAddrs]);
  const ack = useCallback((a: V2Alert) => setAcked((p) => ackAlert(p, a)), []);
  const unacked = unackedCount(alerts, acked);
  const banner = connectionBanner(data.session);

  const content =
    view === "command" ? (
      <CommandView data={data} stage={stage} mobile={mobile} onOpen={setView} tempF={tempF}
        tempConfig={tempConfig} />
    ) :
    view === "health" ? <HealthView data={data} heroBase={stage.staged} unit={settings.tempUnitPref} mobile={mobile} /> :
    view === "journey" ? (
      <Suspense fallback={<ViewLoading />}>
        <JourneyView data={data} base={stage.staged} theme={resolvedTheme} unit={settings.tempUnitPref} mobile={mobile} mapMetric={settings.mapMetricPref} />
      </Suspense>
    ) :
    view === "history" ? <HistoryView data={data} unit={settings.tempUnitPref} mobile={mobile} /> :
    view === "alerts" ? <AlertsView alerts={alerts} acked={acked} onAck={ack} /> :
    <SettingsView />;

  const journeyMobile = mobile && view === "journey";

  return (
    <div style={{ display: "flex", ...(journeyMobile ? { height: "100dvh", overflow: "hidden" } : { minHeight: "100vh" }) }}>
      {!mobile && (
        <Nav view={view} collapsed={collapsed} unackedCount={unacked}
          onSelect={setView} onToggleCollapse={() => setCollapsed((c) => !c)} />
      )}
      <div style={{ flex: 1, display: "flex", flexDirection: "column", minWidth: 0 }}>
        <TopBar view={view} live={data.live} gps={data.gps} synced={data.synced}
          themeMode={settings.themeMode} mobile={mobile}
          onCycleTheme={cycleTheme} onToggleDevice={toggleDevice} onSelectView={setView} />
        {banner && <ConnectionBanner banner={banner} />}
        <main style={journeyMobile
          ? { padding: `0 0 calc(${BAR_H}px + env(safe-area-inset-bottom))`, flex: 1,
              display: "flex", flexDirection: "column", minHeight: 0 }
          : { padding: mobile ? `16px 14px calc(${BAR_H + 12}px + env(safe-area-inset-bottom))` : 18,
              flex: 1 }}>{content}</main>
      </div>
      {mobile && <BottomTabs view={view} unackedCount={unacked} onSelect={setView} />}
    </div>
  );
}
