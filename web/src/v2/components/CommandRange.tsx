import type { ReactNode } from "react";
import type { BaseView } from "../model/baseView";
import { formatRangeLine } from "../../range";
import type { Trip, TripVerdict } from "../trips";
import { classifyTrip } from "../trips";
import { Ago } from "../../components/Ago";
import { Chip } from "./Atoms";
import { fmtDist, toDist, type DistUnit } from "../../units";

const VERDICT_TONE: Record<TripVerdict, string> = {
  go: "var(--ok)", tight: "var(--warn)", "no-go": "var(--live)",
};
const VERDICT_LABEL: Record<TripVerdict, string> = {
  go: "GO", tight: "TIGHT", "no-go": "NO-GO",
};

/** The range band in the display unit (the model is in miles). */
function distText(loMi: number, hiMi: number, u: DistUnit): string {
  const lo = toDist(loMi, u), hi = toDist(hiMi, u);
  return hi < 10 ? `${lo.toFixed(1)}–${hi.toFixed(1)}` : `${Math.round(lo)}–${Math.round(hi)}`;
}

/** "Can you make it?": the staged base's range, bounded by its weaker pack (baseView). */
export function CommandRange({ view, trips, onEditTrips, distUnit }: {
  view: BaseView; trips: Trip[]; onEditTrips: () => void; distUnit: DistUnit;
}) {
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

  // Every empty case says what it is (WEB-19): it used to read "Charging" for all of them.
  const range = view.range;
  if (range.kind !== "estimate") {
    const body: ReactNode =
      range.kind === "charging" ? "Charging — see recharge plan"
        : range.kind === "offline"
          ? (range.lastSeenMs != null
              ? <>Base offline — last seen <Ago tsMs={range.lastSeenMs} /></>
              : "Base offline")
          : "No capacity reading from this base yet";
    return (
      <div className="card" style={{ display: "flex", flexDirection: "column", gap: 14 }}>
        {header}
        <div className="mono" style={{ fontSize: 13, color: "var(--text-4)", padding: "12px 0" }}>{body}</div>
      </div>
    );
  }

  const r = range.range;
  const lk = range.lastKnown;
  const typical = Math.round(toDist((r.milesLo + r.milesHi) / 2, distUnit));

  return (
    <div className="card" style={{ display: "flex", flexDirection: "column", gap: 14 }}>
      {header}
      <div style={{ display: "flex", alignItems: "flex-end", gap: 14, flexWrap: "wrap" }}>
        <div>
          <div className="mono" style={{ fontSize: 30, fontWeight: 700, lineHeight: 1 }}>
            {distText(r.milesLo, r.milesHi, distUnit)}
          </div>
          <div className="eyebrow" style={{ marginTop: 4 }}>
            {distUnit === "km" ? "km" : "miles"} · typical ~{typical}
          </div>
        </div>
        <div className="mono" style={{ fontSize: 12, color: "var(--text-3)", marginLeft: "auto" }}>
          {formatRangeLine(r, distUnit)}
        </div>
      </div>

      {lk && (
        <div className="mono" style={{ fontSize: 11, color: "var(--warn)" }}>
          Includes pack {lk.letter}'s last-known reading (last seen <Ago tsMs={lk.tsMs} />) — it may be lower now.
        </div>
      )}

      {trips.length > 0 && (
        <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
          {trips.map((t) => {
            const v = classifyTrip(t.miles, r);
            return (
              <div key={t.id} style={{ display: "flex", alignItems: "center", gap: 8 }}>
                <span className="mono" style={{ fontSize: 12, flex: 1, overflow: "hidden",
                  textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{t.name}</span>
                <span className="mono" style={{ fontSize: 11, color: "var(--text-4)" }}>
                  {fmtDist(t.miles, distUnit)}
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
