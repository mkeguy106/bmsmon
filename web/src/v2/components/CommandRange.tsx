import type { Base } from "../fleet";
import { isCharging } from "../fleet";
import { estimatePackRange, minRange, formatRangeLine, SEED_RANGE_PARAMS, type PackRange, type RangeParams } from "../../range";
import type { Trip, TripVerdict } from "../trips";
import { classifyTrip } from "../trips";
import { Chip } from "./Atoms";

const VERDICT_TONE: Record<TripVerdict, string> = {
  go: "var(--ok)", tight: "var(--warn)", "no-go": "var(--live)",
};
const VERDICT_LABEL: Record<TripVerdict, string> = {
  go: "GO", tight: "TIGHT", "no-go": "NO-GO",
};

function milesText(lo: number, hi: number): string {
  return hi < 10 ? `${lo.toFixed(1)}–${hi.toFixed(1)}` : `${Math.round(lo)}–${Math.round(hi)}`;
}

export function CommandRange({ base, rangeParams, trips, onEditTrips }: {
  base: Base; rangeParams: Map<string, RangeParams>; trips: Trip[]; onEditTrips: () => void;
}) {
  const live = base.packs.filter((p) => p.connected);
  const anyCharging = live.some((p) => isCharging(p.item));
  const ranges: PackRange[] = live
    .map((p) => estimatePackRange(isCharging(p.item), p.item.remaining_ah,
      rangeParams.get(p.item.address) ?? SEED_RANGE_PARAMS))
    .filter((r): r is PackRange => r != null);

  const header = (
    <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
      <div className="eyebrow">Range · can you make it?</div>
      <button
        type="button"
        onClick={onEditTrips}
        style={{ marginLeft: "auto", border: "none", background: "transparent", cursor: "pointer",
          font: "inherit", color: "var(--text-3)" }}
        onMouseEnter={(e) => { e.currentTarget.style.color = "var(--text)"; }}
        onMouseLeave={(e) => { e.currentTarget.style.color = "var(--text-3)"; }}
      >
        <span className="eyebrow" style={{ color: "inherit" }}>Edit trips ›</span>
      </button>
    </div>
  );

  if (anyCharging || ranges.length === 0) {
    return (
      <div className="card" style={{ display: "flex", flexDirection: "column", gap: 14 }}>
        {header}
        <div className="mono" style={{ fontSize: 13, color: "var(--text-4)", padding: "12px 0" }}>
          Charging — see recharge plan
        </div>
      </div>
    );
  }

  const r = minRange(ranges);
  const typical = Math.round((r.milesLo + r.milesHi) / 2);

  return (
    <div className="card" style={{ display: "flex", flexDirection: "column", gap: 14 }}>
      {header}
      <div style={{ display: "flex", alignItems: "flex-end", gap: 14, flexWrap: "wrap" }}>
        <div>
          <div className="mono" style={{ fontSize: 30, fontWeight: 700, lineHeight: 1 }}>
            {milesText(r.milesLo, r.milesHi)}
          </div>
          <div className="eyebrow" style={{ marginTop: 4 }}>miles · typical ~{typical}</div>
        </div>
        <div className="mono" style={{ fontSize: 12, color: "var(--text-3)", marginLeft: "auto" }}>
          {formatRangeLine(r)}
        </div>
      </div>

      {trips.length > 0 && (
        <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
          {trips.map((t) => {
            const v = classifyTrip(t.miles, r);
            return (
              <div key={t.id} style={{ display: "flex", alignItems: "center", gap: 8 }}>
                <span className="mono" style={{ fontSize: 12, flex: 1, overflow: "hidden",
                  textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{t.name}</span>
                <span className="mono" style={{ fontSize: 11, color: "var(--text-4)" }}>
                  {t.miles.toFixed(1)} mi
                </span>
                <Chip tone={VERDICT_TONE[v]}>{VERDICT_LABEL[v]}</Chip>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
