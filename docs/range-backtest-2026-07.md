# Discharge-range learner backtest — 2026-07-11

Validation of the `RangeLearn.kt` day-qualification + p20/p80 band rules against the real
cloud history (production Postgres, 2026-06-28 → 2026-07-12, ~1.67M samples), run before
shipping per the 2026-07-11 design. The SQL mirrors the learner exactly: gaps 0.5–60 s,
`state='Discharging'`, regen excluded, day qualifies at ≥12 h coverage.

## Result

| address (pack) | qualifying days | p20 Wh/day | p80 Wh/day | frac of days in band |
|---|---|---|---|---|
| C8:47:80:15:67:44 (2012-A, daily driver) | 13 | 81 | 211 | **0.54** |
| C8:47:80:15:62:1B (2012-B, daily driver) | 13 | 83 | 213 | **0.54** |
| C8:47:80:15:DB:13 (2016-A) | 1 | — | — | (seeds apply) |
| C8:47:80:15:25:9A (2016-B) | 2 | — | — | (seeds apply) |
| remaining 4 packs | 0 | — | — | (seeds apply) |

## Verdict

**Pass.** Both daily drivers land at 0.54 in-band (7/13 days) — statistically at the ~0.6
that a p20/p80 band predicts by construction, well above the 0.40 investigate threshold.
Packs with fewer than 3 qualifying days correctly fall back to the seed bands, exactly as
`MIN_LEARN_DAYS` intends. No day-qualification rule changes needed.

Two observations worth keeping:

- The real learned band for the daily drivers (~81–213 Wh/day) brackets the seed band
  (78–182) closely on the low end; the real p80 sits ~17% above the seed's high end. The
  learner replaces seeds after 3 qualifying days, so the seed high-end being slightly
  optimistic only affects the first ~3 days of a fresh install.
- Background packs rarely accumulate 12 h/day of samples (slow rotating poll), so their
  whPerDay/activeW stay seeded until they spend time as the staged base. That is by design —
  a pack we barely sample shouldn't pretend to have learned bands — but explains why only
  the daily drivers learn quickly.

Re-check at the 2026-07-15 accuracy check-in alongside the charge-ETA and power-ring
calibrations (see CLAUDE.md).

## Addendum (2026-07-11): vehicle-context contamination in drive segments

User-prompted follow-up: classifying every gate-passing drive segment on 2012-A by whether any
GPS movement within ±3 min exceeded 4.5 m/s (a speed the chair cannot reach — vehicle context):

| context | segments | miles | avg speed | avg draw |
|---|---|---|---|---|
| genuine chair driving | 60 | 0.10 mi | 1.45 m/s | 68 W |
| vehicle context | 375 | **0.86 mi** | 2.03 m/s | 94 W |

**~90% of gate-passing drive distance was the chair powered (>40 W) inside a van creeping
below the 4.0 m/s cap.** The learner now rejects any chair-speed segment with vehicle-speed
movement within ±3 min (`VEHICLE_SPEED_MPS = 4.5`, `VEHICLE_CONTEXT_WINDOW_MS = 180 s`).
Genuine chair driving adjacent to van boarding is also discarded — the safe direction to err.

Note: the genuine chair-context sample (0.10 mi, ~21 Wh/mi) is too small to re-derive the
Wh/mile seed yet. Re-derive from clean accumulated data at the accuracy check-in.

## Addendum 4 (2026-07-13): track-cleaning backtest

Ran the production `cleanTrack` (web/src/v2/model/cleanTrack.ts, via esbuild+node) against the
real 2026-07-12 15-s-bucket track for 2012-A (5,759 points):

| stage | miles |
|---|---|
| raw (what the v2 map used to draw/sum) | 9.78 |
| after spike rejection (9 fixes dropped) | 9.02 |
| after stay-point snapping | 7.22 |
| fully cleaned (with smoothing) | **5.38** |

