// Accuracy-weighted constant-velocity Kalman smoothing for the Journey track.
//
// Why this exists: inside a vehicle the phone falls back to cell-tower positioning, so fixes
// arrive sparsely and coarsely (measured 2026-07-29: 15-120 s apart, 18-223 m radius after the
// server's 250 m gate). The old 3-point moving average could not tell a 200 m fix from an 18 m
// one, and the map drew straight confident lines across multi-minute holes — a 120 s gap at
// 33 m/s is a 3.9 km chord. This weights every fix by its own reported radius and predicts
// through short holes along the last known velocity.
//
// East and north are filtered INDEPENDENTLY: with diagonal measurement/process noise the two
// axes do not interact, so two 2-state filters are identical to one 4-state filter and much
// easier to verify. Smoothing uses the two-filter (Fraser-Potter) form — the forward posterior
// (includes measurement i) combined with the backward prior (excludes it), which keeps the two
// estimates independent so inverse-variance weighting is valid, and needs no matrix inversion.
// Design: docs/superpowers/specs/2026-07-29-gps-kalman-track-smoothing-design.md
import type { TrackPoint } from "../track";

export const SIGMA_ACCEL_MPS2 = 2;
export const ACC_FLOOR_M = 5;
export const ACC_DEFAULT_M = 30;
export const INIT_VEL_VAR_M2S2 = 400;

const R_EARTH_M = 6_371_000;
const M_PER_DEG_LAT = (Math.PI / 180) * R_EARTH_M;

interface Proj { lat0: number; lon0: number; mPerDegLon: number }

/** Local tangent plane about [lat0, lon0]. Filtering in degrees would make the variances
 *  meaningless and latitude-dependent; metres keep R (accuracy²) and Q comparable. */
function makeProj(lat0: number, lon0: number): Proj {
  const cos = Math.cos((lat0 * Math.PI) / 180);
  return { lat0, lon0, mPerDegLon: M_PER_DEG_LAT * Math.max(Math.abs(cos), 1e-6) };
}
const toE = (p: Proj, lon: number) => (lon - p.lon0) * p.mPerDegLon;
const toN = (p: Proj, lat: number) => (lat - p.lat0) * M_PER_DEG_LAT;
const toLon = (p: Proj, e: number) => p.lon0 + e / p.mPerDegLon;
const toLat = (p: Proj, n: number) => p.lat0 + n / M_PER_DEG_LAT;

/** Measurement variance from the reported accuracy radius, floored so no fix is trusted
 *  absolutely and defaulted for rows predating the server's `acc` field. */
export function varianceFor(acc: number | null | undefined): number {
  const r = acc == null || !Number.isFinite(acc) ? ACC_DEFAULT_M : Math.max(acc, ACC_FLOOR_M);
  return r * r;
}

/** One axis of a constant-velocity filter: position, velocity, and the 2x2 covariance. */
interface Axis { x: number; v: number; p00: number; p01: number; p10: number; p11: number }

const init = (z: number, r: number): Axis =>
  ({ x: z, v: 0, p00: r, p01: 0, p10: 0, p11: INIT_VEL_VAR_M2S2 });

function predict(a: Axis, dtS: number): Axis {
  const q = SIGMA_ACCEL_MPS2 * SIGMA_ACCEL_MPS2;
  const dt2 = dtS * dtS, dt3 = dt2 * dtS, dt4 = dt2 * dt2;
  return {
    x: a.x + a.v * dtS,
    v: a.v,
    p00: a.p00 + dtS * (a.p01 + a.p10) + dt2 * a.p11 + (q * dt4) / 4,
    p01: a.p01 + dtS * a.p11 + (q * dt3) / 2,
    p10: a.p10 + dtS * a.p11 + (q * dt3) / 2,
    p11: a.p11 + q * dt2,
  };
}

function update(a: Axis, z: number, r: number): Axis {
  const s = a.p00 + r;
  const k0 = a.p00 / s, k1 = a.p10 / s;
  const y = z - a.x;
  return {
    x: a.x + k0 * y,
    v: a.v + k1 * y,
    p00: a.p00 - k0 * a.p00,
    p01: a.p01 - k0 * a.p01,
    p10: a.p10 - k1 * a.p00,
    p11: a.p11 - k1 * a.p01,
  };
}

interface Estimate { x: number; varX: number }

/** Forward pass: posterior estimate at every index. */
function forward(z: number[], r: number[], tS: number[]): Estimate[] {
  const out: Estimate[] = [];
  let a: Axis | null = null;
  for (let i = 0; i < z.length; i++) {
    a = a === null ? init(z[i], r[i]) : update(predict(a, tS[i] - tS[i - 1]), z[i], r[i]);
    out.push({ x: a.x, varX: a.p00 });
  }
  return out;
}

/** Backward pass: PRIOR estimate at every index (measurement i deliberately excluded, so it
 *  stays independent of the forward posterior). The last index has no prior — infinite
 *  variance — which is what makes the smoothed head equal the filtered head.
 *
 *  Exported for testing: the independence of out[i] from z[i]/r[i] is exactly what makes the
 *  inverse-variance combination in `combine` valid, and it is easy to break silently (folding
 *  the posterior in here instead would double-count every measurement without failing any
 *  distance-threshold test). */
export function backwardPrior(z: number[], r: number[], tS: number[]): Estimate[] {
  const out: Estimate[] = new Array(z.length);
  let a: Axis | null = null;
  for (let i = z.length - 1; i >= 0; i--) {
    if (a === null) {
      out[i] = { x: 0, varX: Infinity };
    } else {
      a = predict(a, tS[i + 1] - tS[i]);
      out[i] = { x: a.x, varX: a.p00 };
    }
    a = a === null ? init(z[i], r[i]) : update(a, z[i], r[i]);
  }
  return out;
}

/** Inverse-variance combination of the two passes. */
function combine(f: Estimate[], b: Estimate[]): number[] {
  return f.map((fi, i) => {
    const wf = 1 / fi.varX;
    const wb = Number.isFinite(b[i].varX) ? 1 / b[i].varX : 0;
    return (fi.x * wf + b[i].x * wb) / (wf + wb);
  });
}

/**
 * Smooth a time-ordered track. Returns a new array of the same length, order and timestamps
 * with only `lat`/`lon` changed — the energy chart and hover inspection index into it.
 */
export function smoothKalman(points: TrackPoint[]): TrackPoint[] {
  if (points.length < 2) return points;
  const proj = makeProj(points[0].lat, points[0].lon);
  const tS = points.map((p) => p.t / 1000);
  const r = points.map((p) => varianceFor(p.acc));
  const e = points.map((p) => toE(proj, p.lon));
  const n = points.map((p) => toN(proj, p.lat));

  const eS = combine(forward(e, r, tS), backwardPrior(e, r, tS));
  const nS = combine(forward(n, r, tS), backwardPrior(n, r, tS));

  return points.map((p, i) => ({ ...p, lat: toLat(proj, nS[i]), lon: toLon(proj, eS[i]) }));
}
