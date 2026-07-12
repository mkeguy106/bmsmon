import type { EnergyPoint } from "../model/journey";

// Fixed viewBox: the SVG scales to its container via width:100%, preserving aspect ratio.
const VB_W = 600;
const VB_H = 160;
const PAD_L = 44; // room for y-axis (W) labels
const PAD_R = 12;
const PAD_T = 10;
const PAD_B = 24; // room for x-axis (distance) labels
const MI_TO_KM = 1.60934;

/**
 * Energy-over-distance chart: x = cumulative distance, y = instantaneous power (W).
 * Contiguous `transit` legs (vehicle rides — chair idle, GPS moving) are shaded with a
 * label, and a vertical cursor line tracks `cursorIndex` for scrubbing/playback sync.
 */
export function EnergyDistanceChart({ energy, cursorIndex, distUnit }: {
  energy: EnergyPoint[];
  cursorIndex: number;
  distUnit: "mi" | "km";
}) {
  const plotW = VB_W - PAD_L - PAD_R;
  const plotH = VB_H - PAD_T - PAD_B;

  // Insufficient-data guard: need at least 2 points to draw a line.
  if (energy.length < 2) {
    return (
      <svg viewBox={`0 0 ${VB_W} ${VB_H}`} style={{ width: "100%", display: "block" }} role="img"
        aria-label="not enough data">
        <text x={VB_W / 2} y={VB_H / 2} textAnchor="middle" dominantBaseline="middle" fontSize={12}
          fill="var(--text-4)">
          not enough data
        </text>
      </svg>
    );
  }

  const unitLabel = distUnit === "km" ? "km" : "mi";
  const toDist = (mi: number) => (distUnit === "km" ? mi * MI_TO_KM : mi);
  const ds = energy.map((e) => toDist(e.d));

  // ---- X domain: cumulative distance, converted to the display unit. ----
  let dMin = Infinity;
  let dMax = -Infinity;
  for (const d of ds) {
    if (d < dMin) dMin = d;
    if (d > dMax) dMax = d;
  }
  if (!isFinite(dMin) || !isFinite(dMax)) {
    dMin = 0;
    dMax = 1;
  }
  if (dMax - dMin <= 0) {
    // Single distinct distance value (no movement): fabricate a window so we never divide by zero.
    dMin -= 1;
    dMax += 1;
  }
  const dSpan = dMax - dMin;

  // ---- Y domain: power (W), floored at 0 with a little headroom above the peak. ----
  let yHi = 0;
  for (const e of energy) if (e.power > yHi) yHi = e.power;
  if (!isFinite(yHi) || yHi <= 0) yHi = 1; // flat-zero trip: still give the axis a span
  yHi *= 1.08;
  const yLo = 0;
  const ySpan = yHi - yLo;

  const scaleX = (d: number) => PAD_L + ((d - dMin) / dSpan) * plotW;
  const scaleY = (p: number) => PAD_T + (1 - (p - yLo) / ySpan) * plotH;

  // ---- Transit shading: contiguous runs of transit===true points → one rect per run,
  //      spanning from the point before the run entered transit through its last point,
  //      since `transit` is measured on the segment landing on each point. ----
  const transitRects: { x0: number; x1: number }[] = [];
  let runStart = -1;
  for (let i = 0; i < energy.length; i++) {
    if (energy[i].transit) {
      if (runStart === -1) runStart = i;
    } else if (runStart !== -1) {
      transitRects.push({ x0: ds[Math.max(0, runStart - 1)], x1: ds[i - 1] });
      runStart = -1;
    }
  }
  if (runStart !== -1) {
    transitRects.push({ x0: ds[Math.max(0, runStart - 1)], x1: ds[energy.length - 1] });
  }

  // ---- Power polyline. ----
  const linePoints = energy
    .map((e, i) => `${scaleX(ds[i]).toFixed(1)},${scaleY(e.power).toFixed(1)}`)
    .join(" ");

  // ---- Cursor: clamp so an out-of-range index never breaks the draw. ----
  const ci = Math.max(0, Math.min(energy.length - 1, cursorIndex));
  const cursorX = scaleX(ds[ci]);

  // ---- Axis ticks. ----
  const yTicks = [0, 1, 2, 3].map((i) => yLo + (ySpan * i) / 3);
  const xTickCount = 4;
  const xTicks = Array.from({ length: xTickCount }, (_, i) => dMin + (dSpan * i) / (xTickCount - 1));

  return (
    <svg viewBox={`0 0 ${VB_W} ${VB_H}`} style={{ width: "100%", display: "block" }} role="img"
      aria-label="energy over distance">
      {/* Transit shading (background) — faint rect + label over each vehicle-ride leg. */}
      {transitRects.map((r, i) => {
        const x0 = scaleX(r.x0);
        const x1 = scaleX(r.x1);
        const w = Math.max(0, x1 - x0);
        return (
          <g key={`transit-${i}`}>
            <rect x={x0} y={PAD_T} width={w} height={plotH} fill="var(--text-4)" opacity={0.1} />
            {w >= 60 && (
              <text x={(x0 + x1) / 2} y={PAD_T + 12} textAnchor="middle" fontSize={8.5}
                fill="var(--text-4)" letterSpacing={0.4}>
                IN TRANSIT · BATTERY IDLE
              </text>
            )}
          </g>
        );
      })}

      {/* Y gridlines + labels (W). */}
      {yTicks.map((val, i) => {
        const y = scaleY(val);
        return (
          <g key={`y-${i}`}>
            <line x1={PAD_L} y1={y} x2={VB_W - PAD_R} y2={y} stroke="var(--border)" strokeWidth={1} />
            <text x={PAD_L - 6} y={y} textAnchor="end" dominantBaseline="middle" fontSize={10}
              fill="var(--text-4)">
              {Math.round(val)}W
            </text>
          </g>
        );
      })}

      {/* Power line. */}
      <polyline points={linePoints} fill="none" stroke="var(--warn)" strokeWidth={1.75}
        strokeLinejoin="round" strokeLinecap="round" />

      {/* Playback cursor. */}
      <line x1={cursorX} y1={PAD_T} x2={cursorX} y2={PAD_T + plotH} stroke="var(--text)" strokeWidth={1}
        strokeDasharray="3 3" opacity={0.7} />

      {/* X ticks (distance). First/last labels nudged inward so they don't clip the edges. */}
      {xTicks.map((d, i) => {
        const x = scaleX(d);
        const anchor = i === 0 ? "start" : i === xTicks.length - 1 ? "end" : "middle";
        return (
          <text key={`x-${i}`} x={x} y={VB_H - 6} textAnchor={anchor} fontSize={10} fill="var(--text-4)">
            {d.toFixed(1)} {unitLabel}
          </text>
        );
      })}
    </svg>
  );
}
