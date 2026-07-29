# Predictive GPS Track Smoothing (Kalman) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the v2 Journey trail read as a continuous, plausible path — and the live chair marker glide instead of teleport — by filtering GPS through an accuracy-weighted constant-velocity Kalman filter, while visibly marking stretches that are inferred rather than measured.

**Architecture:** The server starts passing each 15 s bucket's GPS accuracy radius through to the client. A new pure module (`web/src/v2/model/kalmanTrack.ts`) projects the track to local metres, runs two independent 1-D constant-velocity filters (east and north) weighted by that radius, rejects outliers by innovation gating, breaks the track where GPS was absent too long, and smooths the result with a two-filter (forward/backward) pass. It slots into `cleanTrack` in place of the current 3-point moving average, so the map, trip miles, energy chart and efficiency card all consume it. Live-marker prediction derives heading from the last two *smoothed* points — no filter state plumbing needed.

**Tech Stack:** TypeScript + React + Leaflet (`web/`), vitest for pure-model tests; FastAPI + asyncpg + Postgres 16 (`server/`), pytest for API tests.

## Global Constraints

- **Commit messages must never mention Claude, AI, or automated generation** (repo rule, `CLAUDE.md`).
- **Raw data stays raw:** no change to what is written to `samples`, and no change to `GPS_ACCURACY_MAX_M = 250` (the server's coarse-fix gate). All cleaning is render-time.
- **Array-shape invariant:** every `cleanTrack` pass *except* `rejectSpikes` must preserve its input's length, order and timestamps. The energy chart, hover inspection and SOC series index into the cleaned array.
- **No Kotlin changes.** The Android chair-miles learner is discharge-gated; transit never reaches it.
- **No changes to the public share page** (`web/share/`) or `gps_track_all`.
- **Pure logic lives in `web/src/v2/model/`** with a colocated `*.test.ts`; components stay thin. This is the established pattern for every v2 feature.
- **Verification before any completion claim:** `npx tsc --noEmit`, `npx vitest run`, and `npx vite build` from `web/`; `.venv/bin/python -m pytest` from `server/` (bare `python` lacks the deps).
- Server tests need the dev Postgres: `docker compose -f server/docker-compose.dev.yml up -d`.

**Constants (exact values, used across tasks):**

```ts
export const SIGMA_ACCEL_MPS2 = 0.5;    // RMS accel sustained over ONE 15 s bucket (see note)
export const ACC_FLOOR_M = 5;           // no fix is truly better than this
export const ACC_DEFAULT_M = 30;        // when the row carries no accuracy (pre-deploy history)
export const INIT_VEL_VAR_M2S2 = 400;   // (20 m/s)² — generous, so a train start isn't fought
export const GATE_CHI2 = 13.8;          // 2 dof, p=0.999 innovation gate
export const COAST_MAX_MS = 30_000;     // longer holes break the track instead of coasting
export const PREDICT_MAX_MS = 10_000;   // live marker: extrapolate at most this long…
export const PREDICT_MAX_M = 200;       // …or this far, whichever binds first
```

**Design doc:** `docs/superpowers/specs/2026-07-29-gps-kalman-track-smoothing-design.md`

---

### Task 1: Server passes the GPS accuracy radius through

**Files:**
- Modify: `server/app/db/queries.py:499-518` (`track_series`)
- Modify: `server/app/routers/web.py:99-108` (`/web/track` response)
- Test: `server/tests/test_web_track.py`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `/web/track` response points gain `"acc": float | None` — the bucket's `avg(gps_accuracy_m)`. Task 2 decodes it.

- [ ] **Step 1: Write the failing test**

Append to `server/tests/test_web_track.py` (follow the existing `_seed`/`USER`/`BUCKET`/`DEV`/`A` fixtures already used in that file):

```python
async def test_track_returns_accuracy_radius(app, client):
    """The Journey map weights each fix by its accuracy radius, so /web/track must
    expose it. Buckets average it like the coordinates; a NULL-accuracy fix yields None."""
    pool = app.state.pool
    async with pool.acquire() as conn:
        await conn.execute(
            "INSERT INTO devices (id, install_uuid, public_key_spki) VALUES ($1,$2,$3)",
            DEV, "uuid-web-track-acc", b"\x00",
        )
        base = int(datetime(2026, 7, 3, tzinfo=timezone.utc).timestamp() * 1000)
        base = (base // BUCKET) * BUCKET
        rows = [
            q.sample_row(DEV, A, {"ts_ms": base + 1000, "lat": 43.0, "lon": -87.9,
                                  "soc": 88, "gps_accuracy_m": 10.0}),
            q.sample_row(DEV, A, {"ts_ms": base + 2000, "lat": 43.0, "lon": -87.9,
                                  "soc": 88, "gps_accuracy_m": 30.0}),
            q.sample_row(DEV, A, {"ts_ms": base + BUCKET + 1000, "lat": 43.001, "lon": -87.9,
                                  "soc": 88}),  # no accuracy reported
        ]
        assert await q.insert_samples(conn, rows) == 3

    r = await client.get("/web/track", headers=USER,
                         params={"address": A, "from_ms": base, "to_ms": base + 2 * BUCKET})
    assert r.status_code == 200
    points = r.json()["points"]
    assert len(points) == 2
    assert points[0]["acc"] == 20.0   # mean of 10 and 30
    assert points[1]["acc"] is None
```

- [ ] **Step 2: Run the test and watch it fail**

```bash
cd server && .venv/bin/python -m pytest tests/test_web_track.py::test_track_returns_accuracy_radius -v
```

Expected: FAIL with `KeyError: 'acc'`.

- [ ] **Step 3: Add the column to the query**

In `server/app/db/queries.py`, inside `track_series`'s SELECT list, after the `soc` aggregate:

```sql
                  avg(power_w)::real AS power_w, avg(current_a)::real AS current_a, avg(soc)::real AS soc,
                  avg(gps_accuracy_m)::real AS acc
```

Update the docstring's first line to mention it:

```python
    """15-second buckets of GPS-carrying real telemetry (lat/lon present) with discharge context
    and the bucket's mean accuracy radius (`acc`) — the Journey map weights fixes by it.
    Coarse fixes (accuracy radius > GPS_ACCURACY_MAX_M) are gated out; NULL accuracy passes.
    The redundant ts predicates exist purely for partition pruning (see history_series)."""
```

- [ ] **Step 4: Add it to the response**

In `server/app/routers/web.py`, in the `track` handler's point construction:

```python
    points = [{"t": int(r["bucket_ms"]), "lat": _f(r["lat"]), "lon": _f(r["lon"]),
               "power_w": _f(r["power_w"]), "current_a": _f(r["current_a"]), "soc": _f(r["soc"]),
               "acc": _f(r["acc"])}
              for r in rows]
```

- [ ] **Step 5: Run the full server track suite**

```bash
cd server && .venv/bin/python -m pytest tests/test_web_track.py tests/test_track.py -v
```

Expected: all PASS, including the pre-existing coarse-fix gate test (unchanged behaviour).

- [ ] **Step 6: Commit**

```bash
git add server/app/db/queries.py server/app/routers/web.py server/tests/test_web_track.py
git commit -m "feat(server): expose per-bucket GPS accuracy radius on /web/track"
```

---

### Task 2: Client carries `acc` end-to-end

**Files:**
- Modify: `web/src/v2/track.ts` (the `TrackPoint` interface)
- Modify: `web/src/decode.ts:191-201` (`decodeTrack`)
- Modify: `web/src/v2/model/journey.ts:98-119` (`mergeBaseTracks`)
- Test: `web/src/decode.test.ts`, `web/src/v2/model/journey.test.ts` (create if absent — check first with `ls web/src/v2/model/journey.test.ts`)

**Interfaces:**
- Consumes: `/web/track` points with `acc` (Task 1).
- Produces: `TrackPoint.acc: number | null` and `TrackPoint.inferred?: boolean` (the latter is declared here so Task 5 can set it without touching the type again). `mergeBaseTracks` averages `acc` across a base's packs.

- [ ] **Step 1: Write the failing tests**

In `web/src/decode.test.ts`, add:

```ts
it("decodes the track accuracy radius, defaulting to null", () => {
  const t = decodeTrack({ address: "a", points: [
    { t: 1, lat: 43, lon: -87.9, acc: 12.5 },
    { t: 2, lat: 43.001, lon: -87.9 },
  ] });
  expect(t?.points[0].acc).toBe(12.5);
  expect(t?.points[1].acc).toBeNull();
});
```

In `web/src/v2/model/journey.test.ts`, add (creating the file with `import { describe, expect, it } from "vitest";` and `import { mergeBaseTracks } from "./journey";` if it does not exist):

```ts
describe("mergeBaseTracks", () => {
  it("averages the accuracy radius across a base's packs", () => {
    const mk = (address: string, acc: number | null) => ({
      address,
      points: [{ t: 1000, lat: 43, lon: -87.9, power_w: -10, current_a: -1, soc: 90, acc }],
    });
    const merged = mergeBaseTracks([mk("A", 10), mk("B", 30)]);
    expect(merged[0].acc).toBe(20);
  });

  it("yields null accuracy when no pack reported one", () => {
    const mk = (address: string) => ({
      address,
      points: [{ t: 1000, lat: 43, lon: -87.9, power_w: null, current_a: null, soc: null, acc: null }],
    });
    expect(mergeBaseTracks([mk("A"), mk("B")])[0].acc).toBeNull();
  });
});
```

- [ ] **Step 2: Run them and watch them fail**

```bash
cd web && npx vitest run src/decode.test.ts src/v2/model/journey.test.ts
```

Expected: FAIL — `acc` is `undefined` (not a declared property).

- [ ] **Step 3: Extend the type**

`web/src/v2/track.ts` becomes:

```ts
export interface TrackPoint {
  t: number; lat: number; lon: number;
  power_w: number | null; current_a: number | null; soc: number | null;
  /** Mean GPS accuracy radius (metres) for the bucket; null when unreported. */
  acc: number | null;
  /** Set by kalmanTrack: the segment from the PREVIOUS point to this one is inferred,
   *  not measured (a GPS hole longer than COAST_MAX_MS). Never set on the first point. */
  inferred?: boolean;
}
export interface Track { address: string; points: TrackPoint[] }
```

- [ ] **Step 4: Decode it**

In `web/src/decode.ts`, inside `decodeTrack`'s push:

```ts
    points.push({ t: p.t as number, lat: p.lat as number, lon: p.lon as number,
      power_w: numOrNull(p.power_w) ?? null, current_a: numOrNull(p.current_a) ?? null,
      soc: numOrNull(p.soc) ?? null, acc: numOrNull(p.acc) ?? null });
```

- [ ] **Step 5: Merge it**

In `web/src/v2/model/journey.ts`, `mergeBaseTracks` currently builds each output point with `mean(...)` for coords. Accuracy needs a *null-preserving* mean (the existing `mean` returns 0 when everything is null, which would claim a perfect fix). Add above the `out.push`:

```ts
    const accs = ps.map((p) => p.acc).filter((v): v is number => v != null && Number.isFinite(v));
```

and extend the pushed object:

```ts
    out.push({ t, lat: mean((p) => p.lat), lon: mean((p) => p.lon),
      power_w: sum((p) => p.power_w), current_a: sum((p) => p.current_a),
      soc: socs.length ? Math.min(...socs) : null,
      acc: accs.length ? accs.reduce((a, b) => a + b, 0) / accs.length : null });
```

- [ ] **Step 6: Run the tests and the typechecker**

```bash
cd web && npx vitest run && npx tsc --noEmit
```

Expected: both PASS. `tsc` will flag any other place constructing a `TrackPoint` without `acc` — fix each by adding `acc: null` (test fixtures and `web/src/preview.tsx` are the likely hits).

- [ ] **Step 7: Commit**

```bash
git add web/src/v2/track.ts web/src/decode.ts web/src/decode.test.ts \
        web/src/v2/model/journey.ts web/src/v2/model/journey.test.ts
git commit -m "feat(web): carry GPS accuracy radius through the track pipeline"
```

---

### Task 3: Kalman core — projection, forward filter, two-filter smoother

**Files:**
- Create: `web/src/v2/model/kalmanTrack.ts`
- Test: `web/src/v2/model/kalmanTrack.test.ts`

**Interfaces:**
- Consumes: `TrackPoint` with `acc` (Task 2).
- Produces: `smoothKalman(points: TrackPoint[]): TrackPoint[]` — same length/order/timestamps, only `lat`/`lon` changed. Task 4 adds gating inside it, Task 5 adds breaks; Task 6 wires it into `cleanTrack`.

**Why two independent 1-D filters:** with diagonal measurement and process noise, east and north do not interact, so two 2-state filters are mathematically identical to one 4-state filter and far easier to get right. **Why a two-filter (Fraser–Potter) smoother rather than RTS:** it needs no matrix inversion — combine the forward *posterior* (which includes measurement `i`) with the backward *prior* (which excludes it), so the two are independent and inverse-variance weighting is valid. At the head the backward prior does not exist, so the smoothed value equals the filtered one — exactly the property the live marker needs.

- [ ] **Step 1: Write the failing tests**

Create `web/src/v2/model/kalmanTrack.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { smoothKalman, ACC_DEFAULT_M } from "./kalmanTrack";
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

  it("returns short tracks untouched", () => {
    expect(smoothKalman([])).toEqual([]);
    const one = [mk(0, 43, -87.9)];
    expect(smoothKalman(one)).toEqual(one);
  });
});
```

- [ ] **Step 2: Run and watch it fail**

```bash
cd web && npx vitest run src/v2/model/kalmanTrack.test.ts
```

Expected: FAIL — cannot resolve `./kalmanTrack`.

- [ ] **Step 3: Write the module**

Create `web/src/v2/model/kalmanTrack.ts`:

```ts
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

export const SIGMA_ACCEL_MPS2 = 0.5;
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
 *  variance — which is what makes the smoothed head equal the filtered head. */
function backwardPrior(z: number[], r: number[], tS: number[]): Estimate[] {
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
```

- [ ] **Step 4: Run the tests**

```bash
cd web && npx vitest run src/v2/model/kalmanTrack.test.ts && npx tsc --noEmit
```

Expected: all 5 PASS. If "recovers a constant-velocity straight line" fails marginally, do **not** loosen the assertion — check that `tS` is in seconds and that `predict` receives `dtS`, not milliseconds; a 1000× error in dt is the most likely cause.

- [ ] **Step 5: Commit**

```bash
git add web/src/v2/model/kalmanTrack.ts web/src/v2/model/kalmanTrack.test.ts
git commit -m "feat(web): accuracy-weighted Kalman smoothing for GPS tracks"
```

---

### Task 4: Innovation gating (outlier rejection)

**Files:**
- Modify: `web/src/v2/model/kalmanTrack.ts`
- Test: `web/src/v2/model/kalmanTrack.test.ts`

**Interfaces:**
- Consumes: `forward`/`backwardPrior` from Task 3.
- Produces: no signature change — `smoothKalman` gains outlier immunity. Both axes must be gated *jointly* (one 2-dof test), so the per-axis passes now take a shared list of accepted indices.

**Why joint:** a fix is one 2-D event. Gating each axis separately would reject a fix that is only wrong east-west, warping the north component with a half-rejected measurement.

- [ ] **Step 1: Write the failing test**

Add to `kalmanTrack.test.ts`:

```ts
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
```

- [ ] **Step 2: Run it and watch it fail**

```bash
cd web && npx vitest run src/v2/model/kalmanTrack.test.ts -t "wild outlier"
```

Expected: FAIL — without gating the filter chases the 500 m fix (offset well over 50 m).

- [ ] **Step 3: Add the gate**

In `kalmanTrack.ts`, add the constant next to the others:

```ts
export const GATE_CHI2 = 13.8;          // 2 dof, p=0.999
```

Add a joint-gating helper and rewrite the pass functions to consume a precomputed accept mask. Replace `forward` and `backwardPrior` with:

```ts
/** Normalized innovation squared for one axis. */
const nis = (a: Axis, z: number, r: number) => ((z - a.x) ** 2) / (a.p00 + r);

/**
 * Which measurements the filter will accept, decided on a FORWARD pass over both axes at
 * once: a fix whose joint normalized innovation exceeds GATE_CHI2 is inconsistent with the
 * motion model and is skipped (the prediction stands). This is what separates "fast because
 * it is a train" from "fast because the fix is wrong" without hand-tuning a speed bound per
 * vehicle type. The first measurement is always accepted — it initializes the filter.
 */
export function acceptMask(e: number[], n: number[], r: number[], tS: number[]): boolean[] {
  const mask = new Array<boolean>(e.length).fill(true);
  let ae: Axis | null = null, an: Axis | null = null;
  for (let i = 0; i < e.length; i++) {
    if (ae === null || an === null) { ae = init(e[i], r[i]); an = init(n[i], r[i]); continue; }
    const dt = tS[i] - tS[i - 1];
    const pe = predict(ae, dt), pn = predict(an, dt);
    if (nis(pe, e[i], r[i]) + nis(pn, n[i], r[i]) > GATE_CHI2) {
      mask[i] = false;
      ae = pe; an = pn;           // keep the prediction; do not fold the bad fix in
      continue;
    }
    ae = update(pe, e[i], r[i]); an = update(pn, n[i], r[i]);
  }
  return mask;
}

function forward(z: number[], r: number[], tS: number[], ok: boolean[]): Estimate[] {
  const out: Estimate[] = [];
  let a: Axis | null = null;
  for (let i = 0; i < z.length; i++) {
    if (a === null) a = init(z[i], r[i]);
    else {
      a = predict(a, tS[i] - tS[i - 1]);
      if (ok[i]) a = update(a, z[i], r[i]);
    }
    out.push({ x: a.x, varX: a.p00 });
  }
  return out;
}

function backwardPrior(z: number[], r: number[], tS: number[], ok: boolean[]): Estimate[] {
  const out: Estimate[] = new Array(z.length);
  let a: Axis | null = null;
  for (let i = z.length - 1; i >= 0; i--) {
    if (a === null) out[i] = { x: 0, varX: Infinity };
    else {
      a = predict(a, tS[i + 1] - tS[i]);
      out[i] = { x: a.x, varX: a.p00 };
    }
    if (a === null) a = init(z[i], r[i]);
    else if (ok[i]) a = update(a, z[i], r[i]);
  }
  return out;
}
```

Then thread the mask through `smoothKalman`, replacing its two `combine` lines:

```ts
  const ok = acceptMask(e, n, r, tS);
  const eS = combine(forward(e, r, tS, ok), backwardPrior(e, r, tS, ok));
  const nS = combine(forward(n, r, tS, ok), backwardPrior(n, r, tS, ok));
```

- [ ] **Step 4: Run the whole file**

```bash
cd web && npx vitest run src/v2/model/kalmanTrack.test.ts
```

Expected: all 6 PASS — the earlier tests must still hold (gating must not reject ordinary noisy fixes; if "recovers a constant-velocity straight line" now fails, the gate is too tight — verify `GATE_CHI2` is 13.8, not 1.38).

- [ ] **Step 5: Commit**

```bash
git add web/src/v2/model/kalmanTrack.ts web/src/v2/model/kalmanTrack.test.ts
git commit -m "feat(web): reject GPS outliers by innovation gating"
```

---

### Task 5: Break the track across long GPS holes

**Files:**
- Modify: `web/src/v2/model/kalmanTrack.ts`
- Test: `web/src/v2/model/kalmanTrack.test.ts`

**Interfaces:**
- Consumes: everything from Tasks 3-4.
- Produces: `smoothKalman` sets `inferred: true` on the point *after* any gap longer than `COAST_MAX_MS`, and restarts the filter there. Task 7 renders those segments dashed.

**Why:** coasting a 40 m/s velocity across a 120 s hole fabricates 4.8 km of confident curve. Smooth where there is data; visibly guess where there is not.

- [ ] **Step 1: Write the failing test**

Add to `kalmanTrack.test.ts` (import `COAST_MAX_MS` alongside the others):

```ts
it("breaks the track across a long GPS hole instead of inventing a curve", () => {
  const mLon = 111_320 * Math.cos((43 * Math.PI) / 180);
  // Moving east at 10 m/s, then a 120 s hole, then fixes resume 3 km further east.
  const before = [0, 1, 2].map((i) => mk(i * 15_000, 43, -87.9 + (i * 150) / mLon, 10));
  const after = [0, 1, 2].map((i) =>
    mk(150_000 + i * 15_000, 43, -87.9 + (3000 + i * 150) / mLon, 10));
  const out = smoothKalman([...before, ...after]);

  expect(out[3].inferred).toBe(true);        // first point after the hole
  expect(out[2].inferred).toBeFalsy();       // last point before it
  expect(out[0].inferred).toBeFalsy();       // never on the first point
  // The far side must sit on its own measurements, NOT be dragged toward a coasted
  // prediction from before the hole.
  expect(Math.abs((out[3].lon - -87.9) * mLon - 3000)).toBeLessThan(50);
});

it("does not break across an ordinary bucket gap", () => {
  const pts = [0, 1, 2, 3].map((i) => mk(i * 15_000, 43 + (i * 150) / 111_320, -87.9, 10));
  expect(smoothKalman(pts).some((p) => p.inferred)).toBe(false);
  expect(COAST_MAX_MS).toBe(30_000);
});
```

- [ ] **Step 2: Run and watch it fail**

```bash
cd web && npx vitest run src/v2/model/kalmanTrack.test.ts -t "hole"
```

Expected: FAIL — `out[3].inferred` is `undefined`.

- [ ] **Step 3: Implement breaks**

Add the constant:

```ts
export const COAST_MAX_MS = 30_000;
```

Add a break detector:

```ts
/** `true` at index i when the hole before it is too long to coast through — the filter
 *  restarts there and the bridging segment is drawn as inferred. Index 0 is never a break. */
export function breakMask(points: TrackPoint[]): boolean[] {
  return points.map((p, i) => i > 0 && p.t - points[i - 1].t > COAST_MAX_MS);
}
```

Every pass must honour it. In `acceptMask`, `forward` and `backwardPrior`, take `brk: boolean[]` and restart the filter at a break. The three loop bodies change like this:

```ts
// acceptMask — inside the loop, before using the previous state:
    if (ae === null || an === null || brk[i]) {
      ae = init(e[i], r[i]); an = init(n[i], r[i]); continue;
    }

// forward — inside the loop:
    if (a === null || brk[i]) a = init(z[i], r[i]);
    else {
      a = predict(a, tS[i] - tS[i - 1]);
      if (ok[i]) a = update(a, z[i], r[i]);
    }

// backwardPrior — running in reverse, the break BEFORE index i+1 ends this segment, so the
// prior at i must not come from across it:
    if (a === null || brk[i + 1]) out[i] = { x: 0, varX: Infinity };
    else {
      a = predict(a, tS[i + 1] - tS[i]);
      out[i] = { x: a.x, varX: a.p00 };
    }
    if (a === null || brk[i + 1]) a = init(z[i], r[i]);
    else if (ok[i]) a = update(a, z[i], r[i]);
```

Finally, in `smoothKalman`, compute the mask, pass it everywhere, and stamp the flag:

```ts
  const brk = breakMask(points);
  const ok = acceptMask(e, n, r, tS, brk);
  const eS = combine(forward(e, r, tS, ok, brk), backwardPrior(e, r, tS, ok, brk));
  const nS = combine(forward(n, r, tS, ok, brk), backwardPrior(n, r, tS, ok, brk));

  return points.map((p, i) => ({
    ...p, lat: toLat(proj, nS[i]), lon: toLon(proj, eS[i]),
    ...(brk[i] ? { inferred: true } : {}),
  }));
```

- [ ] **Step 4: Run the whole file**

```bash
cd web && npx vitest run src/v2/model/kalmanTrack.test.ts && npx tsc --noEmit
```

Expected: all 8 PASS.

- [ ] **Step 5: Commit**

```bash
git add web/src/v2/model/kalmanTrack.ts web/src/v2/model/kalmanTrack.test.ts
git commit -m "feat(web): break GPS tracks across holes too long to coast"
```

---

### Task 6: Wire into `cleanTrack`

**Files:**
- Modify: `web/src/v2/model/cleanTrack.ts:98-113` (delete `smoothTrack`, call `smoothKalman`)
- Test: `web/src/v2/model/cleanTrack.test.ts`

**Interfaces:**
- Consumes: `smoothKalman` (Tasks 3-5).
- Produces: `cleanTrack` = `rejectSpikes → collapseIdleExcursions → snapStays → smoothKalman`. Trip math (`cumulativeMiles`, `tripSummary`, the efficiency card) consumes this unchanged — distances follow the smoothed line automatically.

- [ ] **Step 1: Update the tests**

In `cleanTrack.test.ts`, delete any test importing or asserting on `smoothTrack` (search: `grep -n smoothTrack web/src/v2/model/cleanTrack.test.ts`) and add:

```ts
it("runs the Kalman smoother last, preserving point count and timestamps", () => {
  const pts = [0, 1, 2, 3, 4].map((i) => ({
    t: i * 15_000, lat: 43 + (i * 150) / 111_320, lon: -87.9,
    power_w: -60, current_a: -5, soc: 90, acc: 12,
  }));
  const out = cleanTrack(pts);
  expect(out).toHaveLength(pts.length);
  expect(out.map((p) => p.t)).toEqual(pts.map((p) => p.t));
});
```

- [ ] **Step 2: Run and watch it fail**

```bash
cd web && npx vitest run src/v2/model/cleanTrack.test.ts
```

Expected: FAIL — old `smoothTrack` tests removed but the export still exists, or the new test fails on the `acc` property.

- [ ] **Step 3: Rewire**

In `web/src/v2/model/cleanTrack.ts`: delete the whole `smoothTrack` function (lines 98-109), add the import

```ts
import { smoothKalman } from "./kalmanTrack";
```

and replace the pipeline:

```ts
export function cleanTrack(points: TrackPoint[]): TrackPoint[] {
  return smoothKalman(snapStays(collapseIdleExcursions(rejectSpikes(points))));
}
```

Update the file's header comment: the fourth pass is no longer "3-point smoothing" but "accuracy-weighted Kalman smoothing with outlier gating and gap breaks (see kalmanTrack.ts)".

- [ ] **Step 4: Run everything**

```bash
cd web && npx vitest run && npx tsc --noEmit
```

Expected: all PASS. `tsc` may flag other importers of `smoothTrack` — remove those imports (it has no remaining callers by design).

- [ ] **Step 5: Commit**

```bash
git add web/src/v2/model/cleanTrack.ts web/src/v2/model/cleanTrack.test.ts
git commit -m "feat(web): replace moving-average smoothing with the Kalman pass"
```

---

### Task 7: Render inferred segments as dashed and translucent

**Files:**
- Modify: `web/src/v2/components/JourneyMap.tsx:139-166` (the trail run-grouping loop)

**Interfaces:**
- Consumes: `TrackPoint.inferred` (Task 5).
- Produces: no new exports — a visual change only.

**Why the run key must include it:** the trail groups contiguous same-style segments into one polyline for SVG-node economy. An inferred segment inside an otherwise-identical run would silently inherit the run's solid style, so `inferred` has to participate in the run key exactly like `kind` and `color` do.

- [ ] **Step 1: Extend the run state**

In the trail effect, alongside `runKind` and `runColor`:

```ts
    let runInferred = false;
```

- [ ] **Step 2: Style inferred runs in `flush`**

```ts
    const flush = () => {
      if (run.length >= 2 && runKind != null) {
        // An inferred run bridges a GPS hole — dashed and faded so a guess never reads as
        // measured track, whatever its segment kind would otherwise draw.
        const opts: L.PolylineOptions = runInferred
          ? { color: runColor, weight: 3, opacity: 0.45, dashArray: "2 8", interactive: false }
          : runKind === "active"
            ? { color: runColor, weight: 4, opacity: 0.95, interactive: false }
            : { color: runColor, weight: 3, opacity: 0.8, dashArray: "4 6", interactive: false };
        L.polyline(run, opts).addTo(group);
      }
      run = []; runKind = null; runColor = ""; runInferred = false;
    };
```

- [ ] **Step 3: Break runs on the flag**

In the segment loop, replace the run-key comparison:

```ts
      const inferred = cur.inferred === true;
      if (kind !== runKind || color !== runColor || inferred !== runInferred) {
        flush();
        const prev = points[i - 1];
        run.push([prev.lat, prev.lon]);
        runKind = kind; runColor = color; runInferred = inferred;
      }
```

- [ ] **Step 4: Verify against real data in the browser**

Bring up the local stack (see CLAUDE.md "WebUI smoke test"), then:

```bash
cd server && docker compose -f docker-compose.dev.yml up -d
.venv/bin/python scripts/seed_dev.py
BMSMON_DEV_TRUST_HEADERS=1 .venv/bin/uvicorn app.main:app --port 8000 &
cd ../web && npx vite dev --port 5173 &
node scripts/smoke.mjs
```

Expected: exit 0, no console errors, and `web/smoke-shots/v2-journey.png` renders a trail. The seeded fleet has no long GPS holes, so this proves no regression; the inferred styling itself is verified against production data in Task 9.

- [ ] **Step 5: Commit**

```bash
git add web/src/v2/components/JourneyMap.tsx
git commit -m "feat(web): draw inferred track segments dashed and faded"
```

---

### Task 8: Live marker prediction

**Files:**
- Modify: `web/src/v2/model/live.ts`
- Modify: `web/src/v2/views/JourneyView.tsx:180-194` (feed the predicted position to the map)
- Test: `web/src/v2/model/live.test.ts`

**Interfaces:**
- Consumes: cleaned `TrackPoint[]` (Task 6), `LivePos` (existing).
- Produces: `predictPosition(points: TrackPoint[], nowMs: number): LivePos | null` — the chair's dead-reckoned position, or null when prediction is not warranted.

**Why no filter state is plumbed through:** the cleaned track is already smoothed, so the velocity implied by its last two points *is* the filter's velocity. Deriving it there keeps `cleanTrack`'s signature intact and keeps this function trivially testable.

- [ ] **Step 1: Write the failing tests**

Add to `web/src/v2/model/live.test.ts`:

```ts
import { predictPosition, PREDICT_MAX_MS, PREDICT_MAX_M } from "./live";

const tp = (t: number, lat: number, lon: number, inferred?: boolean) =>
  ({ t, lat, lon, power_w: null, current_a: null, soc: null, acc: 10, ...(inferred ? { inferred } : {}) });

describe("predictPosition", () => {
  const mLon = 111_320 * Math.cos((43 * Math.PI) / 180);
  // Heading east at 10 m/s, last fix at t=15000.
  const pts = [tp(0, 43, -87.9), tp(15_000, 43, -87.9 + 150 / mLon)];

  it("extrapolates along the last known velocity", () => {
    const p = predictPosition(pts, 20_000)!;           // 5 s past the last fix → ~50 m further
    expect((p.lon - -87.9) * mLon).toBeCloseTo(200, 0);
  });

  it("caps extrapolation by time", () => {
    const far = predictPosition(pts, 15_000 + PREDICT_MAX_MS + 60_000)!;
    const atCap = predictPosition(pts, 15_000 + PREDICT_MAX_MS)!;
    expect(far.lat).toBeCloseTo(atCap.lat, 10);
    expect(far.lon).toBeCloseTo(atCap.lon, 10);
  });

  it("caps extrapolation by distance", () => {
    // 40 m/s (train): the 200 m cap binds before the 10 s one.
    const fast = [tp(0, 43, -87.9), tp(15_000, 43, -87.9 + 600 / mLon)];
    const p = predictPosition(fast, 15_000 + PREDICT_MAX_MS)!;
    expect((p.lon - -87.9) * mLon - 600).toBeLessThanOrEqual(PREDICT_MAX_M + 0.5);
  });

  it("never predicts backwards in time", () => {
    const p = predictPosition(pts, 10_000)!;
    expect(p.lon).toBeCloseTo(-87.9 + 150 / mLon, 10);
  });

  it("does not extrapolate across an inferred segment", () => {
    const broken = [tp(0, 43, -87.9), tp(200_000, 43, -87.9 + 3000 / mLon, true)];
    const p = predictPosition(broken, 205_000)!;
    expect(p.lon).toBeCloseTo(-87.9 + 3000 / mLon, 10);   // holds the last fix
  });

  it("returns null without at least two points", () => {
    expect(predictPosition([], 1000)).toBeNull();
    expect(predictPosition([tp(0, 43, -87.9)], 1000)).not.toBeNull();  // single fix: hold it
  });
});
```

- [ ] **Step 2: Run and watch it fail**

```bash
cd web && npx vitest run src/v2/model/live.test.ts
```

Expected: FAIL — `predictPosition` is not exported.

- [ ] **Step 3: Implement it**

Append to `web/src/v2/model/live.ts`:

```ts
export const PREDICT_MAX_MS = 10_000;
export const PREDICT_MAX_M = 200;

const M_PER_DEG_LAT = 111_320;

/**
 * Dead-reckon the chair between fixes so the marker glides instead of teleporting every
 * ~9-15 s. Velocity comes from the last two points of the CLEANED track (already smoothed,
 * so this is the filter's velocity without plumbing filter state through cleanTrack).
 *
 * Extrapolation is capped at PREDICT_MAX_MS or PREDICT_MAX_M, whichever binds first — at
 * train speed the distance cap binds, walking the time cap does — so the marker never claims
 * a position the model cannot support. Past the cap it holds the last position, where the
 * existing LIVE_STALE_MS greying takes over. Never extrapolates across an `inferred` segment:
 * that velocity was inferred, not measured.
 */
export function predictPosition(points: TrackPoint[], nowMs: number): LivePos | null {
  if (points.length === 0) return null;
  const last = points[points.length - 1];
  const held: LivePos = { lat: last.lat, lon: last.lon, tsMs: last.t };
  if (points.length < 2 || last.inferred) return held;

  const prev = points[points.length - 2];
  const dtS = (last.t - prev.t) / 1000;
  if (dtS <= 0) return held;

  const aheadMs = Math.min(Math.max(0, nowMs - last.t), PREDICT_MAX_MS);
  if (aheadMs === 0) return held;

  const mPerDegLon = M_PER_DEG_LAT * Math.max(Math.abs(Math.cos((last.lat * Math.PI) / 180)), 1e-6);
  const vE = ((last.lon - prev.lon) * mPerDegLon) / dtS;
  const vN = ((last.lat - prev.lat) * M_PER_DEG_LAT) / dtS;
  const speed = Math.hypot(vE, vN);
  if (speed === 0) return held;

  const aheadS = Math.min(aheadMs / 1000, PREDICT_MAX_M / speed);
  return {
    lat: last.lat + (vN * aheadS) / M_PER_DEG_LAT,
    lon: last.lon + (vE * aheadS) / mPerDegLon,
    tsMs: last.t,
  };
}
```

Add `import type { TrackPoint } from "../track";` if the file does not already have it (it does — used by `lastTrackPosition`).

- [ ] **Step 4: Run the tests**

```bash
cd web && npx vitest run src/v2/model/live.test.ts
```

Expected: all 6 PASS.

- [ ] **Step 5: Wire it into the view**

In `web/src/v2/views/JourneyView.tsx`, the marker currently resolves from `lastKnownPosition` and `lastTrackPosition`. The predicted position must feed the *rendered* marker while leaving **staleness keyed on the real fix time** (`tsMs` is deliberately the last real fix's timestamp, so the existing 120 s greying is unaffected).

Replace the `marker` line:

```ts
  const marker = isLive
    ? resolveChairMarker(
        [lastKnownPosition(data.items, addresses), predictPosition(points, now)], now)
    : { pos: null as LivePos | null, stale: false };
```

Import `predictPosition` from `../model/live` and drop the now-unused `lastTrackPosition` import if nothing else uses it (`grep -n lastTrackPosition web/src/v2`). Note the marker moves on the existing `useNow(10_000)` tick — the position is recomputed each tick, so the marker steps every 10 s rather than every frame. That is the intended scope here: it removes the teleport without adding an animation loop.

- [ ] **Step 6: Verify the full suite and the app**

```bash
cd web && npx vitest run && npx tsc --noEmit && npx vite build
node scripts/smoke.mjs
```

Expected: tests PASS, build succeeds, smoke exits 0 with no console errors.

- [ ] **Step 7: Commit**

```bash
git add web/src/v2/model/live.ts web/src/v2/model/live.test.ts web/src/v2/views/JourneyView.tsx
git commit -m "feat(web): dead-reckon the live chair marker between GPS fixes"
```

---

### Task 9: Backtest against real data, document, deploy

**Files:**
- Create: `web/scripts/backtest-clean.mjs`
- Modify: `docs/range-backtest-2026-07.md` (new addendum)
- Modify: `CLAUDE.md` (the v2 Journey paragraph)

**Interfaces:**
- Consumes: the whole pipeline (Tasks 1-8).
- Produces: recorded before/after numbers; no runtime code.

**Why a script and not a test:** the numbers depend on production data, which cannot live in a unit test. The script is a measuring tool, run by hand.

- [ ] **Step 1: Write the backtest script**

Create `web/scripts/backtest-clean.mjs`:

```js
// Compare drawn path length before/after the Kalman pass on a real day.
// Usage: node scripts/backtest-clean.mjs <track.json>
// Fetch input with:
//   ssh joely@ddnas02 'bash -lc "docker exec -i bmsmon-db psql -U bmsmon -d bmsmon -qAt -c \
//     \"SELECT json_agg(row_to_json(t)) FROM (SELECT (ts_ms/15000)*15000 AS t, avg(lat) AS lat, \
//      avg(lon) AS lon, avg(power_w) AS power_w, avg(current_a) AS current_a, avg(soc) AS soc, \
//      avg(gps_accuracy_m) AS acc FROM samples WHERE address=ADDR AND lat IS NOT NULL \
//      AND (gps_accuracy_m IS NULL OR gps_accuracy_m<=250) AND ts >= DAY AND ts < DAY+1 \
//      GROUP BY 1 ORDER BY 1) t\""' > track.json
import { readFileSync } from "node:fs";
import { rejectSpikes, collapseIdleExcursions, snapStays } from "../src/v2/model/cleanTrack.ts";
import { smoothKalman } from "../src/v2/model/kalmanTrack.ts";
import { haversineMi } from "../src/v2/model/journey.ts";

const pts = JSON.parse(readFileSync(process.argv[2], "utf8")).map((p) => ({
  t: Number(p.t), lat: Number(p.lat), lon: Number(p.lon),
  power_w: p.power_w == null ? null : Number(p.power_w),
  current_a: p.current_a == null ? null : Number(p.current_a),
  soc: p.soc == null ? null : Number(p.soc),
  acc: p.acc == null ? null : Number(p.acc),
}));
const miles = (a) => a.reduce((s, p, i) => (i ? s + haversineMi(a[i - 1], p) : 0), 0);
const preKalman = snapStays(collapseIdleExcursions(rejectSpikes(pts)));
const cleaned = smoothKalman(preKalman);
console.log(JSON.stringify({
  rawPoints: pts.length,
  rawMiles: +miles(pts).toFixed(3),
  afterSpikeAndStay: +miles(preKalman).toFixed(3),
  afterKalman: +miles(cleaned).toFixed(3),
  inferredSegments: cleaned.filter((p) => p.inferred).length,
}, null, 2));
```

Run it with vite-node so the TypeScript imports resolve: `npx vite-node scripts/backtest-clean.mjs track.json`. If `vite-node` is not installed, run `npx vitest run` style instead by temporarily converting the script into a `*.test.ts` that `console.log`s — do **not** add a dependency for this.

- [ ] **Step 2: Pull two real days and run it**

Pull the 2026-07-29 train ride (pack `C8:47:80:15:67:44`) and the 2026-07-12 outing referenced in the existing backtest doc. Record for each: raw miles, miles after spike/stay passes, miles after Kalman, inferred segment count.

- [ ] **Step 3: Sanity-check the numbers before believing them**

Expected direction, from the 2026-07-29 measurements in the design doc: miles should *drop* slightly (jitter inflation disappears) and the train day should show a handful of inferred segments (its largest hole was 120 s). If miles *rise*, or the outing day shows inferred segments where it had continuous GPS, stop and investigate — that means the filter is fabricating movement or `COAST_MAX_MS` is being tripped by ordinary buckets.

- [ ] **Step 4: Record the results**

Append an addendum to `docs/range-backtest-2026-07.md` with the table of before/after numbers for both days, the date, and one sentence on what changed and why. Follow the existing addendum format in that file.

- [ ] **Step 5: Update CLAUDE.md**

In the v2 Journey paragraph, replace the description of track cleaning to state: four passes ending in accuracy-weighted Kalman smoothing (`model/kalmanTrack.ts`) with innovation gating and `COAST_MAX_MS` breaks; `/web/track` now returns `acc`; inferred segments draw dashed/faded; the live marker dead-reckons within 10 s / 200 m. Keep the existing warnings (windowed chair miles, the discharge gate, the viewport-meta lesson) intact.

- [ ] **Step 6: Commit**

```bash
git add web/scripts/backtest-clean.mjs docs/range-backtest-2026-07.md CLAUDE.md
git commit -m "docs: backtest and document Kalman track smoothing"
```

- [ ] **Step 7: Deploy**

```bash
git push origin main
gh run list --workflow=build-server.yml --limit 1     # note the run id, then:
gh run watch <run-id> --exit-status
ssh joely@ddnas02 'bash -lc "cd /share/bsv/docker-compose && \
  docker compose --env-file .env -f bmsmon/docker-compose.yml pull bmsmon-api && \
  docker compose --env-file .env -f bmsmon/docker-compose.yml up -d bmsmon-api"'
curl -fsS https://bmsmon.covert.life/api/v1/health   # expect {"status":"ok"}
```

The API container re-runs `schema.sql` on start; this change adds no columns, so nothing to migrate.

---

## Self-Review

**Spec coverage:**

| Spec section | Task |
|---|---|
| 1. Server passes accuracy radius | Task 1 (query + route + test), Task 2 (`TrackPoint.acc`, decode, merge) |
| 2. Filter: projection, CV model, R from accuracy, `ACC_FLOOR_M`/`ACC_DEFAULT_M` | Task 3 |
| 2. Innovation gating (`GATE_CHI2`) | Task 4 |
| 2. Gap policy (`COAST_MAX_MS`, break, `inferred`) | Task 5 |
| 2. Output invariant (length/order/timestamps) | Task 3 test 1, Task 6 test |
| 2. `inferred` flag semantics (on the point after the gap) | Task 5 tests |
| 2. Live head uses the filtered state | Falls out of the two-filter smoother (backward prior is infinite at the head) — noted in Task 3 |
| 3. Pipeline rewiring, `smoothTrack` deleted, `snapStays` kept | Task 6 |
| 4. Live marker interpolation + caps | Task 8 |
| 5. Inferred segments dashed/translucent | Task 7 |
| Testing: 7 unit tests + backtest + screenshot | Tasks 3-5, 8 (unit), 7 (smoke), 9 (backtest) |
| Out of scope: no Kotlin, no share page, no map-matching, no gate change | Global Constraints |

**Deviation from the spec, deliberate:** the spec named an RTS backward smoother; the plan implements the mathematically equivalent **two-filter (Fraser–Potter)** form, which needs no matrix inversion given independent per-axis filters and makes the "smoothed head == filtered head" property automatic rather than a special case. The spec should be amended to match before implementation starts.

**Placeholder scan:** no TBD/TODO; every code step carries real code; no "similar to Task N" references.

**Type consistency:** `TrackPoint.acc` / `TrackPoint.inferred` declared once (Task 2) and used identically in Tasks 3-8. `smoothKalman`, `acceptMask`, `breakMask`, `varianceFor`, `predictPosition`, `PREDICT_MAX_MS`, `PREDICT_MAX_M`, `COAST_MAX_MS`, `GATE_CHI2` keep the same names and signatures throughout. The pass helpers (`forward`, `backwardPrior`, `acceptMask`) gain parameters in Tasks 4 and 5 — each task shows the full updated call sites.
