import {
  ENV, formatTemp, tempFillPct, tempZone, zoneColorVar,
  type TempThresholds, type TempUnit,
} from "../temp";

// Fixed zone-band washes (bottom fraction, height fraction, rgba) — cold→hot.
const BANDS: [number, number, string][] = [
  [0, 10, "rgba(47,111,224,0.22)"],   // cold-crit
  [10, 25, "rgba(70,179,201,0.18)"],  // cool
  [35, 40, "rgba(46,204,113,0.16)"],  // safe
  [75, 15, "rgba(226,176,30,0.18)"],  // warm
  [90, 10, "rgba(229,52,43,0.22)"],   // hot-crit
];

/** Vertical temperature thermometer. −30…+70°C maps to 0…100%. */
export function TempGauge({ tempC, thr, unit }:
  { tempC: number; thr: TempThresholds; unit: TempUnit }) {
  const zone = tempZone(tempC, thr);
  const color = zoneColorVar(zone.key);
  const fill = tempFillPct(tempC);
  const markerShadow = zone.rank >= 3
    ? `0 0 22px ${color}, 0 0 8px ${color}`
    : zone.rank > 0 ? `0 0 14px ${color}, 0 0 4px ${color}` : `0 0 8px ${color}`;
  const ticks = [
    { t: ENV.hotCutoffC, note: "CUTOFF" },
    { t: thr.hotCritC, note: "CRITICAL" },
    { t: thr.coldCritC, note: "CRITICAL" },
    { t: ENV.coldCutoffC, note: "CUTOFF" },
  ];
  return (
    <div style={{ display: "flex", alignItems: "stretch", gap: 10 }}>
      <div style={{ position: "relative", width: 18, height: 236, borderRadius: 9, overflow: "hidden",
        background: "var(--input-bg)", border: "1px solid var(--input-border)" }}>
        {BANDS.map(([bottom, height, bg], i) => (
          <div key={i} style={{ position: "absolute", left: 0, right: 0,
            bottom: `${bottom}%`, height: `${height}%`, background: bg }} />
        ))}
        <div style={{ position: "absolute", left: 0, right: 0, bottom: 0, height: `${fill}%`,
          background: `linear-gradient(to top, color-mix(in oklab, ${color} 30%, transparent), color-mix(in oklab, ${color} 6%, transparent))` }} />
        <div style={{ position: "absolute", left: -3, right: -3, height: 4, borderRadius: 2,
          bottom: `${fill}%`, transform: "translateY(2px)", background: color, boxShadow: markerShadow }} />
      </div>
      <div style={{ position: "relative", width: 74, height: 236 }}>
        {ticks.map((tk, i) => (
          <div key={i} style={{ position: "absolute", left: 0, right: 0, bottom: `${tk.t + 30}%`,
            transform: "translateY(50%)", display: "flex", alignItems: "center", gap: 6 }}>
            <span style={{ width: 6, height: 1, background: "var(--input-border)" }} />
            <span className="mono" style={{ fontSize: 10, color: "var(--text2)" }}>{formatTemp(tk.t, unit)}</span>
            <span className="mono" style={{ fontSize: 8, letterSpacing: 0.5, color: "var(--text3)" }}>{tk.note}</span>
          </div>
        ))}
        <div style={{ position: "absolute", right: 0, bottom: `${fill}%`, transform: "translateY(50%)",
          background: "var(--bg)", border: `1px solid ${color}`, borderRadius: 6, padding: "2px 7px" }}>
          <span className="mono" style={{ fontSize: 13, fontWeight: 700, color }}>{formatTemp(tempC, unit)}</span>
        </div>
      </div>
    </div>
  );
}
