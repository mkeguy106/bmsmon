# Calibration check-in — 2026-08-04

Second full pass over every learned constant, against production Postgres.
Previous pass: 2026-07-15 (recorded in CLAUDE.md). Prior range work:
`docs/range-backtest-2026-07.md`.

**Basis.** 4,813,920 samples over 38 days (2026-06-28 → 2026-08-04), all 8 packs.
**300,644 discharge rows** — 2.5× the 2026-07-15 basis (2.0M samples / ~120k discharge).
3,082,528 rows carry GPS. Queries ran against `bmsmon-db` on ddnas02; day boundaries in
`America/Chicago` (the phone's zone), because the learner buckets by local date.

Two derivations were reproduced independently rather than read off the device:
`RangeLearn.kt` was ported to Python (`accumulate`'s dt loop in SQL, `rejectSpikes` +
`windowedSegments` in Python because the spike pass is sequential — it compares each fix
against the last *kept* one, which a SQL window cannot express).

---

## Summary

| Constant | Value | Verdict |
|---|---|---|
| `POWER_RING_FULL_W` | 300 W | **KEEP** — p98 = 292.5 W |
| `REGEN_EPS` | 0.1 A | **KEEP** — deadband reconfirmed at 1.044 A |
| `REGEN_WINDOW_MS` | 30 s | **KEEP** — longest burst 23.2 s of 1664 |
| `GPS_ACCURACY_MAX_M` | 250 m | **KEEP** — gates 0.13% post-GNSS-switch |
| `COAST_MAX_MS` | 30 s | **KEEP** — fires on 0.04% of gaps |
| `CHAIR_MAX_SPEED_MPS` | 4.5 | **KEEP** — p99 = 3.04 m/s |
| `WIN_MAX_ACCURACY_M` | 50 m | **KEEP** — 97–98% of current fixes pass |
| live-marker staleness | 120 s | **KEEP** — 33 of 337k gaps exceed it |
| `PARKED_HOLD_MS` | 5 min | **KEEP** (decision recorded below) |
| seed range bands | 78–182 / 52.5–97.5 / 51–85 | **KEEP** — learned lands 16–27 mi vs seed 15–25 |
| `SEED_TAIL_MIN` | 58 → **70** | **CHANGED** — measured mean 70.6 min |
| `learnedDays` | — | **BUG FIXED** — seed bands reported 12–13 |

---

## 1. Battery flow — no changes

Discharge power across 300,644 rows (per pack):

| p50 | p75 | p90 | p95 | **p98** | p99 | p99.9 | max |
|---|---|---|---|---|---|---|---|
| 52.9 | 87.2 | 147.4 | 210.1 | **292.5** | 363.3 | 713.2 | **1115.7** |

`POWER_RING_FULL_W = 300` still sits essentially at p98 and pegs **1.84%** of discharge
samples (2026-07-15: p98 = 301.5, pegging 2.0%). New per-pack spike record 1115.7 W
(was 1065.5). Restricting to data since 2026-07-15 alone gives p98 = 286.3 — the same
answer from an independent fortnight. **Keep 300.**

**The BMS reporting deadband is reconfirmed and is sharper than recorded.** The smallest
nonzero current in 4.8M rows is **exactly 1.0440 A**, and the values above it are quantized
in ~63.4 mA steps (1.044, 1.108, 1.171, 1.234, 1.298 …). 4,163,695 rows read exactly 0.000 A
and *none* fall in (0, 1.0). So any `REGEN_EPS` in (0, 1.044) is behaviourally identical —
the structural guarantee found in July holds on 2.5× the data. **Keep 0.1.**

Regen bursts have doubled to **1664 runs** (was 838). Longest is still **23.2 s**; **zero**
runs reach 30 s. Peak 34.23 A / 452.2 W. **Keep `REGEN_WINDOW_MS = 30 s`.**

---

## 2. Range — whPerMile has left seed

Both daily drivers are learned. Independent recompute vs what the phone pushed to
`device_range_config`:

| pack | source | whPerDay | activeW | whPerMile |
|---|---|---|---|---|
| 2012-B (62:1B) | device_range_config | 100.8–189.4 | 57.0–79.2 | 47.45–79.16 |
| 2012-B | recompute (14 d) | 101.5–187.5 | 57.1–77.0 | 47.26–77.90 |
| 2012-A (67:44) | device_range_config | 98.6–185.9 | 57.7–82.7 | 47.62–77.29 |
| 2012-A | recompute (14 d) | 99.1–183.7 | 58.1–80.4 | 48.43–79.96 |

The small deltas are the phone's rolling `now − 14 d` cutoff (a partial first day) against
my midnight-aligned window. Treat this as a reproduction.

**The open question from 2026-07-15 — "if it still tops near 74 Wh/mi, the 31-mi upper
readout is real" — is answered: it is not.** `RangeEstimate.kt` computes
`milesHi = remainingAh × 12.8 ÷ whPerMile.lo`, so the upper readout is driven by the band's
**low** end. That came in at **47**, not the 41 the cloud-derived estimate predicted, which
pulls the upper readout from 31 mi down to **~27 mi**. The band's high end (75–80 Wh/mi) is
confirmed, so the lower readout of ~16–17 mi is real.

**At full charge (1280 Wh) the readout is now ~16–27 mi, against the seed's 15–25.** The
seeds are therefore well calibrated and are left alone — they are conservative in the safe
direction and are replaced after 3 qualifying days anyway.

Over the full 38 days (32–33 outing days, ~102 chair miles per pack) the band tightens to
47.2–75.3 (2012-B) and 46.9–75.1 (2012-A) Wh/mi. Outing-day cost ranges 35–105 Wh/mi; the
spread is real, since short outings carry their fixed overhead over fewer miles — which is
exactly what outing-day semantics are meant to capture.

---

## 3. `learnedDays` reported seed bands as learned — FIXED

Found in production: **all six background packs** carried `learned_days` 12–13 in
`device_range_config` while every band was a pure seed (78–182 / 52.5–97.5 / 51–85).

`RangeLearn.kt` set `learnedDays = whPerDay.size` — the count of *coverage-qualifying* days —
independently of whether `bandOf` had fallen back to the seed. A pack that never discharges
still gets 12 h/day of coverage, so it qualifies 13 days and teaches nothing; `bandOf`
correctly seeds it via the `hiRaw <= 0` zero-signal guard while the count claimed otherwise.

This is not cosmetic. `web/src/v2/model/efficiency.ts:88` reads

```ts
const seed = packParams.some((p) => p.learnedDays === 0);
```

to decide whether the EfficiencyCard's chip says **"vs seed est."** — so a pack whose band is
a seed was presented as a real comparison.

Fixed by having `bandOf` report whether it learned, and setting
`learnedDays = if (whPerDayBand.learned) whPerDay.size else 0`. Two existing tests asserted
the old values (2 and 3 learned days alongside seed bands) and were updated to 0 — they had
locked in the defect.

---

## 4. Charge ETA — the learner works, its target is high-variance

**The 2026-07-15 open item is closed: the run-identity dedup held and the EMA converged.**
Predicted time-at-SOC-70 grew monotonically across sessions (267 → 296 min for 2012-B),
i.e. the learned tail moved from the 58-min seed to ~79 min. No double-folding is visible.

Accuracy is nonetheless limited, and the reason is worth recording:

- **Bulk is excellent.** SOC 70 → 98 takes **217.1 min with SD 7.8** across 28 sessions.
- **98 → 99 takes 7.7–8.1 min** on every session, both packs, five weeks. Near-zero variance.
- **The tail is real charging, not idle time.** Current is a flat ~7.95 A through bulk, through
  98→99, and on past it; the pack absorbs **7–9 Ah beyond its rated `full_charge_ah` (105)**,
  reaching `remaining_ah` 111–113, then genuinely terminates with a **~6-minute taper**
  (8.0 → 2.5 A over the last 6 min) before cutoff.
- **But tail duration runs 40.6 → 129.6 min** (mean 70.6, SD 25.8).

Net: ETA mean-absolute-error is **~22 min**, p10–p90 spread **58 min**, and the bias is
directional — shallow evening top-ups (start SOC 87–88) over-predict by **+33…+39 min**,
deep overnight sessions under-predict by **−28…−52 min**. A single scalar cannot be right
for both.

`SEED_TAIL_MIN` reseeded **58 → 70** to match the measured mean. This only affects a fresh
install's first few charges (the EMA converged fine from 58), but 58 sat a fifth below reality.

**Design lead, not applied.** The tail correlates with session depth — **r = +0.67** against
total charge time, **r = −0.48** against start SOC. So a depth-aware tail (predicted from
charge delivered or start SOC) would explain roughly 45% of the variance a scalar EMA
explains none of, and would roughly halve ETA error. That is a design change and is left
open deliberately.

**Checked and clean:** the `remaining_ah` overshoot above is confined to the Charging state —
at SOC 100 while Idle it reads exactly 105.00 = `full_charge_ah`. Since
`estimatePackRange` returns null while charging, the inflated value never reaches the range
readout.

---

## 5. GPS — no changes, and one scare resolved

**The learner's 50 m gate appears to reject half of all fixes — but that is history.**
Across all 3.08M fixes, p50 accuracy is 50.1 m and 1,541,438 (50.0%) fail the gate. The
cause is a single spike: **1,434,180 fixes (46.5%) read exactly 100.0 m**, the Android fused
*network* accuracy, all from before the 2026-07-13 switch to always-on high-accuracy GNSS:

| week | fixes | p50 accuracy | % under 50 m |
|---|---|---|---|
| 06-29 | 661,539 | 100.0 | 10.6 |
| 07-06 | 912,461 | 100.0 | 10.2 |
| 07-13 | 537,765 | 16.0 | 80.4 |
| 07-20 | 454,513 | 14.4 | **98.1** |
| 07-27 | 450,843 | 12.8 | **97.4** |
| 08-03 | 65,495 | 18.2 | 93.3 |

Since the switch, 97–98% of fixes pass. The learner's 14-day window now contains only
post-switch data — which is *why* whPerMile finally left seed. **No gate change.** (Exactly
50.0 m occurs 60 times in 3M fixes, so the `>=` vs `>` boundary is immaterial.)

Other GPS constants, measured since 2026-07-13:

- **`GPS_ACCURACY_MAX_M = 250`** — gates only **0.13%** (1,956 fixes). p99 = 124.8 m,
  p99.9 = 300 m, max 1600 m. Worst *accepted* fix is 247 m. Cutting at the tail without
  eating the body. **Keep.**
- **Fix cadence: p50 5.5 s, p90 9.1 s, p99 9.5 s.** CLAUDE.md's "the fused provider refreshes
  fixes every ~10–30 s" is stale balanced-era text and has been corrected. The windowed
  distance design is unaffected and still necessary — telemetry samples at 1.5 s, still
  faster than fixes arrive.
- **`COAST_MAX_MS = 30 s`** — only **119 of 337,233** gaps (0.04%) exceed it, ~3× the p99 gap.
  Fires on real dropouts, not jitter. **Keep.**
- **120 s live-marker staleness** — only 33 gaps exceed it. The marker essentially never greys
  spuriously. **Keep.**
- **`CHAIR_MAX_SPEED_MPS = 4.5`** — p99 of discharging bucketed segments is **3.04 m/s**, max
  8.00, and only **0.09%** exceed the cap. Comfortably above real chair speed. **Keep.**

---

## 6. Battery saver — the two unmeasured items, measured

### GNSS pause: expected saving ~15 mA

The design shipped with the GNSS budget measured (22 mA) but **the saving from pausing it
never measured end-to-end**, because the phone sat inside its low-battery latch throughout
testing (GNSS was already in balanced mode).

The missing half is the duty cycle. `gpsParked()` is true when no base has discharged for
`PARKED_HOLD_MS`; over 31,681 minutes since 2026-07-13 that holds **68.42% of wall-clock
time**. So the expected saving is ≈ 0.684 × 22 mA ≈ **15 mA**, about **360 mAh/day** — ~3.8%
of the 2,580 mAh / 6 h 33 m baseline the feature was sized against.

This is a duty-cycle-derived estimate, not a power measurement, but both halves now exist.

### The "a spare on a charger holds GNSS on" caveat is unfounded — retire it

CLAUDE.md flags this as "the first knob to turn if the saving ever measures low". It has
**never once happened**: across 38 days there are **0 minutes** in which only a non-daily-driver
discharged. Lifetime discharge rows by pack: 2012-B 154,944 · 2012-A 145,690 · 2016-B **8** ·
2016-A **2** · the other four **0**. Both spare events fell inside minutes when a daily driver
was also discharging. The gate is driven entirely by the chair.

### Transit cost, and the `PARKED_HOLD_MS` decision

The known open decision — the parked gate switches GNSS off during vehicle transit — now has
numbers. Of 357.5 moving miles (≥0.4 m/s) since 2026-07-13, the 5-minute hold drops
**256.5 (71.7%)**, including **205.5 of 227.7 vehicle-speed miles (90%)**.

| `PARKED_HOLD` | GNSS off (duty) | moving miles lost |
|---|---|---|
| **5 min (current)** | **68.4%** | **256.5 (71.7%)** |
| 10 min | 60.6% | 212.9 |
| 15 min | 55.5% | 180.2 |
| 20 min | 51.7% | 150.5 (42.1%) |
| 30 min | 46.7% | 113.2 (31.7%) |

**Decision: keep 5 minutes.** The saving is real, and the lost miles are ones the range
learner discards anyway (no discharge ⇒ no learning). Note for later: 20 min would recover
41% of the lost movement for about a quarter of the saving — the option to reach for if the
frozen live-share marker becomes annoying in practice, which is a UX judgement rather than a
data one.

---

## Follow-ups left open

1. **Depth-aware charge tail** (§4) — the one change that would materially improve ETA
   accuracy. r = +0.67 signal exists; needs a design.
2. **`Idle` rows carrying nonzero power** — 40,102 rows report state `Idle` with power > 0
   (max 987 W), and 2,994 `Discharging` rows report exactly 0 W. Not investigated here; likely
   state/current transition skew within a frame. Worth a look if any consumer trusts `state`
   and `power_w` to agree.
3. Next check-in: ~2026-09. Re-verify whPerMile's low end once more outing days accumulate
   (it moved 41 → 47 between passes, which moved the headline mileage by 4 mi).
