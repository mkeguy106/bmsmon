import type { TempThresholds, TempUnit } from "../temp";
import type { FleetItem } from "../types";
import { PackCard } from "./PackCard";

export function AllBatteries({ items, staleAddrs, thr, unit, now, pinned, onTogglePin }:
  { items: FleetItem[]; staleAddrs: Set<string>; thr: TempThresholds; unit: TempUnit;
    now: number; pinned: Set<string>; onTogglePin: (addr: string) => void }) {
  return (
    <div>
      <div className="mono" style={{ color: "var(--text3)", fontSize: 11, letterSpacing: 2, margin: "8px 4px 16px" }}>ALL BATTERIES</div>
      <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(220px, 1fr))", gap: 16 }}>
        {items.map((it) => (
          <PackCard key={it.address} item={it} stale={staleAddrs.has(it.address)} thr={thr} unit={unit}
            now={now} pinned={pinned.has(it.address)} onTogglePin={() => onTogglePin(it.address)} />
        ))}
      </div>
    </div>
  );
}
