# WebUI v2 — Phase 1: Foundation Shell + Command (Design Spec)

**Status:** approved design, ready for implementation plan · **Date:** 2026-07-12
**Roadmap:** `2026-07-12-webui-v2-roadmap.md` · **Design source:** `design_handoff_bmsmon_webui_v2/`
(claude.ai/design `b03b52d0-...`, pulled via `claude_design` MCP)

Phase 1 delivers the **shared app shell** for all six v2 views plus the flagship **Command**
view bound to live data, served at `/v2/`. The other five views are navigable placeholders.
It also lands a **cell-voltage telemetry pipeline** so Command shows real per-cell voltages.

---

## 1. Goals & non-goals

**Goals**
- A production-served v2 SPA at `bmsmon.covert.life/v2/`, v1 (`/`) untouched.
- The complete app shell: collapsible left nav, top bar, System/Light/Dark theming, responsive
  mobile (bottom tab bar), view-state persistence, design tokens.
- A high-fidelity, fully live **Command** view.
- Real per-cell voltages end-to-end (android → server → web).

**Non-goals (Phase 1)**
- Fleet Health, Journey, History, Alerts, Settings as live views — placeholders only.
- Any Journey/GPS map, long-range history aggregation, or charge-session log.
- New npm dependencies. Icons, rings, and charts are inline SVG.
- Touching v1 code or its build output.

---

## 2. Architecture, build & deploy

### 2.1 Code layout (shares the `web/` project)
- New v2 tree: `web/src/v2/` — `main.tsx` (entry), `App.tsx` (shell), `views/`, `components/`,
  `tokens.css`, plus v2-local hooks/state.
- **Reuses the existing data layer verbatim:** `web/src/api.ts`, `ws.ts`, `decode.ts`, `range.ts`,
  `temp.ts`, `stage.ts`, `store.ts`, `useLocalStorage.ts`, `util.ts`, `types.ts`. If any of these
  need a field added (see §5 cells), the change is additive and v1 keeps working.
- New HTML entry: `web/v2.html` (loads `/src/v2/main.tsx`), mirroring `web/index.html`'s pre-paint
  theme script (reads resolved theme, sets `document.documentElement.dataset.theme` before mount).

### 2.2 Build (separate Vite build, `base: '/v2/'`)
- New `web/vite.v2.config.ts`: `base: '/v2/'`, `root` resolves `v2.html` as the entry,
  `build.outDir: 'dist/v2'`, `build.emptyOutDir: true`. Shares the React plugin + dev proxy config.
- `web/package.json` `build` script becomes:
  `tsc -b && vite build && vite build -c vite.v2.config.ts`
  (v1 first — it empties `dist/` — then v2 into `dist/v2/`).
- **No Dockerfile change needed:** `RUN npm run build` already emits `web/dist`, and
  `COPY --from=web /web/dist ./web/dist` carries `dist/v2` into the image.
- **No server change needed:** the existing `StaticFiles(directory=web_dist, html=True)` mount at
  `/` serves `web/dist/v2/index.html` for `GET /v2/` (Starlette serves the directory's `index.html`
  with `html=True`). All v2 asset URLs are `/v2/...` via the base.
- **Dev:** `vite --config vite.v2.config.ts` (or a `dev:v2` script) serves v2 at `/v2/` locally with
  the same `/web`, `/api`, `/ws` proxy to `localhost:8000`.
- **Verify** during implementation: build locally, confirm `GET /v2/` serves the shell and `/`
  still serves v1 from the same `dist/`.

### 2.3 State-routed
Views are `localStorage`-persisted state, not URL routes — `/v2/` is one entry, no SPA path
fallback required.

---

## 3. Design tokens (`web/src/v2/tokens.css`)

Semantic CSS variables on `:root[data-theme="dark"]` (default) and `:root[data-theme="light"]`.
Status colors are identical in both themes. Exact values (from handoff):

