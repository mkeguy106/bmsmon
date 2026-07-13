# Journey Live Map (WebUI v2) — Design

**Date:** 2026-07-13
**Status:** Approved

## Goal

When the Journey view's selected window includes "now" (day = today, or a range ending
today), the map goes live: the trail refreshes automatically and a wheelchair icon shows the
chair's current position in near-realtime, with follow-until-you-pan camera behavior.

## Approach (approved: hybrid poll + live store)

- **Trail**: re-poll the existing `/web/track` fetch every **15 s** while live (one server
  bucket — faster polling buys nothing). Existing `cleanTrack` pipeline applies unchanged.
- **Marker**: driven by the v2 fleet store, which already receives every sample (with GPS)
  over the live WebSocket at ~1.5 s — the icon moves between trail refreshes.
- No server or Android changes.

## Design

1. **Liveness**: `isLive = toMs > now`, computed in `JourneyView` from the already-resolved
   window. Rolls over naturally at midnight (next render after the window ends). A small
   pulsing `● LIVE` badge (var(--live)) renders beside the date controls while live.

2. **`useTrack(addresses, fromMs, toMs, refreshMs?)`**: optional refresh interval. When set,
   refetch the same window every `refreshMs`; keep the existing alive-guard and
   last-good-track-on-failure semantics. `JourneyView` passes `isLive ? 15_000 : undefined`.

3. **`useLivePosition` (pure selector + thin hook, `web/src/v2/model/live.ts`)**:
   - `isWindowLive(toMs, nowMs): boolean`
   - `livePosition(items, addresses, nowMs): { lat, lon, tsMs } | null` — the freshest
     GPS-carrying item among the base's pack addresses, `null` when the freshest fix is
     older than **120 s** (a stale marker lies; hide it) or absent.
   Both pure and unit-tested; `JourneyView` feeds them from `data.items`.

4. **`JourneyMap` additions** (new props `live: {lat, lon} | null`, `fitKey: string`):
   - **Wheelchair marker**: `L.marker` with an `L.divIcon` — circular accent-colored badge,
     white ♿ glyph, soft CSS pulse ring (keyframes added to the v2 stylesheet). Distinct
     from the playback cursor, which continues to work for scrubbing while live.
   - **Fit-bounds once per window**: the fit effect keys on `fitKey` (the view's
     addresses+window key), NOT on `points` — a 15-s live refresh must not yank pan/zoom.
     (Also fixes the latent refit-on-refetch annoyance for non-live reloads.)
   - **Follow-until-you-pan**: internal `follow` state, true whenever live begins. On each
     live-position change while following, `panTo` inside a programmatic-move guard; a
     user-initiated `dragstart`/`zoomstart` sets follow=false and shows a small `⌖ FOLLOW`
     overlay button (map corner) that recenters and re-enables on click.
   - **Empty-day live init**: the map initializes when there are points OR a live position
     (today with no GPS yet shows the map centered on the chair instead of the
     "No GPS trip recorded" placeholder; placeholder text while live and positionless reads
     "Waiting for GPS…").

5. **Unchanged**: playback/cursor semantics, energy chart, trip summary (they update as the
   refreshed track flows through the same memos), server, Android.

## Testing

- vitest units for `isWindowLive` (window ends in past / future / exactly now) and
  `livePosition` (freshest wins, staleness cutoff at 120 s, missing GPS, empty addresses).
- Full web gate (vitest + tsc + build); visual/deploy verification on the production
  dashboard with the chair live.

## Out of scope

- Client-side WS track appending (approach B).
- Mobile-app map, multi-base live view, geofencing/alerts.
