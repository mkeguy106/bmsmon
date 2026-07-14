# Share Page: Discharge/Transit Trail Detail + Toggle + Legend — Design

**Date:** 2026-07-14
**Status:** Approved by user (design presented in conversation; "yes, and include the legend").

## Goal

Bring the v2 Journey trail semantics to the guest share page: discharge-colored trail
(green→amber→red by effort) with dashed grey transit legs (moving in a vehicle, no
discharge), a guest-facing toggle to fall back to the plain uniform line, and a compact
legend so guests understand the colors.

## Feed change (server)

`gps_track_all` (queries.py) gains `avg(power_w)::real` and `avg(current_a)::real` per
15 s bucket (same aggregation as `track_series`); feed points become exactly
`{t, lat, lon, power_w, current_a}` (nullable). This extends the deliberate
battery-data relaxation from the dock to the day's trail context — still never SOC per
point, voltage, temps, cells, or cycles. Key sets pinned by tests.

## Guest page (web)

- `web/share/src/trail.ts` — pure module:
  - `type TrailMode = "detail" | "plain"`, `TRAIL_KEY = "bmsmon-share-trail"`,
    `loadTrailMode(storage)` (default `"detail"`), `saveTrailMode(storage, m)` —
    same defensive-storage pattern as `theme.ts`.
  - `trailProps(points: TrackPoint[], mode)` → `{ points, segKinds }`:
    - `detail`: segKinds via v2's `classifySegment(p, haversineMi(prev, p))`
      (destination-indexed, index 0 idle-head, exactly like JourneyView); points
      passed through untouched (JourneyMap's `metric="power"` then colors by
      `dischargeColor`).
    - `plain`: points cloned with `power_w`/`current_a` nulled, segKinds all
      `"active"` — today's uniform green line.
  - Vitest coverage: classification passthrough, plain stripping, mode persistence.
- `App.tsx`: feed points now map `power_w`/`current_a` into the TrackPoints
  (`cleanTrack` spreads point objects, so the fields survive cleaning); `trailMode`
  state (loaded from storage, persisted on toggle); `trailProps` feeds JourneyMap.
- **Toggle chip:** bottom-left map overlay (opposite the re-center button), mono chip
  styled like the LIVE badge: `TRAIL · DETAIL` ⇄ `TRAIL · PLAIN`.
- **Legend:** a second compact mono chip stacked above the toggle, shown only in
  detail mode: colored dots green/amber/red = "driving effort", a dashed grey dash
  glyph = "vehicle". One line, no interaction.

## Out of scope

Hotspot markers, playback, energy chart, per-share configuration of the toggle.

## Testing

Server: updated feed key-set/value assertions. Web: trail.ts vitest; build + suites;
deploy verified via curl (feed point shape); visual check by the user.
