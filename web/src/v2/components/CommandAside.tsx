import type { ReactNode } from "react";
import type { Base } from "../fleet";
import type { FleetItem } from "../../types";
import { Bar } from "./Atoms";

function socColor(soc: number | null | undefined): string {
  if (soc == null) return "var(--text-4)";
  return soc < 15 ? "var(--live)" : soc < 30 ? "var(--warn)" : "var(--ok)";
}
function fmtEta(min: number): string {
  const m = Math.round(min);
  if (m < 60) return `${m} min`;
  return `${Math.floor(m / 60)}h ${String(m % 60).padStart(2, "0")}m`;
}
function clock(ms: number): string {
  return new Date(ms).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
}

interface Row { label: string; item: FleetItem }

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="card" style={{ display: "flex", flexDirection: "column", gap: 12 }}>
      <div className="eyebrow">{title}</div>
      {children}
    </div>
  );
}

export function CommandAside({ bases, now, onOpen }: {
  bases: Base[]; now: number; onOpen: (v: "journey" | "history") => void;
}) {
  const allPacks: Row[] = bases.flatMap((b) =>
    b.packs.map((p) => ({ label: `Base ${b.id} · ${p.letter}`, item: p.item })));

  // RECHARGE PLAN — packs charging toward full (have an ETA and aren't already full).
  const charging = allPacks.filter((r) =>
    r.item.eta_full_min != null && Number.isFinite(r.item.eta_full_min) &&
    (r.item.soc ?? 100) < 99);

  // FLEET HEALTH — SOH banding over every pack that reports SOH.
  const withSoh = allPacks.filter((r) => r.item.soh != null);
  const good = withSoh.filter((r) => (r.item.soh ?? 0) >= 90).length;
  const fair = withSoh.filter((r) => (r.item.soh ?? 0) >= 80 && (r.item.soh ?? 0) < 90).length;
  const degraded = withSoh.filter((r) => (r.item.soh ?? 0) < 80).length;
  const total = withSoh.length || 1;
  const worst = withSoh.reduce<Row | null>((w, r) =>
    w == null || (r.item.soh ?? 100) < (w.item.soh ?? 100) ? r : w, null);

  const segments = [
    { n: good, color: "var(--ok)" },
    { n: fair, color: "var(--warn)" },
    { n: degraded, color: "var(--live)" },
  ].filter((s) => s.n > 0);

  return (
    <div style={{ width: 340, flex: "0 0 340px", display: "flex", flexDirection: "column",
      gap: 12, overflowY: "auto", padding: "2px 2px 12px" }}>
      <Section title="Recharge plan">
        {charging.length === 0 ? (
          <div className="mono" style={{ fontSize: 12, color: "var(--text-4)" }}>Nothing charging.</div>
        ) : (
          charging.map((r) => {
            const eta = r.item.eta_full_min!;
            return (
              <div key={r.item.address} style={{ display: "flex", flexDirection: "column", gap: 4 }}>
                <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                  <span className="mono" style={{ fontSize: 12, flex: 1 }}>{r.label}</span>
                  <span className="mono" style={{ fontSize: 11, color: "var(--text-3)" }}>
                    {r.item.soc != null ? `${Math.round(r.item.soc)}%` : "—"}
                  </span>
                </div>
                <Bar frac={(r.item.soc ?? 0) / 100} color={socColor(r.item.soc)} />
                <div className="mono" style={{ fontSize: 11, color: "var(--text-4)" }}>
                  ≈ {fmtEta(eta)} to full · ready by {clock(now + eta * 60_000)}
                </div>
              </div>
            );
          })
        )}
      </Section>

      <Section title="Fleet health">
        <div style={{ display: "flex", height: 8, borderRadius: 4, overflow: "hidden",
          background: "var(--track)" }}>
          {segments.map((s, i) => (
            <div key={i} style={{ flex: s.n, background: s.color }} />
          ))}
        </div>
        <div style={{ display: "flex", gap: 12 }}>
          <span className="mono" style={{ fontSize: 11, color: "var(--ok)" }}>Good {good}</span>
          <span className="mono" style={{ fontSize: 11, color: "var(--warn)" }}>Fair {fair}</span>
          <span className="mono" style={{ fontSize: 11, color: "var(--live)" }}>Degraded {degraded}</span>
        </div>
        {worst && (
          <div className="mono" style={{ fontSize: 11, color: "var(--text-4)" }}>
            Worst: {worst.label} · {worst.item.soh != null ? `${Math.round(worst.item.soh)}%` : "—"} SOH
            {" "}({total} pack{total === 1 ? "" : "s"})
          </div>
        )}
      </Section>

      <Section title="Today's route">
        <div style={{ height: 120, borderRadius: 6, background: "var(--panel-2)",
          display: "flex", alignItems: "center", justifyContent: "center" }}>
          <span className="eyebrow">Route map — Phase 4</span>
        </div>
        <div style={{ display: "flex", gap: 8 }}>
          <AsideButton onClick={() => onOpen("journey")}>Open Journey ›</AsideButton>
          <AsideButton onClick={() => onOpen("history")}>History ›</AsideButton>
        </div>
      </Section>
    </div>
  );
}

function AsideButton({ onClick, children }: { onClick: () => void; children: ReactNode }) {
  return (
    <button
      type="button"
      onClick={onClick}
      style={{ flex: 1, padding: "8px 10px", border: "1px solid var(--border)", borderRadius: 6,
        background: "transparent", color: "var(--text-2)", cursor: "pointer", font: "inherit",
        fontSize: 12 }}
      onMouseEnter={(e) => { e.currentTarget.style.background = "var(--hover)"; }}
      onMouseLeave={(e) => { e.currentTarget.style.background = "transparent"; }}
    >
      {children}
    </button>
  );
}
