import type { CSSProperties } from "react";
import type { BasePack } from "../fleet";
import type { TripSummary } from "../model/journey";
import { dockCapacity, dockFlow, type FlowKind } from "../model/dock";
import { distLabel, toDist, type DistUnit } from "../../units";

const eyebrow: CSSProperties = {
  fontSize: 10, letterSpacing: ".14em", color: "var(--text-4)", width: 46, flexShrink: 0,
};
const rowStyle: CSSProperties = { display: "flex", alignItems: "center", gap: 9 };
const barStyle: CSSProperties = {
  position: "relative", flex: 1, height: 10, borderRadius: 5,
  background: "var(--track)", overflow: "hidden",
};
const valStyle: CSSProperties = {
  width: 112, flexShrink: 0, textAlign: "right", fontSize: 14, fontWeight: 700, color: "var(--text)",
};

const CAP_FILL: Record<"ok" | "warn" | "crit", string> = {
  ok: "var(--ok)", warn: "var(--warn)", crit: "var(--live)",
};
const FLOW_FILL: Record<Exclude<FlowKind, "idle">, string> = {
  out: "linear-gradient(90deg, var(--warn), var(--live))",
  regen: "linear-gradient(90deg, #34d399, var(--ok))",
  chg: "linear-gradient(90deg, #34d399, var(--ok))",
};
const FLOW_LABEL: Record<FlowKind, string> = { out: "OUT", regen: "REGEN", chg: "CHG", idle: "IDLE" };

function Fill({ frac, background }: { frac: number; background: string }) {
  return <div style={{ position: "absolute", inset: "0 auto 0 0", width: `${frac * 100}%`,
    borderRadius: 5, background, transition: "width .6s ease" }} />;
}

/** Mobile Journey dock: one trip line + pair-capacity line + single-direction flow line. */
export function JourneyDock({ summary, packs, distUnit }: {
  summary: TripSummary; packs: BasePack[]; distUnit: DistUnit;
}) {
  const cap = dockCapacity(packs);
  const flow = dockFlow(packs);
  const d = (mi: number) => toDist(mi, distUnit).toFixed(1);
  const sep = <span style={{ color: "var(--text-4)" }}>·</span>;
  const b = (t: string) => <b style={{ color: "var(--text)", fontSize: 14 }}>{t}</b>;
  return (
    <div className="mono" style={{
      padding: "12px 14px", display: "flex", flexDirection: "column", gap: 9, flexShrink: 0,
      borderTop: "1px solid var(--border)", background: "var(--panel-3)",
      fontVariantNumeric: "tabular-nums",
    }}>
      <div style={{ fontSize: 13, color: "var(--text-2)", display: "flex", gap: 6,
        flexWrap: "wrap", alignItems: "baseline" }}>
        {b(`${d(summary.miles)} ${distLabel(distUnit)}`)}{sep}
        <span>ACT {b(d(summary.activeMiles))}</span>{sep}
        <span>TRN {b(d(summary.transitMiles))}</span>{sep}
        <span>PEAK {b(`${Math.round(summary.peakW)} W`)}</span>
      </div>
      <div style={rowStyle}>
        <span style={eyebrow}>CAP</span>
        <div style={barStyle}>
          {/* No live pack: the last-known bound is history, so it is muted (as the rail mutes
              a stale SOC), never an ok/warn/crit colour that reads live. */}
          {cap.pct != null && (
            <Fill frac={cap.pct / 100} background={cap.offline ? "var(--text-4)" : CAP_FILL[cap.band]} />
          )}
        </div>
        {/* "≤": the bound is a pack's last-known reading; still wired in series, it can only
            have discharged since (WEB-14). */}
        <span style={{ ...valStyle, ...(cap.offline && { color: "var(--text-3)" }) }}
          title={cap.lastKnown ? "Includes a pack's last-known reading: it may be lower now" : undefined}>
          {cap.pct != null ? `${cap.lastKnown ? "≤" : ""}${cap.pct}%` : "—"}
          {cap.detail && <small style={{ color: "var(--text-3)", fontWeight: 400, fontSize: 10 }}> {cap.detail}</small>}
        </span>
      </div>
      <div style={rowStyle}>
        <span style={eyebrow}>FLOW</span>
        <div style={barStyle}>
          {flow && flow.kind !== "idle" && <Fill frac={flow.frac} background={FLOW_FILL[flow.kind]} />}
        </div>
        <span style={valStyle}>
          {flow ? (
            <>
              {flow.watts} W
              <small style={{ color: "var(--text-3)", fontWeight: 400, fontSize: 10 }}> {FLOW_LABEL[flow.kind]}</small>
            </>
          ) : "—"}
        </span>
      </div>
    </div>
  );
}
