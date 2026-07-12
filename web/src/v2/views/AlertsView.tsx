import type { V2Alert, AlertSeverity } from "../model/alerts";
import { CAPACITY_LADDER, CRITICAL_SOC } from "../model/alerts";

/** "3s ago" / "5m ago" / "2h ago" / "1d ago". Clamps clock-skew negatives to "just now". */
export function ago(ms: number): string {
  const clamped = Math.max(0, ms);
  if (clamped < 1000) return "just now";
  const s = Math.floor(clamped / 1000);
  if (s < 60) return `${s}s ago`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}m ago`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}h ago`;
  const d = Math.floor(h / 24);
  return `${d}d ago`;
}

const severityColor = (sev: AlertSeverity): string => (sev === "critical" ? "var(--live)" : "var(--warn)");

function AckButton({ acked, onClick }: { acked: boolean; onClick: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={acked}
      style={{
        fontFamily: "inherit",
        fontSize: 11,
        letterSpacing: ".02em",
        padding: "6px 12px",
        borderRadius: 6,
        border: "1px solid var(--border-strong)",
        background: "transparent",
        color: acked ? "var(--text-4)" : "var(--text-2)",
        cursor: acked ? "default" : "pointer",
        whiteSpace: "nowrap",
      }}
    >
      {acked ? "Acknowledged" : "Acknowledge"}
    </button>
  );
}

function AlertRow({ alert, acked, onAck, now }: {
  alert: V2Alert; acked: boolean; onAck: (id: string) => void; now: number;
}) {
  return (
    <div
      className="card"
      style={{
        display: "flex",
        alignItems: "stretch",
        gap: 0,
        padding: 0,
        overflow: "hidden",
        borderLeft: `4px solid ${severityColor(alert.severity)}`,
        opacity: acked ? 0.5 : 1,
      }}
    >
      <div style={{ flex: 1, padding: "12px 14px", minWidth: 0 }}>
        <div style={{ display: "flex", justifyContent: "space-between", gap: 10, alignItems: "baseline" }}>
          <span style={{ fontSize: 13, fontWeight: 600, color: "var(--text)" }}>{alert.title}</span>
          <span className="mono" style={{ fontSize: 10.5, color: "var(--text-4)", whiteSpace: "nowrap" }}>
            {ago(now - alert.tsMs)}
          </span>
        </div>
        <div style={{ fontSize: 12, color: "var(--text-3)", marginTop: 3 }}>{alert.msg}</div>
      </div>
      <div style={{ display: "flex", alignItems: "center", padding: "0 14px" }}>
        <AckButton acked={acked} onClick={() => onAck(alert.id)} />
      </div>
    </div>
  );
}

function EmptyState() {
  return (
    <div style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 6,
      padding: "100px 0", color: "var(--text-4)" }}>
      <span style={{ fontSize: 15, fontWeight: 600, color: "var(--text-2)" }}>All clear.</span>
      <span style={{ fontSize: 12 }}>No active alerts across the fleet.</span>
    </div>
  );
}

function ThresholdsFooter() {
  return (
    <div className="card">
      <div className="eyebrow" style={{ marginBottom: 8 }}>Thresholds</div>
      <div style={{ display: "flex", flexDirection: "column", gap: 4, fontSize: 12, color: "var(--text-3)" }}>
        <span>Capacity — {CAPACITY_LADDER.join(" / ")}% (critical &le; {CRITICAL_SOC}%)</span>
        <span>Temperature — caution / critical zones, per battery profile</span>
        <span>Cell imbalance — &gt; 40 mV across cells</span>
      </div>
    </div>
  );
}

export function AlertsView({ alerts, acked, onAck, now }: {
  alerts: V2Alert[]; acked: Set<string>; onAck: (id: string) => void; now: number;
}) {
  // Stable partition: unacked first, acked last, order otherwise preserved
  // within each group (the caller has already sorted `alerts` by severity/recency).
  const ordered = [...alerts].sort((a, b) => Number(acked.has(a.id)) - Number(acked.has(b.id)));

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
      {ordered.length === 0 ? (
        <div className="card">
          <EmptyState />
        </div>
      ) : (
        <div style={{ display: "flex", flexDirection: "column", gap: 10 }}>
          {ordered.map((a) => (
            <AlertRow key={a.id} alert={a} acked={acked.has(a.id)} onAck={onAck} now={now} />
          ))}
        </div>
      )}
      <ThresholdsFooter />
    </div>
  );
}
