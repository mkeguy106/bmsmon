# bmsmon Android app

Kotlin / Jetpack Compose front-end for the Redodo/LiTime BLE BMS protocol — the GUI
companion to the `bmsmon.py` reference tool one directory up. Monitors **two LiFePO4
packs** (e.g. a power-wheelchair dual-battery setup) live over Bluetooth.

## Screens
- **Home** — per pack: a dual-ring gauge (outer = 16-segment SOC donut, inner = wattage
  arc) with centered SOC/watts, plus a 6-stat grid (Power, Current, Voltage, Capacity,
  Cell V, Temp). Shows `DEMO DATA` (a drifting simulation) when disconnected, `CONNECTED`
  when live.
- **Settings** — two BMS addresses + Connect, accent/power color pickers, dark/light/system
  appearance, About. Color, appearance and addresses persist via DataStore.

## Safety (critical)
The app is **read-only by construction**. `ble/BmsProtocol.kt` frames ONLY the whitelisted
`ReadCommand` opcodes (`0x13` status + the other confirmed-safe reads). No destructive
opcode (charge/discharge MOSFET `0x0A–0x0D`, shutdown `0x60`) exists anywhere in the code,
and a unit test (`BmsProtocolTest.commandWhitelistIsReadOnly`) enforces that. See
`../CLAUDE.md` → "DANGER: Safe vs Destructive Commands".

## Architecture
```
ble/   BmsProtocol (pure, testable: framing + parseTelemetry), BmsConnection
       (persistent GATT poll loop, FFE1 notify / FFE2 write), BmsRepository, BlePermissions
model/ Telemetry
data/  SettingsStore (DataStore persistence)
ui/    theme (BmColors tokens + Inter/JetBrains Mono), gauge/DualRingGauge (Canvas),
       home/HomeScreen, settings/SettingsScreen, App
BatteryViewModel  // UiState, demo sim, connect/disconnect, persistence
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
- BLE needs `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT` (API 31+) granted at runtime.

## Status
Verified live against the 2024 test packs (read-only): telemetry matches `bmsmon.py`.
Pending: bundled-font light/dark splash flash, optional background-service polling.
