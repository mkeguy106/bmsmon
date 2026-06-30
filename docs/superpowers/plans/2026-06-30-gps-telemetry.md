# GPS Telemetry (Phone → Cloud) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Capture the phone's GPS location and send it to the bmsmon cloud on the existing telemetry upload, store it in Postgres, and show a single "GPS" indicator in the WebUI.

**Architecture:** GPS piggybacks the existing pipeline. A `FusedLocationProviderClient` keeps a cached fix; each uploaded sample gains nullable `lat`/`lon`/`gps_accuracy_m`. The server adds three nullable columns (idempotent `ADD COLUMN IF NOT EXISTS`); the WebUI lights a header pill when recent samples carry coordinates. No change to the read-only BMS BLE protocol.

**Tech Stack:** Android (Kotlin/Compose, Play Services Location, DataStore, JUnit4), Server (FastAPI + asyncpg + Postgres 16, pytest), Web (React + TS + Vite).

## Global Constraints

- Read-only BMS protocol only — no new BLE writes/opcodes. Do not touch `ble/` protocol code.
- New telemetry fields, exact names everywhere: `lat` (double precision), `lon` (double precision), `gps_accuracy_m` (real). All nullable; null when GPS off / no permission / no fix.
- Android: `lat: Double?`, `lon: Double?`, `gpsAccuracyM: Float?`. JSON config is `Json { encodeDefaults = true; explicitNulls = false }` → null fields are omitted.
- Location library: `com.google.android.gms:play-services-location:21.3.0` (GMS already used). targetSdk 34, minSdk 26.
- GPS default-on iff cloud sync enabled: reducer `gpsEnabled = p.gpsEnabled ?: p.cloudEnabled`.
- `location` FGS type added ONLY when GPS active AND `ACCESS_FINE_LOCATION` granted (else Android 14 throws).
- Never uninstall on device — `adb install -r` only. Tailscale ADB: `adb connect 100.102.146.11:5555` (connect port may differ — current session used `100.102.146.11:39187`).
- Server pytest requires a reachable Postgres (the project's test DB / `BMSMON_DATABASE_URL`). The conftest `TRUNCATE`s tables each test.

---

## File Structure

- `server/app/db/schema.sql` — add 3 nullable columns to `samples`.
- `server/app/models.py` — `SampleIn` gains 3 optional fields.
- `server/app/db/queries.py` — `_COLS`, `_INSERT`, executemany tuple, `fleet_snapshot` SELECT.
- `server/tests/test_ingest_jwt.py` — new test storing/reading GPS.
- `android/.../cloud/CloudJson.kt` — `SampleJson` + `sampleJson()` gain GPS fields.
- `android/.../cloud/TelemetryReporter.kt` — `report()` gains GPS params.
- `android/.../location/LocationSource.kt` — NEW fused-location wrapper.
- `android/app/build.gradle` — add play-services-location.
- `android/.../monitor/MonitorEngine.kt` — `MonitorState.gpsActive`, `LocationSource`, `setGpsActive`, onPoll passes fix.
- `android/.../monitor/MonitoringService.kt` — dynamic FGS type.
- `android/app/src/main/AndroidManifest.xml` — location permissions + FGS type.
- `android/.../data/SettingsStore.kt` — `gpsEnabled` persistence.
- `android/.../BatteryViewModel.kt` — `gpsEnabled` state, reducer, enroll/forget/setGpsEnabled, start wiring.
- `android/.../ui/App.kt` — location permission launchers + `onSetGpsEnabled` wiring.
- `android/.../ui/settings/SettingsScreen.kt` + `CloudSyncPage.kt` — GPS toggle.
- `android/app/src/test/.../cloud/CloudJsonTest.kt` — GPS serialization tests.
- `web/src/types.ts` — GPS fields on `Sample`/`FleetItem`.
- `web/src/App.tsx` — header "GPS" pill.

---

### Task 1: Server — GPS columns, ingest storage, test

**Files:**
- Modify: `server/app/db/schema.sql`, `server/app/models.py`, `server/app/db/queries.py`
- Test: `server/tests/test_ingest_jwt.py`

**Interfaces:**
- Produces: `samples.lat/lon/gps_accuracy_m` columns; `SampleIn.lat/lon/gps_accuracy_m`;
  `queries.fleet_snapshot()` rows include `lat/lon/gps_accuracy_m`.

- [ ] **Step 1: Write the failing test**

Add to `server/tests/test_ingest_jwt.py` (end of file):

```python
def _gps_payload():
    return {"batch_seq": 8, "samples": [
        {"ts_ms": 1719686400000, "address": A, "alias": "2012 · A", "group_id": "2012",
         "soc": 87.0, "lat": 41.8781, "lon": -87.6298, "gps_accuracy_m": 7.5}]}


async def test_ingest_stores_gps(app, client):
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    body = json.dumps(_gps_payload()).encode()
    r = await client.post("/api/v1/ingest", content=body,
                          headers={"Authorization": f"Bearer {_token(priv, device_id, body)}"})
    assert r.status_code == 200
    assert r.json() == {"accepted": 1, "last_seq": 8}
    async with app.state.pool.acquire() as conn:
        row = await conn.fetchrow("SELECT lat, lon, gps_accuracy_m FROM samples")
    assert row["lat"] == 41.8781
    assert row["lon"] == -87.6298
    assert abs(row["gps_accuracy_m"] - 7.5) < 1e-4
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd server && python -m pytest tests/test_ingest_jwt.py::test_ingest_stores_gps -v`
Expected: FAIL — `column "lat" does not exist` (schema lacks columns) or the values come back null.

- [ ] **Step 3: Add the columns (schema.sql)**

In `server/app/db/schema.sql`, immediately after the `CREATE INDEX ... samples_addr_ts` line, append:

```sql
ALTER TABLE samples ADD COLUMN IF NOT EXISTS lat double precision;
ALTER TABLE samples ADD COLUMN IF NOT EXISTS lon double precision;
ALTER TABLE samples ADD COLUMN IF NOT EXISTS gps_accuracy_m real;
```

- [ ] **Step 4: Add the model fields (models.py)**

In `server/app/models.py`, inside `SampleIn`, after `link_event: str | None = None` add:

```python
    lat: float | None = None
    lon: float | None = None
    gps_accuracy_m: float | None = None
```

- [ ] **Step 5: Persist them (queries.py)**

In `server/app/db/queries.py`:

Extend `_COLS` (so `sample_row` copies them):
```python
_COLS = ["state", "soc", "current_a", "power_w", "voltage_v", "temp_c", "mosfet_temp_c",
         "soh", "full_charge_ah", "remaining_ah", "cycles", "cell_min_v", "cell_max_v",
         "link_event", "lat", "lon", "gps_accuracy_m"]
```

Replace `_INSERT` with (adds 3 columns + `$21,$22,$23`):
```python
_INSERT = """
INSERT INTO samples
  (device_id,address,ts_ms,ts,state,soc,current_a,power_w,voltage_v,temp_c,
   mosfet_temp_c,soh,full_charge_ah,remaining_ah,cycles,cell_min_v,cell_max_v,cells,regen,link_event,
   lat,lon,gps_accuracy_m)
VALUES
  ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18,$19,$20,$21,$22,$23)
ON CONFLICT DO NOTHING
"""
```

Extend the `executemany` tuple in `insert_samples` (append the 3 values):
```python
    await conn.executemany(_INSERT, [
        (r["device_id"], r["address"], r["ts_ms"], r["ts"], r["state"], r["soc"],
         r["current_a"], r["power_w"], r["voltage_v"], r["temp_c"], r["mosfet_temp_c"],
         r["soh"], r["full_charge_ah"], r["remaining_ah"], r["cycles"], r["cell_min_v"],
         r["cell_max_v"], r["cells"], r["regen"], r["link_event"],
         r["lat"], r["lon"], r["gps_accuracy_m"])
        for r in rows
    ])
```

Extend `fleet_snapshot` SELECT to return the columns — change the `s.cycles, ... s.received_at,` line to include them:
```python
              s.cycles, s.cell_min_v, s.cell_max_v, s.cells, s.regen, s.link_event,
              s.lat, s.lon, s.gps_accuracy_m, s.received_at,
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `cd server && python -m pytest tests/test_ingest_jwt.py -v`
Expected: PASS (all ingest tests, incl. `test_ingest_stores_gps`). The `ALTER ... IF NOT EXISTS` runs on pool create.

- [ ] **Step 7: Commit**

```bash
git add server/app/db/schema.sql server/app/models.py server/app/db/queries.py server/tests/test_ingest_jwt.py
git commit -m "feat(server): accept and store GPS lat/lon/accuracy on samples"
```

---

### Task 2: Android — GPS in the upload JSON (serialization)

**Files:**
- Modify: `android/app/src/main/java/dev/joely/bmsmon/cloud/CloudJson.kt`,
  `android/app/src/main/java/dev/joely/bmsmon/cloud/TelemetryReporter.kt`
- Test: `android/app/src/test/java/dev/joely/bmsmon/cloud/CloudJsonTest.kt`

**Interfaces:**
- Produces: `SampleJson` gains `lat: Double?`, `lon: Double?`, `gps_accuracy_m: Float?`;
  `CloudJson.sampleJson(..., lat: Double? = null, lon: Double? = null, gpsAccuracyM: Float? = null)`;
  `TelemetryReporter.report(..., lat: Double? = null, lon: Double? = null, gpsAccuracyM: Float? = null)`.

- [ ] **Step 1: Write the failing tests**

Add to `android/app/src/test/java/dev/joely/bmsmon/cloud/CloudJsonTest.kt` (inside the class):

```kotlin
    @Test fun sampleJson_includes_gps_when_present() {
        val s = CloudJson.sampleJson(
            tsMs = 1L, address = "A", advertisedName = null, alias = null, groupId = null,
            state = null, soc = null, currentA = null, powerW = null, voltageV = null, tempC = null,
            mosfetTempC = null, soh = null, fullChargeAh = null, remainingAh = null, cycles = null,
            cellMinV = null, cellMaxV = null, regen = false, linkEvent = null,
            lat = 41.8781, lon = -87.6298, gpsAccuracyM = 7.5f)
        assertTrue(s.contains("\"lat\":41.8781"))
        assertTrue(s.contains("\"lon\":-87.6298"))
        assertTrue(s.contains("\"gps_accuracy_m\":7.5"))
    }

    @Test fun sampleJson_omits_gps_when_null() {
        val s = CloudJson.sampleJson(
            tsMs = 1L, address = "A", advertisedName = null, alias = null, groupId = null,
            state = null, soc = null, currentA = null, powerW = null, voltageV = null, tempC = null,
            mosfetTempC = null, soh = null, fullChargeAh = null, remainingAh = null, cycles = null,
            cellMinV = null, cellMaxV = null, regen = false, linkEvent = null)
        assertTrue(!s.contains("\"lat\""))
        assertTrue(!s.contains("\"lon\""))
        assertTrue(!s.contains("\"gps_accuracy_m\""))
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.cloud.CloudJsonTest"`
Expected: FAIL — compilation error: `sampleJson` has no parameter `lat`.

- [ ] **Step 3: Add the fields and params (CloudJson.kt)**

In `SampleJson`, after `val link_event: String? = null,` add:
```kotlin
    val lat: Double? = null,
    val lon: Double? = null,
    val gps_accuracy_m: Float? = null,
```

Change `sampleJson(...)` signature — append after `linkEvent: String?,`:
```kotlin
        cellMinV: Float?, cellMaxV: Float?, regen: Boolean, linkEvent: String?,
        lat: Double? = null, lon: Double? = null, gpsAccuracyM: Float? = null,
```
and append the three to the `SampleJson(...)` construction (after `regen, linkEvent`):
```kotlin
        SampleJson(tsMs, address, advertisedName, alias, groupId, state, soc, currentA, powerW,
            voltageV, tempC, mosfetTempC, soh, fullChargeAh, remainingAh, cycles, cellMinV, cellMaxV,
            regen, linkEvent, lat, lon, gpsAccuracyM),
```

- [ ] **Step 4: Add params to `report()` (TelemetryReporter.kt)**

Change the `report(...)` signature — after `regen: Boolean,` add:
```kotlin
        regen: Boolean,
        lat: Double? = null,
        lon: Double? = null,
        gpsAccuracyM: Float? = null,
    ) {
```
and pass them into the `CloudJson.sampleJson(...)` call (replace the trailing `regen, null,` arguments):
```kotlin
            t.cells.minOrNull(), t.cells.maxOrNull(), regen, null,
            lat, lon, gpsAccuracyM,
        )
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.cloud.CloudJsonTest"`
Expected: PASS (incl. the existing 3 tests — the default params keep them valid).

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/dev/joely/bmsmon/cloud/CloudJson.kt \
        android/app/src/main/java/dev/joely/bmsmon/cloud/TelemetryReporter.kt \
        android/app/src/test/java/dev/joely/bmsmon/cloud/CloudJsonTest.kt
git commit -m "feat(android): serialize GPS lat/lon/accuracy in cloud samples"
```

---

### Task 3: Android — LocationSource (fused provider) + engine/service wiring

**Files:**
- Create: `android/app/src/main/java/dev/joely/bmsmon/location/LocationSource.kt`
- Modify: `android/app/build.gradle`, `android/app/src/main/java/dev/joely/bmsmon/monitor/MonitorEngine.kt`,
  `android/app/src/main/java/dev/joely/bmsmon/monitor/MonitoringService.kt`,
  `android/app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `TelemetryReporter.report(..., lat, lon, gpsAccuracyM)` (Task 2).
- Produces: `LocationSource(context)` with `start()`, `stop()`, `current(): GpsFix?`, companion
  `hasLocationPermission(context): Boolean`; `GpsFix(lat: Double, lon: Double, accuracyM: Float?)`;
  `MonitorState.gpsActive: Boolean`; `MonitorEngine.setGpsActive(active: Boolean)`.

- [ ] **Step 1: Add the dependency (app/build.gradle)**

After the `play-services-code-scanner` line add:
```gradle
    implementation("com.google.android.gms:play-services-location:21.3.0")
```

- [ ] **Step 2: Create LocationSource.kt**

```kotlin
package dev.joely.bmsmon.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.concurrent.atomic.AtomicReference

/** A single cached GPS fix attached to outgoing telemetry. */
data class GpsFix(val lat: Double, val lon: Double, val accuracyM: Float?)

/**
 * Thin wrapper over the fused location provider. Holds the latest fix in an atomic reference;
 * [current] is read on each telemetry upload. Safe to call [start]/[stop] repeatedly.
 */
class LocationSource(private val context: Context) {

    private val client = LocationServices.getFusedLocationProviderClient(context)
    private val cache = AtomicReference<GpsFix?>(null)
    private var requesting = false

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let {
                cache.set(GpsFix(it.latitude, it.longitude, if (it.hasAccuracy()) it.accuracy else null))
            }
        }
    }

    @SuppressLint("MissingPermission") // guarded by hasLocationPermission
    fun start() {
        if (requesting || !hasLocationPermission(context)) return
        requesting = true
        client.lastLocation.addOnSuccessListener { loc ->
            loc?.let { cache.set(GpsFix(it.latitude, it.longitude, if (it.hasAccuracy()) it.accuracy else null)) }
        }
        val req = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 10_000L)
            .setMinUpdateIntervalMillis(5_000L)
            .build()
        client.requestLocationUpdates(req, callback, null)
    }

    fun stop() {
        if (!requesting) return
        requesting = false
        client.removeLocationUpdates(callback)
        cache.set(null)
    }

    fun current(): GpsFix? = cache.get()

    companion object {
        fun hasLocationPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
    }
}
```

- [ ] **Step 3: Wire into MonitorEngine.kt**

Add the import:
```kotlin
import dev.joely.bmsmon.location.LocationSource
```
Add `gpsActive` to `MonitorState` (after `peakCurrentA`):
```kotlin
    val peakCurrentA: Float = 0f,
    val gpsActive: Boolean = false,
```
Add a `LocationSource` field (near `private val ble = BmsRepository(appContext)`):
```kotlin
    private val locationSource = LocationSource(appContext)
```
Add the setter (near `setLogging`):
```kotlin
    /** Start/stop GPS capture; cached fixes are attached to each upload while active. */
    fun setGpsActive(active: Boolean) {
        _state.update { it.copy(gpsActive = active) }
        if (active) locationSource.start() else locationSource.stop()
    }
```
In `stop()`, stop location before clearing state — change:
```kotlin
        repository.finalizeOpenSessions()
        ble.stop()
        locationSource.stop()
        _state.value = MonitorState()
```
In `onPoll`, read the fix and pass it to `report` — replace the `reporter?.report(...)` call:
```kotlin
        val fix = if (_state.value.gpsActive) locationSource.current() else null
        reporter?.report(
            addr, roster.batteryAt(addr)?.advertisedName, roster.batteryAt(addr)?.alias,
            group?.id, t, now, regen, fix?.lat, fix?.lon, fix?.accuracyM,
        )
```

- [ ] **Step 4: Dynamic FGS type (MonitoringService.kt)**

Add the import:
```kotlin
import dev.joely.bmsmon.location.LocationSource
```
Replace `startForegroundCompat` with a version that ORs the location type when active+permitted:
```kotlin
    private fun fgsType(): Int {
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (engine.state.value.gpsActive && LocationSource.hasLocationPermission(this)) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        return type
    }

    private var appliedType: Int = -1
    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 30) {
            val type = fgsType()
            startForeground(NOTIF_ID, notification, type)
            appliedType = type
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }
```
In the `collectorJob` `onEach` block, re-promote when the type changes (so toggling GPS while
running updates the FGS type); replace the `else` branch that notifies:
```kotlin
                    if (!st.monitoring) {
                        stopCleanly()
                    } else if (Build.VERSION.SDK_INT >= 30 && fgsType() != appliedType) {
                        startForegroundCompat(buildNotification())
                    } else {
                        NotificationManagerCompat.from(this).notify(NOTIF_ID, buildNotification())
                    }
```

- [ ] **Step 5: Manifest permissions + FGS type (AndroidManifest.xml)**

Add these `<uses-permission>` entries (next to the existing FOREGROUND_SERVICE lines):
```xml
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
```
Change the service's `foregroundServiceType`:
```xml
    <service
        android:name=".monitor.MonitoringService"
        android:exported="false"
        android:foregroundServiceType="connectedDevice|location" />
```

- [ ] **Step 6: Build to verify it compiles + unit tests stay green**

Run: `cd android && ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; all tests pass. (No new unit test here — `LocationSource` needs the
Android framework; it's covered by the on-device verification in Task 6.)

- [ ] **Step 7: Commit**

```bash
git add android/app/build.gradle android/app/src/main/AndroidManifest.xml \
        android/app/src/main/java/dev/joely/bmsmon/location/LocationSource.kt \
        android/app/src/main/java/dev/joely/bmsmon/monitor/MonitorEngine.kt \
        android/app/src/main/java/dev/joely/bmsmon/monitor/MonitoringService.kt
git commit -m "feat(android): capture GPS via fused provider, attach to uploads, location FGS type"
```

---

### Task 4: Android — settings toggle, persistence, permission flow

**Files:**
- Modify: `android/app/src/main/java/dev/joely/bmsmon/data/SettingsStore.kt`,
  `android/app/src/main/java/dev/joely/bmsmon/BatteryViewModel.kt`,
  `android/app/src/main/java/dev/joely/bmsmon/ui/App.kt`,
  `android/app/src/main/java/dev/joely/bmsmon/ui/settings/SettingsScreen.kt`,
  `android/app/src/main/java/dev/joely/bmsmon/ui/settings/CloudSyncPage.kt`

**Interfaces:**
- Consumes: `MonitorEngine.setGpsActive(Boolean)` (Task 3).
- Produces: `UiState.gpsEnabled: Boolean`; `BatteryViewModel.setGpsEnabled(Boolean)`;
  `CloudSyncContent(..., onSetGpsEnabled: (Boolean) -> Unit)`;
  `SettingsScreen(..., onSetGpsEnabled: (Boolean) -> Unit)`.

- [ ] **Step 1: Persist gpsEnabled (SettingsStore.kt)**

Add to the `Persisted` data class (after `enrolled: Boolean,`):
```kotlin
    val gpsEnabled: Boolean?,
```
Add the key to `K` (after `ENROLLED`):
```kotlin
        val GPS_ENABLED = booleanPreferencesKey("gps_enabled")
```
Add to `load()`'s `Persisted(...)` (after `enrolled = p[K.ENROLLED] ?: false,`):
```kotlin
            gpsEnabled = p[K.GPS_ENABLED],
```
Add a setter (after `setEnrolled`):
```kotlin
    suspend fun setGpsEnabled(on: Boolean) = context.dataStore.edit { it[K.GPS_ENABLED] = on }.let {}
```

- [ ] **Step 2: UiState + reducer + ViewModel actions (BatteryViewModel.kt)**

Add to `UiState` (after the cloud fields, near `enrolled`):
```kotlin
    val gpsEnabled: Boolean = false,
```
In the `init` reducer (where `cloudEnabled = p.cloudEnabled,` is), add — default-on iff cloud sync on:
```kotlin
                    cloudEnabled = p.cloudEnabled,
                    gpsEnabled = p.gpsEnabled ?: p.cloudEnabled,
```
Add a setter in the `// --- cloud sync settings ---` block:
```kotlin
    fun setGpsEnabled(on: Boolean) {
        viewModelScope.launch { store.setGpsEnabled(on) }
        _state.update { it.copy(gpsEnabled = on) }
        engine.setGpsActive(on && _state.value.enrolled && _state.value.monitoring)
    }
```
In `enroll()`'s `onSuccess` block (where it sets `cloudEnabled = true`), also default GPS on:
```kotlin
            store.setGpsEnabled(true)
            _state.update { it.copy(apiBaseUrl = baseUrl, enrolled = true, cloudEnabled = true, gpsEnabled = true) }
```
(Place the `store.setGpsEnabled(true)` next to the other `store.set...` calls and merge the
`gpsEnabled = true` into the existing `_state.update { it.copy(...) }`.)

In `forgetDevice()`, turn GPS off and stop capture (add inside the coroutine):
```kotlin
        store.setGpsEnabled(false)
        _state.update { it.copy(enrolled = false, cloudEnabled = false, gpsEnabled = false) }
        engine.setGpsActive(false)
```
(Replace the existing `_state.update { it.copy(enrolled = false, cloudEnabled = false) }`.)

In `startMonitoring()`, activate GPS if enabled (after `engine.setStage(currentStageAddrs())`):
```kotlin
        engine.setGpsActive(_state.value.gpsEnabled && _state.value.enrolled)
```

- [ ] **Step 3: Location permission flow + wiring (App.kt)**

Add launchers near `onMonitorToggle` (the background launcher must be declared first):
```kotlin
    val bgLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* result ignored; foreground GPS still works without background */ }
    val locationPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.any { it } && Build.VERSION.SDK_INT >= 29 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            bgLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }
    fun askLocationPermission() {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!fine) {
            locationPermLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        } else if (Build.VERSION.SDK_INT >= 29 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            bgLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }
    val onSetGps: (Boolean) -> Unit = { on ->
        vm.setGpsEnabled(on)
        if (on) askLocationPermission()
    }
```
Pass it into `SettingsScreen(...)` (after `onSetCloudEnabled = vm::setCloudEnabled,`):
```kotlin
                        onSetGpsEnabled = onSetGps,
```

- [ ] **Step 4: Thread the callback through SettingsScreen.kt**

Add the parameter to `SettingsScreen(...)` (after `onSetCloudEnabled: (Boolean) -> Unit,`):
```kotlin
    onSetGpsEnabled: (Boolean) -> Unit,
```
Pass it into the Cloud page (the `SettingsPage.Cloud` branch):
```kotlin
        SettingsPage.Cloud -> DetailScaffold("Cloud sync", { page = null }) {
            CloudSyncContent(state, onEnroll, onSetCloudEnabled, onForget, onSetGpsEnabled)
        }
```

- [ ] **Step 5: Add the GPS toggle (CloudSyncPage.kt)**

Add the parameter to `CloudSyncContent(...)` (after `onForget: () -> Unit,`):
```kotlin
    onSetGpsEnabled: (Boolean) -> Unit,
```
After the "Report to cloud" `GroupedCard { ToggleRow(...) }` block, add (shown when enrolled):
```kotlin
    if (state.enrolled) {
        GroupedCard {
            ToggleRow(
                "Send GPS location",
                "Attach the phone's location to each upload. Needs location permission.",
                state.gpsEnabled,
                onSetGpsEnabled,
            )
        }
        Text(
            "Location is attached to each upload while permitted. Used later for mapping.",
            color = c.text3,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 8.dp, start = 4.dp),
        )
    }
```

- [ ] **Step 6: Build to verify it compiles + unit tests stay green**

Run: `cd android && ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; all tests pass.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/java/dev/joely/bmsmon/data/SettingsStore.kt \
        android/app/src/main/java/dev/joely/bmsmon/BatteryViewModel.kt \
        android/app/src/main/java/dev/joely/bmsmon/ui/App.kt \
        android/app/src/main/java/dev/joely/bmsmon/ui/settings/SettingsScreen.kt \
        android/app/src/main/java/dev/joely/bmsmon/ui/settings/CloudSyncPage.kt
git commit -m "feat(android): GPS settings toggle, default-on with cloud sync, location permission flow"
```

---

### Task 5: WebUI — "GPS" indicator

**Files:**
- Modify: `web/src/types.ts`, `web/src/App.tsx`

**Interfaces:**
- Consumes: fleet items now carry `lat/lon/gps_accuracy_m` (Task 1).
- Produces: header GPS pill.

- [ ] **Step 1: Add fields to the types (types.ts)**

In `Sample`, add to the field list:
```typescript
  lat?: number | null; lon?: number | null; gps_accuracy_m?: number | null;
```

- [ ] **Step 2: Add the header pill (App.tsx)**

Compute GPS-active from non-stale items (add near `staleAddrs`):
```typescript
  const gpsActive = useMemo(
    () => items.some((i) => !staleAddrs.has(i.address) && i.lat != null),
    [items, staleAddrs],
  );
```
In the `<header>`, after the existing LIVE/RECONNECTING `<span>`, add a GPS pill:
```tsx
        <span style={{ display: "flex", alignItems: "center", gap: 8,
          color: gpsActive ? "var(--regen)" : "var(--text3)", fontSize: 13 }}>
          <span style={{ width: 9, height: 9, borderRadius: "50%",
            background: gpsActive ? "var(--regen)" : "var(--text3)" }} />
          GPS
        </span>
```
(The existing LIVE span uses `marginLeft: "auto"` to push the cluster right; keep that on the
LIVE span so both pills sit together at the right.)

- [ ] **Step 3: Build to verify it compiles**

Run: `cd web && npm run build`
Expected: build succeeds (tsc + vite), no type errors.

- [ ] **Step 4: Commit**

```bash
git add web/src/types.ts web/src/App.tsx
git commit -m "feat(web): show GPS indicator when fleet samples carry coordinates"
```

---

### Task 6: On-device + cloud end-to-end verification

**Files:** none (verification).

- [ ] **Step 1: Build and install over Tailscale**

```bash
cd android && ./gradlew :app:assembleDebug
adb connect 100.102.146.11:39187   # use the phone's current Wireless-debugging port
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
```
Expected: `Success`. (Never `adb uninstall`.)

- [ ] **Step 2: Enable GPS + grant permissions**

In the app: Settings → Cloud sync → toggle **Send GPS location** on. Grant the location prompt,
then **Allow all the time** for background. Ensure monitoring is on.

- [ ] **Step 3: Verify coordinates reach Postgres**

On the server host (or via the DB), confirm recent rows carry coordinates:
```sql
SELECT address, ts, lat, lon, gps_accuracy_m
FROM samples WHERE lat IS NOT NULL ORDER BY ts DESC LIMIT 5;
```
Expected: rows with non-null lat/lon appearing after GPS was enabled.

- [ ] **Step 4: Verify the WebUI indicator**

Open the bmsmon web dashboard. Expected: the header **"GPS ●"** pill is green (a non-stale pack
carries coordinates). Toggle GPS off in the app and confirm new samples have null lat/lon and the
pill goes dim once the GPS-bearing samples age past the 90 s stale window.

- [ ] **Step 5: Report results**

Summarize: coordinates observed in Postgres, pill state, any permission friction. No commit. If a
defect is found, return to the owning task rather than patching blindly.

---

## Self-Review notes

- **Spec coverage:** capture (Task 3), piggyback on upload (Tasks 2–3), background FGS + permissions
  (Tasks 3–4), settings toggle default-on with cloud (Task 4), server columns + ingest + test
  (Task 1), WebUI indicator (Task 5), E2E verify (Task 6). All spec sections mapped. O1 (static
  status string) implemented as the helper Text in Task 4 Step 5.
- **Type consistency:** `lat: Double?`/`lon: Double?`/`gpsAccuracyM: Float?` (Android) ↔ JSON
  `lat/lon/gps_accuracy_m` ↔ Postgres `lat double precision`/`lon double precision`/`gps_accuracy_m real`
  ↔ TS `lat?: number | null` used identically across Tasks 1–5. `setGpsActive`, `setGpsEnabled`,
  `gpsActive`, `gpsEnabled`, `onSetGpsEnabled`, `GpsFix`, `LocationSource.hasLocationPermission`
  match between definition and use.
- **No placeholders:** every code step shows complete code; no TBD/TODO.
- **Compile ordering:** report()/sampleJson() params default null (Task 2) so intermediate states
  compile before the engine passes real values (Task 3).
