import { memo, useMemo } from "react";
import type { EnergyPoint } from "../model/journey";

// Fixed viewBox: the SVG scales to its container via width:100%, preserving aspect ratio.
const VB_W = 600;
const VB_H = 160;
const PAD_L = 44; // room for y-axis (W) labels
const PAD_R = 12;
const PAD_T = 10;
const PAD_B = 24; // room for x-axis (distance) labels
const MI_TO_KM = 1.60934;

const plotW = VB_W - PAD_L - PAD_R;
const plotH = VB_H - PAD_T - PAD_B;

interface Geom {
  /** Pre-scaled x pixel per point — the cursor line indexes into this. */
  xs: number[];
  linePoints: string;
  transitRects: { x0: number; w: number }[];
  yTicks: { y: number; label: string }[];
  xTicks: { x: number; label: string; anchor: "start" | "middle" | "end" }[];
}

/** All the track-derived geometry — everything except the playback cursor. */
function computeGeom(energy: EnergyPoint[], distUnit: "mi" | "km"): Geom | null {
  // Insufficient-data guard: need at least 2 points to draw a line.
  if (energy.length < 2) return null;

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
  const xs = ds.map(scaleX);

  // ---- Transit shading: contiguous runs of transit===true points → one rect per run,
  //      spanning from the point before the run entered transit through its last point,
  //      since `transit` is measured on the segment landing on each point. ----
  const transitRects: Geom["transitRects"] = [];
  const pushRect = (fromIdx: number, toIdx: number) => {
    const x0 = scaleX(ds[Math.max(0, fromIdx - 1)]);
    const x1 = scaleX(ds[toIdx]);
    transitRects.push({ x0, w: Math.max(0, x1 - x0) });
  };
  let runStart = -1;
  for (let i = 0; i < energy.length; i++) {
    if (energy[i].transit) {
      if (runStart === -1) runStart = i;
    } else if (runStart !== -1) {
      pushRect(runStart, i - 1);
      runStart = -1;
    }
  }
  if (runStart !== -1) pushRect(runStart, energy.length - 1);

  // ---- Power polyline. ----
  const linePoints = energy
    .map((e, i) => `${xs[i].toFixed(1)},${scaleY(e.power).toFixed(1)}`)
    .join(" ");

  // ---- Axis ticks. ----
  const yTicks = [0, 1, 2, 3].map((i) => {
    const val = yLo + (ySpan * i) / 3;
    return { y: scaleY(val), label: `${Math.round(val)}W` };
  });
  const xTickCount = 4;
  const xTicks = Array.from({ length: xTickCount }, (_, i) => {
    const d = dMin + (dSpan * i) / (xTickCount - 1);
    return {
      x: scaleX(d),
      label: `${d.toFixed(1)} ${unitLabel}`,
      anchor: (i === 0 ? "start" : i === xTickCount - 1 ? "end" : "middle") as
        "start" | "middle" | "end",
    };
  });

  return { xs, linePoints, transitRects, yTicks, xTicks };
}

/** The static chart body — rects, grid, power line, axis labels. Memoized so the 280 ms
 *  playback-cursor ticks re-render only the cursor line in the parent, not this
 *  potentially ~5760-point polyline. */
const ChartBody = memo(function ChartBody({ geom }: { geom: Geom }) {
  return (
    <>
      {/* Transit shading (background) — faint rect + label over each vehicle-ride leg. */}
      {geom.transitRects.map((r, i) => (
        <g key={`transit-${i}`}>
          <rect x={r.x0} y={PAD_T} width={r.w} height={plotH} fill="var(--text-4)" opacity={0.1} />
          {r.w >= 60 && (
            <text x={r.x0 + r.w / 2} y={PAD_T + 12} textAnchor="middle" fontSize={8.5}
              fill="var(--text-4)" letterSpacing={0.4}>
              IN TRANSIT · BATTERY IDLE
            </text>
          )}
        </g>
      ))}

      {/* Y gridlines + labels (W). */}
      {geom.yTicks.map((t, i) => (
        <g key={`y-${i}`}>
          <line x1={PAD_L} y1={t.y} x2={VB_W - PAD_R} y2={t.y} stroke="var(--border)" strokeWidth={1} />
          <text x={PAD_L - 6} y={t.y} textAnchor="end" dominantBaseline="middle" fontSize={10}
            fill="var(--text-4)">
            {t.label}
          </text>
        </g>
      ))}

      {/* Power line. */}
      <polyline points={geom.linePoints} fill="none" stroke="var(--warn)" strokeWidth={1.75}
        strokeLinejoin="round" strokeLinecap="round" />

      {/* X ticks (distance). First/last labels nudged inward so they don't clip the edges. */}
      {geom.xTicks.map((t, i) => (
        <text key={`x-${i}`} x={t.x} y={VB_H - 6} textAnchor={t.anchor} fontSize={10}
          fill="var(--text-4)">
          {t.label}
        </text>
      ))}
    </>
  );
});

/**
 * Energy-over-distance chart: x = cumulative distance, y = instantaneous power (W).
 * Contiguous `transit` legs (vehicle rides — chair idle, GPS moving) are shaded with a
 * label, and a vertical cursor line tracks `cursorIndex` for scrubbing/playback sync.
 * The body is memoized on [energy, distUnit]; only the cursor follows playback ticks.
 */
export function EnergyDistanceChart({ energy, cursorIndex, distUnit }: {
  energy: EnergyPoint[];
  cursorIndex: number;
  distUnit: "mi" | "km";
}) {
  const geom = useMemo(() => computeGeom(energy, distUnit), [energy, distUnit]);

  if (!geom) {
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

  // ---- Cursor: clamp so an out-of-range index never breaks the draw. ----
  const ci = Math.max(0, Math.min(energy.length - 1, cursorIndex));
  const cursorX = geom.xs[ci];

  return (
    <svg viewBox={`0 0 ${VB_W} ${VB_H}`} style={{ width: "100%", display: "block" }} role="img"
      aria-label="energy over distance">
      <ChartBody geom={geom} />
      {/* Playback cursor. */}
      <line x1={cursorX} y1={PAD_T} x2={cursorX} y2={PAD_T + plotH} stroke="var(--text)" strokeWidth={1}
        strokeDasharray="3 3" opacity={0.7} />
    </svg>
  );
}
