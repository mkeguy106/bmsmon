# Predictive GPS Track Smoothing (Kalman) — Design

**Date:** 2026-07-29
**Status:** Approved

## Goal

Make the v2 Journey trail read as a continuous path instead of a series of lurches, especially
during vehicle/train transit, without inventing travel the data does not support. One filter
serves both the drawn trail and the live chair marker, and (by the user's explicit choice) the
smoothed geometry is also what trip distances are counted from.

## Problem (measured 2026-07-29, live train ride, prod DB)

The chair rode a train at 70–145 km/h while plugged in. Three distinct effects stack up, and
only one of them is the "bad GPS" the symptom suggests.

**Raw fixes, all packs, 3 h window (3529 consecutive-coordinate moves):**

| metric | value |
|---|---|
| implied speed p50 / p90 / p99 | 0.35 / 3.47 / **64.6** m/s |
| implied speed max | 285.7 m/s (1029 km/h) |
| largest single jump | **1988 m in 7.1 s** |
| moves > 45 m/s (`VEHICLE_MAX_MPS`) | 94 |
| moves in 4.5–45 m/s (plausible transit) | 185 |
| claimed accuracy p50 / p95 / max | 20.8 / 104.1 / 700 m |
| new coordinate arrives | p50 9 s, p90 11 s, max gap 77 s |
| chair discharge rows | **0** (avg +3 W — on external power) |

**Accuracy IS a usable discriminator here** (unlike the 2026-07-15 elevator-multipath case,
where fixes claimed 2–32 m while being 100+ m wrong):

| band | count | accuracy p50 | ≤100 m | passes 250 m server gate |
|---|---|---|---|---|
| impossible (>45 m/s) | 94 | **200 m** | 23 | 50 |
| transit (4.5–45 m/s) | 189 | 77.6 m | 121 | 173 |
| slow (<4.5 m/s) | 3239 | **20.5 m** | 3194 | 3239 |

Inside a metal train the phone falls back to cell-tower positioning, and it is honest about it.

**What the map actually draws** (pack 2012-A, 90 min, after `track_series`' 15 s bucketing and
its existing `GPS_ACCURACY_MAX_M = 250` gate — i.e. the real render input):

| metric | value |
|---|---|
| buckets drawn | 322 |
| bucket gap p50 / max | 15 s / **120 s** |
| step p50 / max | 2 m / **3915 m** |
| accuracy p50 / max | 18 m / 223 m |
| segments >60 m/s (`ABSURD_MPS`) | **0** |
| segments 45–60 m/s | 3 |
| drawn path | 10.51 mi |

### Conclusions that drive the design

1. **The server's 250 m gate already removes the true garbage.** Zero drawn segments exceed
   `ABSURD_MPS`. The remaining problem is not mostly phantom fixes.
2. **The visible "jumpiness" is confident straight lines across holes.** A 120 s gap at 33 m/s
   is a 3.9 km chord drawn as solid trail. The map presents a guess in the same visual language
   as measured data.
3. **A pure accuracy gate cannot be tightened further.** During the ride the *legitimate* fixes
   are themselves coarse (per-minute average accuracy 89–511 m); dropping coarse fixes would
   erase the trail entirely. The answer is to **weight** fixes by accuracy, not gate them.
4. **`VEHICLE_MAX_MPS = 45` sits inside real train speed.** Genuine 40 m/s motion nearly trips
   the reject bound while phantom 50 m/s jumps read as plausible. Hand-tuning this constant per
   vehicle type does not generalize; a motion model does.
5. **Nothing here poisons the learners.** The chair never discharges in transit, so the Wh/mile
   learner and `activeMiles` are untouched by this data. This is a rendering + transit-distance
   problem only.

## Design

### 1. Server: pass the accuracy radius through (`server/app/db/queries.py`)

`track_series` gates on `gps_accuracy_m` but discards it. Add `avg(gps_accuracy_m)` per 15 s
bucket to the SELECT and to the `/web/track` response as `acc`.

- No schema change — the column exists.
- The 250 m gate is **unchanged**; it remains the coarse pre-filter.
- Raw `samples` keep every fix, as today.
- `gps_track_all` (the public share feed) is **not** changed — the guest page keeps its current
  neutral trail.

`web/src/v2/track.ts`: `TrackPoint` gains `acc: number | null`.

Without this field the filter degrades to a uniform blur; with it, an 18 m fix outweighs a
223 m fix by ~150×, which is the whole point.

### 2. The filter — `web/src/v2/model/kalmanTrack.ts` (new, pure)

A constant-velocity (CV) Kalman filter with a two-filter (Fraser–Potter) backward smoother.

**Per-axis, not 4-state.** With diagonal measurement and process noise, east and north do not
interact, so the implementation runs two independent 2-state (position, velocity) filters
rather than one 4-state filter — mathematically identical, far easier to verify. The outlier
gate below is still applied *jointly* across both axes, because a fix is one 2-D event.

**Two-filter rather than RTS.** The smoother combines the forward *posterior* at each index
(which includes measurement `i`) with the backward *prior* (which excludes it). The two are
therefore independent, so inverse-variance weighting is valid, and no matrix inversion is
needed. It also makes the live-head property below automatic instead of a special case.

**Units.** Points are projected to a local ENU tangent plane in metres about the track's first
fix, filtered there, and converted back to lat/lon on output. Filtering in degrees would make
the variances meaningless (and anisotropic with latitude).

**State / model.** `x = [e, n, ve, vn]`, standard discrete CV transition over the actual `dt`
between buckets, with discrete white-noise acceleration process noise from a single tunable
`SIGMA_ACCEL_MPS2 = 0.5`.

**Corrected during implementation (2026-07-29).** This constant was first specified as `2`,
which is wrong for the track's 15 s bucket spacing: σ_a is the RMS acceleration sustained
across a *whole sampling interval*, so 2 m/s² implies a 30 m/s velocity change and ~225 m of
legitimate position slack per step — larger than the outliers the gate exists to catch. Measured
on the plan's own test scenario, the gate at σ_a = 2 was **backwards**: it accepted a 500 m
teleport (NIS 3.7) and rejected the good fix after it (NIS 29.5). At 0.5 the separation is
clean — teleport NIS 44.2, real 1 m/s² train acceleration NIS 2.3, against a 13.8 gate. Do not
go below 0.5: by σ_a = 0.3 genuine acceleration reaches NIS 4.7 and the margin erodes.

**Measurement noise.** `R = diag(acc², acc²)` with `ACC_FLOOR_M = 5` (no fix is better than
this in practice, and a zero would make the filter blindly trust it) and `ACC_DEFAULT_M = 30`
when `acc` is null (older rows, pre-deploy history).

**Outlier rejection by innovation gating.** A measurement whose normalized innovation squared
exceeds `GATE_CHI2 = 13.8` (2 dof, p = 0.999) is rejected — the prediction is kept and no
update is applied. This is what resolves the ambiguous 45–60 m/s segments: a fast fix that
agrees with recent velocity is accepted as real train motion; a fast fix inconsistent with it
is rejected. It replaces per-vehicle tuning of `VEHICLE_MAX_MPS`.

`rejectSpikes` remains as a cheap robust pre-pass, because a Kalman filter is not itself robust
to a wild measurement during initialization.

**Gap policy (judgment call, user-approved).** Coasting velocity across a long hole fabricates
path — at 40 m/s a 120 s gap invents 4.8 km of confident curve. So:

- `dt ≤ COAST_MAX_MS` (30 s): normal predict/update; the filter coasts and smooths through.
- `dt > COAST_MAX_MS`: **break the track.** The filter restarts on the far side (fresh state,
  large initial covariance), and the bridging segment is flagged `inferred: true`.

The smoother runs per unbroken segment, never across a break.

**Output.** The pass preserves the length, order and timestamps of *its own* input — like
`collapseIdleExcursions` and `snapStays`, and unlike `rejectSpikes`, which is the one pass
allowed to drop points. This matters because the energy chart, hover inspection and SOC series
index into the cleaned array. Only `lat`/`lon` change, plus the added flag below.

**`inferred` flag semantics.** The flag sits on the point *after* the gap and means "the
segment from the previous point to this one is inferred, not measured." A renderer draws
segment `i-1 → i` dashed when `points[i].inferred` is true. The first point of a track is
never flagged.

**Live head.** The smoother has no future data at the head — the backward prior there has
infinite variance — so the final point's smoothed state equals the forward-filtered state. The live marker therefore consumes the *filtered*
state, and the trail behind it consumes the smoothed one — the marker must never lag the head
just because smoothing is symmetric elsewhere.

### 3. Pipeline wiring (`web/src/v2/model/cleanTrack.ts`)

`cleanTrack` becomes:

```
rejectSpikes → collapseIdleExcursions → snapStays → kalmanSmooth
```

- The existing 3-point moving average (`smoothTrack`) is **deleted** — superseded.
- `snapStays` is **kept**: it yields exactly zero movement while parked, which a filter alone
  would not, and it protects the distance figures from jitter inflation.
- Because trip math consumes `cleanTrack` output, distances follow the smoothed line
  automatically, per the user's "drawn and counted" choice.

### 4. Live marker prediction (`web/src/v2/model/live.ts`, `components/JourneyMap.tsx`)

The filter's final state carries heading and speed, so the marker can move continuously:

- when a newer fix arrives, the marker **interpolates** toward it (catch-up animation);
- with no new fix, it **extrapolates** along the last state, capped at
  `PREDICT_MAX_MS = 10_000` **or** `PREDICT_MAX_M = 200`, whichever binds first (at train
  speed the distance cap binds; while walking, the time cap does);
- past the cap it holds position, and the existing `LIVE_STALE_MS = 120_000` greying/un-pulsing
  takes over unchanged.

A pure `predictPosition(state, nowMs)` does the math; the component ticks it. The marker never
claims a position the model cannot support.

### 5. Rendering inferred segments (`components/JourneyMap.tsx`)

Segments flagged `inferred` render **dashed and slightly translucent**, reusing the dash
vocabulary already used for transit legs. Smooth where there is data; visibly a guess where
there is not. No change to hotspots, colors, or the metric chip.

Inferred segments still count toward transit distance (you did travel), and still contribute
nothing to active/driven miles — that separation is the existing discharge gate and needs no
special-casing.

## Testing

**Pure unit tests** (`kalmanTrack.test.ts`):

1. constant-velocity straight line is recovered (smoothed ≈ truth within tolerance);
2. a single wild outlier is rejected by the innovation gate and does not bend the path;
3. a coarse fix (`acc = 200`) moves the estimate materially less than a fine one (`acc = 10`);
4. stationary jitter collapses toward a point rather than a caterpillar;
5. a 120 s gap produces a break: `inferred` set, straight bridge, no fabricated curve;
6. output preserves input length, order and timestamps;
7. null `acc` falls back to `ACC_DEFAULT_M` rather than trusting the fix absolutely.

**Backtest on real data** (recorded in `docs/range-backtest-2026-07.md`): today's train ride and
the 2026-07-12 outing through old vs new pipeline — drawn miles, spike counts, gap handling —
plus a Playwright screenshot of the Journey view from the smoke harness.

## Out of scope (deliberate)

- **No Kotlin change.** The phone's chair-miles learner is discharge-gated; transit never
  reaches it. Documented divergence, like the existing web/Android tilt divergence.
- **No change to the public share page** or its feed.
- **No map-matching** to OSM rail/road geometry. It would look best for trains but needs
  bundled geometry or an external service, which is a large scope jump and hostile to the
  offline-durable design everything else follows.
- **No change to `GPS_ACCURACY_MAX_M`** or to what is stored in `samples`.

## Risks and mitigations

| risk | mitigation |
|---|---|
| Smoothing changes historical trip distances | Backtest old vs new on two real days and record the deltas before merging; expect a small drop as jitter inflation disappears |
| Filter over-smooths genuine sharp turns | `SIGMA_ACCEL_MPS2` is the single knob; validated against the Jul-12 outing which contains real corners |
| Innovation gate rejects real motion after a break | Restart uses a large initial covariance, so the first fixes after a gap are trusted |
| Re-smoothing on every 15 s live poll costs CPU | O(n) over ≤ a few thousand points, already memoized per track in `JourneyView` |
| Old history has no `acc` | `ACC_DEFAULT_M = 30` fallback, covered by test 7 |
