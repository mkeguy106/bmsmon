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
