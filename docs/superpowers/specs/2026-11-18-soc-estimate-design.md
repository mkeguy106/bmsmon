# Estimated state of charge ("our estimate") — design

Date: 2026-11-18. Status: draft for owner approval (builder check done; replay chosen).

## Why

The BMS's own charge reading is wrong in two ways that have been seen on this fleet, and the owner
relies on the app (not the chair's gauge) to avoid being stranded.

1. **Missed full-detect (2012-B).** The 24 V charger ends on taper, so in a drifting series pair
   the lower pack can miss its BMS full-detect. Its coulomb counter then pins at the 120 Ah
   ceiling instead of clamping to `full_charge_ah` (105), and reads high by the difference
   (≈15 Ah) until the next charge that does hit full-detect. It happened on the 2026-11-10 and
   2026-11-16 charges. On 2026-11-18 18:13Z 2012-B read 99 % / 111.84 Ah while its partner 2012-A
   read 92 % / 97.03 Ah; 111.84 − 15 = 96.84, matching A.
2. **Silent self-drain (spares).** The counter does not integrate current below the BMS's
   ~1.04 A deadband. The spares (2016-A/B, 2023-A/B) have read 99 % since July with no charge,
   while their rest voltage fell to 13.10–13.14 V, roughly 55–65 %. If the owner swaps onto a
   spare base, the app would promise ~40 points more than is there.

The owner wants the app to show and act on **our estimate**, with the BMS reading still visible
but less prominent.

## Goals

- One per-pack estimated SOC (%) and remaining Ah, computed on the phone.
- It drives every display and every decision: the stage ring and number, All Batteries, Detail,
  capacity alerts and notifications, the low-pack seize, range/runtime/miles, charge time-to-full,
  WebUI "packs ready" / need-recharge / fleet capacity, the recharge plan, and the widgets.
- The BMS reading stays on screen **all the time**, in a fixed spot, muted (layouts below).
- A voltage-based estimate is marked with a leading `~` (coarser); a counter-overshoot correction
  is shown without it (exact).
- It works for the swap case: a spare corrected to ~60 % at home counts down from ~60 when
  driven, not from 99.

## Non-goals

- Fixing the packs. The pack-to-pack balancer / top-up stays the owner's separate decision
  (NEXT.md). This only stops the app from repeating the BMS's mistake.
- Rewriting history. Raw BMS fields are never overwritten, on the phone (Room `samples`) or in
  the cloud (`samples`). The History view's trends (rollup) and the charge-session log stay raw.
- A general SOC model below 40 %. The voltage table is calibrated only from 40 to 100 %.
- Sending anything to a battery. No new BLE commands; this is pure arithmetic on `0x13` data.

## Anti-goals (failure even if it "works")

- An estimate **above** the BMS reading. The estimate only ever lowers a reading.
- Numbers or lines that jump position depending on whether the two readings agree (the owner
  rejected a conditional caption because it moved the voltage line).
- A correction that silently vanishes when the chair starts moving.
- Losing the raw reading: the charge-tail learner, range learner inputs that are raw today, the
  Room log and the cloud `soc`/`remaining_ah` columns must stay the BMS's own numbers.

## Approaches considered

- **A. Phone computes, uploads; server stores and passes through; web and widgets display.**
  One implementation of the estimator (Kotlin), one source of truth. Web and widgets only
  choose `est ?? raw`. Picked by the owner.
- B. Each client computes its own estimate from raw telemetry. Three implementations (Kotlin,
  TS, QML) of a stateful estimator that needs charge history; the widget has no history at all.
  Rejected: they would disagree.
- C. Server computes from the samples stream. Works for web and widgets, but the phone is the
  safety display and would need it too, so the phone would still need its own copy (or a
  round-trip it can't rely on away from Wi-Fi). Rejected.

## The estimator (phone)

New pure module, e.g. `model/SocEstimate.kt`, JVM-tested, same shape as `PowerPolicy`/
`BatterySaver` (no Android imports, no clock of its own).

### State per pack (persisted)

```
correctionAh: Float   // ≥ 0. estimateAh = rawRemainingAh − correctionAh
source: COUNTER | VOLTAGE | null
setAtMs: Long
```

Persisted in `SettingsStore` keyed by address (like `charge_tail_run_end_by_address`), so it
survives restarts. Rest tracking (below) is in memory only.

### Output per reading

```
estRemainingAh = clamp(raw.capacityAh − correctionAh, 0, raw.fullChargeAh)
estSoc         = estRemainingAh / raw.fullChargeAh × 100      (rounded like the BMS: integer %)
estSource      = source when correctionAh > 0, else null
```

When `correctionAh == 0`, the estimate equals the BMS reading except that the BMS clamps SOC at
99 while remaining > full; the estimate keeps the BMS's `soc` in that case (no correction ⇒
no change). Never output more than the BMS's `soc` or `capacityAh`.

### Rule 1 — counter overshoot (exact, `COUNTER`)

The counter keeps counting while a pack charges (it routinely reaches 111–113 Ah during a normal
tail), so a held correction stays right through a partial charge. Only a charge that really
reached full may replace or clear it. *(Builder-check fix, my call: the first draft replaced the
correction at every charge end, which would have shown a half-charged spare as full.)*

- **Charge end** = the first non-Charging reading after Charging (regen excluded via the existing
  regen window).
- **A full charge** = the charge end was preceded by the charger's taper: within the last 15 min
  of Charging, pack voltage ≥ 13.90 V and current fell below 3 A. (2012-B's missed charges
  peaked at 13.969/13.977 V on taper; a normal full charge peaks higher.)
- At the end of a **full** charge:
  - `remaining` within 0.1 Ah of `full` (the BMS's full-detect clamp) → **clear**
    (`correctionAh = 0`).
  - `remaining > full + 0.5` (missed full-detect; counter pinned or still high) →
    `correctionAh = remaining − full`, source COUNTER, replacing any earlier correction.
- At the end of a **partial** charge: keep the correction, raised to at least `remaining − full`
  when that is larger. If the counter pinned at the ceiling (≥ 119.5 Ah) mid-charge it stopped
  counting, so the estimate under-reads; that is the safe side and the next rest or full charge
  fixes it.
- **Missed charge end** (phone off or out of range): any non-charging reading with
  `remaining > full + 0.5` raises the correction to at least `remaining − full` (a lower bound:
  after driving, 2012-B at 111.84 shows 6.84; the real offset is 15). And a sighting where the raw
  counter has **risen by more than 2 Ah** since the pack's previous sighting with no Charging
  reading in between means a charge happened unseen: if `remaining` is within 0.1 Ah of `full`,
  clear; otherwise apply the lower bound. *(Builder-check fix, my call: without this a spare
  charged while the phone was away would keep its old correction forever. A rested-voltage clear
  was rejected: a pack that missed full-detect rests at ~13.35 V too, so voltage can't tell a
  full pack from 2012-B.)*

### Rule 2 — rest voltage (coarse, `VOLTAGE`)

- **Rested** = every reading of that pack this session has had `current == 0` for ≥ 30 minutes
  (the deadband means idle reads exactly 0.000 A). Tracked in memory from the first zero-current
  reading; any nonzero reading or a data gap > 10 minutes restarts it. Spares are polled every
  10 s (`SLOW_POLL_MS`), so a spare becomes rested 30 minutes after the phone first sees it.
- **Voltage → SOC table** (pack voltage, rested ≥ 30 min after discharge, 2012 packs,
  2026-11-18 analysis; median SOC per 0.02 V, made monotone; linear between points):

  | V | 12.90 | 12.98 | 13.00 | 13.04 | 13.10 | 13.14 | 13.18 | 13.20 | 13.28 |
  |---|---|---|---|---|---|---|---|---|---|
  | % | 28 | 48 | 51 | 56 | 58 | 66 | 72 | 76 | 80 |

  Below 12.90 V use 28 % as a ceiling (the knee is uncalibrated; anything lower only makes the
  estimate lower, the safe side). Measured accuracy at rest: MAE 3.6 pts, p90 7.5, 40–100 % only.
- **Not applied at or above 13.30 V.** That is the top plateau, where a rested pack can be
  anywhere from ~78 to ~95 % (13.32 V: p10 78, p90 93). On 2026-11-18 2012-A read 92 % at
  13.325 V; the table would have said ~83. The spares sit at 13.10–13.14 V, well below.
  *(My call, from re-checking the margin against today's readings.)*
- **First correction:** apply only when `voltageSoc < currentEstSoc − 10` (above the p90 error,
  so normal noise never moves a daily driver). Then
  `correctionAh = raw.capacityAh − voltageSoc/100 × full`, source VOLTAGE.
- **Once VOLTAGE-sourced, follow it down:** on each later rested reading, lower the estimate to
  `voltageSoc` whenever that is more than 3 points below it. A draining spare then tracks its
  voltage instead of reading up to 10 points high. *(Builder-check fix, my call.)*
- Voltage only ever increases the correction (lowers the estimate). Post-charge surface charge
  reads high, so it can't fire falsely after a charge.

### First start: replay the phone's history (owner's choice)

The moments that set today's corrections (2012-B's 11-10 and 11-16 charge ends) have already
passed, so the estimator seeds itself once from the phone's Room `samples` (14 days, usage logging
is on). Owner chose this over starting fresh, so 2012-B is right from the first minute after the
install.

- Runs once, when no persisted estimator state exists (first install of this version, or after
  Clear data). A persisted "seeded" flag stops it running again.
- Streams each pack's rows in `(tsMs, id)` keyset pages (the `forEachRangeRow` pattern; never a
  list) through the same pure estimator the live path uses, in time order, telemetry rows only
  (`linkEvent IS NULL`). Room has every column the rules need: `state`, `currentA`, `voltageV`,
  `remainingAh`, `fullChargeAh`, `regen`, `tsMs`.
