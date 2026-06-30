# GPS Telemetry (Phone → Cloud) Design

**Date:** 2026-06-30
**Status:** Approved (design)
**Scope:** Android app (`android/`), cloud server (`server/`), WebUI (`web/`).

## Motivation

Capture the phone's GPS location and send it to the bmsmon cloud alongside the existing
battery telemetry, as groundwork for a future map view (see [[bmsmon-gps-telemetry-next]]).
For now we do **nothing** with the coordinates beyond storing them and showing a single
"GPS" indicator in the WebUI confirming GPS telemetry is being received.

Non-goals: any mapping/visualisation of the coordinates; any change to the read-only BMS
BLE protocol or its safety model; heading/speed/altitude capture.

## Decisions (locked with user)

- **Sampling:** piggyback on the existing telemetry upload — attach the latest cached fix
  to each uploaded sample. No separate GPS timer/endpoint.
- **Background:** capture while backgrounded via the existing foreground service
  (`location` FGS type + `ACCESS_BACKGROUND_LOCATION`).
- **Location source:** `FusedLocationProviderClient` (Google Play Services). The app already
  depends on `com.google.android.gms:play-services-code-scanner`, so adding
  `play-services-location` is consistent.
- **Default:** `gpsEnabled` defaults **on** when cloud sync is enabled (set during `enroll()`),
  with a settings toggle to turn it off.
- **Coordinates attach to every sample** (nullable columns), not a separate heartbeat row.
- **WebUI indicator:** a single global header pill ("GPS ●"), green when any non-stale fleet
  item carries a non-null `lat`. Not per-pack, not per-device-in-admin.
- **Fields:** `lat`, `lon`, `gps_accuracy_m` only.
- **Delivery:** build, install over Tailscale, live-verify coordinates reach Postgres and the
  WebUI pill lights.

## Current architecture (as found)

### Android upload path
- `cloud/CloudJson.kt` — `SampleJson` (the JSON row); `sampleJson(...)` builder; `encodeBatch`.
- `cloud/TelemetryReporter.kt` — `report(addr, advertisedName, alias, groupId, t, tsMs, regen)`
  enqueues an `OutboxEntity`; upload loop signs (ES256 JWT) and POSTs batches to
  `/api/v1/ingest` (CloudConfig.ingestUrl). `reportingEnabled` gate.
- `monitor/MonitorEngine.kt` — on each BLE poll calls `reporter?.report(...)` (~line 179).
- `cloud/EnrollClient.kt` + `BatteryViewModel.enroll()` — on success sets
  `apiBaseUrl/deviceId/enrolled/cloudEnabled` and starts the reporter.
- `data/SettingsStore.kt` — `CLOUD_ENABLED`, `API_BASE_URL`, `DEVICE_ID`, `ENROLLED`,
  `INSTALL_UUID` keys; `Persisted` mirror; setters.
- `ui/settings/CloudSyncPage.kt` — `CloudSyncContent(state, onEnroll, onSetCloudEnabled, onForget)`;
  "Report to cloud" `ToggleRow`, enroll (QR/manual), Forget device.
