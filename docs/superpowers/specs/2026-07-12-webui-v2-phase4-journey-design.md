# WebUI v2 — Phase 4: Journey / GPS (Design Spec)

**Status:** approved design, ready for implementation plan · **Date:** 2026-07-12
**Roadmap:** `2026-07-12-webui-v2-roadmap.md` · **Design source:** `design_handoff_bmsmon_webui_v2/`
(view 03-journey) · **Builds on:** Phases 1–3 (shipped + deployed). **This is the final v2 view.**

Phase 4 turns the Journey placeholder into a live GPS trip visualization: a real Leaflet base map
with a **discharge-colored trail** (green→amber→red by battery draw, grey dashed for car/train
transit legs), discharge **hotspots**, DAY/RANGE date navigation, a **playback** scrubber with live
readouts, and an **energy-over-distance** chart. It adds one new read-only endpoint, `GET /web/track`.

---

## 1. Goals & non-goals

**Goals**
- Live **Journey** view: Leaflet map + discharge trail + transit legs + hotspots + date nav +
  playback + energy-over-distance chart + active-pair dock.
- New `GET /web/track` (downsampled per-pack GPS + discharge).
- Pure, tested trail model (segment classification, distance, hotspots, color bucketing, energy series).

**Non-goals (Phase 4)**
- Any change to v1 or Phases 1–3 behavior.
- Historical route replay beyond the selected day/range; no live-follow on the WS.
- Turn-by-turn / routing / geocoding. The map shows the recorded trail on a base map, nothing more.

---

## 2. Backend — `GET /web/track`

- `current_user`, read-only. `GET /web/track?address=<mac>&from_ms=&to_ms=`.
- **15-second buckets** of GPS-carrying real telemetry (new `track_series` in `queries.py`):
  ```sql
  SELECT (ts_ms / 15000) * 15000 AS bucket_ms,
         avg(lat)::double precision AS lat, avg(lon)::double precision AS lon,
         avg(power_w)::real AS power_w, avg(current_a)::real AS current_a, avg(soc)::real AS soc
    FROM samples
   WHERE address = $1 AND ts_ms >= $2 AND ts_ms < $3
     AND link_event IS NULL AND lat IS NOT NULL AND lon IS NOT NULL
   GROUP BY bucket_ms ORDER BY bucket_ms
  ```
  (GPS only records outdoors, so results are small; averaging within 15 s smooths fused-provider
  jitter. `samples.lat/lon` are `double precision`.)
- **Response:** `{ "address", "points": [ { "t", "lat", "lon", "power_w", "current_a", "soc" }, … ] }`.
- Server tests: bucketing/averaging; the `lat IS NOT NULL` filter (indoor rows excluded); route
  auth (401) + seeded round-trip shape (mirror `test_web_history.py`).

**Web:** `getTrack(address, fromMs, toMs)` in `api.ts` + `decodeTrack` in `decode.ts`
(tolerant: drop points missing finite `t`/`lat`/`lon`; non-array/malformed-root → null). Types in a
new `web/src/v2/track.ts` (`TrackPoint`, `Track`).

---

## 3. Leaflet integration (one new npm dependency)

- Add **`leaflet`** to `web/package.json` `dependencies` (+ `@types/leaflet` dev). **~40 KB**; the only
  new runtime dep in all of v2. Use **vanilla Leaflet imperatively** (NO `react-leaflet` — one dep,
  not two). `import "leaflet/dist/leaflet.css"` in the map component (Vite bundles it).
- **Theme-matched raster tiles (no API key):** CARTO basemaps —
  light `https://{s}.basemaps.cartocdn.com/light_all/{z}/{x}/{y}{r}.png`,
  dark `https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png`,
  attribution `'© OpenStreetMap contributors © CARTO'`. Swap the tile layer when the app theme flips.
- **No CSP blocks external tiles** (verified: no CSP on the server, HTML, or prod headers). The map
  degrades gracefully to a blank pane + trail if tiles fail (offline).
- We draw the trail with **vanilla layers only** (no plugins): per-segment `L.polyline` (2-point,
  colored), `L.circleMarker` for hotspots + the playback cursor. Default marker-icon assets are NOT
  used (avoids the known Vite/Leaflet icon-path gotcha).

---

## 4. Frontend — pure trail model (`web/src/v2/model/journey.ts`, tested)

- `haversineMi(a: LatLon, b: LatLon): number` — great-circle miles between two coords.
- `type SegKind = "active" | "transit" | "idle"`;
  `classifySegment(prev: TrackPoint, cur: TrackPoint, movedMi: number): SegKind` —
  **active** when the segment is discharging (`current_a < -DISCHARGE_EPS`), **transit** when idle
  (`|current_a| <= DISCHARGE_EPS`) AND `movedMi > MOVE_EPS_MI` (phone moved, battery idle = car/train),
  else **idle** (stationary stop). `current_a` here is the base sum (see §6).
- `dischargeColor(powerW: number): string` — |power| → `var(--ok)`/`var(--warn)`/`var(--live)` by
  thresholds `POWER_GREEN_W` / `POWER_RED_W` (base-total; seed 150 / 350 W, tunable).
- `detectHotspots(points, { thresholdW, minGapMi }): { index, powerW }[]` — local |power| maxima above
  `thresholdW` (seed ~330 W base), thinned so two hotspots aren't within `minGapMi`.
- `cumulativeMiles(points): number[]` — running haversine sum (0 at index 0).
- `energySeries(points, cumMi): { d: number; power: number; transit: boolean }[]` — per point:
  cumulative distance `d`, base `power`, and whether that segment is transit (for the shaded legs).
