import type { FleetItem } from "../types";
import { Ring } from "./Ring";

export function MainStage({ items, staleAddrs }:
  { items: FleetItem[]; staleAddrs: Set<string> }) {
  const group = items[0]?.group_id;
  return (
    <div style={{ background: "var(--card)", border: "1px solid var(--border)", borderRadius: 16, padding: 24 }}>
      <div style={{ display: "flex", alignItems: "baseline", gap: 10, marginBottom: 16 }}>
        <span className="mono" style={{ color: "var(--text3)", fontSize: 11, letterSpacing: 2 }}>MAIN STAGE</span>
        {group && <span style={{ color: "var(--text2)", fontSize: 13 }}>Base {group}</span>}
      </div>
      <div style={{ display: "flex", gap: 40, justifyContent: "center", flexWrap: "wrap", minHeight: 280 }}>
        {items.length === 0 && (
          <div style={{ color: "var(--text3)", alignSelf: "center" }}>No active base</div>
        )}
        {items.map((it) => {
          const connected = !staleAddrs.has(it.address);
          const cur = it.current_a ?? 0;
          const flowColor = cur > 0.1 ? "var(--regen)" : cur < -0.1 ? "var(--power)" : "var(--text2)";
          return (
            <div key={it.address} style={{ textAlign: "center", minWidth: 220 }}>
              <div style={{ color: "var(--text)", fontWeight: 700, fontSize: 20, marginBottom: 12 }}>{it.alias ?? it.address}</div>
              <Ring soc={it.soc ?? null} current={it.current_a ?? null} power={it.power_w ?? null}
                connected={connected} size={200} />
              {/* Fixed-height readout slot so DISCONNECTED never shifts the layout. */}
              <div style={{ height: 24, marginTop: 12 }}>
                {connected ? (
                  <span className="mono" style={{ color: flowColor, fontSize: 18 }}>
                    {`${(it.power_w ?? 0).toFixed(0)} W · ${cur.toFixed(1)} A`}
                  </span>
                ) : (
                  <span className="mono" style={{ color: "var(--text3)", fontSize: 13, letterSpacing: 2 }}>DISCONNECTED</span>
                )}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
