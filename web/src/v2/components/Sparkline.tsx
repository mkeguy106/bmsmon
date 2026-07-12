import type { HistPoint } from "../history";

export function Sparkline({ points, width = 96, height = 26 }: {
  points: HistPoint[] | undefined; width?: number; height?: number;
}) {
  const pts = points ?? [];
  if (pts.length < 2) {
    return <svg width={width} height={height} aria-hidden><line x1={0} y1={height / 2} x2={width}
      y2={height / 2} stroke="var(--track)" strokeWidth={1} /></svg>;
  }
  const t0 = pts[0].t, t1 = pts[pts.length - 1].t, span = t1 - t0 || 1;
  const d = pts.map((p) => {
    const x = ((p.t - t0) / span) * width;
    const y = height - (Math.max(0, Math.min(100, p.soc)) / 100) * height;
    return `${x.toFixed(1)},${y.toFixed(1)}`;
  }).join(" ");
  return <svg width={width} height={height}>
    <polyline points={d} fill="none" stroke="var(--text-3)" strokeWidth={1.5}
      strokeLinejoin="round" strokeLinecap="round" /></svg>;
}
