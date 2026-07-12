export interface ChartSeries {
  points: { t: number; v: number | null }[];
  color: string;
  label?: string;
}

interface LineChartProps {
  series: ChartSeries[];
  bands?: { from: number; to: number; color: string }[];
  watchLine?: { v: number; color: string; label?: string };
  ribbon?: { points: { t: number; lo: number | null; hi: number | null }[]; color: string };
  yMin?: number;
  yMax?: number;
  height?: number;
  unitLabel?: string;
}

// Fixed viewBox: the SVG scales to its container via width:100%, preserving aspect ratio.
const VB_W = 600;
const PAD_L = 40; // room for y-axis labels
const PAD_R = 12;
const PAD_T = 8;
const PAD_B = 22; // room for x-axis labels

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

// Hand-rolled short-date formatter (no date lib): epoch-ms → "Jul 3".
function shortDate(t: number): string {
  const d = new Date(t);
  return `${MONTHS[d.getMonth()]} ${d.getDate()}`;
}

// Tick label precision scales with the visible span so small ranges keep detail.
function fmtNum(v: number, span: number): string {
  const decimals = span >= 20 ? 0 : span >= 2 ? 1 : 2;
  return v.toFixed(decimals);
}

export function LineChart({
  series,
  bands,
  watchLine,
  ribbon,
  yMin,
  yMax,
  height = 180,
  unitLabel,
}: LineChartProps) {
  const H = height;
  const plotW = VB_W - PAD_L - PAD_R;
  const plotH = H - PAD_T - PAD_B;

  // Insufficient-data guard: need at least one series with >=2 real (non-null) points.
  const hasEnough = series.some((s) => s.points.filter((p) => p.v != null).length >= 2);
  if (!hasEnough) {
    return (
      <svg viewBox={`0 0 ${VB_W} ${H}`} style={{ width: "100%", display: "block" }} role="img"
        aria-label="not enough data">
        <text x={VB_W / 2} y={H / 2} textAnchor="middle" dominantBaseline="middle"
          fontSize={12} fill="var(--text-4)">not enough data</text>
      </svg>
    );
  }

  // ---- X domain: min/max t across every series and ribbon point (null v still has a t). ----
  let tMin = Infinity;
  let tMax = -Infinity;
  for (const s of series) {
    for (const p of s.points) {
      if (p.t < tMin) tMin = p.t;
      if (p.t > tMax) tMax = p.t;
    }
  }
  if (ribbon) {
    for (const p of ribbon.points) {
      if (p.t < tMin) tMin = p.t;
      if (p.t > tMax) tMax = p.t;
    }
  }
  if (!isFinite(tMin) || !isFinite(tMax)) {
    tMin = 0;
    tMax = 1;
  }
  let tSpan = tMax - tMin;
  if (tSpan <= 0) {
    // Single point / all-equal timestamps: fabricate a symmetric window so we never divide by zero.
    tMin -= 1;
    tMax += 1;
    tSpan = tMax - tMin;
  }

  // ---- Y domain: explicit yMin/yMax, else auto from data + chrome (watch-line, bands, ribbon). ----
  let yLo: number;
  let yHi: number;
  if (yMin != null && yMax != null) {
    yLo = yMin;
    yHi = yMax;
  } else {
    let lo = Infinity;
    let hi = -Infinity;
    const consider = (v: number | null | undefined) => {
      if (v == null || !isFinite(v)) return;
      if (v < lo) lo = v;
      if (v > hi) hi = v;
    };
    for (const s of series) for (const p of s.points) consider(p.v);
    if (ribbon) for (const p of ribbon.points) { consider(p.lo); consider(p.hi); }
    if (watchLine) consider(watchLine.v);
    if (bands) for (const b of bands) { consider(b.from); consider(b.to); }
    if (!isFinite(lo) || !isFinite(hi)) {
      lo = 0;
      hi = 1;
    }
    if (lo === hi) {
      // All values identical: pad symmetrically so the flat line sits mid-plot.
      const pad = Math.abs(lo) > 0 ? Math.abs(lo) * 0.05 : 1;
      lo -= pad;
      hi += pad;
    } else {
      const head = (hi - lo) * 0.06; // small headroom top & bottom
      lo -= head;
      hi += head;
    }
    yLo = yMin != null ? yMin : lo;
    yHi = yMax != null ? yMax : hi;
  }
  let ySpan = yHi - yLo;
  if (ySpan <= 0) {
    yLo -= 1;
    yHi += 1;
    ySpan = yHi - yLo;
  }

  const scaleX = (t: number) => PAD_L + ((t - tMin) / tSpan) * plotW;
  const scaleY = (v: number) => PAD_T + (1 - (v - yLo) / ySpan) * plotH;
  const clampY = (y: number) => Math.max(PAD_T, Math.min(PAD_T + plotH, y));

  // ---- Y gridlines + labels (4 evenly spaced rungs). ----
  const yTicks = [0, 1, 2, 3].map((i) => yLo + (ySpan * i) / 3);

  // ---- X time ticks (~3). ----
  const xTickCount = 3;
  const xTicks = Array.from({ length: xTickCount }, (_, i) =>
    tMin + (tSpan * i) / (xTickCount - 1),
  );

  // ---- Ribbon: filled path between lo/hi over each contiguous both-non-null run. ----
  const ribbonPaths: string[] = [];
  if (ribbon) {
    let run: { t: number; lo: number; hi: number }[] = [];
    const flush = () => {
      if (run.length >= 2) {
        const top = run.map((p) => `${scaleX(p.t).toFixed(1)},${scaleY(p.hi).toFixed(1)}`);
        const bot = run
          .slice()
          .reverse()
          .map((p) => `${scaleX(p.t).toFixed(1)},${scaleY(p.lo).toFixed(1)}`);
        ribbonPaths.push(`M${top.join("L")}L${bot.join("L")}Z`);
      }
      run = [];
    };
    for (const p of ribbon.points) {
      if (p.lo == null || p.hi == null) flush();
      else run.push({ t: p.t, lo: p.lo, hi: p.hi });
    }
    flush();
  }

  // ---- Series: break into segments on null, so gaps are never bridged. ----
  const seriesSegs = series.map((s) => {
    const segs: { x: number; y: number }[][] = [];
    let cur: { x: number; y: number }[] = [];
    for (const p of s.points) {
      if (p.v == null) {
        if (cur.length) segs.push(cur);
        cur = [];
      } else {
        cur.push({ x: scaleX(p.t), y: scaleY(p.v) });
      }
    }
    if (cur.length) segs.push(cur);
    return { color: s.color, segs };
  });

  return (
    <svg viewBox={`0 0 ${VB_W} ${H}`} style={{ width: "100%", display: "block" }} role="img"
      aria-label={series.map((s) => s.label).filter(Boolean).join(", ") || "line chart"}>
      {/* Bands (background) — clamped horizontal rects between from/to. */}
      {bands?.map((b, i) => {
        const y0 = clampY(scaleY(b.to));
        const y1 = clampY(scaleY(b.from));
        return (
          <rect key={`band-${i}`} x={PAD_L} y={Math.min(y0, y1)} width={plotW}
            height={Math.abs(y1 - y0)} fill={b.color} opacity={0.1} />
        );
      })}

      {/* Y gridlines + labels. */}
      {yTicks.map((val, i) => {
        const y = scaleY(val);
        return (
          <g key={`y-${i}`}>
            <line x1={PAD_L} y1={y} x2={VB_W - PAD_R} y2={y} stroke="var(--border)" strokeWidth={1} />
            <text x={PAD_L - 6} y={y} textAnchor="end" dominantBaseline="middle" fontSize={10}
              fill="var(--text-4)">
              {fmtNum(val, ySpan)}{unitLabel ?? ""}
            </text>
          </g>
        );
      })}

      {/* Ribbon fill (behind its series). */}
      {ribbon && ribbonPaths.map((d, i) => (
        <path key={`rib-${i}`} d={d} fill={ribbon.color} opacity={0.14} stroke="none" />
      ))}

      {/* Watch-line — dashed horizontal + right-aligned label. */}
      {watchLine && (() => {
        const y = scaleY(watchLine.v);
        return (
          <g>
            <line x1={PAD_L} y1={y} x2={VB_W - PAD_R} y2={y} stroke={watchLine.color} strokeWidth={1}
              strokeDasharray="4 3" opacity={0.8} />
            {watchLine.label && (
              <text x={VB_W - PAD_R} y={y - 3} textAnchor="end" fontSize={10} fill={watchLine.color}>
                {watchLine.label}
              </text>
            )}
          </g>
        );
      })()}

      {/* Series polylines (broken on null). Isolated single points drawn as dots. */}
      {seriesSegs.map((s, si) =>
        s.segs.map((seg, gi) =>
          seg.length === 1 ? (
            <circle key={`s-${si}-${gi}`} cx={seg[0].x} cy={seg[0].y} r={1.6} fill={s.color} />
          ) : (
            <polyline key={`s-${si}-${gi}`}
              points={seg.map((p) => `${p.x.toFixed(1)},${p.y.toFixed(1)}`).join(" ")}
              fill="none" stroke={s.color} strokeWidth={1.75} strokeLinejoin="round"
              strokeLinecap="round" />
          ),
        ),
      )}

      {/* X time ticks. First/last labels nudged inward so they don't clip the edges. */}
      {xTicks.map((t, i) => {
        const x = scaleX(t);
        const anchor = i === 0 ? "start" : i === xTicks.length - 1 ? "end" : "middle";
        return (
          <text key={`x-${i}`} x={x} y={H - 6} textAnchor={anchor} fontSize={10} fill="var(--text-4)">
            {shortDate(t)}
          </text>
        );
      })}
    </svg>
  );
}
