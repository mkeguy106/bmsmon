# Estimated SOC Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The phone computes a corrected per-pack SOC ("our estimate") that every display and decision uses, uploads it, and the WebUI and desktop widgets show it, with the BMS reading always visible but muted.

**Architecture:** A pure Kotlin estimator steps one per-pack state per reading (counter-overshoot rule + rest-voltage rule, holding an Ah correction). The engine swaps the estimate into `BatteryStatus.telemetry` (raw values kept on the same object as `bmsSoc`/`bmsCapacityAh`) while Room, the upload's raw columns and the learners keep the raw frame. Three new nullable sample columns carry it to Postgres, `/web/fleet`, `/ws` and `/api/v1/groups`; the web swaps once at decode; the widget picks `est_soc ?? soc`.

**Tech Stack:** Kotlin/Compose + Room + DataStore (android/), FastAPI + asyncpg + Postgres 16 (server/), React + TS + vitest (web/), KDE Plasma QML (~/joely-pc-cachyos-settings).

**Spec:** `docs/superpowers/specs/2026-11-18-soc-estimate-design.md` (read it first; this plan does not repeat its rationale).

## Global Constraints

- The estimate never exceeds the BMS reading: `estSoc ≤ raw.soc` and `estRemainingAh ≤ raw.capacityAh`, always.
- Raw BMS values are never overwritten: Room `samples`, the upload's `soc`/`remaining_ah`, cloud `samples.soc`/`remaining_ah`, `ChargeTailLearn`, `RangeLearn`, rollups, History.
- The BMS reading is shown **always**, in a fixed spot, muted; never conditional on disagreement.
- `~` prefixes a VOLTAGE-sourced estimate; COUNTER-sourced shows no prefix.
- Constants (exact): full-charge taper `V ≥ 13.90` and `current < 3.0 A` within the last `15 min` of Charging; clamp tolerance `0.1 Ah`; overshoot threshold `full + 0.5 Ah`; ceiling `119.5 Ah`; unseen-charge rise `> 2.0 Ah`; rest `current == 0` for `≥ 30 min`, gap reset `> 10 min`; voltage rule only when `V < 13.30`; first VOLTAGE margin `10` pts, follow-down margin `3` pts.
- Voltage table (V → %), linear between points, `28` below 12.90 V, no estimate at ≥ 13.30 V: `12.90→28, 12.98→48, 13.00→51, 13.04→56, 13.10→58, 13.14→66, 13.18→72, 13.20→76, 13.28→80`.
- Wire names: `est_soc` (real), `est_remaining_ah` (real), `est_source` (`"counter"` | `"voltage"` | null).
- Server deploys before the APK. Never uninstall the app; after `adb install -r`, `am start`; lift the lock-task pin first (bmsmon CLAUDE.md, "Installing while the app is in lock mode").
- Commit messages: no AI/Claude attribution (repo CLAUDE.md overrides).

## Review Focus

1. **Pack reconnecting after hours away mid-charge-cycle** (state flips Charging→Idle across a gap): must not count as a full charge unless the taper reading is within 15 min of the end → test `chargeEndAcrossGapWithStaleTaperIsPartial` (Task 1).
2. **Regen burst reporting `state=Charging` while driving** must never be a charge start/end → test `regenChargingIsNotACharge` (Task 1).
3. **Garbage `fullChargeAh` (0 or negative)** from a bad frame → estimator returns the raw reading and leaves state unchanged → test `zeroFullChargeLeavesStateAlone` (Task 1).
4. **Older server rows / old APK samples with no `est_*`** must render with the BMS line equal to the main number, layout unchanged → test `noEstimateKeepsLayout` (Task 7) and `applyEstimate_without_est_fields` (Task 6).
5. **Process restart during the replay** must not leave a half-seeded state marked done → test `replayFailureLeavesUnseeded` (Task 4).

---

### Task 1: Pure estimator

