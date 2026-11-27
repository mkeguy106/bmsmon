# Open check-in items

The single dated list of open measurement, verification and decision items for bmsmon. Each item
says what "done" means and where its method lives. When an item closes, delete it here and record
the result where durable knowledge belongs: CLAUDE.md for constants and invariants, or a dated
`docs/calibration-checkin-YYYY-MM-DD.md` write-up for a check-in pass. Last reviewed: 2026-10-02.

**Phone rules for every on-device item** (CLAUDE.md, "The Pixel is a dedicated telemetry device"):
ADB reaches the Pixel only on home Wi-Fi; airplane mode stays **ON** with Wi-Fi and Bluetooth
re-enabled on top; never toggle radios right before an outing; never uninstall `dev.joely.bmsmon`;
after any install, `am start` the activity — the screen is the product.

## Calibration check-in #3 — re-dated 2026-10-16 (slipped from ~2026-09)

| # | Item | Done when | Method / basis |
|---|---|---|---|
| 1 | **AR power-cost keep/revert.** Revert condition stands: if periodic Activity Recognition costs more than the GPS pause saves, the motion gate is a net loss and reverts. | Decision recorded (keep or revert) with ≥3 comparable unplugged, screen-normalized stretches against the 2026-08-03 baseline, plus GNSS duty from batterystats. | `docs/ar-power-cost-protocol.md` (day-0 baseline 2026-08-09; 2026-08-10 is the first comparable day) |
| 2 | **Motion-column wire cost.** The four motion columns ride every pack's row (8× per reading). | Real gzipped batch-size delta measured; if material, adopt the documented fallback (populate them only on the staged base's rows). | CLAUDE.md, the motion-state paragraph under "Cloud Server & Deployment" |
| 3 | **Re-quantify the GNSS-off duty and saving under the silence-as-stillness gate.** The recorded ≈15 mA assumed the discharge-only era's 68.4 % duty; the new gate holds GNSS off through whole parked nights. | New duty % and mA figure recorded in CLAUDE.md's battery-saver section. | batterystats gnss time is the primary measure (`docs/ar-power-cost-protocol.md`, decision input 1), cross-checked against prod `motion_still` rows. `docs/calibration-checkin-2026-08-04.md` §6 shows only how the old 68.4 % was derived (share of minutes with no discharge for `PARKED_HOLD_MS`). |
| 4 | **whPerMile re-verify** on the corrected current-sign basis as outing days accumulate. | Learned bands recomputed from the cloud dataset and compared with `device_range_config`; seeds left alone unless they disagree materially. | `docs/calibration-checkin-2026-08-04.md` §2 |
| 5 | **Charge finish-hour histogram** (standing trigger for the depth-aware charge tail). | Histogram re-run; reopen the depth-aware tail only if sessions start finishing 08:00–23:59 from a low start SOC. | `docs/calibration-checkin-2026-08-04.md` §4 |

Write the pass up as `docs/calibration-checkin-YYYY-MM-DD.md`, like 2026-08-04.

## Calibration check-in #4 — due 2026-11-16

Re-run the standing items (4 and 5 above) plus whatever #3 leaves open, and re-verify the gauge
constants the way the 2026-08-04 pass did (`POWER_RING_FULL_W`, `REGEN_EPS`/`REGEN_WINDOW_MS`,
`SEED_TAIL_MIN`, GPS gates).

**Pack-to-pack balance (series pairs).** Added 2026-11-12 after 2012-B missed its BMS full-detect
on the 2026-11-11 charge (peaked 13.977 V; counter pinned at the 120 Ah ceiling, SOC stuck at 99 %).
The 24 V charger holds the pair at ~28.37 V and terminates on taper (~2 A), so the fuller pack takes
the larger share of the voltage; no BMS protection trips. Nothing in a series pair equalizes the
two packs: each BMS balances only its own cells.

- **Metric (per charge, per base):** lag between the two packs first crossing 13.7 V while
  `Charging`, × the CC current (~7.8 A) = Ah imbalance. Also record each pack's peak `voltage_v`
  and `cell_max_v`.
