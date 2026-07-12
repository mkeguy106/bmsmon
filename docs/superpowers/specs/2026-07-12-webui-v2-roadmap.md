# bmsmon WebUI v2 — Multi-Session Roadmap

**Status:** active · **Started:** 2026-07-12 · **Owner:** Joel

A ground-up redesign of the bmsmon cloud dashboard, served in parallel at
`bmsmon.covert.life/v2/` while v1 (`/`) stays live and untouched. This document is the
top-level plan that spans multiple Claude coding sessions. Each phase gets its **own**
design spec → implementation plan → build/verify cycle; this file tracks the whole arc,
the cross-phase decisions, and what each phase needs from the backend.

## Source of the design

- **Design project:** "WebUI redesign with GPS mapping"
  (claude.ai/design `b03b52d0-e28a-4fad-af1d-aa09b37aeaf6`), pulled via the `claude_design`
  MCP (`DesignSync`).
- **Handoff bundle:** `design_handoff_bmsmon_webui_v2/` — `BMS Dashboard.dc.html` (the full
  reference prototype, all six views) + `README.md` (spec-grade notes: exact tokens, formulas,
  interactions) + `screenshots/01–07`.
- The prototype's `<x-dc>` / `DCLogic` runtime is **reference only — do not port it**. We
  recreate the designs in the existing React 18 + Vite + TypeScript `web/` codebase, reusing its
  data layer and `theme.css`-style token approach.

## The six views (target end-state)

A single-page app: collapsible left nav (216↔64px) + top bar (53px) + content area, plus a
responsive **mobile** layout (< 820px → bottom tab bar). Nav groups: **MONITOR** (Command, Fleet
Health, Journey, History) and **MANAGE** (Alerts, Devices [disabled/"SOON"], Settings).

1. **Command** — control-room dashboard (fleet rail / main stage with SOC rings + cell voltages / recharge plan + mini-map).
2. **Fleet Health** — 8-pack capacity/health board.
3. **Journey** — GPS trip visualization; trail colored by **discharge** (not motion); car/train legs render as grey "in transit · battery idle" rail lines.
4. **History** — per-base trend charts (capacity fade, cell imbalance, temperature) with A/B pack breakdown + charge-session log + editable notes.
5. **Alerts** — active warnings list with acknowledge; unacked-count nav badge.
6. **Settings** — units, journey-map metric, theme, about.

## Guiding decisions (agreed 2026-07-12)

- **Parallel, same backend where possible.** v2 reuses existing endpoints; new backend routes are
  added only for views that genuinely need them (History, Journey), when we reach those phases.
  v1 is never modified.
- **v2 shares the `web/` project.** Same data layer (`api.ts`, `ws.ts`, `decode.ts`, `range.ts`,
  `temp.ts`, `stage.ts`, `store.ts`); v2 code lives under `web/src/v2/`. A **separate Vite build**
  with `base: '/v2/'` outputs to `web/dist/v2/`; the server's existing `StaticFiles(html=True)`
  mount at `/` serves it with **no server/Docker change** beyond the build script.
- **State-routed, not URL-routed.** Views persist to `localStorage` (per the handoff); `/v2/` is a
  single SPA entry, no deep-link fallback needed.
- **Fidelity is high.** Tokens, type, spacing, layout, interactions all as designed. Only the
  prototype's fake data/charts/map are representative — everything binds to real telemetry.
- **Formulas mirror production.** Range/runtime reuse `web/src/range.ts`; charge ETA mirrors the
  documented coulomb-bulk + learned-tail model; alert/temp thresholds reuse `temp.ts` and the
  capacity ladder. No re-derivation.

## Phase plan

### Phase 1 — Foundation + Command  ← **in progress**
Spec: `2026-07-12-webui-v2-phase1-foundation-command-design.md`

The shared app shell (nav, top bar, theming System/Light/Dark, mobile bottom-tab layout,
view-state persistence, design tokens, the `/v2/` build+deploy) **plus** the flagship **Command**
view bound to live data. The other five nav items render a navigable placeholder.

Includes one added workstream: a **cell-voltage telemetry pipeline** (android already computes
`cells: List<Float>`; extend upload → server `samples` → `fleet_snapshot` → web) so Command's
CELL VOLTAGES section shows real C1–C4. Additive schema; falls back to min/max until packs report.