- Rest tracking during replay uses the rows' own timestamps and the same 10-minute gap rule.
- Off the main thread, before the engine's first estimate is published; until it finishes, the
  estimate equals the BMS reading (the pre-feature behaviour), never something higher.
- A failure is logged and leaves state empty (estimate = BMS), never a crash; it retries on the
  next start.
- The replay is also the end-to-end check: its JVM test feeds a recorded slice of the real
  11-16 charge (pulled from prod `samples`) and expects 2012-B's correction ≈ 15 Ah.

### Holding

The correction is held while driving and charging and only changes through the rules above. This
is what makes the swap case right: a spare at counter 100.4 Ah with a 39 Ah correction drives
off showing ~60 % and counts down with the counter. It clears at the next real full-detect.

### Expected results on today's fleet

- 2012-B: COUNTER correction 15 Ah, set by the replay from the 11-16 charge end (pinned at
  120 Ah, taper at 13.969 V) → ~92 %, shown without `~`, "BMS 99%" beneath.
- 2012-A: no correction, 92 % = BMS.
- 2016-A (13.099 V), 2016-B (13.112 V), 2023-A/B (13.13–13.14 V): VOLTAGE corrections → ~58–63 %,
  from the replay at once (they have rested in the history).
- 2024-A/B: not seen since 2026-11-08; whatever their last state was, they keep it.

