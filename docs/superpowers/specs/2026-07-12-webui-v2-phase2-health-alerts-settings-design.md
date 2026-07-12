# WebUI v2 — Phase 2: Fleet Health + Alerts + Settings (Design Spec)

**Status:** approved design, ready for implementation plan · **Date:** 2026-07-12
**Roadmap:** `2026-07-12-webui-v2-roadmap.md` · **Design source:** `design_handoff_bmsmon_webui_v2/`
(views 02-health / 05-alerts / 06-settings) · **Builds on:** Phase 1 (shell + Command, shipped).

Phase 2 turns three of the five placeholder views into live views: **Fleet Health**, **Alerts**,
and **Settings**. It adds one small read-only backend endpoint (`GET /web/history`) for the Fleet
Health sparkline, and wires the nav's unacked-count badge.

---

## 1. Goals & non-goals

**Goals**
- Live **Fleet Health** board (summary tiles, in-use hero, 8-pack board with 24H sparkline).
- Live **Alerts** view (derived capacity/temperature/cell-imbalance alerts, acknowledge, nav badge).
- Live **Settings** view (units, journey-map metric, theme, about) editing `bmsmon-v2-settings`.
- One additive endpoint `GET /web/history` for the sparkline (Phase-3 groundwork).

**Non-goals (Phase 2)**
- Journey and History views (Phases 3–4; stay placeholders).
- A real `synced` upload-rate signal (still aliased to `live`; needs upload-rate plumbing — defer).
- Any change to v1, or to Phase-1 Command behavior.
- New npm dependencies. Charts/sparklines are inline SVG.

---

## 2. Backend — `GET /web/history`

- **Route:** `GET /web/history?hours=<int, default 24, clamp 1..168>` in `server/app/routers/web.py`,
  `Depends(current_user)` (NOT admin), read-only.
- **Query** (`server/app/db/queries.py`, new `history_series`): 30-minute buckets of average SOC per
  pack over the window, real telemetry only.
  ```sql
  SELECT address,
         (ts_ms / 1800000) * 1800000 AS bucket_ms,
         avg(soc)::real AS soc
    FROM samples
   WHERE ts_ms >= $1            -- now_ms - hours*3600_000
     AND link_event IS NULL
     AND soc IS NOT NULL
   GROUP BY address, bucket_ms
   ORDER BY address, bucket_ms
  ```
  (30 min = 1_800_000 ms → ≤48 points/pack/24h. Bounded by the window, not row-capped.)
- **Response:** `{ "series": [ { "address": "C8:…", "points": [ { "t": <bucket_ms>, "soc": <num> }, … ] }, … ] }`
  Group the flat rows by address in the route (or return flat and group in `queries`), one array per pack.
- **Additive only:** new route + query; no schema change; v1 untouched. Server test: seed a couple
  packs' samples across buckets, assert grouped/averaged series + the `hours` clamp.

**Web side:** `getHistory(hours=24)` in `web/src/api.ts` + a runtime decoder in `web/src/decode.ts`
(validate `series` array; each `{ address:string, points: {t:finite, soc:finite}[] }`; drop malformed
points/series like the other decoders). New `web/src/v2/useHistory.ts` polls it every ~180 s and
returns `Map<address, {t:number;soc:number}[]>`.

---

## 3. Config plumbing (v2 currently fetches only range-config)

New `web/src/v2/useV2Configs.ts`: polls `getTempConfig()` (→ `selectActiveConfig` → `TempConfig`)
and `getAlertConfig()` every 60 s (mirroring v1 App.tsx), returns `{ tempConfig, alertConfig }`.
Reuses the existing `api.ts` fetchers + `temp.ts` helpers — no new endpoints. `App` calls this once
and passes results down to Alerts (temp thresholds) and, where relevant, Settings/About.

---

## 4. Fleet Health view

