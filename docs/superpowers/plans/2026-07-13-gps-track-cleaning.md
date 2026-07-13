# GPS Track Cleaning Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Kill phantom GPS lines on the v2 Journey map and phantom miles in trip/learner distance, via always-on GNSS capture plus a plausibility-based track cleaner.

**Architecture:** A pure TS cleaning pipeline (`cleanTrack` = spike rejection → stay-point snapping → 3-point smoothing) applied in `JourneyView` between `useTrack` and everything downstream; the same spike rejection in Kotlin between `bucketedFixes` and `windowedSegments`; `LocationSource` switched to unconditional `PRIORITY_HIGH_ACCURACY`. Raw DB data and the `/web/track` endpoint stay untouched. Spec: `docs/superpowers/specs/2026-07-13-gps-track-cleaning-design.md`.

**Tech Stack:** TypeScript + vitest (web/src/v2), Kotlin + JUnit4 (android), Leaflet map unchanged.

## Global Constraints

- Commit messages: NEVER reference AI/Claude/automated generation (CLAUDE.md rule).
- Speed bounds, exact: chair **4.5 m/s** (discharging context: `current_a < -0.1` on either endpoint in TS; `discharging` flag on either endpoint in Kotlin), vehicle **45 m/s**, absurd **60 m/s** (dropped unconditionally). Stay radius **30 m** from the cluster's FIRST point; snapped to cluster centroid; points are never removed by stay-snapping.
- The cleaner must preserve array timeline semantics: same point count out of stay-snap + smoothing; only spike rejection may drop points.
- Capture: `Priority.PRIORITY_HIGH_ACCURACY`, interval 5 000 ms, min-update 2 000 ms — UNCONDITIONAL (no discharge-linked switching; phone is on chair USB power).
- Raw samples/DB/server endpoints unchanged.
- Verification: web `cd /home/joely/bmsmon/web && npx vitest run && npx tsc --noEmit && npm run build`; android `cd /home/joely/bmsmon/android && ./gradlew :app:testDebugUnitTest`.

---

### Task 1: `cleanTrack` — pure TS cleaning pipeline

**Files:**
- Create: `web/src/v2/model/cleanTrack.ts`
- Test: `web/src/v2/model/cleanTrack.test.ts`

**Interfaces:**
- Consumes: `TrackPoint` (`web/src/v2/track.ts`: `{ t, lat, lon, power_w, current_a, soc }`), `haversineMi`, `DISCHARGE_EPS` from `./journey`.
- Produces (Task 2 relies on): `export function cleanTrack(points: TrackPoint[]): TrackPoint[]`; also exported for tests: `rejectSpikes`, `snapStays`, `smoothTrack`, constants `CHAIR_MAX_MPS = 4.5`, `VEHICLE_MAX_MPS = 45`, `ABSURD_MPS = 60`, `STAY_RADIUS_MI`.

- [ ] **Step 1: Write the failing test**

