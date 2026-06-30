import type { FleetItem } from "../types";
import { Ring } from "./Ring";

const Stat = ({ label, value }: { label: string; value: string }) => (
  <div style={{ background: "var(--input-bg)", borderRadius: 8, padding: "8px 10px" }}>
    <div style={{ color: "var(--text3)", fontSize: 10, letterSpacing: 1 }} className="mono">{label}</div>
    <div className="mono" style={{ color: "var(--text)", fontSize: 15, fontWeight: 600 }}>{value}</div>
  </div>
);

export function PackCard({ item, stale }: { item: FleetItem; stale: boolean }) {
  const connected = !stale;
  const n = (v: number | null | undefined, d = 1) => (v == null ? "—" : v.toFixed(d));
  return (
    <div style={{ background: "var(--card)", border: "1px solid var(--border)", borderRadius: 14,
      padding: 16, display: "flex", flexDirection: "column", alignItems: "center", gap: 10,
      minWidth: 220 }}>
      <div style={{ color: "var(--text2)", fontWeight: 600 }}>{item.alias ?? item.address}</div>
      <Ring soc={item.soc ?? null} current={item.current_a ?? null} power={item.power_w ?? null}
        connected={connected} size={120} />
      {/* Fixed-height status slot: reserves space so DISCONNECTED never reflows the layout. */}
      <div style={{ height: 14, display: "flex", alignItems: "center" }}>
        {!connected && (
          <span className="mono" style={{ color: "var(--text3)", fontSize: 11, letterSpacing: 2 }}>
            DISCONNECTED
          </span>
        )}
      </div>
      <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 8, width: "100%" }}>
        <Stat label="POWER" value={connected ? `${n(item.power_w, 0)} W` : "—"} />
        <Stat label="CURRENT" value={connected ? `${n(item.current_a)} A` : "—"} />
        <Stat label="VOLTAGE" value={connected ? `${n(item.voltage_v, 2)} V` : "—"} />
        <Stat label="TEMP" value={connected ? `${n(item.temp_c, 0)}°C` : "—"} />
      </div>
    </div>
  );
}