- **2012 baseline:** 98 s / 0.21 Ah (10-02), 117 s (10-15), 125 s (11-04), **152 s / 0.33 Ah
  (11-11)**. 2012-B's peak fell 14.07 → 13.98 V. 2012-A's peak rose 14.29 → 14.40 V, cell max
  3.599 → 3.622 V. 2012-B's full-detect sits between 13.977 V (missed) and 14.028 V (hit 11-04).
- **Done when:** the lag is re-measured for every base with charges since, and the trend is
  recorded. Act when 2012-B misses full-detect on two consecutive charges, or when 2012-A's
  `cell_max_v` reaches 3.64 V. The owner can't reach the packs, so any fix is a service-visit job:
  either a one-off 12 V LiFePO4 top-up of the low pack, or (preferred, since it never needs another
  visit) a fused pack-to-pack balancer. The balancer's current must stay inside the BMS's 1.04 A
  deadband, so it can't read as discharge. Status 2026-11-12: a technician install guide for a
  Victron Battery Balancer on 2012's existing bolted harness exists (owner's Claude Docs); the
  owner hasn't decided whether to buy it.
- **2026-11-16 charge: the act trigger is MET.** 2012-B missed full-detect a second time in a
  row (peak 13.969 V; counter pinned at 120 Ah, SOC 99 % at Idle; 2012-A cleared to 105 Ah /
  100 %). Lag 154 s (flat vs 152 s). 2012-A cell max 3.625 V. Between misses B's counter runs
  ~15 Ah high: on 11-16 before the charge, A read 80 % / 84.4 Ah and B 94 % / 98.9 Ah. Through
  11-10 the two packs had read within 1 %. The base range figure is bounded by the weaker pack,
  so it follows A and is unaffected. B's own SOC reads high.
- **Query:** `min(ts) filter (where voltage_v >= 13.7)` per address per charge night, over
  `state = 'Charging'` rows (worked example in the 2026-11-12 session).

**Spare packs self-drain where the BMS counter can't see it.** Found 2026-11-18. 2016-A/B and
2023-A/B have read 99 % since July with no charge or use, while their rest voltage fell from
~13.32 V to 13.11–13.14 V. 2016-A's counter moved 100.44 → 100.40 Ah over the whole period. On
the daily drivers' rest-voltage curve that is roughly 55–65 %. 2024-A already rested at 13.13 V in
July while reading 99 %. The drain is sub-deadband (estimated 10–17 mA, ~0.3 Ah/day). The phone's
persistent BLE links to the spares might add to it, but that hasn't been tested. The spares are
evidently not on live chargers, though `share.py` and the stage logic assume they are.
- **Voltage → SOC (measured 2026-11-18, 2012 packs, held-out days):** rest MAE 3.6 pts (p90 7.5),
  driving with current+temp MAE 4.1 (p90 8.2), against 8.4/10.5 for guessing the median. It holds
  only from 40 to 100 %: just 2 days ever went below 40 %, so the low knee is uncalibrated.
- **App-side decision: DONE 2026-11-18** — built as the estimated-SOC feature
  (`docs/superpowers/specs/2026-11-18-soc-estimate-design.md`; "Estimated SOC" in `CLAUDE.md`): the
  phone shows rest-voltage-corrected SOC (`~58 %`-style) with the BMS reading muted beside it, so the
  spares no longer promise 99 %.
- **Still open — the spares need charging** (or their chargers checking). The estimate makes the
  shortfall visible; it does not fix the packs, and the drain continues until they are charged.
- **Done when:** the spares are recharged or their chargers checked (the cross-check decision is made).

