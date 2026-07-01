# Charge-time estimate (self-learning "time to full")

**Date:** 2026-07-01
**Status:** Approved design → planning

## Problem

When a pack is being charged, the app should show a live **estimated time to full (100%)**. The
estimate must cover the full arc including the top-end CV tail, and it should **improve itself
over time** from the data we already collect — without requiring us to periodically re-analyze
and hand-tune constants.

## Data basis (what we learned from the logs)

Pulled the phone Room DB (`bms.db`, ~314k samples, 2026-06-28 → 07-01). Charging is cleanly
labeled by the BMS battery-state (offset 88 = `Charging`), distinct from regen (only 130
regen-flagged samples). **28,416 charging-state samples.** Key findings:

- **CC bulk (SOC up to ~98%) is dead-linear coulomb counting.** ~7.7 min per 1% SOC, rock-steady,
  at a constant ~8 A, voltage drifting 13.26 → 13.50 V. Matches physics exactly (1% of 105 Ah =
  1.05 Ah / 8.1 A = 7.8 min). Cross-validated across packs/chargers (DB:13: 12% in 93 min at 8.3 A).
- **Backtest of the coulomb estimator over the CC region: mean abs error ≈ 4 min, p90 ≈ 7 min**
  (~15.6k predictions) — essentially the SOC-quantization floor.
- **The tail (98 → 100%) is a real CV taper**, but charger/temperature dependent: near the end,
  voltage reaches ~14.1 V and current decays 8 → 3.8 → 2.8 A and keeps dropping. The 99% plateau
  (4,202 samples) is the absorption phase where SOC pins while current tapers.
- **`fullChargeAh` (~105) and `remainingAh` are already parsed/stored**, so the bulk physics needs
  no new protocol work.
- **Only 2 packs reached 100%** in this window (the daily drivers 67:44 + 62:1B); the other six
  capped at 99%. So the tail prior is currently weak → it must be learned, and a check-in is
  scheduled (see CLAUDE.md, 2026-07-15).

## Decisions (from brainstorming)

- **Learning scope:** simple self-tuning constant — a per-pack `tailMin` EMA. (Temperature-keyed
  and server-side fleet learning explicitly deferred.)
- **Display surfaces:** Android stage **and** WebUI.
- **Target:** 100% (not "99% = practically full").
- **Where the math runs:** on the phone; the computed ETA is uploaded and the WebUI mirrors it
  read-only (model stays on-device).

## Design

### 1. Estimate (two parts, target = 100%)

- **Bulk region (SOC ≤ 98%)** — pure physics, live measured current:
  `t_bulk = (0.98 × fullChargeAh − remainingAh) / I_avg`
  where `I_avg` is a short rolling mean of `currentA` (rejects regen/noise blips; use the same
  windowing already proven in the analysis). Self-calibrating on any charger.
- **Tail region (98 → 100%)** — learned per-pack constant `tailMin[pack]`:
  `t_tail = tailMin[pack] × (100 − SOC) / (100 − 98)`
- **Total ETA** = `t_bulk + t_tail`; once SOC ≥ 98, just the scaled tail.

### 2. Learning loop (on-device — the self-improvement)

- A charge **session** that passes through ≤ 98% and later reaches 100% while still `Charging`
  yields one clean observation: `observed_tail = t(first 100%) − t(first 98%)`.
  Sessions unplugged early never hit 100%, so they are simply not learned from — no fuzzy
  completion detection needed.
- Update per pack: `tailMin = α·observed_tail + (1−α)·tailMin` (EMA, α ≈ 0.3).
- Fresh install seeds `tailMin = 45 min` (current best estimate) until real data overwrites it.
- Storage: a small persisted per-pack value (DataStore keyed by address, or a tiny Room table).
  The existing DB already holds the sample history needed to compute `observed_tail` at
  session-completion time.

### 3. Display (Android + WebUI)

- ETA computed on the phone. New nullable telemetry field `etaFullMin` rides the existing upload
  outbox; new nullable `samples` column server-side (additive schema — applies on container start).
- **Android stage:** under the SOC ring while charging — e.g. `⚡ ~2h 14m to full`. While in the
  tail with few learned observations, annotate `~45m (learning)`.
- **WebUI stage:** renders the uploaded `etaFullMin` read-only (no server-side math).
- **Gating:** shown only when steadily charging — state `Charging` for a few consecutive samples
  and current above a floor. Hidden otherwise (never shown for idle/discharge/regen).

### 4. Accuracy posture

- Bulk region trustworthy immediately (±4–7 min backtested).
- Tail starts from the seed prior and tightens per completed full charge. Scheduled re-check
  2026-07-15 (CLAUDE.md + memory) to confirm convergence and decide whether the deferred
  temperature-keyed tail model is worth it.

## Out of scope (YAGNI / deferred)

- Temperature-keyed or current-keyed tail model.
- Server-side/fleet-aggregated learning with param push-back.
- Estimating charge time for regen (regen is transient, not a charge session).