## Where the estimate is used (phone)

The swap happens at the engine's per-pack status boundary: `BatteryStatus` (model/Fleet.kt)
carries the estimate next to the raw telemetry, and everything that decides or displays reads
the estimated values. Builder's call how (my suggestion: `BatteryStatus.telemetry` becomes the
effective telemetry with `soc`/`capacityAh` replaced, plus a `bms: Telemetry` raw copy and
`estSource`, so the ~40 existing `.soc` reads switch over without being touched one by one;
then grep that nothing that must stay raw reads the effective copy).

Uses the estimate: stage, All Batteries, Detail, `Alerts.kt`/`reconcileFleetNotifications`,
`seizeCandidate`, `stageAlert`, `socSeverityFor` colouring, `RangeEstimate` (remainingAh),
`ChargeEstimate` (time-to-full from the estimated Ah), the ongoing notification's "lowest".

Stays raw: Room `samples` logging, the upload's `soc`/`remaining_ah`, `ChargeTailLearn`,
`RangeLearn` (it learns from energy, not SOC; confirm), session rollups and History.

## Upload, server, API

- `SampleJson` / `SampleIn` gain three optional fields: `est_soc` (real), `est_remaining_ah`
  (real), `est_source` (text, `counter` | `voltage`, else null). Sent on live batches for every
  telemetry row; null when no correction. Re-sync from Room does not carry them (same as
  `eta_full_min`), which only affects old rows.
- `schema.sql`: three `pg_temp.add_column_if_missing('samples', …)` lines. Through `_COLS`,
  `sample_row()`, the fleet snapshot (`/web/fleet`), and the `/ws` broadcast, like
  `eta_full_min`. Unknown `est_source` values clamp to null.
