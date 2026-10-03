// Journey efficiency card — replaces the old playback scrubber. Answers "can you make it?"
// live (miles left at today's vs usual rate) and "this outing" on a past day (cost/mile vs
// the learned whPerMile band). Pure inputs from model/efficiency.ts.
import type { EfficiencySummary, BandStatus } from "../model/efficiency";
import type { LastKnownRef } from "../model/baseView";
import { Chip, LastKnownNote } from "./Atoms";
import { distLabel, fmtDist, perDist, toDist, type DistUnit } from "../../units";

const STATUS_LABEL: Record<BandStatus, string> = {
  below: "below band", inside: "in band", above: "above band",
};
// below the band = cheaper than usual (good), above = pricier (worse).
const STATUS_TONE: Record<BandStatus, string> = {
  below: "var(--ok)", inside: "var(--ok)", above: "var(--warn)",
};

function Cell({ label, value }: { label: string; value: string }) {
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 2, minWidth: 62 }}>
      <span className="eyebrow" style={{ color: "var(--text-4)" }}>{label}</span>
      <span className="mono" style={{ fontSize: 14, color: "var(--text)", fontWeight: 600 }}>{value}</span>
    </div>
  );
}

const mi = (n: number) => (n < 10 ? n.toFixed(1) : String(Math.round(n)));

export function EfficiencyCard({ summary, live, charging, distUnit, lastKnown = null }: {
  summary: EfficiencySummary; live: boolean; charging: boolean; distUnit: DistUnit;
  /** A last-known pack inside the usable energy behind the projection (baseView). */
  lastKnown?: LastKnownRef | null;
}) {
  const { costPerMile, band, status, seed, drainedPct, wh, activeMiles,
    milesAtTodayRate, milesAtUsualRate } = summary;

  const eyebrow = (
    <div className="eyebrow" style={{ color: "var(--text-4)" }}>
      {live ? "CAN YOU MAKE IT?" : "THIS OUTING"}
    </div>
  );

  // No usable outing in the window → honest empty state, no fabricated number.
  if (costPerMile == null) {
    return (
      <div className="card" style={{ display: "flex", flexDirection: "column", gap: 10 }}>
        {eyebrow}
        <div className="mono" style={{ fontSize: 13, color: "var(--text-4)", padding: "8px 0" }}>
          Not enough driving to gauge efficiency
        </div>
      </div>
    );
  }

  const bandChip = band && status
    ? (seed
        ? <Chip tone="var(--text-4)">vs seed est.</Chip>
        : <Chip tone={STATUS_TONE[status]}>
            {STATUS_LABEL[status]} · {Math.round(perDist(band.lo, distUnit))}–{Math.round(perDist(band.hi, distUnit))}
          </Chip>)
    : null;

  return (
    <div className="card" style={{ display: "flex", flexDirection: "column", gap: 12 }}>
      {eyebrow}

      <div style={{ display: "flex", alignItems: "baseline", gap: 10, flexWrap: "wrap" }}>
        <span className="mono" style={{ fontSize: 30, fontWeight: 700, color: "var(--text)", lineHeight: 1 }}>
          {Math.round(perDist(costPerMile, distUnit))}
        </span>
        <span className="mono" style={{ fontSize: 13, color: "var(--text-3)" }}>Wh / {distLabel(distUnit)}</span>
        {bandChip}
      </div>

      <div style={{ display: "flex", flexWrap: "wrap", gap: 20 }}>
        <Cell label="DRIVEN" value={fmtDist(activeMiles, distUnit)} />
        <Cell label="USED" value={`${Math.round(wh)} Wh`} />
        <Cell label="DRAINED" value={drainedPct != null ? `${Math.round(drainedPct)}%` : "—"} />
      </div>

      {live && (charging ? (
        <div className="mono" style={{ fontSize: 12, color: "var(--text-4)" }}>
          Charging — see recharge plan
        </div>
      ) : milesAtTodayRate != null && (
        <div className="mono" style={{ fontSize: 13, color: "var(--text-2)" }}>
          ~<span style={{ color: "var(--text)", fontWeight: 600 }}>
            {mi(toDist(milesAtTodayRate, distUnit))} {distLabel(distUnit)}
          </span> left at today’s rate
          {milesAtUsualRate != null && <> · ~{mi(toDist(milesAtUsualRate, distUnit))} at your usual</>}
          {lastKnown && (
            <div style={{ fontSize: 11, color: "var(--warn)", marginTop: 4 }}>
              <LastKnownNote lk={lastKnown} /> — it may be lower now
            </div>
          )}
        </div>
      ))}
    </div>
  );
}
