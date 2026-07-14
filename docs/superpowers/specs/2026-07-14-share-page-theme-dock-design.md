# Share Page: Light-Default Theme Toggle + Guest Battery Dock — Design

**Date:** 2026-07-14
**Status:** Approved in intent by user ("default light mode + top-right toggle, saved in
browser; put back a very simple battery status and flow like the 2-line mobile version");
details decided autonomously while the user was away.

## Goal

Two changes to the public guest "find me" page (`/share/<token>`):

1. **Theme:** default **light**, with a sun/moon toggle at the top right of the header,
   persisted in `localStorage["bmsmon-share-theme"]` (`"light" | "dark"`). The
   pre-hydration script in `web/share/index.html` reads the stored value (else light)
   so there is no flash; the map tiles follow the toggle live.
2. **Guest battery dock:** re-introduce a minimal 2-line CAP/FLOW readout — the mobile
   Journey dock's bottom two lines — so a guest can see whether the chair is actively
   discharging/moving. **This deliberately relaxes the original "no battery data"
   rule** (user decision): the guest now sees SOC and net power of the active base,
   and nothing else (no voltage, temperature, cells, cycles, or fleet info).

## Feed change (server)

`GET /share/{token}/feed` gains one top-level key:

```
"status": {
  "ts": <ms of freshest sample>,
  "soc": <min SOC across the active base's fresh packs, int>,
  "packs": [{"label": "A", "soc": 98}, ...],   // per-pack, for the "A98·B97" detail
  "current_a": <summed A, signed>,
  "power_w": <summed W, signed>,
  "regen": <true if any fresh pack flags regen>
} | null
```

- Source: the existing `q.fleet_snapshot(conn)` (latest real-telemetry row per pack) —
  no new SQL. A **pure helper** `pick_guest_status(rows, now_ms)` in
  `server/app/routers/share.py` does the aggregation:
  - Active base = `group_id` of the freshest row (the staged base polls at 1.5 s, so
    it is effectively always freshest).
  - Fresh = `ts_ms >= now_ms - 120_000` (mirrors the web LIVE_STALE_MS). Freshest row
    older than that → `status: null` (guest sees an empty, dimmed dock).
  - Pack label = trailing letter of the battery alias (`"2012 · A"` → `"A"`), falling
    back to the last 2 address chars.
- Exact key sets are pinned by tests: top-level feed keys now
  `{points, last, expires_at, now, owner, status}`; `status` keys exactly
  `{ts, soc, packs, current_a, power_w, regen}`; pack keys exactly `{label, soc}`.
  Still absolutely no voltage/temp/cell/cycle fields.

## Guest dock (web)

- `web/share/src/dock.ts` — pure model mirroring `web/src/v2/model/dock.ts` semantics,
  operating on the feed's `status` object:
  - `guestCap(status)` → `{pct, detail, band}`: pct = status.soc; detail =
    `"A98·B97"` join; band ok > 30, warn > 15, else crit (same alert bands).
  - `guestFlow(status)` → `{kind, watts, frac}`: direction from summed current vs
    `DISCHARGE_EPS = 0.1 A` (current < −ε → `out`; > ε → `regen` if regen flag else
    `chg`; else `idle`); watts = `|power_w|` rounded; frac vs `PAIR_FLOW_FULL_W = 600`.
  - Both handle `status: null` → `{pct: null, …}` / idle-empty.
  - Vitest coverage for bands, direction thresholds, null handling.
- `web/share/src/Dock.tsx` — the two CAP/FLOW rows, visually identical to
  `JourneyDock`'s bottom lines (same bar/fill/eyebrow styles, `CAP_FILL`/`FLOW_FILL`
  gradients, tabular-nums mono). Rendered between the map and the ArrowPanel.
  `status: null` → empty bars, "—" and dimmed IDLE.

## Theme toggle (web)

- `web/share/src/theme.ts` — `loadShareTheme()` (localStorage else `"light"`) and
  `saveShareTheme(t)`; App holds `theme` state, applies
  `document.documentElement.dataset.theme` in an effect, passes it to `JourneyMap`
  (replacing the one-shot dataset read), and saves on toggle.
- Toggle button: far right of the header row (☀ in dark mode → switch to light shows
  ☾ … concretely: button shows the icon of the mode it switches TO), `aria-label`
  "Switch to dark/light mode".
- `web/share/index.html` pre-hydration script becomes: stored
  `bmsmon-share-theme` value if `"dark"`/`"light"`, else **light** (no more
  prefers-color-scheme).

## Out of scope

- No changes to v1/v2 dashboards, the share CRUD, tokens, or the Traefik zone.
- No per-share opt-out of the dock (all guests see it).
- Android app untouched.

## Testing

- Server: pure `pick_guest_status` unit tests (freshest-group selection, staleness
  null, label derivation, sums/min/regen); feed endpoint test updated for the new
  key sets and a seeded two-pack status case.
- Web: vitest for `dock.ts` (+ `theme.ts` load/save); build + full suites; deploy
  verified via curl (feed JSON shape) since the visual toggle needs a browser.
