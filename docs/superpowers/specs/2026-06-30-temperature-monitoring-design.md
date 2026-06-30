# Temperature Monitoring & Alerting (Android + cloud push) — Implementation Spec

**Date:** 2026-06-30
**Status:** Approved
**Design source:** claude.ai/design project `Battery temperature monitoring setup`
(`design_handoff_temperature_alerts/` — README + `Android - Temperature.dc.html`). That handoff is
the high-fidelity source of truth for look/copy/behavior; this spec covers how it lands in the repo
and the integration decisions beyond the mock.

## Scope (this round)
- **Android app: full feature** — vertical temperature gauge on the main stage, temperature alerts
  unified with the existing capacity alerts, per-profile thresholds tunable in a new Settings page,
  °C/°F unit preference, reset-to-defaults.
- **Cloud push (phone → server):** on threshold change (when sync is on) the phone uploads the
  profile's temp config (signed + gzipped, like ingest) to a new endpoint; the server stores the
  latest per device. **WebUI rendering of it is the next phase** (not this round).
- Verified on the **local emulator** (`test_device`) before installing on the Pixel.

## Pure logic — `model/TempAlerts.kt` (new, TDD'd)

```kotlin
data class TempThresholds(val coldCautionC: Int=5, val hotCautionC: Int=45,
                          val coldCritC: Int=-12, val hotCritC: Int=53)
data class TempEnvelope(val coldCutoffC: Int=-20, val hotCutoffC: Int=60,
                        val chargeLockColdC: Int=0, val chargeResumeColdC: Int=5,
                        val chargeLockHotC: Int=50, val defaults: TempThresholds = TempThresholds())

enum class TempSide { COLD, HOT, NONE }
enum class TempRank { SAFE, CAUTION, WARNING, CRITICAL, CUTOFF }  // ordinal 0..4

data class TempZone(val rank: TempRank, val side: TempSide)
fun tempZone(tempC: Float, t: TempThresholds, env: TempEnvelope): TempZone
```

Ladder (from the handoff, evaluated cold→hot):
```
t <= coldCutoff(-20)        -> CUTOFF  · COLD
t <= coldCrit              -> CRITICAL· COLD   (margin to -20)
t <= 0                     -> WARNING · COLD   (charge lock)
t <= coldCaution           -> CAUTION · COLD
t >= hotCutoff(60)         -> CUTOFF  · HOT
t >= hotCrit               -> CRITICAL· HOT    (margin to 60)
t >= 50                    -> WARNING · HOT
t >= hotCaution            -> CAUTION · HOT
else                       -> SAFE
```

Helpers (also pure, tested):
- `tempFillPct(tempC) = clamp(tempC + 30, 0, 100)` — gauge mercury/marker height (gauge spans −30…+70).
- `formatTemp(c, unit)` (default **F**), `formatDelta(dC, unit)` (`Δ°F = round(Δ°C*9/5)`).
- `tempZoneColor(zone)` → `Bm` token (cold-crit/cool/safe/warm/power/critical) for gauge + stat.

## Battery profile — `ble/profile/BatteryProfile.kt`
Add `val tempEnvelope: TempEnvelope = TempEnvelope()`; set Redodo's explicitly (defaults `5/45/-12/53`,
cutoffs `-20/60`, lock `0`, resume `5`, hot lock `50`). Reset reads `profile.tempEnvelope.defaults`.

## Unified alerts (extend the alert work already in the engine/ViewModel)
- Add `enum AlertType { CAPACITY, TEMPERATURE }`.
- The stage's shown alert is the **worst** of the capacity alert (existing `evalStageAlert`) and the
  temperature alert (new `tempZone` over the worst stage pack), carrying its `AlertType`.
- `StageAlert` gains `alertType` + a `detail` string. `DangerOverlay` (HomeScreen) shows the naming
  headline `TEMPERATURE` / `BATTERY CAPACITY` (~42sp/800, white, layered text-shadow) + a mono detail
  line (`CRITICAL · COLD · 2012·A · 11°F TO CUTOFF`). Ack → persistent strip with the headline.
- **Temperature flashes on rank ≥ CRITICAL**, gated by `tempAlertsEnabled`. Capacity behavior is
  unchanged.
- `AlertNotifier` (headless): a temperature crossing at rank ≥ CRITICAL posts on the critical channel;
  WARNING/CAUTION do not notify (they color the gauge/stat only). Dedup mirrors the SOC path, keyed by
  `(alertType, band)`.

