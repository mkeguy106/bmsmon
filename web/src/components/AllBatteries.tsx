import type { TempThresholds, TempUnit } from "../temp";
import type { FleetItem } from "../types";
import { PackCard } from "./PackCard";

export function AllBatteries({ items, staleAddrs, thr, unit }:
  { items: FleetItem[]; staleAddrs: Set<string>; thr: TempThresholds; unit: TempUnit }) {
  return (
    <div>
      <div className="mono" style={{ color: "var(--text3)", fontSize: 11, letterSpacing: 2, margin: "8px 4px 16px" }}>ALL BATTERIES</div>
      <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(220px, 1fr))", gap: 16 }}>
        {items.map((it) => (
          <PackCard key={it.address} item={it} stale={staleAddrs.has(it.address)} thr={thr} unit={unit} />
        ))}
      </div>
    </div>
  );
}
