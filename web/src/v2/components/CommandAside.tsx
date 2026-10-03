import type { ReactNode } from "react";
import type { Base } from "../fleet";
import type { FleetItem } from "../../types";
import { useNow } from "../../useNow";
import { Bar } from "./Atoms";
import { RouteSketch } from "./RouteSketch";
import type { TrackPoint } from "../track";
import { socColor } from "../colors";
import { Ago } from "../../components/Ago";
import { minutesLeft, rechargePhase, rechargePlan, type RechargeRow } from "../model/recharge";

function fmtEta(min: number): string {
  const m = Math.round(min);
  if (m < 60) return `${m} min`;
  return `${Math.floor(m / 60)}h ${String(m % 60).padStart(2, "0")}m`;
}
/** A clock time, with the weekday when it is not today ("Tue 5:00 PM"). */
function when(ms: number, nowMs: number): string {
  const d = new Date(ms);
  const n = new Date(nowMs);
  const today = d.getFullYear() === n.getFullYear() && d.getMonth() === n.getMonth()
    && d.getDate() === n.getDate();
  return today
    ? d.toLocaleTimeString([], { hour: "numeric", minute: "2-digit" })
    : d.toLocaleString([], { weekday: "short", hour: "numeric", minute: "2-digit" });
}

/** One recharge-plan row: live rows count down to their anchored ready time; a pack that is
 *  not live is muted and says when it was last seen and when it was due (WEB-18). */
function RechargeLine({ row, now }: { row: RechargeRow; now: number }) {
  const phase = rechargePhase(row, now);
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 4, opacity: row.live ? 1 : 0.55 }}>
      <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
        <span className="mono" style={{ fontSize: 12, flex: 1 }}>{row.label}</span>
        <span className="mono" style={{ fontSize: 11, color: "var(--text-3)" }}>
          {row.soc != null ? `${Math.round(row.soc)}%` : "—"}
        </span>
      </div>
      <Bar frac={(row.soc ?? 0) / 100} color={socColor(row.soc, row.live)} />
      <div className="mono" style={{ fontSize: 11, color: "var(--text-4)" }}>
        {phase === "charging"
          ? <>≈ {fmtEta(minutesLeft(row, now))} to full · ready by {when(row.readyAtMs, now)}</>
          : <>last seen <Ago tsMs={row.tsMs} /> · {phase === "due" ? "due full" : "was due full"} {when(row.readyAtMs, now)}</>}
      </div>
    </div>
  );
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

export function CommandAside({ bases, onOpen, todayPoints }: {
  bases: Base[]; onOpen: (v: "journey" | "history") => void; todayPoints: TrackPoint[];
}) {
  // Local clock for the countdown and the due/past-due split — h:mm resolution, so a 30 s
  // tick keeps it honest without the parent carrying a hot `now`. The ready times
  // themselves are anchored to each sample (model/recharge.ts), not to this clock.
  const now = useNow(30_000);
  const allPacks: Row[] = bases.flatMap((b) =>
    b.packs.map((p) => ({ label: `Base ${b.id} · ${p.letter}`, item: p.item })));

  // RECHARGE PLAN — packs charging toward full, live or last known.
  const plan = rechargePlan(bases);

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
    <div style={{ width: "100%", display: "flex", flexDirection: "column",
      gap: 12, overflowY: "auto", padding: "2px 2px 12px" }}>
      <Section title="Recharge plan">
        {plan.length === 0 ? (
          <div className="mono" style={{ fontSize: 12, color: "var(--text-4)" }}>Nothing charging.</div>
        ) : (
          plan.map((r) => <RechargeLine key={r.address} row={r} now={now} />)
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
        <RouteSketch points={todayPoints} />
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
