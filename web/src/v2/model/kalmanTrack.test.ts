import { describe, expect, it } from "vitest";
import { smoothKalman, ACC_DEFAULT_M, backwardPrior } from "./kalmanTrack";
import type { TrackPoint } from "../track";

const mk = (t: number, lat: number, lon: number, acc: number | null = 10): TrackPoint =>
  ({ t, lat, lon, power_w: null, current_a: null, soc: null, acc });

/** Metres between two close points (flat-earth approximation, good enough for tests). */
const distM = (a: TrackPoint, b: TrackPoint) => {
  const mLat = 111_320, mLon = 111_320 * Math.cos((a.lat * Math.PI) / 180);
  return Math.hypot((b.lat - a.lat) * mLat, (b.lon - a.lon) * mLon);
};

describe("smoothKalman", () => {
  it("preserves length, order and timestamps", () => {
    const pts = [mk(0, 43, -87.9), mk(15_000, 43.0005, -87.9), mk(30_000, 43.001, -87.9)];
    const out = smoothKalman(pts);
    expect(out.map((p) => p.t)).toEqual([0, 15_000, 30_000]);
    expect(out).toHaveLength(3);
  });

  it("recovers a constant-velocity straight line", () => {
    // Due north at ~10 m/s, 15 s buckets, with ±3 m of noise injected.
    const noise = [0, 3, -3, 2, -2, 1, -1, 0, 2, -2];
    const pts = noise.map((n, i) =>
      mk(i * 15_000, 43 + (i * 150 + n) / 111_320, -87.9, 10));
    const out = smoothKalman(pts);
    // Every smoothed point should sit within 3 m of the true line it was generated from.
    out.forEach((p, i) => {
      const truth = mk(i * 15_000, 43 + (i * 150) / 111_320, -87.9);
      expect(distM(truth, p)).toBeLessThan(3);
    });
  });

  it("trusts a fine fix far more than a coarse one", () => {
    // Same 40 m sideways excursion, once reported as 10 m accurate, once as 200 m.
    const run = (acc: number) => {
      const pts = [mk(0, 43, -87.9, 10), mk(15_000, 43, -87.9, 10),
        mk(30_000, 43, -87.9 + 40 / (111_320 * Math.cos((43 * Math.PI) / 180)), acc),
        mk(45_000, 43, -87.9, 10), mk(60_000, 43, -87.9, 10)];
      return smoothKalman(pts)[2];
    };
    const anchor = mk(0, 43, -87.9);
    expect(distM(anchor, run(200))).toBeLessThan(distM(anchor, run(10)));
  });

  it("treats a null accuracy as ACC_DEFAULT_M rather than a perfect fix", () => {
    const withNull = smoothKalman([mk(0, 43, -87.9, 10), mk(15_000, 43, -87.9, 10),
      mk(30_000, 43.0004, -87.9, null), mk(45_000, 43, -87.9, 10)])[2];
    const withDefault = smoothKalman([mk(0, 43, -87.9, 10), mk(15_000, 43, -87.9, 10),
      mk(30_000, 43.0004, -87.9, ACC_DEFAULT_M), mk(45_000, 43, -87.9, 10)])[2];
    expect(withNull.lat).toBeCloseTo(withDefault.lat, 10);
  });

  it("backward prior at i ignores the measurement at i (no double-counting)", () => {
    const tS = [0, 15, 30, 45];
    const r = [100, 100, 100, 100];
    const z = [0, 150, 300, 450];
    const moved = [0, 150, 9999, 450]; // index 2 perturbed wildly
    const ok = z.map(() => true);
    const a = backwardPrior(z, r, tS, ok);
    const b = backwardPrior(moved, r, tS, ok);
    expect(b[2].x).toBeCloseTo(a[2].x, 9); // prior at 2 must not move
    expect(b[2].varX).toBeCloseTo(a[2].varX, 9);
    expect(b[1].x).not.toBeCloseTo(a[1].x, 3); // but index 1 DOES see it (sanity: the test can fail)
  });

  it("returns short tracks untouched", () => {
    expect(smoothKalman([])).toEqual([]);
    const one = [mk(0, 43, -87.9)];
    expect(smoothKalman(one)).toEqual(one);
  });

  it("rejects a single wild outlier instead of bending the path around it", () => {
    // Due north at 10 m/s with one fix teleported 500 m east, claiming to be accurate.
    const mLon = 111_320 * Math.cos((43 * Math.PI) / 180);
    const pts = Array.from({ length: 9 }, (_, i) =>
      mk(i * 15_000, 43 + (i * 150) / 111_320, -87.9, 10));
    pts[4] = mk(4 * 15_000, pts[4].lat, -87.9 + 500 / mLon, 10);
    const out = smoothKalman(pts);
    // The outlier's own smoothed position must stay near the true line, not out at 500 m.
    const offsetM = Math.abs(out[4].lon - -87.9) * mLon;
    expect(offsetM).toBeLessThan(50);
    // …and its neighbours must be barely disturbed.
    expect(Math.abs(out[3].lon - -87.9) * mLon).toBeLessThan(20);
    expect(Math.abs(out[5].lon - -87.9) * mLon).toBeLessThan(20);
  });

  it("accepts a real train acceleration instead of gating it as an outlier", () => {
    // ~40 m/s cruise, then one bucket of genuine 1 m/s² acceleration. This must NOT be
    // rejected — the gate's job is to catch teleports, not physics.
    const mLat = 111_320;
    const pts = [0, 1, 2, 3, 4].map((i) => mk(i * 15_000, 43 + (i * 600) / mLat, -87.9, 10));
    const extra = 0.5 * 1.0 * 15 * 15;                     // 112.5 m beyond constant velocity
    pts.push(mk(5 * 15_000, 43 + (5 * 600 + extra) / mLat, -87.9, 10));
    const out = smoothKalman(pts);
    // The accelerated fix must still land essentially on its measurement, not be pulled back
    // to the constant-velocity prediction as a rejected outlier would be.
    const measuredN = (pts[5].lat - 43) * mLat, smoothedN = (out[5].lat - 43) * mLat;
    expect(Math.abs(smoothedN - measuredN)).toBeLessThan(40);
  });
});
