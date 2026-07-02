import { dualStr, type TempEnvelope, type TempThresholds } from "../temp";

const SPECS: [string, string][] = [
  ["CHEMISTRY", "LiFePO4"], ["NOMINAL", "12.8 V"], ["CAPACITY", "100 Ah"], ["ENERGY", "1280 Wh"],
  ["SMART BMS", "100 A"], ["MAX DISCHARGE", "100 A"], ["MAX CHARGE", "100 A"], ["CYCLE LIFE", "4000+"],
  ["BLUETOOTH", "5.0"], ["GROUP (BCI)", "24"], ["DIMENSIONS", "10.2×6.6×8.3 in"], ["WEIGHT", "22.2 lb"],
  ["EXPANSION", "4S4P · 20.5 kWh"], ["WARRANTY", "5 yr"],
];

function rows(t: TempThresholds, env: TempEnvelope) {
  return [
    { zone: "CAUTION · COLD", cond: `≤ ${dualStr(t.coldCautionC)}`, action: "Charge lockout imminent — warm pack before charging.", color: "var(--cool)" },
    { zone: "CAUTION · HOT", cond: `≥ ${dualStr(t.hotCautionC)}`, action: "Approaching charge limit — improve airflow.", color: "var(--warm)" },
    // WEB-6: charging resumes at the charge-resume point, not the caution threshold.
    { zone: "CHARGE LOCK · COLD", cond: `≤ ${dualStr(env.lockColdC)}`, action: `BMS pauses charging; resumes above ${dualStr(env.chargeResumeColdC)}.`, color: "var(--cold)" },
    { zone: "CHARGE LOCK · HOT", cond: `≥ ${dualStr(env.lockHotC)}`, action: "Above max charge temp — stop charging, cool pack.", color: "var(--hot)" },
    { zone: "CRITICAL · COLD", cond: `≤ ${dualStr(t.coldCritC)}`, action: "Approaching cutoff — act NOW while power remains.", color: "var(--critical)" },
    { zone: "CRITICAL · HOT", cond: `≥ ${dualStr(t.hotCritC)}`, action: "Approaching cutoff — act NOW while power remains.", color: "var(--critical)" },
    { zone: "CUTOFF · COLD", cond: `≤ ${dualStr(env.coldCutoffC)}`, action: "BMS disconnects the load — pack goes offline.", color: "var(--critical)" },
    { zone: "CUTOFF · HOT", cond: `≥ ${dualStr(env.hotCutoffC)}`, action: "Over-temp protection — pack goes offline.", color: "var(--critical)" },
  ];
}

/** Position on the gauge scale: −30…+70 °C maps to 0…100%. */
const pct = (tC: number): number => tC + 30;

const Label = ({ children }: { children: React.ReactNode }) => (
  <div className="mono" style={{ color: "var(--text3)", fontSize: 11, letterSpacing: 2 }}>{children}</div>
);