- `/api/v1/groups`: add the three names to `PACK_FIELDS` in `routers/api_widget.py`. `soc`
  stays raw there; the widget chooses. The server-side status ladder (`_status()`, which reads
  `soc ≥ 99` as a ready backup) uses `est_soc ?? soc`.
- Not in the rollup, trends, charge sessions or share feed (the guest dock's CAP may use
  `est_soc ?? soc`; builder's call, low stakes).
- **Deploy order:** server first, then the APK (an old server ignores the unknown keys, so a
  wrong order only loses the fields; still, server first).

## WebUI (v2 and v1)

- Swap once at the decode/store boundary (`web/src/decode.ts` / `store.ts`): each fleet row's
  `soc` / `remaining_ah` become `est_* ?? raw`, and the raw values are kept as `bms_soc` /
  `bms_remaining_ah`, plus `est_source`. Every v2 and v1 consumer (stageBase seize, alerts,
  health tiles, recharge, dock, efficiency, baseView, v1 MainStage/PackCard/stage) then follows
  the estimate unchanged.
- Display, option **A (muted line)**, always shown:
  - Command stage pack: under the W · A line, a muted `BMS 99%`.
  - Capacity tile: `98.1/105 Ah` with a muted `BMS 113.1 Ah` sub-line.
  - Fleet rail row: `93%` then a muted `bms 99`.
  - A voltage estimate is prefixed `~`.
  - Fleet Health per-pack board: same muted `bms NN` beside the pack's %.
- When a row has no `est_*` (older data, or no correction), the muted line still shows (BMS = ours),
  so the layout never changes.

## Android displays (option A)

- **Stage:** ring and big number are ours (`~` when VOLTAGE). Under the pack name, a muted
  `BMS 99%` line, always present. Capacity tile shows our Ah with a muted `BMS 113 Ah` sub-line.
- **All Batteries list (option 2):** the header's big % is ours. The capacity line reads
  `CAPACITY [bar = ours] 98 Ah   bms 99% · 113`, with the muted BMS text at the end of the line,
  always present, so rows keep today's height and every row's BMS text sits in the same place.
- **Detail:** same as the stage: ours prominent, BMS muted beside it.
- The severity colour/LOW/CRIT word follows our estimate.

## Desktop widgets (option A, always on)

`~/joely-pc-cachyos-settings`, plasmoid `com.joely.bmsgroup` (`main.qml`, `PackRing.qml`).

- Ring and number: `est_soc ?? soc`, `~` prefix when `est_source == "voltage"`.
- Under the pack letter, a dim `bms NN` line on **every** pack, always, so voltage and current
  sit at the same height in every card.
- `lowSoc` / LOW tag / `flashSoc` flashing use our estimate.
- The tooltip line (`main.qml` ~159) shows ours and `bms NN`.

## Testing

- Phone: JVM tests for the estimator with replays of the real cases: 2012-B's 11-16 charge end
  (pinned 120 → correction 15) followed by driving; the missed-end lower bound; a full-detect
  clear; a spare at 13.099 V rested 30 min → ~58 %; a daily driver at rest within the margin →
  no change; post-charge 13.34 V → no change; the swap (correction held through discharge);
  only-lowers invariant as a property over random inputs. Persistence round-trip. The replay:
  runs once, pages (no list read), survives a throwing row, and on the recorded 11-16 slice
  yields 2012-B ≈ 15 Ah.
- Server: ingest stores the three fields, garbage clamps to null, `/web/fleet` and
  `/api/v1/groups` expose them, GPS-exclusion test still passes.
- Web: decode swap tests (est present / absent), seize and alerts follow the estimate, and a
  render test that the muted BMS line is present in both the agree and disagree cases.
- Widget: manual check on the desktop against prod.
- On device after install: 2012-B shows ~92 % with "BMS 99%"; spares show `~6x%`.

## Docs to update on ship

CLAUDE.md (a short "Estimated SOC" section: the two rules, the table, only-lowers, what stays
raw, deploy order), NEXT.md (close the spares decision item; the spares still need charging),
the Obsidian note, and the widget repo's notes.

## Left to the builder

- The exact mechanism of the `BatteryStatus` swap (suggestion above).
- Whether `RangeLearn` reads SOC anywhere (it should stay raw/energy-based).
- The guest share dock's CAP (estimate or raw).
- Exact typography of the muted lines, within each app's existing secondary-text tokens.