- `tripSummary(points, cumMi): { miles, activeMiles, transitMiles, peakW, durationMin }`.
- Constants: `DISCHARGE_EPS = 0.1` (A, matches android/fleet), `MOVE_EPS_MI` (~0.003 mi ≈ 5 m),
  `POWER_GREEN_W`, `POWER_RED_W`, hotspot `thresholdW`. All named + tunable.

**Base-sum helper:** `mergeBaseTracks(tracks: Track[]): TrackPoint[]` — align the base's packs by
bucket `t`, **sum `power_w` and `current_a`** across packs, take the mean `lat/lon` (they're the same
phone), keep `soc` per-pack-min or avg (document which). This yields the chair's single track with
total draw. Tested.

---

## 5. Frontend — map + view components

- `web/src/v2/components/JourneyMap.tsx` — vanilla-Leaflet map: creates the map on a ref div, adds the
  theme tile layer, draws the trail as per-segment polylines (active = `dischargeColor`, transit =
  grey dashed `--text-4`, idle = skipped/faint), hotspot halos, and a playback cursor `circleMarker` at
  the current index; fits bounds to the trip; re-swaps tiles on theme change; cleans up on unmount.
  Props: `{ points, segKinds, hotspots, cursorIndex, theme }`. Empty (no points) → a muted "no GPS
  trip recorded" overlay (no map).
- `web/src/v2/components/EnergyDistanceChart.tsx` — inline-SVG chart (x = cumulative miles, y = base
  power), transit legs shaded with a faint band + "IN TRANSIT · BATTERY IDLE" label, a cursor line at
  the playback index. (A small dedicated chart — the History `LineChart` is time-x; this is
  distance-x with shaded legs.)
- `web/src/v2/useTrack.ts` — fetch the base's packs' tracks for `[fromMs,toMs]`, `mergeBaseTracks` →
  the chair track; `alive` guard; refetch on deps.
- `web/src/v2/views/JourneyView.tsx`:
  - **Date toolbar:** ‹ › day steppers, native `<input type="date">`, DAY/RANGE `Segmented` (RANGE
    reveals from/to). Resolve `[fromMs,toMs]`; default = today. Persist to `bmsmon-v2-journey`.
  - **Layout:** `JourneyMap` (main) + right dock (active-pair SOC `Ring`s + a discharge strip) +
    bottom **playback bar** (play/pause + a range scrubber, ~280 ms/step) driving `cursorIndex`, with
    SOC / draw (W) / distance (cumMi) / state readouts for the cursor, and the `EnergyDistanceChart`.
  - Base = daily-driver (`DAILY_DRIVER_BASE`), or the staged/first base with GPS that day.
  - On `mobile`, the map keeps a fixed height, the dock + playback stack below.

---

## 6. Shell wiring (`App.tsx`)

- Replace the `journey` placeholder with `<JourneyView data={data} theme={resolvedTheme} unit={settings.tempUnitPref} mobile={mobile} />`.
  (JourneyView needs the resolved `"dark"|"light"` theme for the tile layer — `useTheme` returns it;
  thread it through App.) No change to the single live store, nav, or the other five views.
- **This completes all six v2 views** — every nav item is now live (Devices stays "SOON").

---

## 7. Testing & verification

- **Web (vitest):** `journey.ts` — `haversineMi` (known coord pairs), `classifySegment`
  (active/transit/idle by current + movement), `dischargeColor` thresholds, `detectHotspots`
  (maxima + gap thinning), `cumulativeMiles`, `energySeries` transit flags, `mergeBaseTracks`
  (power/current summed, coords meaned, bucket-aligned); `decodeTrack` (valid + drop malformed +
  non-array→null).
- **Server (pytest):** `track_series` bucketing/averaging + `lat IS NOT NULL` filter; the route
  (401 + seeded round-trip shape).
- **End-to-end (verify skill):** build (with the new leaflet dep); drive `/v2/` (headless) — Journey
  renders the map + a discharge-colored trail from a seeded outdoor trip (with a transit leg + a
  hotspot), the date nav switches days, playback walks the cursor and updates readouts + the energy
  chart, and an indoor-only day shows the empty state; Command/Health/Alerts/Settings/History + v1
  unaffected; **theme flip re-styles the tiles**.

---

## 8. Out of scope / deferred

- Live-follow / WS-streamed trail; routing/geocoding; multi-day heatmaps.
- Self-hosting tiles (uses CARTO's public CDN; fine for personal use with attribution).
- The standing Phase-2/3 backlog (shared `v2/colors.ts`, alerts epsilon, notes CSRF) — separate task.

---

## 9. Open items to confirm during implementation

- **Hotspot & color thresholds** — seed `POWER_GREEN_W`/`POWER_RED_W`/hotspot `thresholdW` from the
  CLAUDE.md base-total calibration (per-pack p50~53/p95~164 W → base sum ~106/330 W); tune against a
  real trip in the verify step.
- **Base-track `soc` merge** — min across packs (the weaker pack bounds range) vs mean; pick and document.
- **Leaflet CSS import location** — import once in `JourneyMap.tsx`; confirm Vite emits it into the v2
  bundle and it doesn't leak into v1 (separate entry, so it won't).
- **RANGE mode across multiple days** — the trail simply concatenates the buckets in time order; large
  ranges stay bounded by the 15 s bucketing + outdoor-only GPS. Confirm the map fit-bounds handles a
  multi-day extent sanely.
- **Tile load failure / offline** — the map should still render the trail on a blank pane (Leaflet does
  this by default); confirm no crash when tiles 4xx.
