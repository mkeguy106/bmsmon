import {
  formatDelta, marginToCutoffC, zoneColorVar, zoneCopy, zoneLabel,
  type TempEnvelope, type TempThresholds, type TempUnit, type Zone,
} from "../temp";

export interface PackTemp { name: string; tempC: number; zone: Zone }

/** Escalating alert banner reflecting the worst stage pack (mirrors the phone's stage banner). */
export function TempBanner({ worst, thr, env, unit }:
  { worst: PackTemp | null; thr: TempThresholds; env: TempEnvelope; unit: TempUnit }) {
  if (!worst || worst.zone.rank === 0) {
    return (
      <Banner color="var(--safe)" border="rgba(46,204,113,0.35)" bg="rgba(46,204,113,0.07)"
        label="ALL SAFE" title="All packs within the safe window" msg="No temperature action required." />
    );
  }
  const color = zoneColorVar(worst.zone.key);
  const copy = zoneCopy(worst.zone.key, thr, env);
  let msg = copy.msg;
  if (worst.zone.rank === 3) {
    const m = Math.max(0, Math.round(marginToCutoffC(worst.tempC, worst.zone.side, env)));
    msg += ` Margin to cutoff: ${formatDelta(m, unit)}.`;
  }
  const strong = worst.zone.rank >= 4;
  return (
    <Banner color={color}
      border={`color-mix(in oklab, ${color} ${strong ? 75 : 45}%, transparent)`}
      bg={`color-mix(in oklab, ${color} ${strong ? 22 : 11}%, transparent)`}
      label={zoneLabel(worst.zone.key)} title={`${worst.name}: ${copy.title}`} msg={msg} />
  );
}

function Banner({ color, border, bg, label, title, msg }:
  { color: string; border: string; bg: string; label: string; title: string; msg: string }) {
  return (
    <div style={{ display: "flex", alignItems: "flex-start", gap: 14, padding: "14px 16px",
      borderRadius: 12, border: `1px solid ${border}`, background: bg }}>
      <span style={{ width: 10, height: 10, borderRadius: "50%", marginTop: 5, flex: "none",
        background: color, boxShadow: `0 0 10px ${color}` }} />
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ display: "flex", alignItems: "baseline", gap: 10, flexWrap: "wrap" }}>
          <span className="mono" style={{ fontSize: 11, letterSpacing: 2, color }}>{label}</span>
          <span style={{ fontSize: 14, fontWeight: 600, color: "var(--text)" }}>{title}</span>
        </div>
        <div style={{ fontSize: 13, color: "var(--text2)", marginTop: 3 }}>{msg}</div>
      </div>
    </div>
  );
}
