import type { CSSProperties } from "react";
import { Icon, type IconName } from "./icons";
import { NAV_GROUPS, type V2View } from "../nav";
import type { ThemeMode } from "../useTheme";

const TOP_BAR_H = 53;

/** Command/Health/Journey, in NAV_GROUPS order — the desktop view-switcher circles. */
const SWITCHER_VIEWS: V2View[] = ["command", "health", "journey"];

const VIEW_META = new Map<V2View, { label: string; icon: IconName }>(
  NAV_GROUPS.flatMap((g) => g.items)
    .filter((i) => !i.disabled)
    .map((i) => [i.view, { label: i.label, icon: i.icon }]),
);

const THEME_STEPS: { mode: ThemeMode; icon: IconName; label: string }[] = [
  { mode: "system", icon: "monitor", label: "System" },
  { mode: "light", icon: "sun", label: "Light" },
  { mode: "dark", icon: "moon", label: "Dark" },
];

function Pill({ label, active }: { label: string; active: boolean }) {
  return (
    <span
      className="mono"
      style={{
        display: "flex",
        alignItems: "center",
        gap: 7,
        fontSize: 11,
        letterSpacing: 0.4,
        color: active ? "var(--ok)" : "var(--text-4)",
      }}
    >
      <span
        style={{
          width: 7,
          height: 7,
          borderRadius: "50%",
          background: active ? "var(--ok)" : "var(--text-4)",
        }}
      />
      {label}
    </span>
  );
}

function iconButton(active: boolean): CSSProperties {
  return {
    display: "flex",
    alignItems: "center",
    justifyContent: "center",
    width: 32,
    height: 32,
    borderRadius: "50%",
    border: "1px solid " + (active ? "var(--border-strong)" : "transparent"),
    background: active ? "var(--nav-active)" : "transparent",
    color: active ? "var(--text)" : "var(--text-3)",
    cursor: "pointer",
  };
}

export function TopBar({ view, live, gps, synced, themeMode, mobile, onCycleTheme, onToggleDevice, onSelectView }: {
  view: V2View; live: boolean; gps: boolean; synced: boolean;
  themeMode: ThemeMode; mobile: boolean;
  onCycleTheme: () => void; onToggleDevice: () => void; onSelectView: (v: V2View) => void;
}) {
  const title = VIEW_META.get(view)?.label ?? view;
  const themeStep = THEME_STEPS.find((s) => s.mode === themeMode) ?? THEME_STEPS[0];

  return (
    <header
      style={{
        height: TOP_BAR_H,
        flex: `0 0 ${TOP_BAR_H}px`,
        display: "flex",
        alignItems: "center",
        gap: 16,
        padding: "0 16px",
        background: "var(--panel)",
        borderBottom: "1px solid var(--border)",
      }}
    >
      <div style={{ display: "flex", alignItems: "center", gap: 10, minWidth: 0 }}>
        <span className="mono" style={{ fontSize: 15, fontWeight: 700, color: "var(--text)" }}>
          b<span style={{ color: "var(--text-4)" }}>v2</span>
        </span>
        <span style={{ width: 1, height: 16, background: "var(--border)" }} />
        <span style={{ fontSize: 14, fontWeight: 600, color: "var(--text)", whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>
          {title}
        </span>
      </div>

      <div style={{ flex: 1 }} />

      {!mobile && (
        <div style={{ display: "flex", alignItems: "center", gap: 6 }}>
          {SWITCHER_VIEWS.map((v) => {
            const meta = VIEW_META.get(v);
            if (!meta) return null;
            return (
              <button
                key={v}
                type="button"
                onClick={() => onSelectView(v)}
                title={meta.label}
                aria-label={meta.label}
                style={iconButton(v === view)}
              >
                <Icon name={meta.icon} size={16} />
              </button>
            );
          })}
        </div>
      )}

      {!mobile && (
        <div style={{ display: "flex", alignItems: "center", gap: 16 }}>
          <Pill label="LIVE" active={live} />
          <Pill label="GPS" active={gps} />
          <Pill label="SYNCED" active={synced} />
        </div>
      )}

      <button
        type="button"
        onClick={onCycleTheme}
        title={`Theme: ${themeStep.label} (click to cycle)`}
        aria-label={`Theme: ${themeStep.label}`}
        style={{
          display: "flex",
          alignItems: "center",
          gap: 6,
          padding: "6px 10px",
          borderRadius: 6,
          border: "1px solid var(--border)",
          background: "transparent",
          color: "var(--text-3)",
          cursor: "pointer",
          fontSize: 12,
        }}
      >
        <Icon name={themeStep.icon} size={15} />
        {!mobile && themeStep.label}
      </button>

      <button
        type="button"
        onClick={onToggleDevice}
        title="Toggle Desktop/Mobile layout"
        aria-label="Toggle Desktop/Mobile layout"
        style={{
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          width: 32,
          height: 32,
          borderRadius: 6,
          border: "1px solid var(--border)",
          background: "transparent",
          color: "var(--text-3)",
          cursor: "pointer",
        }}
      >
        <Icon name="monitor" size={16} />
      </button>
    </header>
  );
}
