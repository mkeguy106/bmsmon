import type { FleetItem } from "../types";
import { Ring } from "./Ring";

export function MainStage({ items, staleAddrs }:
  { items: FleetItem[]; staleAddrs: Set<string> }) {
  return (
    <div style={{ background: "var(--card)", border: "1px solid var(--border)", borderRadius: 16, padding: 24 }}>
      <div className="mono" style={{ color: "var(--text3)", fontSize: 11, letterSpacing: 2, marginBottom: 16 }}>MAIN STAGE</div>
      <div style={{ display: "flex", gap: 32, justifyContent: "center", flexWrap: "wrap" }}>
        {items.length === 0 && <div style={{ color: "var(--text3)" }}>No active base</div>}
        {items.map((it) => {
          const connected = !staleAddrs.has(it.address);
          return (
            <div key={it.address} style={{ textAlign: "center" }}>
              <div style={{ color: "var(--text)", fontWeight: 700, fontSize: 20, marginBottom: 12 }}>{it.alias ?? it.address}</div>
              <Ring soc={it.soc ?? null} connected={connected} size={200} />
              <div className="mono" style={{ color: "var(--power)", marginTop: 12, fontSize: 18 }}>
                {connected ? `${(it.power_w ?? 0).toFixed(0)} W · ${(it.current_a ?? 0).toFixed(1)} A` : "DISCONNECTED"}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
