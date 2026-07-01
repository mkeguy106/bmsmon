# WebUI Temperature Mirror — Implementation Spec

**Date:** 2026-07-01
**Status:** Approved
**Design source:** `design_handoff_temperature_alerts/Main Stage - Temperature.dc.html` (+ README) from the
claude.ai design project `5c6fbbf8-...`. Phase 2 of the temperature feature; the Android app + phone→cloud
push shipped in phase 1 ([[bmsmon-temperature-feature]]).

## Scope
Read-only WebUI mirror of the phone's temperature monitoring: a vertical temperature gauge per stage
pack, an escalating alert banner, a full-viewport flash/acknowledge overlay (rank ≥ CRITICAL), a
"synced from Android" indicator, a battery-profile panel (specs + safety-envelope diagram + threshold
table), a °C/°F unit toggle, and a conditions simulator (test tool). The webui **reads** the synced
thresholds; there is **no edit path** back to the phone.

## Server — read endpoint
- `GET /web/temp-config` in `server/app/routers/web.py` (Authentik-auth via `current_user`, like
  `/web/fleet`). Returns `{ "configs": [TempConfig, ...] }` (usually one device/profile).
- `q.get_temp_config_all(conn)` in `queries.py` — `SELECT ... FROM device_temp_config ORDER BY
  device_id, profile_id`.
- `TempConfig` Pydantic model in `models.py` (device_id str, profile_id, the four °C ints, unit,
  updated_at_ms, received_at iso). Response JSONable via the existing `_jsonable` helper.
- Test: `server/tests/test_web_temp_config.py` — after an upsert, `GET /web/temp-config` returns it
  (with dev-trust-headers auth as the other web tests do).

## Shared TS logic — `web/src/temp.ts` (vitest'd)
Mirror of Android `TempAlerts.kt`:
- `TempThresholds { coldCautionC, hotCautionC, coldCritC, hotCritC }`, envelope constants
  (cutoffs −20/60, locks 0/50) as module consts (Redodo is the only profile).
- `TempRank` (SAFE..CUTOFF, 0..4), `TempSide` (COLD/HOT/NONE), `tempZone(tempC, thr) → { rank, side,
  key }` with the exact ladder (cutoff → crit → charge-lock warning → caution → safe).
- `tempFillPct(tempC) = clamp(tempC+30, 0, 100)`.
- `formatTemp(c, unit)` (°F default), `formatDelta(dC, unit)`, `cToF`, `dualStr(c)` ("c°C / f°F").
- `zoneColorVar(zone) → "var(--...)"`, `zoneLabel(zone)`, `zoneCopy(zone, thr)` (title + message, from
  the mock's Z table), `marginToCutoffC(tempC, side)`.
- `worstPack(packs)` helper (max rank).
Tests cover the ladder boundaries, fill clamp, unit/delta conversion, worst-of selection.

## Theme — `web/src/theme.css`
Add tokens (dark + `[data-theme="light"]`): `--cool #46b3c9`, `--cold #3d86d6`, `--cold-crit #2f6fe0`,
`--safe #2ecc71`, `--warm #e2b01e`, `--hot #e67e22` (light variants per the handoff). Add the
`.temp-slider` gradient range styling from the mock.

## Components (`web/src/components/`)
- `TempGauge.tsx` — vertical gauge (18×236): five fixed zone bands, mercury fill to `tempFillPct`
  (zone-color gradient), zone-colored marker with rank-scaled glow, tick column (CUTOFF ±, CRITICAL ±
  at `(t+30)%`), live reading chip. Props: `{ tempC, thr, unit }`.
- `TempBanner.tsx` — worst-pack banner: dot (glow) + zone label + title + message (+ margin sentence at
  rank 3). Props: `{ worst, unit }`. Renders nothing / "ALL SAFE" when rank 0.
- `TempOverlay.tsx` — fixed full-viewport wash (pulsing), big `TEMPERATURE` headline + mono detail +
  ACKNOWLEDGE. Shown when worst rank ≥ 3 and not acknowledged (ack keyed by zone `key`, resets when the
  zone changes). Props: `{ worst, unit, onAck }`.
- `SyncedIndicator.tsx` — green dot + "Alert thresholds **synced from Android** · <phone> · updated
  <rel> ago" + `READ-ONLY · EDIT ON PHONE` badge. Props: `{ config }` (uses `updated_at_ms`).
- `BatteryProfilePanel.tsx` — title + id + spec grid (static Redodo specs) + `TEMPERATURE SAFETY
  ENVELOPE` diagram (discharge/charge windows + red critical bands + scale) + threshold table (8 rows
  driven by the synced thresholds + fixed lock/cutoff points). Props: `{ thr, unit }`.
- `ConditionsSimulator.tsx` — range slider (−30..70) + presets (FREEZING/COLD/NORMAL/WARM/OVERHEAT);
  drives the **featured pack's** displayed temp when active, with a `SIM` badge on that pack. Clearly a
  test tool; default off (live). Props: `{ value, onChange, unit, featuredName }`.

## Wire-up
- `App.tsx`: fetch `GET /web/temp-config` on load + refresh (~60 s); pick the latest config; derive
  `thr` (fall back to Redodo defaults if none synced yet) + default unit. Add a °C/°F toggle + keep the
  existing theme toggle in the header (unit persisted in localStorage).
- `MainStage.tsx`: render `SyncedIndicator` + `TempBanner` above the stage; per pack show the existing
  `Ring` + `TempGauge` side by side + a zone label under the readout; compute `worstPack`; render
  `TempOverlay` and the `ConditionsSimulator` (sim temp overrides the featured pack). New props:
  `{ thr, unit, config, simTemp, onSim }` threaded from App.
- `PackCard.tsx`: TEMP tile honors the unit + turns its value zone-colored (small consistency win).

## Testing / verification
- vitest: `web/src/temp.test.ts` (zone ladder, fill, conversions, worst-of).
- server: `test_web_temp_config.py`.
- Build: `cd web && npm run build` (tsc + vite) clean.
- Visual: run `npm run dev` against the local dev backend (`docker-compose.dev.yml`) with a seeded
  `device_temp_config` + a fake fleet sample; screenshot via headless Chromium if available, else hand
  off a dev URL. Verify dark + light, safe + critical (via the simulator).

## Out of scope
Editing thresholds from the web (one-way by design). Any non-Redodo profile UI. Historical temp charts.

## Files
- Server: `web.py`, `queries.py`, `models.py`, `tests/test_web_temp_config.py`.
- Web new: `temp.ts`, `temp.test.ts`, components `TempGauge/TempBanner/TempOverlay/SyncedIndicator/
  BatteryProfilePanel/ConditionsSimulator.tsx`.
- Web edit: `App.tsx`, `api.ts`, `theme.css`, `components/MainStage.tsx`, `components/PackCard.tsx`,
  `types.ts` (add `TempConfig` type).
