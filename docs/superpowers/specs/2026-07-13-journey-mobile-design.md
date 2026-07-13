# Journey Mobile Redesign (map-first + line dock) — Design

**Date:** 2026-07-13
**Status:** Approved (user-selected V2 "inline rows" dock with single-direction flow bar;
based on the user's design handoff "WebUI redesign with GPS mapping" + mockup iteration at
claude.ai/code/artifact/958aa110-cf93-4286-9e92-f29c2d95ad4c)

## Goal

On mobile, the Journey view becomes map-first: a fixed-height, non-scrolling column where the
map fills everything between the date toolbar and a compact bottom dock. The dock keeps the
trip readouts and replaces the battery gauges with two thin lines — pair capacity and a
single-direction flow bar. Desktop keeps its current layout, gaining only the re-center
button and honest trail-metric coloring.

## Design

### 1. Shell + layout (mobile only)

- `web/src/v2/App.tsx`: when `mobile && view === "journey"`, the shell row gets
  `height: 100dvh; overflow: hidden` (instead of `minHeight: 100vh`) and `main` drops its
  padding (`padding: 0 0 76px` — BottomTabs still reserve 76 px) and becomes
  `display:flex; flexDirection:column; minHeight:0`. Other views/scrolling unchanged.
- `JourneyView` mobile root: `flex:1; display:flex; flexDirection:column; minHeight:0;
  overflow:hidden` — toolbar (own padding `12px 14px`, flex-shrink 0), map wrapper
  (`flex:1; minHeight:0; position:relative`), dock (flex-shrink 0).
- **Mobile does NOT render**: the playback bar, the energy-over-distance chart, the desktop
  side dock (rings card + trip card). No scrub cursor on mobile (cursorIndex pinned to the
  latest point for the map's cursor marker, or simply not passed — the live ♿ marker covers
  "where am I"; pass `cursorIndex = points.length - 1`).

### 2. Mobile dock (~86 px, per approved mockup)

Container: `padding:12px 14px; display:flex; flexDirection:column; gap:9px;
borderTop: 1px solid var(--border); background: var(--panel-3);` mono, tabular-nums.

Row 1 — **trip line** (11px, `--text-2`, values bold `--text` 12px, `·` separators
`--text-4`): `{miles} mi · ACT {activeMiles} · TRN {transitMiles} · PEAK {peakW} W`
from the existing `tripSummary` (one decimal for miles figures, whole watts).

Row 2 — **CAP**: eyebrow label (9px, letter-spacing .14em, `--text-4`, 40px wide), thin bar
(8 px tall, radius 4, track `--track`), value right (96px, right-aligned: `{pct}%` bold +
` A{a}·B{b}` suffix 9px `--text-3`).
- Pair pct = **min** reachable pack SOC (the weaker pack ends the trip); suffix lists each
  pack letter+SOC. Single-pack bases: no suffix.
- Fill color by the alert bands: `--ok` > 30, `--warn` ≤ 30, `--live` ≤ 15.

Row 3 — **FLOW**: same row anatomy. **Single-direction fill from the left**:
- magnitude = `|Σ pack power_w|` against `PAIR_FLOW_FULL_W = 600` (2 × the 300 W per-pack
  ring calibration), clamped to 100%;
- **discharging** (Σ current < −0.1 A): fill `linear-gradient(90deg, var(--warn), var(--live))`,
  suffix `OUT`;
- **charging/regen** (Σ current > 0.1 A): fill green (`--regen`→`--ok` gradient), suffix
  `REGEN` when any pack's freshest sample has `regen === true`, else `CHG`;
- **idle**: empty track, `0 W · IDLE`.
- Data source: live fleet items (`data.items`) for the staged base — the dock reflects NOW,
  not the historical track.

### 3. Map overlays (mobile only; absolutely positioned inside the map wrapper)

Overlay chrome: `background: rgba(9,9,11,.72); border: 1px solid var(--border-strong);
border-radius: 6px;` mono 9-10px letter-spaced.
- Top-left chip: `TRAIL · POWER` or `TRAIL · SOC` (follows `settings.mapMetricPref`).
- Top-right badge: `LIVE · GPS` with an 8 px `--ok` dot (`box-shadow: 0 0 0 3px
  rgba(34,197,94,.2)`) — shown when `isLive`; on mobile this REPLACES the toolbar LIVE
  badge (desktop keeps the toolbar badge).
- Bottom-left legend: `transit / light / mod / heavy` swatches (dashed-grey, `--ok`,
  `--warn`, `--live`) matching the trail palette in use.
- Bottom-right: re-center button (see 4).

### 4. Re-center button (BOTH platforms; replaces the ⌖ FOLLOW text button)

38×38, radius 9, same overlay chrome, crosshair SVG (18 px, stroke currentColor 2, center
dot). Persistent (always visible when the map renders). Click: if a live position exists →
recenter on it and re-engage follow; else → refit to the trail bounds. Positioned
bottom-right inside the map (Leaflet zoom control stays top-left default).

### 5. Trail metric wiring (both platforms — makes the chip honest)

`settings.mapMetricPref` ("power" | "soc") finally drives the trail: active segments color
by `dischargeColor(power_w)` (existing) or new `socColor(soc)` — `--ok` > 30, `--warn` ≤ 30,
`--live` ≤ 15 (same bands as CAP; pure fn in `model/journey.ts`, unit-tested). App passes
the pref into JourneyView → JourneyMap. Transit/idle segment treatment unchanged.

### 6. Unchanged

Desktop layout (side dock, playback, energy chart, toolbar badge), track fetching/cleaning/
live machinery, server, Android.

## Testing

- Pure fns unit-tested (vitest): `socColor` bands; dock model — `dockCapacity(packs)`
  (min-SOC pct, suffix text, band color key) and `dockFlow(packs)` (kind OUT/REGEN/CHG/IDLE,
  watts, fill fraction vs 600 W clamp) in a new `web/src/v2/model/dock.ts`.
- Full web gate + on-phone verification against prod (map fills, no scroll, dock states,
  re-center, chip/legend/badge).

## Out of scope

- Desktop dock/playback restyling; Android app; server. Zoom-level preferences.
