# GPS Track Cleaning (capture + map + learner) — Design

**Date:** 2026-07-13
**Status:** Approved

## Goal

Stop the WebUI v2 Journey map from drawing places the user never went, make its trip miles
honest, and harden the range learner's distance input — using physical plausibility (the
chair's ~8.5 mph maximum) as the primary outlier knife, plus a capture-quality upgrade.

## Problem (measured, 2026-07-12 track, pack 2012-A)

The v2 track pipeline (`track_series` 15-s buckets → `mergeBaseTracks` → raw haversine
polyline) has no accuracy filter and no outlier rejection. Yesterday's track contained:

- **~3 phantom miles from 51 impossible jumps** (bucket-to-bucket speeds over 4.5 m/s while
  the chair was discharging; one >15 m/s teleport). Reported GPS accuracy does NOT
  discriminate these (spikes averaged 59 m accuracy vs 88 m for good fixes) — accuracy
  gating alone cannot fix this; speed plausibility can.
- **Stationary-jitter "caterpillar"**: thousands of ~2 m wiggles while parked that fuzz the
  map and inflate cumulative miles (6.8 mi "plausible" movement on a ~3–4 mi day).
- Root cause of the coarse data: `LocationSource` requests balanced-power location
  (WiFi/cell, ~10 s) → ~90 m typical fixes.

## Design

### 1. Capture: always-on high-accuracy GNSS (Android `LocationSource`)

`Priority.PRIORITY_HIGH_ACCURACY`, 5 000 ms interval, 2 000 ms min-update — **unconditional**
(no discharge-linked switching; the phone rides the chair on constant USB power, so battery
cost is irrelevant). Outdoor fixes go from ~90 m to ~3–10 m; most spikes never get born.

### 2. Track cleaning — pure function, TS (`web/src/v2/model/cleanTrack.ts`)

Input: time-ordered track points `(t, lat, lon, current_a, …)` (the merged base track).
Three passes:

1. **Spike rejection (context-aware speed plausibility).** For candidate B between
   last-accepted A and next point C:
   - speed bound at B: **4.5 m/s when the chair is discharging** near B
     (`current_a < -0.1` on B or A, matching `DISCHARGE_EPS`), else **45 m/s**
     (vehicle/train journeys are real and must keep drawing);
   - if speed(A→B) > bound AND speed(B→C) > bound AND speed(A→C) ≤ bound → drop B
     (the out-and-back spike test);
   - speed(A→B) > 60 m/s → drop B unconditionally (absurd even for a vehicle).
2. **Stay-point clamping.** Consecutive points that never leave a 30 m radius around their
   running centroid collapse to a single centroid point (keeping first/last timestamps) —
   parked drift becomes one dot and contributes zero distance.
3. **Smoothing.** 3-point moving average over surviving points (endpoints untouched).

### 3. v2 Journey wiring

`JourneyView` applies `cleanTrack()` to the output of `mergeBaseTracks()`; the map polyline,
`cumulativeMiles`, `energySeries`, `detectHotspots`, and `tripSummary` all consume the
cleaned track. Displayed miles become honest along with the picture.

### 4. Range learner (Kotlin `RangeLearn`)

The same impossible-speed spike rejection inserted after `bucketedFixes()` and before
`windowedSegments()`: drop bucketed fix B when speed(A→B) and speed(B→C) both exceed
4.5 m/s while speed(A→C) does not (A = previous kept fix, C = next fix). Stay-point
clamping is NOT added (the 0.4 m/s window floor already ignores parked jitter).

Known limitation (accepted): an out-and-back spike at *plausible* speed over a 30-s window
is mathematically indistinguishable from genuinely rolling out and back; the GNSS capture
upgrade — not inference — is what eliminates those.

### 5. Unchanged

Raw samples stay raw in Postgres; `/web/track` stays raw; historical days are cleaned at
read/compute time. No schema, server, or v1 WebUI changes.

## Testing

- TS: `cleanTrack.test.ts` synthetic vectors — discharging spike dropped; idle high-speed
  (vehicle) leg kept; >60 m/s dropped even while idle; stay cluster collapses to centroid
  with zero distance; genuine chair drive preserved; smoothing preserves endpoints.
- Kotlin: `RangeLearnTest` — spike between drive fixes dropped (distance excludes it);
  genuine drive unaffected; existing suite stays green.
- Backtest: re-run the Jul 12 track through the cleaner (SQL or node) — expect roughly the
  ~3 phantom miles gone and cumulative miles near the user's real 3–4 mi.
- Capture change is verified on-device (fix accuracy visibly ≤10 m outdoors in fresh samples).

## Out of scope

- Kalman filtering / map matching (revisit only if GNSS + rejection still disappoints).
- Rewriting historical rows.
- v1 WebUI map (has none).