```ts
import { describe, expect, it } from "vitest";
import type { TrackPoint } from "../track";
import { haversineMi } from "./journey";
import { cleanTrack, rejectSpikes, smoothTrack, snapStays } from "./cleanTrack";

// ~degrees of latitude per meter (1 deg lat = 111 320 m).
const M = 1 / 111_320;

/** Point [m] meters north of origin at time [tS] seconds, drawing [amps] (negative = discharge). */
const pt = (tS: number, m: number, amps = -2): TrackPoint => ({
  t: tS * 1000, lat: 40 + m * M, lon: -75, power_w: Math.abs(amps) * 25, current_a: amps, soc: 80,
});

const totalMi = (ps: TrackPoint[]) =>
  ps.reduce((acc, p, i) => (i ? acc + haversineMi(ps[i - 1], p) : 0), 0);

describe("rejectSpikes", () => {
  it("drops a discharging out-and-back spike (impossible at chair speed)", () => {
    // A at 0 m, B jumps 200 m in 15 s (13.3 m/s in AND out), C back at 10 m.
    const track = [pt(0, 0), pt(15, 200), pt(30, 10)];
    const out = rejectSpikes(track);
    expect(out).toHaveLength(2);
    expect(out[1].t).toBe(30_000);
  });

  it("keeps a sustained fast leg while NOT discharging (vehicle ride)", () => {
    // 300 m per 15 s = 20 m/s, current 0 — a real van; every point kept.
    const track = [pt(0, 0, 0), pt(15, 300, 0), pt(30, 600, 0), pt(45, 900, 0)];
    expect(rejectSpikes(track)).toHaveLength(4);
  });

  it("keeps a sustained fast leg even while discharging (GPS reacquire, not a spike)", () => {
    // B is fast IN but the track CONTINUES from B (B→C plausible) — not out-and-back; kept.
    const track = [pt(0, 0), pt(15, 200), pt(30, 210), pt(45, 220)];
    expect(rejectSpikes(track)).toHaveLength(4);
  });

  it("drops absurd jumps regardless of discharge state", () => {
    // 1200 m in 15 s = 80 m/s — impossible even for a vehicle.
    const track = [pt(0, 0, 0), pt(15, 1200, 0), pt(30, 10, 0)];
    expect(rejectSpikes(track)).toHaveLength(2);
  });
});

describe("snapStays", () => {
  it("snaps parked jitter to one spot without removing points", () => {
    // 6 points wobbling within ±10 m, then a real move to 200 m.
    const track = [pt(0, 0), pt(15, 8), pt(30, -6), pt(45, 10), pt(60, 2), pt(75, -9), pt(90, 200)];
    const out = snapStays(track);
    expect(out).toHaveLength(7);                      // timeline preserved
    const cluster = out.slice(0, 6);
    expect(new Set(cluster.map((p) => p.lat)).size).toBe(1);  // all snapped to one coord
    expect(totalMi(cluster)).toBe(0);                 // parked distance is zero
    expect(out[6].lat).toBe(track[6].lat);            // the move survives
  });
});

describe("smoothTrack", () => {
  it("preserves endpoints and point count", () => {
    const track = [pt(0, 0), pt(15, 30), pt(30, 55), pt(45, 90)];
    const out = smoothTrack(track);
    expect(out).toHaveLength(4);
    expect(out[0].lat).toBe(track[0].lat);
    expect(out[3].lat).toBe(track[3].lat);
  });
});

describe("cleanTrack", () => {
  it("preserves a genuine chair drive's distance within a few percent", () => {
    // Steady 2 m/s: 30 m per 15 s, 40 points → 39 segments × 30 m = 1170 m ≈ 0.727 mi.
    const track = Array.from({ length: 40 }, (_, i) => pt(i * 15, i * 30));
    const out = cleanTrack(track);
    expect(totalMi(out)).toBeGreaterThan(0.727 * 0.97);
    expect(totalMi(out)).toBeLessThan(0.727 * 1.01);
  });

  it("removes the spike's phantom distance from the total", () => {
    // Same drive with one 400 m out-and-back spike injected mid-way (~0.5 phantom mi raw).
    const drive = Array.from({ length: 40 }, (_, i) => pt(i * 15, i * 30));
    const spiked = [...drive.slice(0, 20), pt(20 * 15 - 7, 400 + 20 * 30), ...drive.slice(20)];
    const out = cleanTrack(spiked);
    expect(totalMi(out)).toBeLessThan(0.727 * 1.05);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/joely/bmsmon/web && npx vitest run src/v2/model/cleanTrack.test.ts`
Expected: FAIL — cannot resolve `./cleanTrack`.

- [ ] **Step 3: Write the implementation**

```ts
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /home/joely/bmsmon/web && npx vitest run src/v2/model/cleanTrack.test.ts`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
cd /home/joely/bmsmon
git add web/src/v2/model/cleanTrack.ts web/src/v2/model/cleanTrack.test.ts
git commit -m "feat(webui-v2): GPS track cleaner — spike rejection, stay snapping, smoothing"
```

---

### Task 2: Wire the cleaner into JourneyView

**Files:**
- Modify: `web/src/v2/views/JourneyView.tsx` (the `useTrack` line, ~line 122)

**Interfaces:**
- Consumes: `cleanTrack` (Task 1).
- Produces: nothing new — every downstream consumer (`JourneyMap`, `cumulativeMiles`, `energySeries`, `detectHotspots`, `tripSummary`, playback) already reads the local `points` variable.

- [ ] **Step 1: Apply the wiring change**

In `JourneyView.tsx`, add to the model imports (the `../model/journey` import block is at lines 8-11; add a separate import after it):

```ts
import { cleanTrack } from "../model/cleanTrack";
```

Replace:

```ts
  const points = useTrack(addresses, fromMs, toMs);
```

with:

```ts
  const rawPoints = useTrack(addresses, fromMs, toMs);
  // Cleaned at render time (spike rejection / stay snapping / smoothing) — raw data stays
  // raw in the DB; the map, miles, energy chart, and playback all consume the cleaned track.
  const points = useMemo(() => cleanTrack(rawPoints), [rawPoints]);