**Backend:** additive `samples` cell columns + ingest model + snapshot select (small, additive).
GPS-derived Command bits (DRIVEN TODAY, Today's Route mini-map) are **stubbed** here, wired in Phase 4.

### Phase 2 — Fleet Health + Alerts + Settings  ← **done**
The three remaining zero-backend views. All drive off `/web/fleet` + `/ws` + `/web/{temp,alert,range}-config`:
- **Fleet Health:** 8-pack board (capacity/health bars, temp, cycles, 24h sparkline, status). The
  24h sparkline needs a small recent-samples read — decide then whether to reuse `/web/samples`
  (admin) or add a light web-scoped recent-history endpoint (may pull this into Phase 3's endpoint work).
- **Alerts:** derived live from the capacity ladder + temp envelope + cell-imbalance (>40 mV),
  reusing `temp.ts` + ladder logic; acknowledge state in `localStorage`.
- **Settings:** units, map metric, theme, about — segmented toggles, persisted; reconcile with
  existing prefs.

**Backend:** none required (pending the sparkline decision above).

### Phase 3 — History  *(needs new backend)*
Per-base trend hub: capacity fade (SOH over months/years + "≈ N mo to 80%" projection), cell
imbalance trend, temperature; A/B pack breakdown keyed by MAC; charge-session log; editable notes.

**Backend (new):** the current `/web/samples` is admin-only, clamped to 7 days, row-capped —
insufficient. Need **downsampled long-range trend aggregation per MAC** (e.g. daily/weekly buckets
of SOH, cell spread, temp) and a **charge-session detection/log** query. Design these as read-only
web endpoints when the phase starts.

### Phase 4 — Journey / GPS  *(needs new backend + a map-tech decision)*
GPS trip visualization with discharge-colored trail, transit rail lines, hotspots, and playback.

**Backend (new):** a **web-scoped GPS-trail endpoint** (per day/range, per pack, with `current_a`/
`power_w` for discharge bucketing) — the admin 7-day `/web/samples` isn't the right shape/scope.
Mind the row volume (≈1.5 s polling → tens of thousands of rows/pack/day) — the endpoint should
downsample/bucket server-side.

**Deferred decision — map rendering:** keep the prototype's **stylized schematic SVG street grid**,
or wire a real map lib (**Leaflet / MapLibre** with a dark/light style). Either way, preserve the
**discharge-vs-transit coloring**, which is the core idea. Make this call at the start of Phase 4.

## Cross-phase / deferred decisions

- **Journey map tech** (schematic SVG vs Leaflet/MapLibre) — decide at Phase 4 start.
- **Recent-history source for Fleet Health sparklines** — reuse admin `/web/samples` vs new
  web-scoped recent endpoint — decide at Phase 2 start (may fold into Phase 3 endpoint work).
- **Full per-cell history** — the Phase 1 pipeline uploads live cells; whether to also backfill/
  store per-cell trend for History is a Phase 3 consideration.
- **Eventual v1 retirement** — out of scope; v2 runs in parallel indefinitely until a separate
  decision.

## Progress log

- **2026-07-12** — Brainstormed scope, decomposed into 4 phases, confirmed same-`web/` +
  separate-`/v2/`-build architecture, chose full 4-cell pipeline for Command. Roadmap + Phase 1
  spec written.
- **2026-07-12** — Phase 1 (Foundation + Command) implementation COMPLETE on branch
  `feat/webui-v2-phase1`: app shell (nav/theme/mobile) and the live Command view are in place,
  and the per-cell-voltage telemetry pipeline (android → server → web) has landed. Full-suite
  verification passed (web 95 vitest + tsc clean, server 99 pytest incl. `test_cells.py`, android
  295 JVM unit tests incl. `CloudJsonTest`); both bundles build (`dist/index.html` v1 +
  `dist/v2/index.html` v2 sharing `dist/assets`) and serve correctly. Pending: final review,
  merge, and deploy.
- **2026-07-12** — Phase 2 (Fleet Health + Alerts + Settings) implementation COMPLETE on branch
  `feat/webui-v2-phase2`: the three zero-backend views are live — Fleet Health (tiles + 8-pack
  board + 24h sparkline), Alerts (capacity ladder + temp zones + cell-imbalance, per-pack
  acknowledge in `localStorage`), Settings (units, map trail color, theme — segmented toggles).
  A read-only `GET /web/history` endpoint (30-min bucketed per-pack SOC) was added to back the
  sparkline. Full-suite verification passed (web 109 vitest + tsc clean, server 107 pytest incl.
  `test_history.py` + `test_web_history.py`); both bundles build and serve correctly; `/v2/`
  driven end-to-end headlessly (Health/Alerts/Settings all render; v1 unaffected). Pending: final
  review, merge, and deploy.
