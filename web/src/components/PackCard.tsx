import { memo } from "react";
import { formatTemp, tempZone, type TempEnvelope, type TempThresholds, type TempUnit } from "../temp";
import type { FleetItem } from "../types";
import { Ago } from "./Ago";
import { PinButton } from "./PinButton";
import { Ring } from "./Ring";

const Stat = ({ label, value, color }: { label: string; value: string; color: string }) => (
  <div style={{ background: "var(--input-bg)", borderRadius: 8, padding: "8px 10px" }}>
    <div style={{ color: "var(--text3)", fontSize: 10, letterSpacing: 1 }} className="mono">{label}</div>
    <div className="mono" style={{ color, fontSize: 15, fontWeight: 600 }}>{value}</div>
  </div>
);

// WEB-8: memoized so per-sample re-renders don't redraw the whole grid. The
// 1 s "ago" clock is deliberately NOT a `now` prop: the card takes the stable
// `lastSeenMs` (only for stale packs) and the self-ticking <Ago> leaf renders
// the live age text, so the memoized card itself never re-renders on the tick.
// `onTogglePin` takes the address so the parent can pass one stable callback.
export const PackCard = memo(function PackCard({ item, stale, lastSeenMs, thr, env, unit, pinned, onTogglePin }:
  { item: FleetItem; stale: boolean; lastSeenMs: number | null; thr: TempThresholds; env: TempEnvelope;
    unit: TempUnit; pinned: boolean; onTogglePin: (addr: string) => void }) {
  const connected = !stale;
  const n = (v: number | null | undefined, d = 1) => (v == null ? "—" : v.toFixed(d));
  // Disconnected packs keep their last-known telemetry, just muted — we still want to see it.
  const valColor = connected ? "var(--text)" : "var(--text3)";
  const tempCrit = connected && item.temp_c != null && tempZone(item.temp_c, thr, env).rank >= 3;
  const tempColor = tempCrit ? "var(--critical)" : valColor;
  const tempStr = item.temp_c != null ? formatTemp(item.temp_c, unit) : "—";
  return (
    <div style={{ background: "var(--card)", border: "1px solid var(--border)", borderRadius: 14,
      padding: 16, display: "flex", flexDirection: "column", alignItems: "center", gap: 10,
      minWidth: 220, opacity: connected ? 1 : 0.82 }}>
      <div style={{ display: "flex", alignItems: "center", gap: 8, width: "100%" }}>
        <div style={{ color: "var(--text2)", fontWeight: 600, flex: 1, minWidth: 0,
          overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
          {item.alias ?? item.address}
        </div>
        <PinButton pinned={pinned} onToggle={() => onTogglePin(item.address)} />
      </div>
      <Ring soc={item.soc ?? null} current={item.current_a ?? null} power={item.power_w ?? null}
        connected={connected} size={120} />
      {/* Fixed-height status slot: last-seen time when disconnected, so the layout never reflows. */}
      <div style={{ height: 14, display: "flex", alignItems: "center" }}>
        {!connected && lastSeenMs != null && (
          <span className="mono" style={{ color: "var(--text3)", fontSize: 11, letterSpacing: 1 }}>
            DISCONNECTED · <Ago tsMs={lastSeenMs} />
          </span>
        )}
      </div>
      <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 8, width: "100%" }}>
        <Stat label="POWER" value={`${n(item.power_w, 0)} W`} color={valColor} />
        <Stat label="CURRENT" value={`${n(item.current_a)} A`} color={valColor} />
        <Stat label="VOLTAGE" value={`${n(item.voltage_v, 2)} V`} color={valColor} />
        <Stat label="TEMP" value={tempStr} color={tempColor} />
      </div>
    </div>
  );
});
