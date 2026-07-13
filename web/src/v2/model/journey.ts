import type { TrackPoint, Track } from "../track";

export interface LatLon { lat: number; lon: number }

export const DISCHARGE_EPS = 0.1;   // A — matches fleet.ts/android
export const MOVE_EPS_MI = 0.003;   // ~5 m
export const POWER_GREEN_W = 150;   // base-total; below → green
export const POWER_RED_W = 350;     // base-total; at/above → red
export const HOTSPOT_W = 330;       // base-total local-max threshold
export const HOTSPOT_MIN_GAP_MI = 0.1;

const R_MI = 3958.7613;
export function haversineMi(a: LatLon, b: LatLon): number {
  const toRad = (d: number) => (d * Math.PI) / 180;
  const dLat = toRad(b.lat - a.lat), dLon = toRad(b.lon - a.lon);
  const s = Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(a.lat)) * Math.cos(toRad(b.lat)) * Math.sin(dLon / 2) ** 2;
  return 2 * R_MI * Math.asin(Math.min(1, Math.sqrt(s)));
}

export type SegKind = "active" | "transit" | "idle";
/** Segment kind from the destination point's base current + how far it moved. */
export function classifySegment(cur: TrackPoint, movedMi: number): SegKind {
  const a = cur.current_a ?? 0;
  if (a < -DISCHARGE_EPS) return "active";
  if (Math.abs(a) <= DISCHARGE_EPS && movedMi > MOVE_EPS_MI) return "transit";
  return "idle";
}

export function dischargeColor(powerW: number): string {
  const p = Math.abs(powerW);
  if (p >= POWER_RED_W) return "var(--live)";
  if (p >= POWER_GREEN_W) return "var(--warn)";
  return "var(--ok)";
}

/** Trail/capacity color by SOC, on the alert bands: ok > 30, warn ≤ 30, live ≤ 15. */
export function socColor(soc: number | null | undefined): string {
  if (soc == null) return "var(--text-4)";
  if (soc <= 15) return "var(--live)";
  if (soc <= 30) return "var(--warn)";
  return "var(--ok)";
}

export function cumulativeMiles(points: TrackPoint[]): number[] {
  const out: number[] = [];
  let acc = 0;
  for (let i = 0; i < points.length; i++) {
    if (i > 0) acc += haversineMi(points[i - 1], points[i]);
    out.push(acc);
  }
  return out;
}

export interface Hotspot { index: number; powerW: number }
export function detectHotspots(
  points: TrackPoint[], cumMi: number[],
  { thresholdW = HOTSPOT_W, minGapMi = HOTSPOT_MIN_GAP_MI }: { thresholdW?: number; minGapMi?: number } = {},
): Hotspot[] {
  const cand: Hotspot[] = [];
  for (let i = 1; i < points.length - 1; i++) {
    const p = Math.abs(points[i].power_w ?? 0);
    if (p < thresholdW) continue;
    if (p >= Math.abs(points[i - 1].power_w ?? 0) && p >= Math.abs(points[i + 1].power_w ?? 0))
      cand.push({ index: i, powerW: p });
  }
  cand.sort((a, b) => b.powerW - a.powerW);
  const kept: Hotspot[] = [];
  for (const c of cand) {
    if (kept.every((k) => Math.abs(cumMi[k.index] - cumMi[c.index]) >= minGapMi)) kept.push(c);
  }
  return kept.sort((a, b) => a.index - b.index);
}

export interface EnergyPoint { d: number; power: number; transit: boolean }
export function energySeries(points: TrackPoint[], cumMi: number[]): EnergyPoint[] {
  return points.map((pt, i) => {
    const moved = i > 0 ? haversineMi(points[i - 1], pt) : 0;
    return { d: cumMi[i], power: Math.abs(pt.power_w ?? 0), transit: classifySegment(pt, moved) === "transit" };
  });
}

export interface TripSummary { miles: number; activeMiles: number; transitMiles: number; peakW: number; durationMin: number }
export function tripSummary(points: TrackPoint[], cumMi: number[]): TripSummary {
  let activeMiles = 0, transitMiles = 0;
  for (let i = 1; i < points.length; i++) {
    const seg = haversineMi(points[i - 1], points[i]);
    const kind = classifySegment(points[i], seg);
    if (kind === "active") activeMiles += seg;
    else if (kind === "transit") transitMiles += seg;
  }
  const peakW = points.reduce((m, p) => Math.max(m, Math.abs(p.power_w ?? 0)), 0);
  return { miles: cumMi[cumMi.length - 1] ?? 0, activeMiles, transitMiles, peakW,
    durationMin: points.length > 1 ? (points[points.length - 1].t - points[0].t) / 60000 : 0 };
}

/** Align the base's packs by bucket t; sum power+current, mean coords, min soc → the chair track. */
export function mergeBaseTracks(tracks: Track[]): TrackPoint[] {
  const byT = new Map<number, TrackPoint[]>();
  for (const tr of tracks) for (const p of tr.points) {
    const arr = byT.get(p.t) ?? byT.set(p.t, []).get(p.t)!;
    arr.push(p);
  }
  const out: TrackPoint[] = [];
  for (const t of [...byT.keys()].sort((a, b) => a - b)) {
    const ps = byT.get(t)!;
    const sum = (f: (p: TrackPoint) => number | null) =>
      ps.reduce((acc, p) => acc + (f(p) ?? 0), 0);
    const mean = (f: (p: TrackPoint) => number | null) => {
      const vs = ps.map(f).filter((v): v is number => v != null && Number.isFinite(v));
      return vs.length ? vs.reduce((a, b) => a + b, 0) / vs.length : 0;
    };
    const socs = ps.map((p) => p.soc).filter((v): v is number => v != null);
    out.push({ t, lat: mean((p) => p.lat), lon: mean((p) => p.lon),
      power_w: sum((p) => p.power_w), current_a: sum((p) => p.current_a),
      soc: socs.length ? Math.min(...socs) : null });
  }
  return out;
}
