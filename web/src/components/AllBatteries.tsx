import type { FleetItem } from "../types";
import { PackCard } from "./PackCard";

export function AllBatteries({ items, staleAddrs }:
  { items: FleetItem[]; staleAddrs: Set<string> }) {
  return (
    <div>
      <div className="mono" style={{ color: "var(--text3)", fontSize: 11, letterSpacing: 2, margin: "8px 4px 16px" }}>ALL BATTERIES</div>
      <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(220px, 1fr))", gap: 16 }}>
        {items.map((it) => <PackCard key={it.address} item={it} stale={staleAddrs.has(it.address)} />)}
      </div>
    </div>
  );
}