**Dark:** `--app-bg:#09090b` `--nav-bg:#08080a` `--panel:#18181b` `--panel-2:#202024`
`--panel-3:#131316` `--track:#27272a` `--border:rgba(255,255,255,.07)`
`--border-strong:rgba(255,255,255,.11)` `--hover:rgba(255,255,255,.04)`
`--nav-active:rgba(255,255,255,.06)` `--row-alt:rgba(255,255,255,.012)` `--text:#f4f4f5`
`--text-2:#d4d4d8` `--text-3:#a1a1aa` `--text-4:#71717a` `--text-5:#52525b`

**Light:** `--app-bg:#fafafa` `--nav-bg:#f1f1f3` `--panel:#ffffff` `--panel-2:#f4f4f5`
`--panel-3:#f4f4f5` `--track:#e4e4e7` `--border:rgba(0,0,0,.09)`
`--border-strong:rgba(0,0,0,.14)` `--hover:rgba(0,0,0,.045)` `--nav-active:rgba(0,0,0,.06)`
`--row-alt:rgba(0,0,0,.02)` `--text:#18181b` `--text-2:#3f3f46` `--text-3:#52525b`
`--text-4:#71717a` `--text-5:#a1a1aa`

**Status (both):** `--ok:#22c55e` `--warn:#eab308` `--live:#ef4444`.

**Scale:** card radius 8px; chip/tile radius 5–7px; ring stroke 9px (large)/7px (small).
Type: eyebrows 9–10px mono uppercase `letter-spacing:.12–.14em` color `--text-4`; body 12–13px;
metrics 13–30px; big range/SOC 28–30px. Spacing: card gaps 12–16px, page padding 16–18px,
card padding 14–18px. Nav 216/64px, top bar 53px. **All numbers/codes/axis labels/chips use
JetBrains Mono** (already loaded by `index.html`; add the same `<link>` to `v2.html`).

Card base: `background:var(--panel); border:1px solid var(--border); border-radius:8px;
padding:14–18px`.

---

## 4. App shell

### 4.1 Left nav (`components/Nav.tsx`)
- 216px expanded / 64px collapsed (icon-only), chevron flips. **Persisted** `bmsmon-v2-nav`
  (`"1"`/`"0"`).
- Groups: **MONITOR** — Command, Fleet Health, Journey, History; **MANAGE** — Alerts,
  Devices (disabled, "SOON" tag), Settings.
- Active item: left accent bar + `--nav-active` bg + brighter text. Hover: `--hover`.
- Alerts item carries an unacked-count badge slot (wired in Phase 2; renders 0/none in Phase 1).
- Icons: inline SVG 24×24, `stroke=currentColor` width 1.7 (grid, activity, map-pin, bar-chart,
  bell, cpu, gear, chevron, sun, moon, monitor). A small `components/icons.tsx` module.

### 4.2 Top bar (`components/TopBar.tsx`), 53px
- Left: brand `b`+`v2`, current view title.
- Center/right: view-switcher circles (Command/Health/Journey), LIVE / GPS / synced pills (reuse
  the same live/gps derivation as v1 App.tsx), **theme cycle** button (System→Light→Dark, icon+
  label), **Desktop/Mobile** toggle.
- On mobile the pills hide; brand + title + device/theme toggles remain.

### 4.3 Theming (`v2/useTheme.ts`)
- `themeMode: "system"|"light"|"dark"` in settings (§4.5). Resolve to `"dark"|"light"`:
  `system` → `matchMedia("(prefers-color-scheme: dark)")`, **live-updating** on OS change.
- Write resolved theme to `document.documentElement.dataset.theme` (drives tokens + `color-scheme`).
- Top-bar cycle and the (Phase-2) Settings toggle share this one source of truth. Reconcile with
  v1's `bmsmon-theme`: v2 owns `bmsmon-v2-settings.themeMode`; on first load, if no v2 setting
  exists, seed `themeMode` from `bmsmon-theme` (light/dark) else `system`.

### 4.4 Responsive / mobile (`v2/useDeviceMode.ts`)
- Resolve **mobile when viewport < 820px**, or forced by `settings.deviceMode` (`"mobile"|
  "desktop"|null`). Re-evaluate on resize (a `winW` listener). Real CSS media queries + this hook,
  **not** the prototype's JS-width layout.
- Mobile: sidebar → fixed **bottom tab bar** (Command · Health · Journey · History · Alerts ·
  Settings; icon + short label; Alerts badge). Command reflows to a single column
  (stage → fleet rail → right column via flex `order`). Desktop layout unchanged.