### 4.1 Pure logic — `web/src/v2/model/health.ts` (tested)
- `interface HealthSummary { ready:number; needRecharge:number; degraded:number; capacityPct:number }`
- `healthSummary(items, staleAddrs): HealthSummary`:
  - `ready` = count of **connected** packs with `soc >= 90`.
  - `needRecharge` = count of connected packs with `soc < 30`.
  - `degraded` = count of packs with `soh != null && soh < 80`.
  - `capacityPct` = `Σremaining_ah / Σfull_charge_ah * 100` over connected packs with both present
    (0 when denominator is 0).
- `healthBoardOrder(items, staleAddrs): FleetItem[]`: attention-first sort — **disconnected first,
  then ascending SOC** (nulls last), stable.
- `packStatus(item, connected): "in-use"|"charging"|"low"|"idle"|"offline"` (reuse
  `isCharging`/`isDischarging` from `fleet.ts`; `low` when `soc < 30`).

### 4.2 Components
- `web/src/v2/components/Sparkline.tsx` — `Sparkline({ points, width, height })`: inline-SVG polyline
  of SOC over the window, y scaled 0–100, muted stroke; renders a flat baseline / "—" when `points`
  is empty (pack not yet in history).
- `web/src/v2/views/HealthView.tsx`:
  - **4 summary `StatTile`s** from `healthSummary` (PACKS READY `n/8`, NEED RECHARGE, DEGRADED,
    FLEET CAPACITY `n%`).
  - **"IN USE NOW" hero:** the daily-driver base (`groupBases` → base id `2012`, `DAILY_DRIVER_BASE`);
    both packs with SOC / capacity / health `Bar`s. If 2012 absent, fall back to the first base.
  - **8-row board** from `healthBoardOrder`: PACK (alias/letter) · CAPACITY (`Bar`+%) · HEALTH
    (`Bar`+%) · TEMP (`formatTemp`, unit from settings) · CYCLES · `<Sparkline points={history.get(addr)}>`
    · STATUS `Chip` (`packStatus`). Disconnected rows muted (no %). On mobile the board scrolls
    horizontally (min-width ~720px) per the design.

---

## 5. Alerts view

### 5.1 Pure logic — `web/src/v2/model/alerts.ts` (tested)
- `type AlertSeverity = "critical" | "warning"`
- `interface V2Alert { id:string; address:string; severity:AlertSeverity; title:string; msg:string; tsMs:number; kind:"capacity"|"temp"|"cell" }`
- `deriveAlerts(items, staleAddrs, tempCfg): V2Alert[]` — for each **connected** pack, emit:
  - **capacity:** fixed ladder `[30,25,20,15,10,5]`; a pack fires **at** the highest rung it is
    `<=`; `severity = soc <= 15 ? "critical" : "warning"`. `id = "cap:"+address`.
  - **temperature:** `z = tempZone(temp_c, thresholdsFromConfig(tempCfg), envelopeFromConfig(tempCfg))`;
    emit when `z.rank >= 1` (CAUTION+); `severity = z.rank >= 3 ? "critical" : "warning"`; title/msg
    from `zoneCopy(z.key, …)`. `id = "temp:"+address`.
  - **cell imbalance:** `deltaMv(item) > 40` → `severity = "warning"` (`> 60` may bump to critical),
    `id = "cell:"+address`. Title "Cell imbalance", msg `"Δ NN mV"`.
  - `tsMs = item.ts_ms`. Sort critical-first, then most-recent.
- Fixed ladder + thresholds are module constants matching the design (30/25/20/15/10/5, crit ≤15).
  Temperature thresholds come from the synced `tempCfg` (Redodo defaults when null), reusing `temp.ts`.

### 5.2 View + badge
- `web/src/v2/views/AlertsView.tsx`: list of alert rows (severity color bar `--live`/`--warn`, title,
  msg, relative time, **Acknowledge** button). Acked ids dim and sort last. **Empty state "All clear."**
  Footer restates the thresholds. Ack state is **session** (a `Set<string>` of alert ids).
- **Ack state is lifted to `App`** so the nav badge can read it: `App` derives `alerts` (via a
  `useAlerts` hook or inline `deriveAlerts` memo over the fleet + tempConfig), holds `acked: Set<string>`
  (session `useState`), computes `unackedCount = alerts.filter(a => !acked.has(a.id)).length`, and
  passes `unackedCount` to **both** `Nav` and `BottomTabs` (Phase 1 hardcoded 0) and `alerts`/`acked`/
  `onAck` to `AlertsView`.