**Files:**
- Create: `android/app/src/main/java/dev/joely/bmsmon/model/SocEstimate.kt`
- Test: `android/app/src/test/java/dev/joely/bmsmon/SocEstimateTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  enum class EstSource { COUNTER, VOLTAGE }
  data class SocCorrection(val correctionAh: Float = 0f, val source: EstSource? = null, val setAtMs: Long = 0L)
  data class EstReading(val tsMs: Long, val state: BatteryState, val regen: Boolean, val currentA: Float,
                        val voltageV: Float, val remainingAh: Float, val fullChargeAh: Float, val soc: Float)
  data class EstPackState(val correction: SocCorrection = SocCorrection(),
                          val wasCharging: Boolean = false, val taperAtMs: Long? = null,
                          val restSinceMs: Long? = null, val lastTsMs: Long? = null, val lastRawAh: Float? = null)
  data class SocEstimate(val soc: Float, val remainingAh: Float, val source: EstSource?)
  fun stepEstimate(prev: EstPackState, r: EstReading): EstPackState
  fun estimateOf(state: EstPackState, r: EstReading): SocEstimate
  fun voltageSoc(voltageV: Float): Float?          // null at ≥ 13.30 V
  ```

`charging` in every rule means `r.state == Charging && !r.regen`. `stepEstimate` applies, in order: (1) taper tracking (`taperAtMs = r.tsMs` on a charging reading with `voltageV ≥ 13.90 && currentA < 3f`; cleared when a new charge starts, i.e. `!prev.wasCharging && charging`); (2) charge end (`prev.wasCharging && !charging`): full ⇔ `taperAtMs != null && r.tsMs − taperAtMs ≤ 15 min` → clamp-clear or COUNTER replace; partial → raise to lower bound; (3) unseen charge (`!prev.wasCharging && !charging && prev.lastRawAh != null && r.remainingAh − prev.lastRawAh > 2f`) → clear if clamped, else lower bound; (4) lower bound on any non-charging reading with `remainingAh > full + 0.5`; (5) rest tracking; (6) voltage rule when rested and `voltageSoc != null` (first: `< est − 10`; once VOLTAGE: `< est − 3`), correction = `max(existing, remainingAh − v/100·full)`. Lower-bound raises keep the existing source unless the correction was 0 (then COUNTER). `setAtMs` = `r.tsMs` whenever the correction changes. If `fullChargeAh ≤ 0` or any input non-finite, return `prev` with only `lastTsMs` updated.

`estimateOf`: correction 0 → raw `soc`/`remainingAh`, source null. Else `ah = clamp(raw − corr, 0, full)`, `soc = min(raw.soc, round(ah / full × 100))`, `ah = min(ah, raw.remainingAh)`.

- [ ] **Step 1: Write the failing tests** — one per rule, with these exact cases:
  - `missedFullDetectSetsCounterCorrection`: charging readings ending with V 13.969, I 2.0 at t; next reading Idle, remaining 120.0, full 105 → correction 15.0, COUNTER; `estimateOf` at remaining 111.84, soc 99 → soc 92, ah 96.84.
  - `fullDetectClampClears`: same taper, end reading remaining 105.0 → correction 0.
  - `partialChargeKeepsVoltageCorrection`: state correction 39 VOLTAGE; charge 100.4→108.4 Ah with V never ≥ 13.90; end → correction stays 39; estimate 69.4 Ah.
  - `partialChargeRaisesLowerBound`: correction 0; partial end at remaining 108 → correction 3.0.
  - `chargeEndAcrossGapWithStaleTaperIsPartial`: taper at t, end reading at t + 16 min → partial.
  - `regenChargingIsNotACharge`: Discharging, then `state=Charging, regen=true`, then Discharging → `wasCharging` never true, correction unchanged.
  - `unseenChargeClampClears`: correction 39 VOLTAGE, lastRawAh 100.4, next sighting Idle remaining 105.0 full 105 → 0.
  - `lowerBoundAfterDriving`: no state, Idle remaining 111.84 full 105 → correction 6.84.
  - `spareRestVoltageCorrects`: 21 readings at 0 A every 90 s spanning 30 min, V 13.099, remaining 100.4, full 100.44, soc 99 → VOLTAGE; `estimateOf` soc ≈ 58 (±1), prefix source VOLTAGE.
  - `notRestedBefore30Min`: same at 29 min → no correction.
  - `gapRestartsRest`: 20 min at 0 A, 11-min gap, 15 min more → no correction.
  - `dailyDriverPlateauIgnored`: rested 60 min at 13.325 V, soc 92 → no correction (voltageSoc null).
  - `withinMarginIgnored`: rested at 13.18 V (72) with soc 80 → no correction.
  - `voltageFollowsDown`: VOLTAGE at 60 %; later rested at 13.10 V (58) → unchanged; at 13.08 V (57.3) unchanged (not more than 3 below 60); at 13.00 V (51) → est 51.
  - `swapHoldsThroughDischarge`: correction 39 VOLTAGE; discharge readings with raw remaining 100.4 → 95.4 → 90.4 → `estimateOf` ah 61.4 → 56.4 → 51.4 (raw − 39 at each).
  - `zeroFullChargeLeavesStateAlone`: full 0 → state equal except `lastTsMs`.
  - `onlyLowersProperty`: 2,000 random readings (seeded `Random(7)`) through `stepEstimate` → every `estimateOf` has `soc ≤ r.soc && remainingAh ≤ r.remainingAh`.
  - `voltageTable`: `voltageSoc(12.90f) == 28f`, `voltageSoc(13.10f) == 58f`, `voltageSoc(13.12f) == 62f`, `voltageSoc(12.5f) == 28f`, `voltageSoc(13.30f) == null`.