```

- [ ] **Step 2: Full web gate**

Run: `cd /home/joely/bmsmon/web && npx vitest run && npx tsc --noEmit && npm run build`
Expected: all green.

- [ ] **Step 3: Commit**

```bash
cd /home/joely/bmsmon
git add web/src/v2/views/JourneyView.tsx
git commit -m "feat(webui-v2): Journey consumes the cleaned track (map, miles, energy, playback)"
```

---

### Task 3: Spike rejection in the Kotlin range learner

**Files:**
- Modify: `android/app/src/main/java/dev/joely/bmsmon/model/RangeLearn.kt`
- Test: `android/app/src/test/java/dev/joely/bmsmon/RangeLearnTest.kt`

**Interfaces:**
- Consumes: existing private `Fix` (`tsMs, lat, lon, discharging`), `distanceM`, `bucketedFixes`, `windowedSegments`, `CHAIR_MAX_SPEED_MPS = 4.5f` — all already in RangeLearn.kt.
- Produces: private `rejectSpikes(fixes: List<Fix>): List<Fix>` inserted into the drive pass: `windowedSegments(rejectSpikes(bucketedFixes(rows)))`.

- [ ] **Step 1: Write the failing test**

Add to `RangeLearnTest.kt` (uses the existing `outingDrive`/`idleFiller`/`ts` helpers; `outingDrive` emits rows every 10 s at 30 m steps, bucketed to 30 s fixes):

```kotlin
    @Test fun teleportSpikeIsBridgedNotCounted() {
        // 90 m / 30 s cruise (10 fixes, 810 m total) with the 9:02:30 fix teleported 500 m
        // off-path. In/out speeds ~20/14 m/s are impossible for a discharging chair while
        // the neighbors agree with each other (180 m / 60 s), so the spike fix is DROPPED
        // and the bridged A→C window keeps the real distance — the day still qualifies and
        // learns the clean 10.73 Wh/mi. Without rejection, the two spike-adjacent windows
        // fall outside the chair speed band and their real distance is lost: the day drops
        // under the 0.5 mi outing bar and stays seeded. This pins the recovery behavior.
        val rows = (1..3).flatMap { d ->
            (0..9).map { k ->
                val onPathM = k * 90
                val m = if (k == 5) onPathM + 500 else onPathM
                RangeRow(ts(d, 9) + k * 30_000L, "Discharging", 72f,
                    lat = 40.0 + m / 111_320.0, lon = -75.0, gpsAccuracyM = 10f, regen = false)
            } + idleFiller(d, 10, 13)
        }
        val p = learnRangeParams(rows, zone, nowMs = ts(4, 0))
        // Burn: 9 × 30 s × 72 W = 5.4 Wh; distance after bridging: 810 m = 0.50331 mi → 10.73.
        assertEquals(10.73f, (p.whPerMile.lo + p.whPerMile.hi) / 2f, 0.3f)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/joely/bmsmon/android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.RangeLearnTest"`
Expected: FAIL — without rejection the spike-adjacent windows are discarded (630 m < the 0.5 mi outing bar), whPerMile stays seeded (mid 68), assertion expects 10.73.

- [ ] **Step 3: Write the implementation**

In `RangeLearn.kt`, add constants next to `CHAIR_MAX_SPEED_MPS`:

```kotlin
// Spike rejection bounds (TS sibling: web/src/v2/model/cleanTrack.ts — keep in sync).
private const val VEHICLE_MAX_MPS = 45f
private const val ABSURD_MPS = 60f
```

Add after `windowedSegments`:

```kotlin
private fun speedMps(a: Fix, b: Fix): Float {
    val dtS = (b.tsMs - a.tsMs) / 1000f
    if (dtS <= 0f) return Float.POSITIVE_INFINITY
    return distanceM(a.lat, a.lon, b.lat, b.lon) / dtS
}

/** Drop out-and-back spike fixes: a fix demanding impossible speed both to reach AND to
 *  leave — at chair speed while discharging, vehicle speed otherwise — while its neighbors
 *  agree with each other, is a GPS lie. Sustained movement (vehicle legs, reacquires) keeps. */
private fun rejectSpikes(fixes: List<Fix>): List<Fix> {
    val out = ArrayList<Fix>(fixes.size)
    for (i in fixes.indices) {
        val b = fixes[i]
        if (out.isEmpty()) { out.add(b); continue }
        val a = out.last()
        val vIn = speedMps(a, b)
        if (vIn > ABSURD_MPS) continue
        val bound = if (a.discharging || b.discharging) CHAIR_MAX_SPEED_MPS else VEHICLE_MAX_MPS
        if (vIn > bound) {
            val c = fixes.getOrNull(i + 1)
            if (c != null && speedMps(b, c) > bound && speedMps(a, c) <= bound) continue
        }
        out.add(b)
    }
    return out
}
```

And change the drive pass in `accumulate` from:

```kotlin
    for (seg in windowedSegments(bucketedFixes(rows))) {
```

to:

```kotlin
    for (seg in windowedSegments(rejectSpikes(bucketedFixes(rows)))) {
```

- [ ] **Step 4: Run the full Android suite**

Run: `cd /home/joely/bmsmon/android && ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL — new test passes, all existing learner tests (incl. vehicleRideDoesNotCount, frozenFixCruiseStillMeasured) unaffected.

- [ ] **Step 5: Commit**

```bash
cd /home/joely/bmsmon
git add android/app/src/main/java/dev/joely/bmsmon/model/RangeLearn.kt \
        android/app/src/test/java/dev/joely/bmsmon/RangeLearnTest.kt
git commit -m "fix(android): reject out-and-back GPS spikes before windowed drive measurement"
```

---

### Task 4: Always-on high-accuracy GNSS capture

**Files:**
- Modify: `android/app/src/main/java/dev/joely/bmsmon/location/LocationSource.kt:43-45`

**Interfaces:**
- Consumes/produces: nothing new — `GpsFix`/`start`/`stop`/`current` signatures unchanged.

- [ ] **Step 1: Change the location request**

Replace:

```kotlin
        val req = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 10_000L)
            .setMinUpdateIntervalMillis(5_000L)
            .build()