---

## 6. Settings view

- `web/src/v2/components/Segmented.tsx` — `Segmented<T>({ options:{value,label}[], value, onChange })`:
  a token-styled segmented toggle (active segment `--nav-active` bg + `--text`).
- `web/src/v2/views/SettingsView.tsx` — cards, each a `Segmented` writing via `useV2Settings().patch`:
  - **Units:** Distance `mi`/`km` → `distUnit`; Temperature `°F`/`°C` → `tempUnitPref`.
  - **Journey Map:** trail color `DISCHARGE`/`SOC` → `mapMetricPref`.
  - **Appearance:** Theme `SYSTEM`/`LIGHT`/`DARK` → `themeMode` (already the single source; the
    top-bar cycle and this control stay in sync automatically).
  - **About:** static card — fleet (8 packs / 4 bases), hardware (Redodo 12V 100Ah, Beken BK-BLE-1.0),
    server (`bmsmon.covert.life`), and the app build note. Read-only text; no fetch.

---

## 7. Shell wiring (`web/src/v2/App.tsx`)

- Call `useV2Configs()` and `useHistory()` once in `App` (alongside `useFleetData`/`useV2Settings`).
- Derive `alerts` + `unackedCount`; hold session `acked`. Pass `unackedCount` to `Nav` and `BottomTabs`.
- Replace the three placeholder branches:
  - `health` → `<HealthView data={data} history={history} unit={tempF?"F":"C"} />`
  - `alerts` → `<AlertsView alerts={alerts} acked={acked} onAck={ack} now={data.now} tempCfg={tempConfig} />`
  - `settings` → `<SettingsView />` (reads/writes settings itself via `useV2Settings`).
- Keep Command, nav/theme/device, single live store unchanged.

**Opportunistic Phase-1 minor sweep** (only these, in files we're already editing): add the
`aria-label` to the theme-cycle button (`TopBar.tsx`) and replace the badge's hardcoded `#fff` with a
`--badge-text` token in `tokens.css` (used by `Nav.tsx`/`BottomTabs.tsx`). The real `synced` signal,
1s-tick re-render, and other carried minors stay deferred.

---

## 8. Testing & verification

- **Web (vitest):** `health.ts` (summary counts incl. disconnected exclusion + zero-denominator
  capacityPct; board order disconnected-first-then-ascending-SOC); `alerts.ts` (capacity rung
  selection + critical≤15; temp via a stub tempCfg hitting CAUTION/CRITICAL; cell>40; sort order);
  `decode.ts` history decoder (valid + malformed points dropped). Reuse `temp.ts`/`fleet.ts` tests
  unchanged.
- **Server (pytest):** `history_series` buckets/averages correctly across ≥2 buckets and packs; the
  `hours` clamp; empty result for a pack with no samples.
- **End-to-end (verify skill):** build both bundles; drive `/v2/` (headless) — Health board renders
  with sparklines from the real/seeded history endpoint, Alerts derives from a low/hot/imbalanced
  pack and the nav badge counts unacked, acknowledging dims + decrements, Settings toggles persist
  and the theme control mirrors the top-bar cycle. Confirm v1 (`/`) and Phase-1 Command unaffected.

---

## 9. Out of scope / deferred

- Journey (Phase 4), History rich trends + charge log (Phase 3).
- Real `synced` upload-rate signal; 1s-tick re-render optimization; other carried Phase-1 minors
  (except the two opportunistic sweeps in §7).
- Persisting Alerts acknowledgement across reloads (design specifies session-only).

---

## 10. Open items to confirm during implementation

- Whether the Fleet Health board's null-SOH packs render an empty HEALTH bar or "—" (prefer "—",
  consistent with Command's disconnected handling).
- Exact "About" card copy (pull current values from `CLAUDE.md` hardware table).
- `useAlerts` as a dedicated hook vs an inline `useMemo` in App — pick whichever keeps App readable
  (the alert list + unacked count must be available to App for the badge either way).