~4.4 phantom miles removed; the map loses the off-track spurs and the parked caterpillar.
The 5.38 mi still likely overstates the true distance somewhat: on this day's ~90 m
balanced-power fixes, parked jitter wider than the 30 m stay radius and latched (repeated)
spike coordinates survive the cleaner — both shrink sharply under the new GNSS capture, so
don't treat 5.38 as clean truth at the 2026-07-15 check-in.
Kotlin learner gets the same spike rejection (bridged windows recover real distance across a
dropped fix). Capture is now always-on PRIORITY_HIGH_ACCURACY GNSS (5 s), so future raw
tracks start far cleaner than this one.

## Addendum 2 (2026-07-12): miles switched to outing-day semantics

The user (correctly) rejected the cruise-physics range reading (37–62 mi at 69%): the original
Wh/mile measured energy per mile *while cruising* (~21 Wh/mi/pack), ignoring that most real
energy goes to indoor maneuvering and idle-on overhead. The learner now computes **outing-day
Wh/mile** — a day's TOTAL discharge ÷ that day's clean outdoor miles, on coverage-qualified
days with ≥0.5 mi of vehicle-excluded outdoor driving — so lived overhead prices into every
mile and the estimate converges on experiential range. The seed became 51–85 Wh/mi per pack
(= a conservative 15–25 practical miles at 100%; user was unsure of the chair's true range).
69% now reads ~11–18 mi instead of 37–62. No qualifying outing days exist yet in the history;
the first few real outings will start replacing the seed.

## Addendum 3 (2026-07-12): windowed distance + discharge-gate vehicle exclusion

The user reported real outings the learner couldn't see (Chicago ~3 mi, Milwaukee 3–4 mi).
Root causes, verified against the cloud data:

1. **Fix latching:** the fused provider (balanced power, 10 s interval) refreshes fixes every
   ~10–30 s while telemetry samples every 1.5 s — consecutive-sample distances read as
   freeze-then-teleport. Pairwise measurement caught 0.02 mi of a 4.8-mile day; the inflated
   teleport "speeds" then triggered the ±3 min vehicle-context exclusion around genuine
   driving. **Fix:** distance is measured between one representative fix per 30-s bucket
   (accuracy < 50 m), windows of 15–90 s — recovers true speed regardless of latching.
2. **Speed cap too low:** the chair tops out ~9 mph (4.0 m/s), over the old 4.0 cap downhill.
   Chair band is now 0.4–4.5 m/s windowed.
3. **Vehicle discrimination replaced:** the chair draws ZERO while in the van/on a train
   (user-confirmed), so the discharge gate alone separates vehicle rides from driving. The
   ±3 min context-window heuristic was removed — it erased genuine rolling adjacent to van
   boarding.

Validation (final rule replicated in SQL, pack 2012-A): Jul 11 (Milwaukee) 5.63 mi, Jul 10
(Chicago) 2.40 mi, home days 0.3–1.0 mi — matching the user's account. Outing-day Wh/mile on
real days: ~35 (heavy-rolling Milwaukee) to ~79 (home/mixed) per pack → ~16–37 practical
miles per full charge; the 51–85 seed brackets this and will hand off to learned bands after
3 qualifying days of post-v4 local GPS.

## Addendum 5 (2026-07-29): Kalman track-smoothing backtest

Ran `web/scripts/backtest-clean.mjs` (via `npx vite-node`) against the full production
`cleanTrack` pipeline — `rejectSpikes → collapseIdleExcursions → snapStays → smoothKalman`
(`web/src/v2/model/kalmanTrack.ts`) — on two real days for pack `C8:47:80:15:67:44` (2012-A),
pulled with the server's own `/web/track` query (15 s buckets, `link_event IS NULL`, lat/lon
non-null, `gps_accuracy_m IS NULL OR <= 250`).

