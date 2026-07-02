import { formatDelta, marginToCutoffC, zoneLabel, type TempEnvelope, type TempUnit } from "../temp";
import type { PackTemp } from "./TempBanner";

/**
 * Full-viewport flash + acknowledge, shown when the worst pack is at rank ≥ CRITICAL and unacked.
 * Theme-independent (white text + drop-shadow on the critical wash) — mirrors the phone overlay.
 */
export function TempOverlay({ worst, env, unit, onAck }:
  { worst: PackTemp; env: TempEnvelope; unit: TempUnit; onAck: () => void }) {
  const label = zoneLabel(worst.zone.key);
  const detail = worst.zone.rank >= 4
    ? `${label} · ${worst.name} · LOAD OFFLINE`
    : `${label} · ${worst.name} · ${formatDelta(Math.max(0, Math.round(marginToCutoffC(worst.tempC, worst.zone.side, env))), unit)} TO CUTOFF`;
  return (
    <div style={{ position: "fixed", inset: 0, zIndex: 50, display: "flex",
      alignItems: "center", justifyContent: "center" }}>
      <div style={{ position: "absolute", inset: 0, background: "var(--critical)",
        animation: "washPulse 1s ease-in-out infinite alternate" }} />
      <div style={{ position: "relative", textAlign: "center", padding: "0 24px" }}>
        <div style={{ fontSize: 20, color: "#fff", textShadow: "0 2px 8px rgba(0,0,0,0.6)" }}>⚠</div>
        <div style={{ fontSize: 84, fontWeight: 800, letterSpacing: 2, color: "#fff", lineHeight: 1,
          marginTop: 10, textShadow: "0 2px 4px rgba(0,0,0,0.55), 0 6px 22px rgba(0,0,0,0.45)" }}>
          TEMPERATURE
        </div>
        <div className="mono" style={{ fontSize: 18, fontWeight: 700, letterSpacing: 0.8, color: "#fff",
          marginTop: 18, textShadow: "0 1px 3px rgba(0,0,0,0.6), 0 3px 12px rgba(0,0,0,0.5)" }}>
          {detail}
        </div>
        <button onClick={onAck} style={{ marginTop: 34, background: "rgba(0,0,0,0.18)",
          border: "1.5px solid rgba(255,255,255,0.5)", color: "#fff", fontSize: 16, fontWeight: 800,
          letterSpacing: 1.5, padding: "15px 40px", borderRadius: 13, cursor: "pointer",
          textShadow: "0 1px 3px rgba(0,0,0,0.5)" }}>
          ACKNOWLEDGE
        </button>
      </div>
    </div>
  );
}
