import type { TempEnvelope, TempThresholds, TempUnit } from "../temp";
import type { FleetItem } from "../types";
import { PackCard } from "./PackCard";

export function AllBatteries({ items, staleAddrs, thr, env, unit, pinned, onTogglePin }:
  { items: FleetItem[]; staleAddrs: Set<string>; thr: TempThresholds; env: TempEnvelope;
    unit: TempUnit; pinned: Set<string>; onTogglePin: (addr: string) => void }) {
  return (
    <div>
      <div className="mono" style={{ color: "var(--text3)", fontSize: 11, letterSpacing: 2, margin: "8px 4px 16px" }}>ALL BATTERIES</div>
      <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(220px, 1fr))", gap: 16 }}>
        {items.map((it) => {
          const stale = staleAddrs.has(it.address);
          return (
            <PackCard key={it.address} item={it} stale={stale}
              lastSeenMs={stale ? it.ts_ms : null}
              thr={thr} env={env} unit={unit}
              pinned={pinned.has(it.address)} onTogglePin={onTogglePin} />
          );
        })}
      </div>
    </div>
  );
}