**The train day is pinned to a closed window, not "the day."** 2026-07-29 was still in progress
at measurement time — a re-run about an hour after the first pull returned materially different
numbers (a new 22.5-minute gap had appeared by 12:49 UTC), so a full-calendar-day figure would
not have been reproducible. The table below uses `ts >= '2026-07-29 00:00:00' AND ts <
'2026-07-29 13:00:00'` (UTC), a bound safely in the past by the time this was written, so these
numbers are fixed. The outing day (2026-07-12) is a full closed calendar day — it ended long
before this backtest ran.

| day | window (UTC) | context | points | mean acc (m) | raw mi | after spike+stay | after Kalman | inferred segs |
|---|---|---|---|---|---|---|---|---|
| 2026-07-29 | 00:00–13:00 | train ride, 70–145 km/h | 2,768 | 21.6 (p50 10.2, p90 30.3, max 222.8) | 95.658 | 88.300 | **86.850** | **15** |
| 2026-07-12 | full day | normal wheelchair outing | 5,754 | 82.3 (p50 100.0, p90 100.0, max 170.3) | 18.155 | 12.413 | **11.54** | **1** |

**Direction checks out on both days.** Miles drop at the Kalman step on both (train
88.300→86.850, −1.6%; outing 12.413→11.54, −7.0%) — jitter inflation shrinking, not movement
being fabricated, matching the expected direction from the design doc.

**Train day:** 15 inferred segments inside the bounded window, all inside the actual
high-speed running (fixes above 70 km/h from 11:21:45 to 12:58:00 UTC, up to 196.5 km/h
measured, gated fixes as coarse as 222.8 m). The largest hole is **47 minutes**
(11:38:30–12:25:30 UTC) — fully inside the 00:00–13:00 window, confirmed directly against the
bounded pull — far past the ~120 s the design doc measured from a partial-day snapshot taken
earlier the same day; the ride evidently ran through a much longer dead zone than that snapshot
caught. This is the strongest possible validation of the feature: previously that 47-minute
blackout would have drawn one straight confident chord across ~40 km of track at apparent
highway speed. It now renders as a single dashed/inferred bridge instead. A second, shorter
22.5-minute gap (12:27:00–12:49:30 UTC) falls inside the window too, contributing one more
inferred segment; the ride was evidently still producing dead zones near the end of the
bounded window.

**Outing day: no bug.** The one inferred segment sits at 04:34:15–04:35:45, a 90 s gap with
`current_a = 0` and *identical* lat/lon before and after (a stationary overnight sample drop,
not a hole in the actual outing). `COAST_MAX_MS` is not mistriggering on ordinary driving
buckets — the daytime outing itself has zero gaps over 30 s.

**Open question, deliberately left open:** this day's full-24h raw mileage (18.155 mi, 5,754
buckets) runs almost double Addendum 4's 9.78 mi for what is nominally the same 2026-07-12
2012-A track (5,759 buckets). Two plausible explanations were checked against prod and **both
are ruled out**:

- *The accuracy gate excluded a lot of coarse fixes Addendum 4 didn't gate.* Checked: only
  **3 of 48,584** GPS-carrying rows that day exceed the 250 m gate (day-average accuracy
  82.3 m). The gate removes essentially nothing — it cannot explain a ~2x difference. (Also,
  as a matter of direction, if Addendum 4's query predated the gate, its raw figure should have
  come out *higher* than this gated pull, not lower — the opposite of what's observed.)
- *Addendum 4 measured a narrower time window than a full calendar day.* Checked: the bucket
  counts are near-identical (5,754 vs 5,759), which is what a full calendar day at 15 s
  buckets looks like (86,400 / 15 = 5,760) either way — there's no room for a materially
  narrower window to produce a near-identical count.

The cause of the discrepancy is **unexplained**. It does not affect this backtest's
conclusions — the before/after comparison above runs on one internally consistent input, so
whatever produced Addendum 4's lower absolute number doesn't bear on whether this day's own
raw→cleaned drop is correctly signed and sized. Recording the ruled-out numbers here so a
future investigation doesn't re-derive them from scratch.