### 4.5 View state & persistence (`v2/useV2Settings.ts`, `v2/useV2State.ts`)
- `view: command|health|journey|history|alerts|settings` — persisted `bmsmon-v2-view`.
- `navCollapsed: bool` — persisted `bmsmon-v2-nav`.
- `settings` — persisted `bmsmon-v2-settings` =
  `{ distUnit:"mi"|"km", tempUnitPref:"F"|"C", mapMetricPref:"power"|"soc",
  themeMode:"system"|"light"|"dark", deviceMode:"mobile"|"desktop"|null }`.
  Defaults: `mi, F, power, system, null`. Reconcile `tempUnitPref` with existing `bmsmon-temp-unit`.
- `stageBase` (Command staged base, default the daily-driver base **2012**; session only).
- Reuse `useLocalStorage.ts` with per-key codecs, matching v1's pattern.
- Placeholder views render a centered "Coming in a later phase" card so the whole shell is
  navigable and testable in Phase 1.

---

## 5. Cell-voltage telemetry pipeline (added workstream)

Goal: real C1–C4 in Command. Today the cloud stores only `cell_min_v`/`cell_max_v`; the phone
already computes all cells (`Telemetry.cells: List<Float>`).

- **android:** add `cells: List<Float>?` (or `cell_v: List<Float>`) to the upload `SampleIn`
  mapping (the outbox/telemetry serializer). Keep existing `cell_min_v`/`cell_max_v`.
- **server:** add `cells` to `SampleIn` (`server/app/models.py`); persist to `samples` — a
  `cell_v real[]` column (or `cellsjsonb`) added **additively** in `schema.sql`
  (`ADD COLUMN IF NOT EXISTS`, auto-applies on container start). Ingest writes it; `fleet_snapshot`
  selects it.
- **web:** add `cells?: number[] | null` to `Sample` (`types.ts`) and to `SAMPLE_KEYS`/`FLEET_KEYS`
  (`decode.ts`) so the decoder passes it through (validate as an optional array of finite numbers).
- **Command rendering:** if `cells` present → four real C1–C4 tiles; else **fall back** to
  min/max/mean derived from `cell_min_v`/`cell_max_v` (surface those two to web too). Δ spread =
  `cell_max_v - cell_min_v` (or `max(cells)-min(cells)` when present), amber if > 40 mV.
- **Tested end-to-end** (server ingest test with cells; web decode test; a pack with no cells still
  renders via fallback). Real 4-cell values appear only after a phone-app deploy — expected.

---

## 6. Command view — layout & data bindings