```

with:

```kotlin
        // Always-on GNSS (2026-07-13): balanced-power WiFi/cell fixes averaged ~90 m and
        // spawned the phantom map spikes; the phone rides the chair on constant USB power,
        // so there is no battery reason to accept coarse fixes — high accuracy, always.
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000L)
            .setMinUpdateIntervalMillis(2_000L)
            .build()
```

- [ ] **Step 2: Build + suite**

Run: `cd /home/joely/bmsmon/android && ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
cd /home/joely/bmsmon
git add android/app/src/main/java/dev/joely/bmsmon/location/LocationSource.kt
git commit -m "feat(android): always-on high-accuracy GNSS location (5s interval)"
```

---

### Task 5: Backtest, docs, deploy

**Files:**
- Modify: `docs/range-backtest-2026-07.md` (addendum), `CLAUDE.md` (discharge-estimate + v2 paragraphs)

- [ ] **Step 1: Backtest the cleaner against the Jul 12 track**

Replicate spike rejection over the real 2026-07-12 15-s-bucket track for 2012-A in SQL (drop bucket-pairs whose in+out speeds exceed 4.5 m/s while discharging with plausible neighbor-agreement — approximate is fine) OR run the actual TS `cleanTrack` in node against `/web/track` JSON pulled via psql. Acceptance: cleaned cumulative miles lands near the real ~3–4 mi (raw was 6.8 + 3 phantom); record before/after in `docs/range-backtest-2026-07.md` as "Addendum 4 (2026-07-13): track cleaning backtest".

- [ ] **Step 2: Update CLAUDE.md**

In the discharge-estimate paragraph, after the windowed-measurement sentence, add:

```markdown
Bucketed fixes additionally pass **out-and-back spike rejection** (impossible speed in AND
out at the context bound — 4.5 m/s discharging / 45 m/s otherwise, 60 m/s absurd cap —
while the neighbors agree; TS sibling `web/src/v2/model/cleanTrack.ts` adds stay-point
snapping + smoothing for the v2 Journey map). Location capture is **always-on
PRIORITY_HIGH_ACCURACY GNSS** (5 s) — the phone rides the chair on USB power.
```

- [ ] **Step 3: Full verification sweep**

```bash
cd /home/joely/bmsmon/android && ./gradlew :app:testDebugUnitTest :app:assembleDebug
cd /home/joely/bmsmon/web && npx vitest run && npx tsc --noEmit && npm run build
```

Expected: all green.

- [ ] **Step 4: Commit docs**

```bash
cd /home/joely/bmsmon
git add CLAUDE.md docs/range-backtest-2026-07.md
git commit -m "docs: GPS track-cleaning backtest + architecture notes"
```

- [ ] **Step 5: Deploy (controller-driven, after merge to main)**

- Push main → GitHub Actions builds the server image (web/** changed) → `gh run watch` → NAS: `docker compose --env-file .env -f bmsmon/docker-compose.yml pull bmsmon-api && ... up -d bmsmon-api` → health check.
- Android: `./gradlew :app:assembleDebug` → `adb -s 192.168.0.16:35287 install -r app/build/outputs/apk/debug/app-debug.apk` (in-place, never uninstall) → relaunch.
- On-device verification: fresh samples show `gps_accuracy_m` ≤ ~10 outdoors; v2 Journey for Jul 12 draws without the phantom spurs and the TRIP distance drops to ≈ real miles.

## Post-plan notes

- The learner re-reads raw Room rows every pass, so spike rejection retroactively cleans any already-recorded local GPS days — no data rewrite needed.
- Historical map days clean up automatically (render-time cleaning).
