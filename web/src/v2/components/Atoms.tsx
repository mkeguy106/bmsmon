import type { ReactNode } from "react";
import { deltaMv } from "../fleet";
import type { FleetItem } from "../../types";

export function Bar({ frac, color }: { frac: number; color: string }) {
  return <div style={{ height: 4, background: "var(--track)", borderRadius: 3 }}>
    <div style={{ width: `${Math.max(0, Math.min(1, frac)) * 100}%`, height: "100%", background: color, borderRadius: 3 }} /></div>;
}
export function StatTile({ label, value, sub }: { label: string; value: string; sub?: string }) {
  return <div className="card" style={{ padding: 12 }}>
    <div className="eyebrow">{label}</div>
    <div className="mono" style={{ fontSize: 16, marginTop: 4 }}>{value}</div>
    {sub && <div className="mono" style={{ fontSize: 11, color: "var(--text-4)" }}>{sub}</div>}
  </div>;
}
export function Chip({ tone = "var(--text-3)", children }: { tone?: string; children: ReactNode }) {
  return <span className="mono" style={{ fontSize: 10, letterSpacing: ".08em", padding: "2px 6px",
    borderRadius: 5, color: tone, border: `1px solid ${tone}`, textTransform: "uppercase" }}>{children}</span>;
}

/** Below this cell-voltage spread the per-cell bars carry no real signal — min-max scaling
 *  just amplifies noise — so they fade out. (The imbalance WARN threshold is 40 mV.) */
const TRIVIAL_SPREAD_MV = 10;

export function CellTiles({ item }: { item: FleetItem }) {
  const delta = deltaMv(item);
  if (delta == null) return null;
  const trivial = delta < TRIVIAL_SPREAD_MV;

  const cells = item.cells;
  const hasCells = !!cells && cells.length > 0;
  const lo = hasCells ? Math.min(...cells!) : item.cell_min_v ?? 0;
  const hi = hasCells ? Math.max(...cells!) : item.cell_max_v ?? 0;
  const span = hi - lo;

  const tiles: { label: string; v: number; bar: boolean }[] = hasCells
    ? cells!.map((v, i) => ({ label: `C${i + 1}`, v, bar: true }))
    : [
        { label: "HIGH", v: item.cell_max_v ?? 0, bar: false },
        { label: "LOW", v: item.cell_min_v ?? 0, bar: false },
        { label: "MEAN", v: ((item.cell_min_v ?? 0) + (item.cell_max_v ?? 0)) / 2, bar: false },
      ];

  return (
    <div>
      <div style={{ display: "grid", gridTemplateColumns: `repeat(${tiles.length}, 1fr)`, gap: 8 }}>
        {tiles.map((t) => (
          <div key={t.label} className="card" style={{ padding: 10 }}>
            <div className="eyebrow">{t.label}</div>
            <div className="mono" style={{ fontSize: 14, marginTop: 4 }}>{t.v.toFixed(3)} V</div>
            {t.bar && (
              <div style={{ marginTop: 6, opacity: trivial ? 0.2 : 1, transition: "opacity .4s ease" }}
                title={trivial ? `Cells within ${TRIVIAL_SPREAD_MV} mV — balanced; bars carry no signal` : undefined}>
                <Bar frac={span > 0 ? (t.v - lo) / span : 0} color="var(--text-3)" />
              </div>
            )}
          </div>
        ))}
      </div>
      <div className="mono" style={{ fontSize: 11, marginTop: 8, color: delta > 40 ? "var(--warn)" : "var(--text-3)" }}>
        {Math.round(delta)} mV
      </div>
    </div>
  );
}