export function BatteryProfilePanel({ thr, env }: { thr: TempThresholds; env: TempEnvelope }) {
  return (
    <div style={{ background: "var(--card)", border: "1px solid var(--border)", borderRadius: 16, padding: 24 }}>
      <Label>BATTERY PROFILE</Label>
      <div style={{ fontSize: 18, fontWeight: 700, margin: "6px 0 2px" }}>Redodo 12V 100Ah · Group 24 · Bluetooth</div>
      <div style={{ fontSize: 13, color: "var(--text2)", marginBottom: 20 }}>
        LiFePO4 (Grade-A, 4S) · profile <span className="mono">redodo-beken-bk-ble-1.0</span>
      </div>

      <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill,minmax(150px,1fr))",
        gap: 8, marginBottom: 26 }}>
        {SPECS.map(([label, value]) => (
          <div key={label} style={{ background: "var(--input-bg)", borderRadius: 8, padding: "9px 11px" }}>
            <div className="mono" style={{ color: "var(--text3)", fontSize: 10, letterSpacing: 1 }}>{label}</div>
            <div className="mono" style={{ color: "var(--text)", fontSize: 15, fontWeight: 600, marginTop: 2 }}>{value}</div>
          </div>
        ))}
      </div>

      <Label>TEMPERATURE SAFETY ENVELOPE</Label>
      {/* WEB-6: band geometry + labels derive from the resolved envelope/thresholds. */}
      <div style={{ position: "relative", height: 86, margin: "14px 8px 6px" }}>
        <div style={{ position: "absolute", top: 6, left: `${pct(env.coldCutoffC)}%`,
          width: `${pct(env.hotCutoffC) - pct(env.coldCutoffC)}%`, height: 22, borderRadius: 6,
          background: "rgba(46,204,113,0.10)", border: "1px solid rgba(46,204,113,0.35)",
          display: "flex", alignItems: "center", padding: "0 10px" }}>
          <span className="mono" style={{ fontSize: 10, color: "var(--text2)" }}>
            DISCHARGE  {env.coldCutoffC} → {env.hotCutoffC}°C</span>
        </div>
        <div style={{ position: "absolute", top: 6, left: `${pct(env.coldCutoffC)}%`,
          width: `${pct(thr.coldCritC) - pct(env.coldCutoffC)}%`, height: 22, borderRadius: "6px 0 0 6px",
          background: "rgba(229,52,43,0.28)", border: "1px solid rgba(229,52,43,0.6)" }} />
        <div style={{ position: "absolute", top: 6, left: `${pct(thr.hotCritC)}%`,
          width: `${pct(env.hotCutoffC) - pct(thr.hotCritC)}%`, height: 22, borderRadius: "0 6px 6px 0",
          background: "rgba(229,52,43,0.28)", border: "1px solid rgba(229,52,43,0.6)" }} />
        <div style={{ position: "absolute", top: 34, left: `${pct(env.lockColdC)}%`,
          width: `${pct(env.lockHotC) - pct(env.lockColdC)}%`, height: 22, borderRadius: 6,
          background: "rgba(230,126,34,0.10)", border: "1px solid rgba(230,126,34,0.35)",
          display: "flex", alignItems: "center", padding: "0 10px" }}>
          <span className="mono" style={{ fontSize: 10, color: "var(--text2)" }}>
            CHARGE  {env.lockColdC} → {env.lockHotC}°C</span>
        </div>
        <div style={{ position: "absolute", bottom: 0, left: 0, right: 0, height: 18, borderTop: "1px solid var(--divider)" }}>
          {[["0", "0"], ["30%", "0"], ["55%", "25"], ["80%", "50"]].map(([left, label], i) => (
            <span key={i} className="mono" style={{ position: "absolute", left, top: 4, fontSize: 9,
              color: "var(--text3)", transform: "translateX(-50%)" }}>{i === 0 ? "−30" : label}</span>
          ))}
          <span className="mono" style={{ position: "absolute", right: 0, top: 4, fontSize: 9,
            color: "var(--text3)", transform: "translateX(50%)" }}>70</span>
        </div>
      </div>
      <div style={{ fontSize: 12, color: "var(--text2)", margin: "2px 8px 0" }}>
        Red bands are the <span style={{ color: "var(--critical)", fontWeight: 600 }}>critical alert</span> zones —
        they fire before the BMS discharge cutoff so the chair warns you with power to spare.
      </div>

      <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: "10px 26px", marginTop: 18 }}>
        {rows(thr, env).map((r) => (
          <div key={r.zone} style={{ display: "flex", alignItems: "flex-start", gap: 11, padding: "9px 0",
            borderTop: "1px solid var(--divider)" }}>
            <span style={{ width: 8, height: 8, borderRadius: "50%", marginTop: 5, flex: "none", background: r.color }} />
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ display: "flex", alignItems: "baseline", gap: 8, flexWrap: "wrap" }}>
                <span className="mono" style={{ fontSize: 11, letterSpacing: 1, color: r.color }}>{r.zone}</span>
                <span className="mono" style={{ fontSize: 12, color: "var(--text)" }}>{r.cond}</span>
              </div>
              <div style={{ fontSize: 12, color: "var(--text2)", marginTop: 2 }}>{r.action}</div>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
