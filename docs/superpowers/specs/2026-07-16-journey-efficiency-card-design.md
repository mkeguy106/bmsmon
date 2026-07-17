# Journey efficiency card — design

**Date:** 2026-07-16
**Status:** approved, implementing
**Supersedes:** the Journey playback scrubber (WebUI v2, desktop)

## Problem

The Journey view's playback card (play button + slider that auto-advances a cursor
around the already-visible track) adds little — it animates a dot along a path you can
already see. Meanwhile the learned per-mile energy cost (`whPerMile`, learned by the
Android range learner and shipped to the WebUI via `/web/range-config`) never appears in
Journey, even though Journey is exactly where "can I get home on this?" gets asked.

Replace the playback card with an **efficiency card** that answers, mode-aware:

- **Live today window** → *can you make it?* — today's actual cost-per-mile, how it
  compares to the learned band, and how far the remaining charge goes at today's rate vs
  the usual rate.
- **Past day** → *this outing* — the outing's cost-per-mile, energy used, % drained, and
  where the cost landed against the learned band.

Point-inspection (previously driven by the slider cursor) is **kept via hover**: hovering
the energy-over-distance chart moves the map marker and shows that point's readouts. The
play loop and slider are removed.

## Scope

- Desktop v2 Journey only. Mobile Journey is map-first (`JourneyDock`) with no playback;
  it is unchanged.
- Range-mode (multi-day) windows: the efficiency card is day-scoped, so it shows a hint
  ("Select a single day") in range mode.

## Data

All inputs already reach `JourneyView`:

- Cleaned merged base track `points` (`cleanTrack(useTrack(...))`), `cumMi`, `summary`
  (has `activeMiles`, chair-driven miles excluding transit/vehicle legs).
- `data.rangeParams: Map<address, RangeParams>` (already on `FleetData`) — the learned
  `whPerMile` band + `learnedDays` per pack.
- The base's connected packs' live `remaining_ah` and charging state.

No new endpoint. Energy is the **viewed outing's** energy, not the learner's whole-day
(incl. idle) figure — an acceptable, labelled difference ("this outing").

## Pure model — `web/src/v2/model/efficiency.ts` (+ `efficiency.test.ts`)

Energy basis is **base-total** throughout (the merged track sums pack power, so remaining
Wh and the band are summed across connected packs to match).

- `outingWh(points)` — integrate `|power_w| × Δt` over buckets where `current_a <
  -DISCHARGE_EPS`. Cap each bucket's Δt at 60 s so a disconnect gap can't inflate Wh.
- `drainedPct(points)` — first→last SOC drop over the track (merged points carry the
  weaker pack's min SOC); null if flat/charging/unknown.
- `baseBand(packParams)` — sum each connected pack's `whPerMile` band → base-total band;
  null if none valid.
- `bandStatus(costPerMile, band)` — `below` (cheaper = better) / `inside` / `above`.
- `efficiencySummary({ points, activeMiles, packParams, remainingAh, charging, live })`
  → `{ wh, activeMiles, costPerMile, drainedPct, band, status, seed, milesAtTodayRate,
  milesAtUsualRate }`:
  - `costPerMile = wh / activeMiles`, null when `activeMiles < MIN_OUTING_MI` (0.5, the
    learner's own gate) or `wh <= 0`.
  - `seed = packParams.some(p => p.learnedDays === 0)` → the band is still the seed
    estimate; the card labels it and softens the comparison.
  - Projection only when `live && !charging` and base has remaining Wh:
    `milesAtTodayRate = baseRemWh / costPerMile`,
    `milesAtUsualRate = baseRemWh / mid(band)` (`baseRemWh = Σ remaining_ah × 12.8`).

## Component — `web/src/v2/components/EfficiencyCard.tsx`

Takes the slot the playback card vacated.

- Eyebrow: `live ? "CAN YOU MAKE IT?" : "THIS OUTING"`.
- Headline: `{cost} Wh/mi` (or an empty-state line when `costPerMile == null`:
  "Not enough driving to gauge efficiency").
- Band chip: status → label + tone (`inside`/`below` = ok, `above` = warn); when `seed`,
  chip reads "vs seed est." muted.
- Readouts: DRIVEN (`activeMiles` mi), USED (`wh` Wh), DRAINED (`drainedPct` %).
- Live projection line (when `milesAtTodayRate != null`):
  "~{X} mi left at today's rate · ~{Y} at your usual".
- Charging (live): projection suppressed, small "charging — see recharge plan" note; the
  retrospective figures still show if the window has an outing.

## Interaction — hover inspection

- `JourneyView` state: replace `playing`/`cursorIndex`/playback `useEffect`/`PLAY_STEP_MS`
  with `hoverIndex: number | null`.
- `EnergyDistanceChart` gains `onHover(index | null)`: `mousemove` maps pixel-x → nearest
  point index; `mouseleave` → null. Its cursor line renders only when an index is set.
- `JourneyMap` cursor marker driven by `hoverIndex ?? -1` (an out-of-range index already
  removes the marker — no map change beyond passing the value).
- The SOC / DRAW / DIST / STATE readouts move onto the energy-chart card and are
  hover-driven; with no hover they show trip totals (or "—").

## Testing

- `efficiency.test.ts`: `outingWh` (discharge-only, Δt cap, sign), `drainedPct`,
  `baseBand` sum + invalid-band rejection, `bandStatus` boundaries, `efficiencySummary`
  gates (`MIN_OUTING_MI`, seed flag, projection only when live+not-charging), and the
  base-total energy consistency (cost vs band vs projection share one basis).
- Full `web/` vitest + `tsc` build + Playwright smoke (Journey view screenshot).

## Out of scope

- The per-outing segmentation / outings list (brainstorm idea 1) — deferred.
- Mobile efficiency readout — deferred; mobile Journey unchanged.
