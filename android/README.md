# bmsmon Android app

Kotlin / Jetpack Compose front-end for the Redodo/LiTime BLE BMS protocol — the GUI
companion to the `bmsmon.py` reference tool one directory up. Monitors a **fleet of LiFePO4
packs** (tested with 8, e.g. a power-wheelchair multi-battery setup) live over Bluetooth, with
optional cloud upload.

## Screens
- **Home** — a dynamic "main stage" shows the whole in-use base (dual-ring SOC/wattage gauge +
  6-stat grid per pack); All Batteries lists the rest. Unreachable packs render as
  **DISCONNECTED** (dimmed, no %), not synthetic data. The top bar shows the stage status, a
  small cloud **upload indicator** (`↑ KB/s` / `synced` / `queued`) when enrolled, and flashes a
  **low-battery alert** wash (amber/red) until acknowledged.
- **Settings** — Monitoring & stage, **Alerts** (full 5% threshold ladder 95→5, configurable
  critical level, reset to defaults), Battery groups, Appearance & color, Display & units, Lock
  screen, Data & logging, **Cloud sync** (enroll via QR, Report to cloud, **Send GPS location**),
  About. Everything persists via DataStore.

## Safety (critical)
The app is **read-only by construction**. `ble/BmsProtocol.kt` frames ONLY the whitelisted
`ReadCommand` opcodes (`0x13` status + the other confirmed-safe reads). No destructive
opcode (charge/discharge MOSFET `0x0A–0x0D`, shutdown `0x60`) exists anywhere in the code,
and a unit test (`BmsProtocolTest.commandWhitelistIsReadOnly`) enforces that. See
`../CLAUDE.md` → "DANGER: Safe vs Destructive Commands".

## Architecture
```
ble/      BmsProtocol (pure, testable: framing + parseTelemetry), BmsConnection
          (persistent GATT poll loop, FFE1 notify / FFE2 write), BmsRepository, BlePermissions
model/    Telemetry, Roster/groups, stage resolution
data/     SettingsStore (DataStore), Room DB (telemetry log + cloud outbox)
monitor/  MonitorEngine (process-lifetime BLE/logging/GPS), MonitoringService (foreground service)
cloud/    TelemetryReporter (signed batch upload + offline outbox), CloudJson, DeviceKeys (ES256),
          EnrollClient, UploadRate (KB/s indicator)
location/ LocationSource (fused provider → GPS attached to uploads)
ui/       theme, gauge/DualRingGauge (Canvas), home/HomeScreen, settings/SettingsScreen, App
BatteryViewModel  // UiState; delegates BLE work to MonitorEngine; alerts, stage, settings
```

## Build & run
Requires JDK 17 and an Android SDK with platform 34 (`ANDROID_HOME`/`ANDROID_SDK_ROOT` set).

```bash
./gradlew :app:assembleDebug          # build
./gradlew :app:testDebugUnitTest      # parser + safety tests
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Wireless install/test over ADB (phone in TCP mode):
```bash
adb connect <phone-ip>:5555
adb -s <phone-ip>:5555 install -r app/build/outputs/apk/debug/app-debug.apk
```

- `minSdk 26`, `compileSdk`/`targetSdk 34`, AGP 8.2.2, Kotlin 1.9.22, Compose BOM 2024.02.02.
- BLE needs `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT` (API 31+) granted at runtime. Background
  monitoring uses a foreground service (`connectedDevice`, plus `location` type when GPS is on).
  GPS upload needs `ACCESS_FINE/COARSE_LOCATION` + `ACCESS_BACKGROUND_LOCATION`.

## Status
Read-only by construction; verified live against the test packs (telemetry matches `bmsmon.py`).
Background foreground-service monitoring, configurable low-battery alerts, signed cloud upload
(offline-durable outbox), and GPS telemetry (phone → production DB) are all shipped. Cloud server
build + QNAP NAS deploy are documented in `../CLAUDE.md` → "Cloud Server & Deployment".