- [ ] **Step 2: Run** `cd android && ./gradlew :app:testDebugUnitTest --tests '*SocEstimateTest*'` — expect FAIL (unresolved references).
- [ ] **Step 3: Implement** `SocEstimate.kt` per the interface and rule order above (no Android imports; constants as `const val` at file top with the Global Constraints names).
- [ ] **Step 4: Run** the same command — expect PASS.
- [ ] **Step 5: Commit** `feat(android): pure SOC estimator (counter overshoot + rest voltage)`.

### Task 2: Telemetry fields and persisted corrections

**Files:**
- Modify: `android/app/src/main/java/dev/joely/bmsmon/model/Telemetry.kt`
- Modify: `android/app/src/main/java/dev/joely/bmsmon/data/SettingsStore.kt` (keys near line 155, setters near 303, load near 218)
- Test: `android/app/src/test/java/dev/joely/bmsmon/SocEstimateTest.kt` (add), `.../DataStoreSafetyTest.kt` pattern for codec tests → new `SocCorrectionCodecTest.kt`

**Interfaces:**
- Consumes: Task 1 types.
- Produces:
  - `Telemetry` gains `val bmsSoc: Float? = null, val bmsCapacityAh: Float? = null, val estSource: EstSource? = null` (null on raw frames).
  - `fun Telemetry.withEstimate(e: SocEstimate): Telemetry` (in `SocEstimate.kt`) — returns `copy(soc = e.soc, capacityAh = e.remainingAh, bmsSoc = soc, bmsCapacityAh = capacityAh, estSource = e.source)`.
  - `val Telemetry.rawSoc: Float get() = bmsSoc ?: soc` and `val Telemetry.rawCapacityAh: Float get() = bmsCapacityAh ?: capacityAh`.
  - SettingsStore: key `soc_corrections_by_address` (string), `soc_estimator_seeded` (boolean); `suspend fun setSocCorrections(map: Map<String, SocCorrection>)`, `suspend fun setSocEstimatorSeeded(done: Boolean)`; loaded settings expose `socCorrections: Map<String, SocCorrection>` and `socEstimatorSeeded: Boolean`. Codec `encodeSocCorrections`/`decodeSocCorrections` (address → `correctionAh|SOURCE|setAtMs`, the same hand-rolled style as `encodeChargeTail`); a malformed entry is dropped, not fatal.

- [ ] **Step 1: Failing tests:** `withEstimateKeepsRaw` (raw soc 99 / 111.84 + estimate 92 / 96.84 → soc 92, capacityAh 96.84, bmsSoc 99, bmsCapacityAh 111.84, rawSoc 99); `codecRoundTrip` (two packs, one VOLTAGE, one COUNTER); `codecDropsMalformed` (`"A=x|COUNTER|1"` → empty map).
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*SocEstimateTest*' --tests '*SocCorrectionCodecTest*'` — FAIL.
- [ ] **Step 3: Implement.** `encodeTelemetry` (the last-telemetry seed) does not encode the three new fields.
- [ ] **Step 4: Run** — PASS. Also run the whole suite once (`./gradlew :app:testDebugUnitTest`) since `Telemetry` gained fields.
- [ ] **Step 5: Commit** `feat(android): carry BMS values beside the estimate; persist corrections`.

### Task 3: Engine wiring and upload fields

