const DAY_MS = 86_400_000;
const MONTH_DAYS = 30.44;

/** Months until the least-squares SOH fit reaches 80%. null when < minPoints, span < minSpanDays,
 *  or the trend isn't declining (slope >= 0) — the UI shows "insufficient data". */
export function projectMonthsTo80(
  points: { t: number; soh: number | null }[],
  { minPoints = 6, minSpanDays = 10 }: { minPoints?: number; minSpanDays?: number } = {},
): number | null {
  const pts = points.filter((p): p is { t: number; soh: number } => p.soh != null && Number.isFinite(p.soh));
  if (pts.length < minPoints) return null;
  const t0 = pts[0].t;
  const xs = pts.map((p) => (p.t - t0) / DAY_MS);
  const ys = pts.map((p) => p.soh);
  if (xs[xs.length - 1] - xs[0] < minSpanDays) return null;
  const n = xs.length;
  const mx = xs.reduce((a, b) => a + b, 0) / n, my = ys.reduce((a, b) => a + b, 0) / n;
  let num = 0, den = 0;
  for (let i = 0; i < n; i++) { num += (xs[i] - mx) * (ys[i] - my); den += (xs[i] - mx) ** 2; }
  if (den === 0) return null;
  const slope = num / den;               // soh per day
  if (slope >= 0) return null;           // not declining
  const fittedLast = my + slope * (xs[n - 1] - mx);
  const daysTo80 = (fittedLast - 80) / -slope;
  if (!Number.isFinite(daysTo80) || daysTo80 <= 0) return null;
  return daysTo80 / MONTH_DAYS;
}

export function sohBand(soh: number): "good" | "fair" | "degraded" {
  return soh >= 90 ? "good" : soh >= 80 ? "fair" : "degraded";
}
