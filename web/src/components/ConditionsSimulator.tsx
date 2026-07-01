import { formatTemp, type TempUnit } from "../temp";

const PRESETS: [string, number][] = [
  ["FREEZING", -22], ["COLD", -2], ["NORMAL", 24], ["WARM", 48], ["OVERHEAT", 62],
];

/** Test tool: drag to drive the featured pack's displayed temperature and preview the zones. */
export function ConditionsSimulator({ value, active, onChange, onReset, unit, featuredName, readoutColor }:
  { value: number; active: boolean; onChange: (t: number) => void; onReset: () => void;
    unit: TempUnit; featuredName: string; readoutColor: string }) {
  return (
    <div style={{ marginTop: 26, paddingTop: 20, borderTop: "1px solid var(--divider)" }}>
      <div style={{ display: "flex", alignItems: "baseline", gap: 10, marginBottom: 12, flexWrap: "wrap" }}>
        <span className="mono" style={{ color: "var(--text3)", fontSize: 11, letterSpacing: 2 }}>CONDITIONS SIMULATOR</span>
        <span style={{ color: "var(--text2)", fontSize: 13 }}>
          Drag to test {featuredName} — preview only, not live data
        </span>
        {active && (
          <button onClick={onReset} className="mono" style={{ background: "var(--input-bg)",
            border: "1px solid var(--input-border)", color: "var(--text2)", fontSize: 11,
            padding: "4px 10px", borderRadius: 8, cursor: "pointer" }}>↻ live</button>
        )}
        <span className="mono" style={{ marginLeft: "auto", fontSize: 15, fontWeight: 700, color: readoutColor }}>
          {formatTemp(value, unit)}
        </span>
      </div>
      <input type="range" min={-30} max={70} step={1} value={value} className="temp-slider"
        onInput={(e) => onChange(Number((e.target as HTMLInputElement).value))}
        style={{ width: "100%", margin: "6px 0 14px" }} />
      <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
        {PRESETS.map(([label, t]) => (
          <button key={label} onClick={() => onChange(t)} className="mono" style={{ background: "var(--input-bg)",
            border: "1px solid var(--input-border)", color: "var(--text2)", fontSize: 11, letterSpacing: 0.5,
            padding: "7px 13px", borderRadius: 8, cursor: "pointer" }}>{label}</button>
        ))}
      </div>
    </div>
  );
}