**Files:**
- Modify: `android/app/src/main/java/dev/joely/bmsmon/monitor/MonitorEngine.kt` (`onPoll`, lines ~989–1100; `start()` ~294 for loading)
- Modify: `android/app/src/main/java/dev/joely/bmsmon/cloud/CloudJson.kt` (`SampleJson`, `sampleJson()` ~79)
- Modify: `android/app/src/main/java/dev/joely/bmsmon/cloud/TelemetryReporter.kt` (`report()` ~225)
- Test: `android/app/src/test/java/dev/joely/bmsmon/EngineWiringTest.kt` (existing source-scan style), `.../cloud/` CloudJson test file (existing)

**Interfaces:**
- Consumes: Tasks 1–2.
- Produces: `MonitorEngine` holds `estState: MutableMap<String, EstPackState>` (address uppercased), loaded from `settings.socCorrections` at `start()`; `SampleJson` gains `est_soc: Float? = null, est_remaining_ah: Float? = null, est_source: String? = null`; `CloudJson.sampleJson(..., estSoc: Float? = null, estRemainingAh: Float? = null, estSource: String? = null, cells = …)`; `TelemetryReporter.report(..., est: SocEstimate? = null)` passing `est?.soc`, `est?.remainingAh`, `est?.source?.name?.lowercase()` only when `est.source != null`.

In `onPoll`, after the `t == null` return: build `EstReading` from raw `t` (+ `regen`), `next = stepEstimate(prev, r)`, `est = estimateOf(next, r)`, `eff = t.withEstimate(est)`. Use `eff` for `estimateChargeMinutes`, `estimatePackRange` and `BatteryStatus.telemetry`. Keep raw `t` for `reporter.report` telemetry args and `repository.ingest`. Pass `est` to `report`. The charge-tail trigger reads `prevTel.rawSoc`. When `next.correction != prev.correction`, persist the whole map (`scope.launch { runCatching { settings.setSocCorrections(…) } }`). While Task 4's replay is running (`seeding == true`), skip the step and use raw `t` as `eff` (never higher than BMS).

- [ ] **Step 1: Failing tests:** in the CloudJson test, `sampleJsonCarriesEstimate` (encodes `"est_soc":92.0`, `"est_remaining_ah":96.84`, `"est_source":"counter"`; omitted/null when not passed). In `EngineWiringTest`, source-scan assertions: `repository.ingest(addr, t,` still takes raw `t`; `BatteryStatus(` copy in `onPoll` uses `telemetry = eff`; `prevTel.rawSoc` in the tail trigger.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*EngineWiringTest*' --tests '*CloudJson*'` — FAIL.
- [ ] **Step 3: Implement** as described.
- [ ] **Step 4: Run** the full unit suite — PASS.
- [ ] **Step 5: Commit** `feat(android): engine uses the SOC estimate for display and decisions; upload est_* fields`.

### Task 4: One-time history replay

**Files:**
- Modify: `android/app/src/main/java/dev/joely/bmsmon/data/db/Daos.kt` (new projection + SQL beside `RANGE_PAGE_SQL`)
- Modify: `android/app/src/main/java/dev/joely/bmsmon/data/TelemetryRepository.kt` (beside `forEachRangeRow` ~273)
- Modify: `android/app/src/main/java/dev/joely/bmsmon/monitor/MonitorEngine.kt`
- Create: `android/app/src/main/java/dev/joely/bmsmon/model/SocReplay.kt`
- Create: `android/app/src/test/resources/soc/2012b-2026-11-16-charge-end.csv` (pulled from prod, below)
- Test: `android/app/src/test/java/dev/joely/bmsmon/SocReplayTest.kt`, `.../EstimateSqlTest.kt` (SqlTestDb pattern like `RangeSqlTest`)

**Interfaces:**
- Consumes: Task 1 `stepEstimate`, Task 2 settings.
- Produces:
  - `data class EstRowColumns(val id: Long, val tsMs: Long, val state: String?, val regen: Boolean, val currentA: Float?, val voltageV: Float?, val remainingAh: Float?, val fullChargeAh: Float?, val soc: Float?)`
  - `internal const val ESTIMATE_PAGE_SQL` = `RANGE_PAGE_SQL`'s WHERE/ORDER with the above columns; DAO `estimatePage(address, afterTs, afterId, limit): List<EstRowColumns>`.
  - `suspend fun TelemetryRepository.forEachEstimateRow(address: String, sinceMs: Long, consume: (EstRowColumns) -> Unit)`.
  - `fun EstRowColumns.toReading(): EstReading?` (null when any needed column is null or `state` unparseable) and `fun replayEstimate(rows: Sequence<EstReading>, start: EstPackState = EstPackState()): EstPackState` in `SocReplay.kt`.
  - Engine: `seeding` flag; on `start()`, if `!settings.socEstimatorSeeded`, launch on IO: for each roster pack, replay from `now − 14 d`, then under the engine lock set `estState[addr]`, persist corrections, then `setSocEstimatorSeeded(true)`, then `seeding = false`. Any Throwable: log, leave `seeded` false and `estState` empty, `seeding = false` (retry next start).

