# Discharge Estimate (miles + time remaining) — Design

**Date:** 2026-07-11
**Status:** Approved

## Goal

Show an estimated discharge remaining for the staged base as high/low bands — e.g.
`~37–50 mi · ~9–13h use · ~5–9 days` — learned from the user's real battery + GPS
telemetry, displayed on the Android stage in the same visual slot family as the
recharge ETA, and mirrored read-only on the WebUI.

Three figures, all counting down to 0% SOC:

1. **Miles** — remaining energy ÷ learned Wh-per-mile. Classic EV-range semantics:
   "if you spent the remaining charge driving."
2. **Active-use hours** — remaining energy ÷ learned typical discharge draw:
   "hours of actual driving/use left."
3. **Wall-clock time to empty** — remaining energy ÷ learned energy-per-day:
   "when must I charge?" Rendered as days when >48 h, else hours.

## Data findings that shaped the design (from 2 weeks of real cloud data, 1.67M samples)

- Daily usage (2012 daily-driver base): 51–260 Wh discharged/day per pack,
  0.8–3.5 h active discharge/day, SOC drop 4–18%/day.
- Confirmed *outdoor* driving (good GPS + discharging + wheelchair-speed movement) is
  only 0.03–0.25 mi/day — most driving is indoors where GPS jitter (20–100 m) cannot
  measure wheelchair-speed distance. But Wh/mile on qualified outdoor segments is
  remarkably stable: **~18–24 Wh/mile per pack at ~4.3 mph**.
- GPS jitter masquerades as movement: naive speed-band filtering counted ~6.6 "miles"
  where segments averaged only 23 W (idle jitter). Movement must be gated on
  *actively discharging* + power > 40 W + accuracy < 20 m + speed 0.4–4.0 m/s.
- **GPS is not currently stored in the phone's Room DB** — it only rides the cloud
  outbox. Local storage is a prerequisite for on-phone Wh/mile learning.
- Local Room retention is 14 days (`SAMPLE_RETENTION_DAYS`) — the learning window.

## Architecture (Approach A — approved)

Phone learns from its local Room history; learned params ride the existing one-way
`POST /api/v1/config` channel to the server; both UIs evaluate a shared pure formula
(Kotlin + TypeScript twin). Mirrors two proven patterns: `tailMin` charge-tail
learning and the temperature-config mirror.

### 1. Room schema addition

Add nullable `lat`, `lon`, `gpsAccuracyM` columns to `SampleEntity` (additive Room
migration). `MonitorEngine` already holds the GPS fix when recording a sample — write
it locally as well as to the outbox. Existing 14-day retention prunes it; nothing
else changes.

### 2. Learner (phone, periodic)

A learning pass runs on engine start and every ~6 h (engine-scoped coroutine, like
other engine timers). It scans the last 14 days of Room `samples` per pack and
produces three parameter bands, stored in `SettingsStore` keyed by address (pattern:
`tailMinByAddress`), each with `updatedMs`:

| Parameter | Per-day statistic (qualifying days only) | Band | Cold-start seed |
|---|---|---|---|
| `whPerDay` | Σ power·dt while `Discharging` (day needs ≥12 h coverage) | p20/p80 across days | 130 Wh/day ±40% |
| `activeW` | discharge Wh ÷ discharge hours | p20/p80 across days | 75 W ±30% |
| `whPerMile` | Σ(power·dt) ÷ Σ distance over qualified drive segments; day needs ≥0.05 qualified miles | p20/p80 across days | 20 Wh/mi ±25% |

Qualified drive segment (all conditions): consecutive samples with `state=Discharging`,
`power_w > 40`, both fixes `gps_accuracy_m < 20`, `0.5 s ≤ dt ≤ 15 s`, segment speed
0.4–4.0 m/s. Distance = equirectangular approximation between fixes.

Seeds apply until ≥3 qualifying days exist for that parameter (per pack). Regen
samples are excluded from burn statistics. Link-event rows are skipped.

### 3. Estimate formula (pure, shared)

`model/RangeEstimate.kt` + line-for-line twin `web/src/range.ts`. Per pack:

```
remainingWh = remainingAh × 12.8
milesLo     = remainingWh / whPerMile.hi     milesHi     = remainingWh / whPerMile.lo
activeHrsLo = remainingWh / activeW.hi       activeHrsHi = remainingWh / activeW.lo
daysLo      = remainingWh / whPerDay.hi      daysHi      = remainingWh / whPerDay.lo
```

**Base readout = min across staged packs** for each figure — captures the series-pair
reality (weaker pack ends the trip) with no series-specific assumptions; works
unchanged for a single staged pack.

**Live tilt (Android only):** the estimator takes today's observed burn (discharge Wh
and hours since local midnight, from Room). Tilt weight grows with today's actual
discharge time, capped at 50% so history anchors:

```
w = min(0.5, todayDischargeHours / 4h × 0.5)
historyMid = (lo + hi) / 2
tiltedMid  = (1 − w) · historyMid + w · todayRate
```

The tilt shifts the band's center; band width stays from history. Applied to
`whPerDay` and `activeW` (today's data carries no reliable miles signal — `whPerMile`
is never tilted).

### 4. Android display

One muted base-level line on the stage, centered under the rings — same typography as
the per-pack recharge ETA (`MonoFont`, 12 sp, `text2`):

```
~37–50 mi · ~9–13h use · ~5–9 days
```

- Wall-clock: whole days (`~5–9 days`) when the low bound >48 h, else whole hours
  (`~34–42h`).
- Miles: whole numbers; one decimal when <10.
- Shown when staged packs are connected and **none is charging**; while charging the
  existing per-pack recharge ETA owns the slot and this line hides.
- Any staged pack disconnected → line hidden (consistent with DISCONNECTED semantics —
  no fake numbers).

### 5. Sync & server

- Phone: after a learning pass, if params changed, `POST /api/v1/config` gains an
  optional `range` block: per-address `{wh_per_day_lo/hi, active_w_lo/hi,
  wh_per_mile_lo/hi, updated_ms}`.
- Server: new `device_range_config` table (device_id + address, latest-wins upsert),
  idempotent DDL in `schema.sql` (auto-applies on container start; no migration step).
- WebUI: read-only `GET /web/range-config` (pattern: `/web/temp-config`);
  `range.ts` computes the strip on `MainStage.tsx` from mirrored params + live fleet
  SOC. **Documented divergence:** WebUI shows the history band without live tilt
  (tilt inputs live in the phone's Room DB; not worth a new sync channel).

### 6. Testing & validation

- Kotlin unit tests (mirroring `ChargeEstimateTest`): learner percentiles,
  qualifying-day rules, segment qualification, cold-start seeds, tilt math, formula,
  min-across-packs, display formatting/gating.
- TS twin gets the same test vectors so both implementations provably agree.
- **Backtest before shipping:** run the learner's logic as SQL over the real 14 days
  in cloud Postgres; verify produced bands would have bracketed actual per-day usage
  on ~60–80% of days (the p20/p80 expectation).
- Server pytest coverage for the `range` config block + `/web/range-config`.

## Out of scope

- Predicting miles from indoor driving (GPS physically can't measure it).
- Pushing live-tilt inputs to the WebUI.
- Per-pack discharge readouts on the stage (base-level only; per-pack recharge ETA
  is unchanged).
- Changing alert/seize behavior — this feature is display-only.
