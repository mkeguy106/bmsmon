// GPS track cleaning for the Journey map + trip math. Raw fixes stay raw in the DB; this
// runs at render time. Three passes: (1) spike rejection — a fix demanding impossible speed
// both to reach AND to leave, while its neighbors agree with each other, is a lie (the chair
// tops out ~9 mph; vehicle rides are only plausible while the chair isn't discharging);
// (2) stay-point snapping — parked jitter collapses onto one spot WITHOUT removing points
// (the playback timeline / energy series iterate this array); (3) 3-point smoothing.
// Design: docs/superpowers/specs/2026-07-13-gps-track-cleaning-design.md
// Kotlin sibling (spike rejection only): android .../model/RangeLearn.kt rejectSpikes().
import type { TrackPoint } from "../track";
import { DISCHARGE_EPS, haversineMi } from "./journey";

export const CHAIR_MAX_MPS = 4.5;   // ~9 mph chair top speed + downhill margin
export const VEHICLE_MAX_MPS = 45;  // ~100 mph — anything under this is a plausible vehicle
export const ABSURD_MPS = 60;       // beyond this the fix is garbage regardless of context
export const STAY_RADIUS_MI = 30 / 1609.34;

const discharging = (p: TrackPoint) => (p.current_a ?? 0) < -DISCHARGE_EPS;

function speedMps(a: TrackPoint, b: TrackPoint): number {
  const dtS = (b.t - a.t) / 1000;
  if (dtS <= 0) return Infinity;
  return (haversineMi(a, b) * 1609.34) / dtS;
}

/** Drop out-and-back spikes and absurd jumps; keep sustained movement (vehicle or reacquire). */
export function rejectSpikes(points: TrackPoint[]): TrackPoint[] {
  const out: TrackPoint[] = [];
  for (let i = 0; i < points.length; i++) {
    const b = points[i];
    const a = out[out.length - 1];
    if (!a) { out.push(b); continue; }
    const vIn = speedMps(a, b);
    if (vIn > ABSURD_MPS) continue;
    const bound = discharging(a) || discharging(b) ? CHAIR_MAX_MPS : VEHICLE_MAX_MPS;
    if (vIn > bound) {
      const c = points[i + 1];
      if (c && speedMps(b, c) > bound && speedMps(a, c) <= bound) continue; // spike: drop b
    }
    out.push(b);
  }
  return out;
}

/** Snap runs of points that never leave STAY_RADIUS_MI of the run's first point onto their
 *  centroid. Points are kept (same count/order/timestamps) so playback survives. */
export function snapStays(points: TrackPoint[]): TrackPoint[] {
  const out = points.slice();
  let i = 0;
  while (i < out.length) {
    let j = i + 1;
    while (j < out.length && haversineMi(out[i], out[j]) <= STAY_RADIUS_MI) j++;
    if (j - i >= 3) {
      const n = j - i;
      const lat = out.slice(i, j).reduce((s, p) => s + p.lat, 0) / n;
      const lon = out.slice(i, j).reduce((s, p) => s + p.lon, 0) / n;
      for (let k = i; k < j; k++) out[k] = { ...out[k], lat, lon };
    }
    i = j;
  }
  return out;
}

/** 3-point moving average on coordinates; endpoints untouched. */
export function smoothTrack(points: TrackPoint[]): TrackPoint[] {
  if (points.length < 3) return points;
  return points.map((p, i) => {
    if (i === 0 || i === points.length - 1) return p;
    return {
      ...p,
      lat: (points[i - 1].lat + p.lat + points[i + 1].lat) / 3,
      lon: (points[i - 1].lon + p.lon + points[i + 1].lon) / 3,
    };
  });
}

export function cleanTrack(points: TrackPoint[]): TrackPoint[] {
  return smoothTrack(snapStays(rejectSpikes(points)));
}