## Stage UI
- New `ui/gauge/TempGauge.kt` — vertical thermometer per the mock: track 16×158, 5 zone bands
  (bottom→top with the handoff's rgba washes), mercury fill to `tempFillPct`, zone-colored 4px marker
  with glow (stronger when critical), reading chip, CUTOFF tick at 10%/90%.
- `StageScreen`: featured pack becomes a `Row` (gap ~18) of the dual-ring gauge + `TempGauge`; child
  order flips with `tempGaugeSide` (default **LEFT** of the SOC gauge); gauge wrapped in
  `if (showTempGauge)`; when hidden the SOC gauge centers alone.
- `StatGrid`: add a **TEMP** tile; value color `critical` when a temp alert is active, else `accent`.
  Honors the °C/°F unit.

## Settings — `ui/settings/SettingsScreen.kt`
- New `SettingsPage.Temperature`. Entry from the settings list → a single Redodo battery-profile page
  (per-profile, keyed by `profileId`; only one profile exists today).
- Sections per the mock: identity card (G24 badge, name, id, spec chips, model-specific note);
  "Flash stage on temperature" master toggle; "Main stage" (show gauge + Left/Right position);
  Caution thresholds (cold ≤ 0…15 def 5, hot ≥ 35…55 def 45); Critical thresholds (cold ≤ −19…−2 def
  −12, hot ≥ 50…59 def 53) with the "fires before the BMS cutoff" note; next-alert sentence; fixed BMS
  cutoffs (read-only −20/60); cloud-sync section (toggle, status row, PHONE→CLOUD→WEB diagram); reset.
- Each threshold row: colored dot, label, value in user unit + alt unit, −/+ steppers (±1°C), slider.
- A °C/°F unit control (preference) drives every temperature display app-wide.

## State — `data/SettingsStore.kt` (new keys)
- `tempUnit: {C,F} = F`
- `tempThresholdsByProfile: Map<profileId, TempThresholds>` (serialized JSON; absent → profile defaults)
- `tempAlertsEnabled: Boolean = true`
- `showTempGauge: Boolean = true`, `tempGaugeSide: {LEFT,RIGHT} = LEFT`
- `cloudSyncAlerts: Boolean = true`
- setters: `setTempUnit`, `setTempThreshold(profileId,key,valueC)`, `resetTempThresholds(profileId)`,
  `setTempAlertsEnabled`, `setShowTempGauge`, `setTempGaugeSide`, `setCloudSyncAlerts`.

## Cloud push (phone → server) — one-way, signed, gzipped, durable
Rather than mixing a config blob into the telemetry sample outbox, use a durable "pending config"
that reuses the existing signing/gzip/upload-loop:
- When temp thresholds change and `cloudSyncAlerts` is on, persist a `pendingTempConfig` JSON
  (`profileId`, thresholds, unit, `updatedAtMs`) in prefs (latest-wins).
- `TelemetryReporter` upload loop: if a pending config exists and online, `postSigned` it (gzip +
  `Content-Encoding: gzip`, same JWT body-hash scheme) to **`POST /api/v1/config`**; clear on 2xx.
- **Server** (`server/`): `POST /api/v1/config` — device-JWT auth + body-hash verify + gzip-decompress
  (same as `/ingest`). Upserts a new table `device_temp_config(device_id PK, profile_id, thresholds
  jsonb, unit text, updated_at_ms bigint, received_at)`. Idempotent schema in `schema.sql`. Returns
  `{ok:true}`. (WebUI read endpoint + rendering = next phase.)
- Status row: "Synced 2m ago" (green) when pending cleared recently / "Paused" when sync off.

## Testing
- `TempAlertsTest` (unit): the full zone ladder incl. exact boundaries (coldCrit, 0, cutoff, hot
  mirror); `tempFillPct` clamp; `formatTemp`/`formatDelta` C↔F incl. negative + rounding; reset →
  profile defaults; per-profile threshold storage round-trip (SettingsStore JSON).
- Server: `POST /api/v1/config` accepts a valid signed (gzipped) body and upserts; rejects bad sig /
  corrupt gzip; idempotent on repeat.
- Notification building stays thin/untested; Compose UI verified visually on the emulator.

## Files
- Android new: `model/TempAlerts.kt`, `ui/gauge/TempGauge.kt`.
- Android edit: `ble/profile/BatteryProfile.kt`+`Profiles.kt`, `ui/home/StageScreen.kt`,
  `ui/home/HomeScreen.kt` (DangerOverlay+strip), `ui/settings/SettingsScreen.kt`,
  `data/SettingsStore.kt`, `BatteryViewModel.kt`, `monitor/MonitorEngine.kt`,
  `monitor/AlertNotifier.kt`, `cloud/TelemetryReporter.kt` (+ `cloud/CloudJson.kt` for the config JSON).
- Server: `app/routers/api_device.py` (+ `app/models.py`, `app/db/schema.sql`, `app/db/queries.py`),
  tests under `server/tests/`.
- Tests: `app/src/test/.../TempAlertsTest.kt`, server `test_config.py`.

## Out of scope (next phase)
WebUI temperature gauge, alert banner, profile panel, and the read-only "synced from Android"
indicator (`Main Stage - Temperature.dc.html`); the server **read** endpoint the webui will call.