Fixture: from prod, 2012-B (`C8:47:80:15:62:1B`) rows from 30 min before to 3 h after the 2026-11-16 charge end, columns `ts_ms,state,regen,current_a,voltage_v,remaining_ah,full_charge_ah,soc`, all Charging rows in the last 20 min kept, every 10th row otherwise (≤ 2,000 rows). Pull with `\copy (…) to stdout with csv header` via `ssh joely@192.168.101.5 'bash -lc "docker exec -i bmsmon-db psql -U bmsmon -d bmsmon"'`.

- [ ] **Step 1: Failing tests:** `EstimateSqlTest.pageOrderAndExcludesLinks` (seeded rows incl. a link row, two pages); `SocReplayTest.realChargeEndYields15Ah` (fixture → `correctionAh` in 14.5..15.5, COUNTER); `SocReplayTest.nullColumnsSkipped`; `replayFailureLeavesUnseeded` (engine source-scan or a fake repository throwing mid-replay → `setSocEstimatorSeeded(true)` not reached; assert by structure: the seeded write is after the replay call inside the same `runCatching` success path).
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*SocReplay*' --tests '*EstimateSql*'` — FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the full suite — PASS.
- [ ] **Step 5: Commit** `feat(android): seed SOC corrections once from local history`.

### Task 5: Android displays

**Files:**
- Create: `android/app/src/main/java/dev/joely/bmsmon/ui/EstimateText.kt`
- Modify: `ui/home/StageScreen.kt` (ring number ~224, name label ~172, `StatGrid` ~333), `ui/all/AllBatteriesScreen.kt` (header % ~492, capacity line ~526), `ui/detail/BatteryDetailScreen.kt` (~121–124)
- Test: `android/app/src/test/java/dev/joely/bmsmon/EstimateTextTest.kt`

**Interfaces:**
- Consumes: Task 2 `Telemetry` fields.
- Produces: `fun socText(t: Telemetry): String` (`"~58%"` / `"92%"`), `fun bmsSocLine(t: Telemetry): String` (`"BMS 99%"`), `fun bmsCapLine(t: Telemetry): String` (`"BMS 112 Ah"`), `fun bmsListTail(t: Telemetry): String` (`"bms 99% · 112"`). Each uses `rawSoc`/`rawCapacityAh`, so a raw frame shows BMS = ours.

Layout (spec "Android displays"): Stage — ring number via `socText`; muted (`c.text3`, 11 sp, letterSpacing 0.08 em-ish) `bmsSocLine` directly under the pack name, always rendered when connected (when not connected, render the line as `"BMS —"` so height never changes); Capacity stat gets a muted sub-line `bmsCapLine` (extend `Stat`/`StatCell` with an optional `sub: String?`, rendered as an empty line of the same height when null, for every stat in that row so cells align). All Batteries — header % via `socText`; capacity row ends with `bmsListTail` in `c.text3` mono 10.5 sp, fixed width so every row's text aligns. Detail — `KeyVal("SOC", socText + "  (BMS 99 %)")` and Capacity `"%.1f / %.1f Ah  (BMS %.1f)"`.

- [ ] **Step 1: Failing tests** for the four functions with raw and estimated telemetry (values: raw 99/111.84; estimated 92/96.84 COUNTER; 58/58.3 VOLTAGE → `"~58%"`).
- [ ] **Step 2: Run** `--tests '*EstimateTextTest*'` — FAIL.
- [ ] **Step 3: Implement** the functions and wire the three screens.
- [ ] **Step 4: Run** full suite — PASS; `./gradlew :app:assembleDebug` — BUILD SUCCESSFUL. Render a screenshot via the debug `TempPreviewActivity` or emulator if the stage changed shape; check the name label and tile heights match with and without an estimate.
- [ ] **Step 5: Commit** `feat(android): show our estimate with the BMS reading muted beside it`.

