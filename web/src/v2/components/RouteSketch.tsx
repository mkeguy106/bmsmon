import { useMemo } from "react";
import type { TrackPoint } from "../track";

/** Tiny tile-free thumbnail of today's cleaned track: the path, a start dot, and an
 *  end dot (the chair's latest position). The Command aside's "Today's route" — the
 *  full interactive map lives in the Journey view. */
export function RouteSketch({ points, height = 120 }: { points: TrackPoint[]; height?: number }) {
  const geo = useMemo(() => {
    if (points.length < 2) return null;
    const lats = points.map((p) => p.lat), lons = points.map((p) => p.lon);
    const latMin = Math.min(...lats), latMax = Math.max(...lats);
    const lonMin = Math.min(...lons), lonMax = Math.max(...lons);
    // Degenerate track (parked all day): no meaningful shape to sketch.
    if (latMax - latMin < 1e-5 && lonMax - lonMin < 1e-5) return null;
    const W = 280, H = 100, PAD = 8;
    // Meters-true aspect: squash longitude by cos(latitude) so the shape isn't stretched.
    const kx = Math.cos(((latMin + latMax) / 2) * Math.PI / 180);
    const spanX = Math.max((lonMax - lonMin) * kx, 1e-9);
    const spanY = Math.max(latMax - latMin, 1e-9);
    const s = Math.min((W - PAD * 2) / spanX, (H - PAD * 2) / spanY);
    const x = (lon: number) => PAD + ((lon - lonMin) * kx * s) + (W - PAD * 2 - spanX * s) / 2;
    const y = (lat: number) => H - PAD - ((lat - latMin) * s) - (H - PAD * 2 - spanY * s) / 2;
    const d = points.map((p, i) => `${i ? "L" : "M"}${x(p.lon).toFixed(1)} ${y(p.lat).toFixed(1)}`).join(" ");
    const a = points[0], b = points[points.length - 1];
    return { d, start: [x(a.lon), y(a.lat)] as const, end: [x(b.lon), y(b.lat)] as const, W, H };
  }, [points]);

  if (!geo) {
    return (
      <div style={{ height, borderRadius: 6, background: "var(--panel-2)",
        display: "flex", alignItems: "center", justifyContent: "center" }}>
        <span className="eyebrow">No route yet today</span>
      </div>
    );
  }
  return (
    <div style={{ height, borderRadius: 6, background: "var(--panel-2)", overflow: "hidden" }}>
      <svg viewBox={`0 0 ${geo.W} ${geo.H}`} width="100%" height="100%" preserveAspectRatio="xMidYMid meet">
        <path d={geo.d} fill="none" stroke="var(--text-3)" strokeWidth="2"
          strokeLinejoin="round" strokeLinecap="round" />
        <circle cx={geo.start[0]} cy={geo.start[1]} r="3" fill="var(--text-4)" />
        <circle cx={geo.end[0]} cy={geo.end[1]} r="4" fill="var(--ok)" stroke="#fff" strokeWidth="1.5" />
      </svg>
    </div>
  );
}