- `AndroidManifest.xml` — `MonitoringService` `foregroundServiceType="connectedDevice"`;
  permissions incl. `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `POST_NOTIFICATIONS`.
- `monitor/MonitoringService.kt` — `startForegroundCompat()` passes
  `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE` on API ≥ 30.
- `ui/App.kt` — `POST_NOTIFICATIONS` runtime request via
  `rememberLauncherForActivityResult` (the pattern to mirror for location).
- `app/build.gradle` — deps incl. `play-services-code-scanner:16.1.0`; targetSdk 34, minSdk 26.

### Server (FastAPI + asyncpg + Postgres 16)
- `server/app/models.py` — `SampleIn` (telemetry fields), `IngestBody{batch_seq, samples}`.
- `server/app/db/schema.sql` — `samples` table (partitioned by `ts`), `batteries`, `devices`,
  `enrollment_codes`. Schema is idempotent SQL run on pool create (`db/pool.py`).
- `server/app/db/queries.py` — `sample_row()` (uses `_COLS`), `insert_samples()` (`_INSERT`),
  `fleet_snapshot()` (DISTINCT ON latest per address), `upsert_battery()`.
- `server/app/routers/api_device.py` — `POST /api/v1/ingest` verifies JWT, parses `IngestBody`,
  upserts batteries, inserts samples, publishes each to the live bus.
- `server/app/routers/web.py` — `GET /web/fleet` → `fleet_snapshot`. `ws.py` streams samples.
- `server/tests/test_ingest_jwt.py` — ingest happy-path test (the one to extend).

### WebUI (React + Vite + TS)
- `web/src/types.ts` — `Sample`/`FleetItem` types.
- `web/src/store.ts` — merges samples by address; stale logic.
- `web/src/App.tsx` — header with LIVE/RECONNECTING pill; `STALE_MS = 90_000`; MainStage +
  AllBatteries + AdminDevices.
- `web/src/ws.ts`, `web/src/api.ts` — live feed + REST.

## Data model

New nullable fields, consistent across the stack:

| Field | Android (`SampleJson`) | JSON key | Postgres column | Type |
|-------|------------------------|----------|-----------------|------|
| Latitude | `lat: Double?` | `lat` | `lat` | `double precision` |
| Longitude | `lon: Double?` | `lon` | `lon` | `double precision` |
| Accuracy (m) | `gpsAccuracyM: Float?` | `gps_accuracy_m` | `gps_accuracy_m` | `real` |

All null when GPS is disabled, permission not granted, or no fix yet. Timing uses the
existing per-sample `ts_ms` (piggyback) — no separate location timestamp.

## Component changes

### A. Android — location source
**New** `android/app/src/main/java/dev/joely/bmsmon/location/LocationSource.kt`:
- Holds a `FusedLocationProviderClient` and an `AtomicReference<GpsFix?>`.
- `data class GpsFix(val lat: Double, val lon: Double, val accuracyM: Float?)`.
- `start()`: if `ACCESS_FINE_LOCATION` (or coarse) granted, `requestLocationUpdates` with a
  `LocationRequest` (priority `PRIORITY_BALANCED_POWER_ACCURACY`, interval 10 000 ms, fastest
  5 000 ms); the callback stores a `GpsFix`. Also seeds from `lastLocation`.
- `stop()`: `removeLocationUpdates`, clears the cache.
- `current(): GpsFix?` returns the cached fix.
- `hasPermission(context): Boolean` helper (fine or coarse granted).

`app/build.gradle`: add `implementation("com.google.android.gms:play-services-location:21.3.0")`.

### B. Android — engine wiring
`monitor/MonitorEngine.kt`:
- Hold a `LocationSource`. Add `setGpsActive(active: Boolean)` that `start()`s/`stop()`s it.
  The engine starts location when `monitoring && gpsActive` and the reporter is active.
- In `onPoll`, read `locationSource.current()` and pass `lat/lon/accuracy` into
  `reporter.report(...)` (new params, nullable).

`cloud/TelemetryReporter.kt`:
- `report(...)` gains `lat: Double?, lon: Double?, gpsAccuracyM: Float?` params, forwarded to
  `CloudJson.sampleJson(...)`.

`cloud/CloudJson.kt`:
- `SampleJson` gains `lat`, `lon`, `gps_accuracy_m` (`@Serializable`, default null, so
  `encodeDefaults=false` omits them when null — confirm the Json config omits nulls).
- `sampleJson(...)` builder gains the three params.

### C. Android — foreground service type
`AndroidManifest.xml`:
- Add `<uses-permission ACCESS_FINE_LOCATION/>`, `ACCESS_COARSE_LOCATION`,
  `ACCESS_BACKGROUND_LOCATION`, `FOREGROUND_SERVICE_LOCATION`.
- `MonitoringService` `android:foregroundServiceType="connectedDevice|location"`.

`monitor/MonitoringService.kt`:
- `startForegroundCompat` computes the type bitmask: always `CONNECTED_DEVICE`, OR-ing
  `FOREGROUND_SERVICE_TYPE_LOCATION` **only when** GPS is active and `ACCESS_FINE_LOCATION`
  is granted (querying the engine state). Re-call `startForeground` with the updated type when
  GPS activation changes while the service runs.

### D. Android — settings + permission flow
`data/SettingsStore.kt`:
- Add `GPS_ENABLED = booleanPreferencesKey("gps_enabled")`; `Persisted.gpsEnabled: Boolean?`;
  `load()` reads it (null when unset → ViewModel default); `setGpsEnabled(on)` setter.

`BatteryViewModel.kt`:
- `UiState.gpsEnabled: Boolean = false`.
- Reducer: `gpsEnabled = p.gpsEnabled ?: p.cloudEnabled` — when the user has never set the GPS
  toggle, it defaults to **on iff cloud sync is enabled**. This makes GPS default-on for the
  already-enrolled device too (not only fresh enrolls), while an explicit off persists.
- `enroll()` success: also `store.setGpsEnabled(true)` and set `gpsEnabled = true` (default-on
  when cloud sync turns on).
- `forgetDevice()`: set `gpsEnabled = false`, persist, and `engine.setGpsActive(false)`.
- `setGpsEnabled(on)`: persist, update state, and call `engine.setGpsActive(on && enrolled)`.
- The engine's effective GPS-active = `monitoring && gpsEnabled && enrolled && cloudEnabled`.

`ui/App.kt`:
- Location permission launchers mirroring `askNotificationPermission`:
  - `RequestMultiplePermissions` for `ACCESS_FINE_LOCATION` + `ACCESS_COARSE_LOCATION`.
  - On grant (API ≥ 29), a second `RequestPermission` for `ACCESS_BACKGROUND_LOCATION`.
  - `onSetGpsEnabled(true)` triggers the request chain; result never blocks the toggle.
- Wire `onSetGpsEnabled = vm::setGpsEnabled` into `SettingsScreen` → `CloudSyncContent`.

`ui/settings/CloudSyncPage.kt`:
- Add a **"Send GPS location"** `ToggleRow` (visible when `state.enrolled`), bound to
  `state.gpsEnabled` / `onSetGpsEnabled`.
- A status line beneath it: "Permission needed" (toggle on, permission missing), "No fix yet"
  (on + permitted, no cached fix surfaced via state), or "Sending". For v1 the status may be a
  static helper string ("Location is attached to each upload when permitted") to avoid plumbing
  live fix state into the UI — see Open question O1.

### E. Server
`server/app/db/schema.sql` — after the `samples` CREATE TABLE, append:
```sql
ALTER TABLE samples ADD COLUMN IF NOT EXISTS lat double precision;
ALTER TABLE samples ADD COLUMN IF NOT EXISTS lon double precision;
ALTER TABLE samples ADD COLUMN IF NOT EXISTS gps_accuracy_m real;
```
`server/app/models.py` `SampleIn`: add `lat: float | None = None`, `lon: float | None = None`,
`gps_accuracy_m: float | None = None`.

`server/app/db/queries.py`:
- `_COLS`: add `lat`, `lon`, `gps_accuracy_m` (so `sample_row` copies them).
- `_INSERT`: add the three columns and `$21,$22,$23`; extend the `executemany` tuple.
- `fleet_snapshot()` SELECT: add `s.lat, s.lon, s.gps_accuracy_m`.

Ingest endpoint, JWT, auth, live bus: unchanged — new fields flow through `model_dump()`.

### F. WebUI
`web/src/types.ts`: add `lat?: number | null; lon?: number | null; gps_accuracy_m?: number | null`
to `Sample` and `FleetItem`.

`web/src/App.tsx`: add a header **"GPS"** pill beside the LIVE indicator. Compute
`gpsActive = nonStaleFleet.some(it => it.lat != null)`. Green dot + "GPS" when true, dimmed
when false. No other use of the coordinates.

## Data flow

BLE poll → engine reads `LocationSource.current()` → `reporter.report(..., lat, lon, acc)` →
`SampleJson` (coords included when present) → Outbox → signed POST `/api/v1/ingest` → server
`insert_samples` writes `lat/lon/gps_accuracy_m` → live bus → WS → WebUI store → header GPS
pill lights when recent samples carry coords.

## Error handling / edges

- **Permission denied / revoked at OS level:** `LocationSource.start()` no-ops; `current()`
  stays null; samples carry null coords; FGS stays `connectedDevice`. Monitoring unaffected.
- **No fix yet:** coords null until the first fused callback; columns nullable.
- **GPS off but enrolled:** reporter sends null coords; WebUI pill stays dim.
- **Background permission denied (foreground only granted):** fixes arrive while the app is
  visible; null while backgrounded. Acceptable degradation; no crash.
- **Old samples (pre-migration):** `lat/lon` null; WebUI treats null as "no GPS".
- **Android 14 FGS:** never include `location` type unless `ACCESS_FINE_LOCATION` granted, or
  the service start throws.

## Testing / verification

- **Server pytest** (`server/tests/test_ingest_jwt.py` or a sibling): POST a batch with one
  sample carrying `lat/lon/gps_accuracy_m`; assert `accepted == 1` and that `fleet_snapshot`
  (or a direct query) returns the stored coordinates.
- **Android `CloudJsonTest`:** assert `sampleJson(...)` serializes `lat/lon/gps_accuracy_m`
  when provided and omits them (no key) when null.
- **On-device (Tailscale):** enable "Send GPS location", grant fine + background location,
  confirm: (a) coordinates appear in Postgres `samples`, (b) the WebUI header "GPS" pill turns
  green. `adb install -r` only (preserve data).

## Open questions

- **O1 — GPS status line fidelity:** v1 uses a static helper string under the toggle to avoid
  plumbing live fix/permission state into `UiState`. If we want "Sending / Permission needed /
  No fix yet" to be live, the engine must surface permission + last-fix state into `UiState`.
  Default: ship the static string in v1; upgrade later if desired.

## Files touched

Android: `app/build.gradle`, `AndroidManifest.xml`,
`location/LocationSource.kt` (new), `monitor/MonitorEngine.kt`, `monitor/MonitoringService.kt`,
`cloud/TelemetryReporter.kt`, `cloud/CloudJson.kt`, `data/SettingsStore.kt`,
`BatteryViewModel.kt`, `ui/App.kt`, `ui/settings/CloudSyncPage.kt`,
`app/src/test/.../CloudJsonTest.kt`.
Server: `app/db/schema.sql`, `app/models.py`, `app/db/queries.py`, `tests/test_ingest_jwt.py`.
Web: `src/types.ts`, `src/App.tsx`.