**Rest-voltage → SOC table: measure below 12.97 V.** Added 2026-11-18, re-scoped 2026-11-25. The
table was refit on 2026-11-25 against charger-delivered Ah (`docs/calibration-checkin-2026-11-25.md`);
the first fit used the BMS counter and read ~15–20 points high. What is still not measured is the
low knee: the 12.90 V → 21 % point is extrapolated, and 12.97–13.11 V rests on a handful of rest
episodes. The owner will run base 2012 down to about 20 % a few times to supply the data.
- **Where it lives:** `VOLTAGE_TABLE`, `VOLTAGE_PLATEAU_V` (13.30 V), `FIRST_VOLTAGE_MARGIN_PTS` (10) and
  `FOLLOW_DOWN_MARGIN_PTS` (3) in `android/app/src/main/java/dev/joely/bmsmon/model/SocEstimate.kt`
  (tests in `SocEstimateTest.kt`; the table is also in `CLAUDE.md`'s "Estimated SOC").
- **Method (do not use `remaining_ah / full_charge_ah` as truth):** between two full charges, true
  remaining = counter − drift, with the drift fixed at the next charge start by the Ah the charger
  delivers and spread linearly in time. Skip intervals where the BMS re-anchored (`pendingAh`).
- **Done when:** rests below 12.97 V are in the cloud data and the table's low end is re-fit.

**Decide whether to upload `pendingAh`.** Added 2026-11-25. The BMS's pending counter correction
is parsed on the phone but is not in Room or the upload, so a re-anchor shows in prod only as
`est_remaining_ah − remaining_ah`. Until the parser fix the value leaked into `full_charge_ah` (as
~40 million Ah); those 151 300 rows were repaired on 2026-11-25 and what they encoded is kept in
`docs/data/bms-reanchor-episodes-2026-10-11.csv` (10-minute buckets, five episodes).
- **If yes:** a nullable `samples` column (server first, `add_column_if_missing`), `SampleJson` /
  `SampleIn`, and a Room column so the one-time replay can see a re-anchor.
- **Done when:** decided, and built if yes.

**Standby-drain estimate: confirm it in the field.** Added 2026-11-25 (`DrainModel`, "Estimated
SOC" in `CLAUDE.md`). Installed while both 2012 packs were mid BMS re-anchor, so it has no anchor yet.
- **First full charge after install:** both packs should anchor (`est_soc` = BMS reading right
  after, then falling ~2.5 Ah/day below the counter). 2012-B is only "full" to the estimator while
  its taper reaches `TAPER_MIN_V` 13.90 V under 3 A; it read 13.95–13.96 V on 2026-11-17 and has been
  falling ~10 mV a charge. If B stops anchoring, lower the threshold or anchor B off its partner.
- **Second full charge:** the first learned rate. Check it against the SQL figure (counter at charge
  start − (105 − Ah delivered), over the days between).
- **The BMS's re-anchor overshoot:** the first full charge after 2026-11-25 is the second
  measurement (October's was 7–11 Ah low). If it agrees, decide whether the estimate may read above
  a freshly re-anchored BMS.
- **Charge ETA:** decided 2026-11-26. An estimated pack's ETA is now the Ah still missing at the
  live current plus 2.5 min, with no learned tail (`CLAUDE.md`, "Charge ETA on an estimated pack").
  **Check at the next full charge of base 2012:** `eta_full_min` against the real time left, hourly
  and in the last 30 min. Expect within ~10 min and a little long (the estimate ran ~1 Ah low in
  the backtest). If it is not, look at the estimate's error at charge start before touching the 2.5.
- **Counter ceiling:** 2026-11-26, base 2016's first charge froze the display at 57 % for six hours
  (counter pinned at 120 Ah). Fixed the same day (`ChargeSeg.uncountedAh`). **Check at the next
  charge of a spare** (2023 or 2024, both read 99 % on the BMS at a real ~45 %): `est_soc` keeps
  rising after `remaining_ah` reaches 120, and ends within a few points of 100 before the taper.
- **Done when:** two full charges are in, the learned rate matches SQL within 0.5 Ah/day, and the
  ETA decision is made.

## Opportunistic — next time the code is touched

- **Settings › Battery saver "Motion sensing active" line:** both permission states (granted /
  denied) have never been confirmed on-device. Done when each state has been seen on the Pixel.
- **`foldMotion` test:** pin "a run starts at the reading's own `atMs`" (immediate close on an old
  reading) next time `model/BatterySaver.kt` is edited.
- **Cosmetic deferrals** from `.superpowers/HANDOVER-2026-08-10.md` item 6 (`_clip_motion_at`
  lower-bound test, a long `sampleJson` line, two phrasing imprecisions, `MotionSource` late-callback
  guard) — batch with adjacent work; never blocking.

## Architecture review follow-ups

The Tier 2–4 roadmap (and the status of every Tier-1 item) is tracked in the 2026-10-02
architecture review (kept out of the public repo), §Fix roadmap — that table, with its Status
column, is the tracker; don't duplicate its rows here. Its one-off sizing checks are listed there
under "Read-only checks the owner can run".