Desktop grid: `252px | 1fr | 340px`. All values bind to the live fleet (`/web/fleet` snapshot +
`/ws` samples via the existing `store`), the synced `/web/{temp,alert,range}-config`, and
`range.ts`. Bases group packs by `group_id`/alias (reuse v1's grouping); the 4 bases are 2012
(daily driver), 2024, 2023, 2016.

### 6.1 Left rail — Fleet (`components/CommandFleetRail.tsx`)
Four base groups. Header: status dot + "Base YYYY" + tag (IN USE / CHARGING / BACKUP / SPARES,
derived from pack `state`/SOC). Two pack rows (A/B): pack letter, SOC bar, SOC %, SOH dot.
**Clicking a base header OR a pack row sets `stageBase`** → promotes it to the main stage; staged
base shows a "STAGE" chip. Disconnected packs (stale > `STALE_MS`, reuse v1's 90 s) render muted
(no %), never as 0%.

### 6.2 Center — Main stage (`components/CommandStage.tsx`)
- **Thermal banner** (amber) — only when a staged pack `temp_c ≥ 44`.
- MAIN STAGE card: "Base YYYY" + status tag (color per `state`) + role text. Two packs side by side.
  Each pack:
  - name + SOH dot; **SOC ring** 132px (reuse/port the ring; `soc`).
  - power·current line: `power_w` W · `current_a` A (negative = discharge).
  - 2×2 stat grid: **CAPACITY** `remaining_ah`/`full_charge_ah` Ah · **TEMP** `temp_c` (unit per
    `tempUnitPref`) · **HEALTH** `soh` % · **CYCLES** `cycles`.
  - **CELL VOLTAGES** (§5): 4 tiles C1–C4 (3-decimal V) each with a balance bar + label; Δ spread
    readout (mV, amber if > 40). Fallback to high/low/mean when `cells` absent.
- **Flow tiles (3):** DRAW NOW / CHARGE IN / FLOW (W, from `power_w` / charging state) ·
  EST. RUNTIME (from `range.ts` active-hours band) or TIME TO FULL (`eta_full_min`) when charging ·
  **DRIVEN TODAY** → stub "—" (Phase 4).
- **RANGE · CAN YOU MAKE IT?** card (`components/CommandRange.tsx`): big est. range left, runtime
  band + typical, and go/tight/no-go chips for **saved trips**. **All figures recompute from the
  staged pair's remaining Wh** using `range.ts` verbatim: `estimatePackRange(charging,
  remaining_ah, params)` per pack → `minRange([...])` (weaker pack bounds); params from
  `/web/range-config` (`selectRangeParams`) else `SEED_RANGE_PARAMS`. **Charging → no range**
  (the recharge/ETA owns the slot). Saved trips: a v2-local `localStorage` list
  `{ name, miles }[]`; a trip is **go** if `miles ≤ minRange.milesLo`, **tight** if
  `≤ milesHi`, else **no-go**. (Editing saved trips is a small v2-local form; keep minimal.)

### 6.3 Right column (`components/CommandAside.tsx`)
- **RECHARGE PLAN:** low packs (below the capacity ladder / not full) with SOC bar + "≈ time to
  full" (`eta_full_min`) + "ready by" clock (`now + eta_full_min`).
- **FLEET HEALTH:** good/fair/degraded stacked bar over the 8 packs (by `soh` bands) + worst-pack note.
- **TODAY'S ROUTE:** mini-map **stubbed** → a placeholder panel with "Open Journey →" and
  "History →" buttons (switch `view`). Real trail in Phase 4.

---

## 7. Interactions (Phase 1 scope)

- View switching: left-nav items, top-bar circles, "Open Journey →" / "History →" buttons (all set
  `view`; persisted).
- Nav collapse toggle (persisted). Theme cycle (System→Light→Dark, live OS-following). Device
  Desktop/Mobile toggle (persisted; auto < 820px otherwise, re-evaluated on resize).
- Command stage selection: click base/pack → `stageBase` → rings/cells/tiles/thermal-banner/range
  all follow.
- Live pills: LIVE / GPS / synced; pulsing-dot animations (`rx-pulse`, `bm-pulse`).

---

## 8. Testing & verification

- **Unit (vitest):** cell-array decode (present / absent / malformed → dropped); saved-trip
  go/tight/no-go classification; theme resolution (system→dark/light + OS-change); device-mode
  resolution (< 820 / forced). Reuse existing `range.ts` tests unchanged.
- **Server (pytest):** ingest accepts+persists `cells`; `fleet_snapshot` returns it; absent cells
  still ingests.
- **End-to-end (verify skill):** build both bundles; `GET /v2/` serves the shell, `/` still serves
  v1; Command renders live fleet against the dev/prod API; theme + mobile toggles work; cell tiles
  fall back cleanly when a pack has no `cells` yet.

---

## 9. Out of scope / deferred

- Fleet Health, Journey, History, Alerts, Settings live views (Phases 2–4; placeholders now).
- GPS-derived Command bits: DRIVEN TODAY, Today's Route mini-map (Phase 4).
- Journey map-tech choice; long-range history endpoints; charge-session log (Phases 3–4).
- v1 retirement.

---

## 10. Open items to confirm during implementation

- Exact `samples` cell column type (`real[]` vs `jsonb`) — pick when editing `schema.sql`/ingest.
- Precise base↔`group_id` mapping in the live data (reuse v1's grouping; confirm the 4 base labels
  render from real `alias`/`group_id`).
- Ring/stat sub-components: port from v1 (`Ring.tsx`) or re-author minimal v2 versions under
  `web/src/v2/components/` — prefer small v2-local components styled by tokens, reusing v1 math.
