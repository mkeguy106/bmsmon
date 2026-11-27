# Calibration check-in, 2026-11-26: first low run of base 2012

Planned measurement. The owner ran base 2012 down to about 25 %, rested it for one hour and charged
it fully. It is the first rested point below 12.97 V and the second measurement of how far the
BMS's re-anchor target sits below the truth. No constant changed.

## The run (UTC)

| | 2012-A | 2012-B |
|---|---|---|
| Last current | 02:41:18 | 02:41:18 |
| Rest voltage, last 5 min before plug-in (61 min rested) | 12.889 V | 12.866 V |
| Cell spread at rest | 7.7 mV | 8.1 mV |
| BMS counter at plug-in | 21.67 Ah (20 %) | 23.27 Ah (22 %) |
| Estimate shown | 18.0 Ah (17 %, `voltage`) | 18.0 Ah (17 %, `voltage`) |
| Charge | 03:42:49 → 13:34:47 (9 h 52 min) | same |
| Ah delivered (own current sensor, largest frame gap 2.5 s) | 79.21 | 76.81 |
| Peak voltage / cell max | 14.380 V / 3.621 V | 13.996 V / 3.524 V |
| Counter at charge end | 97.20 → clamped to 105.00 | 94.76, no clamp |

Cells 23–28 °C throughout.

## Findings

1. **Truth at plug-in, 2012-A: 105 − 79.21 = 25.8 Ah (24.6 %)** at a 12.889 V rest. A ended on a
   real full-detect, so this is the solid figure.
2. **The estimate read 7.8 Ah (8 points) low**, because both packs were still mid re-anchor from
   2026-11-25 04:35 and the estimate shows the BMS's target while `pendingAh < 0`. The target was
   18.0 Ah. The pending correction was applied during the charge: A's counter gained 75.5 Ah for
   79.2 delivered (3.7 short), B's 71.5 for 76.8 (5.3 short).
3. **The voltage table alone would have read 21 % (22.05 Ah): 3.7 Ah low.** The 12.90 V → 21 %
   point was extrapolated and is now bracketed by one measurement.
4. **The two charges that followed a re-anchor disagree about the table's sign.**

   | Charge | Voltage before plug-in (A) | True | BMS counter / target | `VOLTAGE_TABLE` |
   |---|---|---|---|---|
   | 2026-10-24 03:11 | 12.97–12.99 V (not a clean rest: last current 6 min before) | 28.9 Ah, 27.5 % | 24.5 Ah, 4.4 low | 30–32 %, 3–4 points high |
   | 2026-11-26 03:43 | 12.889 V (61 min rest) | 25.8 Ah, 24.6 % | 18.0 Ah, 7.8 low | 21 %, 3.6 points low |

   The BMS target is low both times; the table is within 4 points both times, once on each side.
5. **2012-B missed full-detect for the third charge in a row.** Its counter stopped at 94.76 Ah
   (SOC 90 %) on a pack that is full or nearly so: the two packs crossed 13.7 V 154 s apart
   (13:19:47 / 13:22:21), which is 0.34 Ah at 7.85 A, the same lag as 2026-11-16. By that measure B
   held about 25.5 Ah at plug-in and its BMS target (17.95 Ah) was 7.5 low, like A's. B has no
   full-detect, so its truth is inferred, not measured.
6. **Both packs anchored the drain model.** B's taper reached 13.97–14.00 V under 3 A from
   13:32:07. `est_remaining_ah` equalled the counter at charge end and fell 0.19 Ah in the next
   1.9 h (about 2.4 Ah/day). Whether a rate was learned is not visible from the server; none is
   expected, because the re-anchor had dropped the previous anchor.
7. **The two BMSs disagree about the same series current**: 79.21 against 76.81 Ah (3 %), A's
   sensor higher while charging, as in every charge since July.

## Decisions

- **`VOLTAGE_TABLE` unchanged.** Raising the bottom to fit this run would make October's error
  larger.
- **The re-anchor rule unchanged.** Showing the table instead of the BMS target would have read
  high in October, the unsafe direction. A third run decides.
- **Until then:** after a low run that triggered a re-anchor, the screen reads about 4–8 points
  below the truth. After this charge base 2012 reads about 10 points low (B's counter), expected to
  recover over the next one or two full charges as the counter drifts up.

## Next

The owner will repeat the run (down to about 20 %, one hour of rest, full charge). Open items are
in `docs/checkins/NEXT.md`.
