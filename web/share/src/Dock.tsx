import type { CSSProperties } from "react";
import { guestCap, guestFlow, type FlowKind } from "./dock";
import type { GuestStatus } from "./feed";

// Visual twin of the mobile Journey dock's CAP/FLOW lines (web/src/v2/components/
// JourneyDock.tsx) — duplicated rather than imported because that component is
// coupled to BasePack/TripSummary and this bundle only has the feed's status object.
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

/** Guest dock: battery capacity + live flow so a guest can see active discharge. */
export function Dock({ status }: { status: GuestStatus | null }) {
  const cap = guestCap(status);
  const flow = guestFlow(status);
  return (
    <div className="mono" style={{
      padding: "10px 14px", display: "flex", flexDirection: "column", gap: 9, flexShrink: 0,
      borderTop: "1px solid var(--border)", background: "var(--panel-3)",
      fontVariantNumeric: "tabular-nums", opacity: status ? 1 : 0.5,
    }}>
      <div style={rowStyle}>
        <span style={eyebrow}>CAP</span>
        <div style={barStyle}>
          {cap.pct != null && <Fill frac={cap.pct / 100} background={CAP_FILL[cap.band]} />}
        </div>
        <span style={valStyle}>
          {cap.pct != null ? `${cap.pct}%` : "—"}
          {cap.detail && <small style={{ color: "var(--text-3)", fontWeight: 400, fontSize: 10 }}> {cap.detail}</small>}
        </span>
      </div>
      <div style={rowStyle}>
        <span style={eyebrow}>FLOW</span>
        <div style={barStyle}>
          {flow.kind !== "idle" && <Fill frac={flow.frac} background={FLOW_FILL[flow.kind]} />}
        </div>
        <span style={valStyle}>
          {flow.watts} W
          <small style={{ color: "var(--text-3)", fontWeight: 400, fontSize: 10 }}> {FLOW_LABEL[flow.kind]}</small>
        </span>
      </div>
    </div>
  );
}