### Task 6: Server columns, pass-through, widget feed

**Files:**
- Modify: `server/app/db/schema.sql` (after line 192)
- Modify: `server/app/models.py` (`SampleIn` ~125, validators ~144)
- Modify: `server/app/db/queries.py` (`_COLS` 11, INSERT 40–60 → `$32..$34`, `_INSERT_FIELDS` 62, `fleet_snapshot` SELECT ~296)
- Modify: `server/app/routers/api_widget.py` (`PACK_FIELDS` 47, `_status` 54)
- Test: existing ingest and fleet tests in `server/tests/` (find with `grep -l eta_full_min server/tests`), `server/tests/test_api_widget.py`

**Interfaces:**
- Produces: `samples.est_soc real`, `samples.est_remaining_ah real`, `samples.est_source text`; `SampleIn.est_soc: RealOrNone`, `est_remaining_ah: RealOrNone`, `est_source: TextOrNone` with validator `_clip_est_source` → value if in `{"counter","voltage"}` else None; `/web/fleet`, `/ws` and `/api/v1/groups` packs carry all three.

- [ ] **Step 1: Failing tests:** ingest stores the three fields and returns them from `/web/fleet`; `est_source: "bogus"` stores NULL and the batch is accepted; `/api/v1/groups` pack has `est_soc`; `_status` returns `"spares"` for a live idle base with `soc 99, est_soc 60` and `"backup"` for `soc 99, est_soc null`; `test_gps_is_never_exposed` unchanged and passing.
- [ ] **Step 2: Run** `docker compose -f server/docker-compose.dev.yml up -d && cd server && .venv/bin/python -m pytest -q -k "ingest or fleet or widget"` — FAIL.
- [ ] **Step 3: Implement** (`SELECT pg_temp.add_column_if_missing('samples', 'est_soc', 'real')` etc.; never a bare `ALTER`). `_status`: `est = p.get("est_soc"); s = est if est is not None else p.get("soc")`.
- [ ] **Step 4: Run** the full server suite (`.venv/bin/python -m pytest -q`) and `python3 .github/scripts/check_workflows.py` — PASS.
- [ ] **Step 5: Commit** `feat(server): store and pass through est_soc/est_remaining_ah/est_source`.

### Task 7: WebUI swap and displays

**Files:**
- Create: `web/src/estimate.ts`, `web/src/estimate.test.ts`
- Modify: `web/src/types.ts` (Sample), `web/src/decode.ts` (`SAMPLE_KEYS` 16; the sample and fleet-item decoders call `applyEstimate`)
- Modify: `web/src/v2/components/CommandStage.tsx` (~61–70), `web/src/v2/components/CommandFleetRail.tsx` (~24–46), `web/src/v2/views/HealthView.tsx` (~58, ~103)
- Test: `web/src/decode.test.ts`, a CommandStage render test beside `CommandNoData.test.tsx`

**Interfaces:**
- Produces: `Sample` gains `est_soc?, est_remaining_ah?: number | null; est_source?: "counter" | "voltage" | null; bms_soc?, bms_remaining_ah?: number | null` (the `bms_*` pair is decode-internal, never on the wire). `applyEstimate<T extends Sample>(s: T): T` — when `s.soc` is a finite number: `bms_soc = soc`, `bms_remaining_ah = remaining_ah`, `soc = est_soc ?? soc`, `remaining_ah = est_remaining_ah ?? remaining_ah`; otherwise returns `s` unchanged (link events). `socLabel(i: Sample): string` (`"~58%"`/`"92%"`/`"—"`), `bmsLabel(i: Sample): string` (`"BMS 99%"`, `"BMS —"` without soc).

Displays (option A, always present): Command stage — muted `bmsLabel` line under the W · A line; Capacity tile sub-line `BMS 111.8 Ah` (`bms_remaining_ah ?? remaining_ah`, 1 dp); fleet rail — `socLabel` then muted `bms NN`; Health per-pack board — same muted `bms NN` beside the %. Muted = the existing secondary-text token those components already use.

