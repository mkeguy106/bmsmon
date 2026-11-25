# Calibration check-in, 2026-11-25: rest-voltage table refit, BMS re-anchor, capacity parse bug

Unscheduled. Trigger: base 2012's pack B showed 0 % at 13.02 V.

## Findings

1. **Parse bug.** Full-charge capacity is the uint16 at frame offset 64, not a uint32. The int16 at
   offset 66 is a separate field, zero until the BMS re-anchors its counter (below). Read together
   they gave ~41 million Ah, and the estimate (38.23 Ah over that) rounded to 0 %. Fixed in
   `BmsProtocol.parseTelemetry`; the new field is `Telemetry.pendingAh`.
2. **The BMS re-anchors its own counter.** About 2 h after the last measured current, at a pack
   voltage of 13.04 V or lower, the firmware picks a rest-voltage SOC and eases `remaining_ah` down
   to it with zero current flowing. `remaining_ah + pendingAh` is the target and stays constant.

   | When (UTC) | Pack | Rest voltage | Counter before | BMS target |
   |---|---|---|---|---|
   | 2026-10-20 20:17 | 2024-A | 13.04 V | 103.4 Ah | 25.00 Ah |
   | 2026-10-23 03:31 | 2012-A | 13.00 V | 58.7 Ah | 23.00 Ah |
   | 2026-10-23 03:31 | 2012-B | 12.98 V | 58.2 Ah | 22.00 Ah |
   | 2026-11-25 04:35 | 2012-A | 13.04 V | 57.7 Ah | 25.00 Ah |
   | 2026-11-25 04:35 | 2012-B | 13.02 V | 72.1 Ah | 25.00 Ah |

   The decay is exponential, τ ≈ 16 h (2024-A took two days). The estimator now shows the target at
   once and drops its own held correction while `pendingAh < 0`.
3. **The BMS's target is low by ~7–11 Ah.** The full charge that followed October's re-anchor
   (2026-10-24 03:11 → 12:47) delivered 76.1 Ah into 2012-A and 73.8 Ah into 2012-B. Their counters
   started at 24.5 / 22.4 Ah and reached the taper at 99.4 / 95.6 Ah, not 105.
4. **The counter drifts high by ~3 Ah/day on the daily drivers.** Drift = counter at charge start −
   (105 − Ah the charger then delivers), over 66 inter-charge intervals since 2026-07-01: 1.2–2.5
   Ah/day until mid-July, mostly 2–4.5 since, proportional to elapsed time, not to Ah driven. It is
   load under the BMS's 1.04 A deadband. A week after a full charge the BMS SOC is ~20 points high.
5. **The 2026-11-18 voltage table inherited that drift**, because it was fitted to the counter.

## Refit

Truth = charger-delivered Ah. For each interval between two full charges (taper seen: ≥ 13.9 V,
last current < 3 A), with `t` running from the first charge's end to the second's start:

```
offset0  = counter 10 min after the first charge − 105        (0 after a clamp; 13–15 on 2012-B)
drift    = (counter at second charge start − offset0) − (105 − Ah delivered by the second charge)
true(t)  = counter(t) − offset0 − drift × (t − t_first_end) / (t_second_start − t_first_end)
```

Rested points are 5-minute buckets with zero current that follow ≥ 30 min of zero current after a
discharge. Intervals in which the BMS re-anchored are skipped. 27 327 rested buckets, both 2012
packs, 24–30 °C. Median true SOC per 20 mV:

| Rest voltage | n | p10 / median / p90 | Counter said | 2026-11-18 table |
|---|---|---|---|---|
| 12.97–12.99 (5 rests, 3 dates) | | 28–32 | 52–55 | 46–50 |
| 13.00–13.02 | 24 | 33.5 / 33.6 / 35.7 | 55 | 52 |
| 13.04–13.06 | 58 | 37.3 / 37.5 / 37.8 | 56 | 56 |
| 13.06–13.08 | 202 | 35.5 / 37.3 / 38.6 | 57 | 57 |
| 13.08–13.10 | 69 | 36.4 / 36.9 / 40.4 | 57 | 58 |
| 13.10–13.12 | 27 | 38.3 / 38.5 / 47.7 | 58 | 60 |
| 13.12–13.14 | 146 | 42.5 / 46.1 / 55.8 | 65 | 64 |
| 13.14–13.16 | 1 021 | 41.6 / 50.6 / 61.2 | 66 | 68 |
| 13.16–13.18 | 4 153 | 49.0 / 57.4 / 64.7 | 69 | 71 |
| 13.18–13.20 | 2 719 | 59.7 / 64.5 / 67.9 | 73 | 74 |
| 13.20–13.22 | 832 | 66.0 / 67.8 / 72.3 | 78 | 76 |
| 13.22–13.30 | 1 547 | 69–72 (flat) | 77–79 | 77–80 |
| 13.30–13.32 | 909 | 70.3 / 72.5 / 80.7 | 79 | none (plateau) |
| ≥ 13.32 | 15 610 | 73–92 | 87 | none (plateau) |

New `VOLTAGE_TABLE` (mV → %): 12900→21, 12970→30, 13010→34, 13050→37, 13110→38, 13130→46,
13150→51, 13170→57, 13190→64, 13210→68, 13230→70, 13290→71. Cutoff 13.30 V, margins 10 / 3 and the
30-minute rest are unchanged.

## Limits

- 12.90 V → 21 % is extrapolated; nothing rested below 12.97 V.
- 13.12–13.18 V is the steep middle: 20 mV spans ~10 points and rest length matters (at 13.15 V a
  30–90 min rest reads 57 %, a ≥ 2 h rest 43 %). The table uses all rests ≥ 30 min, which leans low.
- Truth assumes a taper-ended charge ends at 105 Ah and that drift accrues evenly in time.
- Only the 2012 packs. The spares have never been charged in the data.

## Standby-drain estimate (built the same day)

`estimate = raw − correction − rate × days since the last full charge`, anchored at every full
charge and with the rate learned there from the Ah delivered (details in `CLAUDE.md`). Backtest:
rate learned from 2026-07 on, scored at the start of each of the 24 full charges since 2026-09-01
against `105 − Ah delivered`:

| Pack | Model error, mean | mean \|error\| | worst | Raw counter error, mean | worst |
|---|---|---|---|---|---|
| 2012-A | −1.0 Ah | 1.3 Ah | −3.4 Ah | +17.5 Ah | +27.0 Ah |
| 2012-B | −0.8 Ah | 1.0 Ah | −2.5 Ah | +15.4 Ah | +24.3 Ah |

Negative is the estimate reading low. The largest errors are the last three charges, as the rate
fell from ~3.3 to ~2.4 Ah/day faster than a 0.5 EMA follows.

## Implication

- Spares now read by this table come out at ~37–47 % (13.06–13.13 V), not 57–66 %.

## Prod repair (done 2026-11-25)

151 300 `samples` rows carried the garbage capacity (2024-A 2026-10-20…22; 2012-A and 2012-B
2026-10-23…24 and 2026-11-25 04:35…13:49 UTC). Each episode had the same good value on both sides
(103.45 Ah for 2024-A, 105.00 for the 2012 packs), so the rows were set to it in one transaction
that aborted unless every count matched. The 220 `samples_rollup` buckets whose capacity averaged
over 1000 Ah were re-rolled with the app's own upsert; none remain. The pending-correction values
the bad rows encoded are kept in `docs/data/bms-reanchor-episodes-2026-10-11.csv`. The phone's own
Room database still holds the bad rows for 2026-11-25 until they age out after 14 days.
