# Calibration check-in, 2026-11-26: first charge of a spare base (2016)

Unplanned measurement. Base 2016 had not been charged or used since July and its estimate had
fallen to 37 %. The owner charged it fully on the 24 V charger. It is the first charge of a spare
in the data, so the first check of the rest-voltage table on packs other than base 2012. It also
found the counter's ceiling (fixed the same day) and a new end-of-charge state on 2016-B.

## The charge (UTC)

| | 2016-A | 2016-B |
|---|---|---|
| Rest voltage, last 5 min before plug-in (shelved since July) | 13.058 V | 13.069 V |
| Cell spread at rest | 1.6 mV | 4.2 mV |
| BMS counter at plug-in | 100.40 Ah (99 %) | 100.76 Ah (99 %) |
| Estimate shown | 37.38 Ah (37 %, `voltage`) | 37.68 Ah (37 %, `voltage`) |
| Charge | 16:24:10 → 00:49:51 (8 h 26 min) | same |
| Ah delivered (own current sensor; largest frame gap) | 69.15 (22 s) | 65.97 (31 s) |
| Bulk current | 8.28 A | 7.89 A |
| Counter pinned at 120.00 Ah from | ~18:30 | ~18:30 |
| Peak voltage / cell max | 13.916 V / 3.493 V | 14.370 V / 3.651 V |
| Cell spread at the peak | 25 mV | 114 mV |
| State after the last charging frame | Idle | **Disabled** |
| Counter after | 120.00 (no full-detect) | 120.00 → 105.00 |
| `full_charge_ah` after | 100.44 | 100.80 → 105.00 |
| Estimate after | 100.44 Ah (99 %, `counter`) | 105.00 Ah (100 %) |

Cells 24–29 °C.

## Findings

1. **Truth at plug-in: 31–37 %, against the table's 37 %.** Full capacity minus Ah delivered:
   - 2016-A: 100.44 − 69.15 = 31.3 Ah (31 %); 35.9 Ah (34 %) if its capacity is really 105.
   - 2016-B: 105.00 − 65.97 = 39.0 Ah (37 %) on the capacity its BMS set at full-detect; 34.8 Ah
     (35 %) on the 100.80 it carried before.

   The two BMSs read the same series current 4.8 % apart (base 2012's differ by 3 %), which alone
   is 3 Ah. So at 13.06–13.07 V after months of rest the table was between right and 6 points
   high. High is the unsafe direction, and it agrees with the 2026-11-25 note that a longer rest
   reads lower at the same voltage. One charge with a 6-point band is not a basis for a refit:
   **`VOLTAGE_TABLE` unchanged.** Read the other spares (2023 at 45–48 %) as possibly a few
   points lower.
2. **The display froze at 57 % for six hours.** The counter started at ~100 Ah, reached the BMS's
   120 Ah ceiling 19.5 Ah into the charge and stopped; `counter − correction` stopped with it, and
   so did the ETA (it read ~370 min until the end). Until the ceiling the counter's rise matched
   current × time within 0.01 Ah (and within 0.02 Ah in ~50 on base 2012's November charges).
   Fixed the same day: `ChargeSeg.uncountedAh` (`CLAUDE.md`, "The counter's 120 Ah ceiling").
3. **Charge ETA.** With the fix the phone counts the Ah still missing at the live current plus
   2.5 min of taper for a pack that carries an estimate, with no learned tail (`CLAUDE.md`,
   "Charge ETA on an estimated pack"). Basis, 30 full charges of both 2012 packs 2026-09…11: the
   last 10.5 Ah took 80.2–84.3 min against 78.3–80.8 at the bulk current.
4. **2016-B ended in `Disabled`.** At a cell maximum of 3.651 V its BMS went from Charging to
   `Disabled` (state 0x0004) in one frame, reset its counter 120.00 → 105.00 and its
   `full_charge_ah` 100.80 → 105.00. The charger was at 2.4–2.6 A, where it also cuts off on base
   2012, so which of the two ended the charge is not known. 2012-A's full-detect (3.62 V) ends in
   Idle. **Discharge is not blocked:** two Discharging frames at 00:52:32–43 passed current, with
   the state back at `Disabled` after them. Still `Disabled` six minutes later at a cell maximum
   of 3.527 V; when it clears is open (`docs/checkins/NEXT.md`).
5. **2016-A missed its full-detect**, as 2012-B does. It crossed 13.7 V 124 s after B (0.28 Ah at
   8.2 A), so it is nearly as full, but it peaked 0.45 V lower: one cell of B runs ~0.1 V ahead of
   its neighbours at the top (spread 114 mV) and takes B's voltage with it. A's counter stays at
   120. The estimator still called the charge full: A's taper read 13.90–13.92 V under 3 A from
   00:48:48, 16 mV over `TAPER_MIN_V`. It will not always clear that threshold.
6. **Predicting the end.** The voltage of the 2026-11-26 base-2012 charge was a good clock: at
   13.48 V those packs had 198–224 min left, and base 2016 had 237 min left at the same voltage.

## Decisions

- `VOLTAGE_TABLE` unchanged (finding 1).
- Counter ceiling and ETA fixed in the app, installed 2026-11-27 00:52 UTC.
- Nothing done about `Disabled` or A's missed full-detect; both are in `docs/checkins/NEXT.md`.