- [ ] **Step 1: Failing tests:** `applyEstimate_with_est_fields` (99/111.84 + est 92/96.84 → soc 92, bms_soc 99); `applyEstimate_without_est_fields` (soc 93 → soc 93, bms_soc 93); `applyEstimate_link_event_untouched`; decode keeps `est_*` (whitelist); `noEstimateKeepsLayout`: CommandStage renders a "BMS" line both for an item with and without `est_soc`, and the seize/alerts consumer `selectStageBase` sees `soc` 60 for a pack with `soc 99, est_soc 60` (one assertion through `stageBase` is enough).
- [ ] **Step 2: Run** `cd web && npx vitest run src/estimate.test.ts src/decode.test.ts` — FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `npx tsc --noEmit -p . && npx vitest run` — PASS. Run the Playwright smoke (`server/scripts/seed_dev.py`, dev-trust API, `npx vite dev`, `node scripts/smoke.mjs`) — exits 0; look at the Command shot.
- [ ] **Step 5: Commit** `feat(web): show our estimate everywhere, BMS reading muted`.

### Task 8: Desktop widget

**Files (repo `~/joely-pc-cachyos-settings`):**
- Modify: `home/joely/.local/share/plasma/plasmoids/com.joely.bmsgroup/contents/ui/main.qml` (flash ~41–45, tooltip ~159, ring/letter/voltage block ~255–295)
- Modify: `.../contents/ui/PackRing.qml` (number text ~92)
- Test: `tests/test-bmsgroup-widget.sh` (new, modelled on `tests/test-qnaphealth-widget.sh`)

**Interfaces:**
- Consumes: `/api/v1/groups` pack fields `est_soc`, `est_source` (Task 6).
- Produces: `function estSoc(p)` in `main.qml` → `p.est_soc !== null && p.est_soc !== undefined ? p.est_soc : p.soc`; `PackRing.approx: bool` (prefix `~`).

Ring `soc`, LOW tag, `flashSoc` and the tooltip all use `root.estSoc(modelData)`. A new dim `Text` (`bms NN`, `card.px(10)`, `Kirigami.Theme.disabledTextColor`, monospace) sits between the letter and the voltage on **every** pack, `"bms —"` with no soc. Tooltip line: `A: 92% (bms 99)  13.32 V`.

- [ ] **Step 1: Failing test:** the script follows the qnaphealth test's approach (static checks + `qmllint` if that test uses it): asserts `estSoc(` is used for the ring, LOW and flash; a `bms ` Text exists unconditionally (no `visible:` binding on it).
- [ ] **Step 2: Run** `bash tests/test-bmsgroup-widget.sh` — FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the test — PASS. After the server deploy (Task 9), reload plasmashell's widget per that repo's CLAUDE.md and check base 2012 and 2016 cards: voltage lines level across all four packs.
- [ ] **Step 5: Commit** in that repo: `bmsgroup: show estimated SOC with the BMS reading always under the letter`.

### Task 9: Docs, deploy, on-device check

**Files:**
- Modify: `CLAUDE.md` (new "Estimated SOC" subsection in the Android section: the two rules, table, constants, only-lowers, what stays raw, deploy order; add `est_*` to the samples column description and `PACK_FIELDS` note), `docs/checkins/NEXT.md` (spares item: decision done, spares still need charging), `~/GoogleDrive/obsidian/notes/Bmsmon.md`.

- [ ] **Step 1:** Write the docs; commit `docs: estimated SOC as built`.
- [ ] **Step 2:** Merge to `main`, push; wait for CI `promote` (`gh run watch`). Deploy the server per CLAUDE.md "Production deploy" (outside 07:35–08:45 UTC); `curl -fsS https://bmsmon.covert.life/api/v1/health` → `{"status":"ok"}`; record the `deploy/YYYYMMDDTHHMMZ` tag.
- [ ] **Step 3:** Install the APK: lift the pin (`op start`), `adb install -r`, `am start`, confirm `mLockTaskModeState=LOCKED` and `ps -A | grep bmsmon`. Confirm BLE back on all reachable packs.
- [ ] **Step 4: Verify on device and in prod:** within a few minutes 2012-B shows ~92 % with "BMS 99%"; after ≤ 30 min the spares show `~5x–6x%`; prod `SELECT address, soc, est_soc, est_source FROM samples WHERE ts > now() - interval '5 min' …` shows est fields arriving; WebUI Command and the widgets show the same numbers.
- [ ] **Step 5:** Tell the owner the spares still need charging; leave NEXT.md's re-calibration item open.
