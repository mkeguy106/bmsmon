# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Git Commits
Never include any of the following in commit messages:
- "Generated with Claude Code"
- "Co-Authored-By: Claude"
- Any reference to AI, Claude, or automated generation

## Project Overview

**bmsmon** is a BLE battery monitoring tool for Redodo (and compatible) LiFePO4 batteries. It reads real-time telemetry — voltage, current, SOC, temperature, cell voltages, cycle count, etc. — over Bluetooth Low Energy using a reverse-engineered proprietary protocol.

## Supported Batteries

All use the same Beken BK-BLE-1.0 UART-to-BLE bridge module with identical protocol:

| Brand | BLE Name Prefix | Examples |
|-------|----------------|----------|
| **Redodo** | `R-12*`, `R-24*`, `RO-12*`, `RO-24*` | R-12100BNNA70-* |
| **LiTime** | `L-12*`, `L-24*`, `L-51*`, `LT-*` | |
| **PowerQueen** | `P-12*`, `P-24*`, `PQ-12*`, `PQ-24*` | |
| **Starry Sea** | `S-*`, `SS-*` | |

## DANGER: Safe vs Destructive Commands

**NEVER send unknown/undocumented command bytes to a live battery.** Scanning command ranges (e.g. iterating 0x00-0xFF) caused a real battery to enter an unrecoverable software shutdown during development. The battery was installed in a power wheelchair and could not be physically accessed to recover it. The only recovery method is applying a 12V LiFePO4 charger directly to the battery terminals, which required disassembling the wheelchair.

### Safe commands (read-only, confirmed safe):

| CMD  | Description |
|------|-------------|
| 0x10 | Get serial number |
| 0x13 | Query battery status (main telemetry) |
| 0x15 | Read BMS configuration/parameters |
| 0x16 | Get firmware version |
| 0x41 | Get SOH and SOC |
| 0x43 | Get nominal capacity |

### Destructive commands (NEVER send without explicit user consent):

| CMD  | Description | Consequence |
|------|-------------|-------------|
| 0x0A | Turn on charging MOSFET | Alters BMS state |
| 0x0B | Turn off charging MOSFET | Disables charging |
| 0x0C | Turn on discharge MOSFET | Alters BMS state |
| 0x0D | Turn off discharge MOSFET | **Disconnects load from battery** |
| 0x60 | **Shutdown** | **Puts BMS into deep sleep. BLE module powers off. Battery appears dead. Only recoverable by applying a charger directly to physical terminals.** |

### Commands with unknown effects (NEVER send):

Any command byte not listed above as "safe" is **unknown and potentially destructive**. This includes 0x01, 0x02, 0x04, 0x06, 0x07, 0x30, 0x44, 0x49, 0x65, and everything in 0x80-0xFF. Do not probe, scan, or iterate command bytes on a live battery.

### Recovery from BMS shutdown

If the BMS enters shutdown (0x60 or unknown command side effect):
1. The BLE module loses power — no wireless recovery is possible
2. Connect a **12V LiFePO4 charger (14.4-14.6V)** directly to the battery's physical terminals
3. The BMS wake circuit detects charging voltage and exits sleep mode
4. If the battery is in a series configuration (e.g. 24V wheelchair), the series circuit is broken by the shutdown — a 24V charger will NOT work. The dead battery must be individually charged with a 12V charger.
5. If a charger does not wake it: briefly connect another charged 12V battery in parallel to provide wake voltage
6. Last resort: open the battery case and disconnect/reconnect the BMS balance wire connector to hard-reset the BMS controller
7. Contact Redodo support: service@redodopower.com (5-year warranty)

## Protocol Details

### BLE GATT Structure

- **Service**: `0000FFE0-0000-1000-8000-00805f9b34fb`
- **FFE1** (notify): BMS responses (UART RX from BMS MCU)
- **FFE2** (write-no-response): Commands to BMS (UART TX to BMS MCU)
- **FFE3** (notify/write): AT command interface for the Beken BLE module itself (not BMS data)
- **Battery Service** (0x180F): Present but returns 0% always — non-functional placeholder
- **TI OAD** (`f000ffc0-0451-4000-b000-000000000000`): Firmware update service, not used

### Command Format (8 bytes, write to FFE2)

```
00 00 04 01 CMD 55 AA CHECKSUM
```

Checksum = `sum(all_bytes) & 0xFF`

### Command Table

| CMD  | Full Bytes                       | Description |
|------|----------------------------------|-------------|
| 0x01 | `00 00 04 01 01 55 AA 05`       | Product registration (initial pairing) |
| 0x02 | `00 00 04 01 02 55 AA 06`       | Disconnect registration |
| 0x13 | `00 00 04 01 13 55 AA 17`       | **Query battery status** (main telemetry) |
| 0x0A | `00 00 04 01 0A 55 AA 0E`       | Turn on charging MOSFET |
| 0x0B | `00 00 04 01 0B 55 AA 0F`       | Turn off charging MOSFET |
| 0x0C | `00 00 04 01 0C 55 AA 10`       | Turn on discharge MOSFET |
| 0x0D | `00 00 04 01 0D 55 AA 11`       | Turn off discharge MOSFET |
| 0x10 | `00 00 04 01 10 55 AA 14`       | Get serial number |
| 0x16 | `00 00 04 01 16 55 AA 1A`       | Get firmware version |
| 0x41 | `00 00 04 01 41 55 AA 45`       | Get SOH and SOC |
| 0x43 | `00 00 04 01 43 55 AA 47`       | Get nominal capacity |
| 0x60 | `00 00 04 01 60 55 AA 64`       | Shutdown command |

### Response Format (from FFE1, ~105 bytes for cmd 0x13)

Response header: `00 00 <payload_len> 01 93 55 AA ...`

All multi-byte values are **little-endian**.

| Parameter | Offset | Size | Type | Conversion |
|-----------|--------|------|------|------------|
| Cell sum voltage | 8 | 4 bytes | uint32 | / 1000 → V |
| Total voltage | 12 | 2 bytes | uint16 | / 1000 → V |
| Cell voltages (up to 16) | 16 | 2 bytes each | uint16 | / 1000 → V |
| Current | 48 | 4 bytes | int32 | / 1000 → A (negative = discharge) |
| Cell temperature | 52 | 2 bytes | int16 | direct → °C |
| MOSFET temperature | 54 | 2 bytes | int16 | direct → °C |
| Remaining capacity | 62 | 2 bytes | uint16 | / 100 → Ah |
| Full charge capacity | 64 | 4 bytes | uint32 | / 100 → Ah |
| Battery state | 88 | 2 bytes | uint16 | 0x0000=Idle, 0x0001=Charging, 0x0002=Discharging, 0x0004=Disabled |
| SOC | 90 | 2 bytes | uint16 | direct → % |
| SOH | 92 | 4 bytes | uint32 | direct → % |
| Cycle count | 96 | 4 bytes | uint32 | direct |

### Serial Number Response (cmd 0x10)

Header: `00 00 <payload_len> 01 90 55 AA ...` (response cmd = `0x10 | 0x80 = 0x90`).

The serial occupies the payload as ASCII (offset 8 to checksum). On tested R-12100 units the field is **all `0xFF`** — i.e. no serial is programmed — so the parser returns `None`. The BLE advertised name (e.g. `R-12100BNNA70-A02402`) is not stored here.

### Firmware Version Response (cmd 0x16)

Header: `00 00 <payload_len> 01 96 55 AA ...` (response cmd = `0x16 | 0x80 = 0x96`). Offsets below are relative to the payload (after the 8-byte header).

| Parameter | Offset | Size | Type | Conversion |
|-----------|--------|------|------|------------|
| Version triplet | 0 | 2 bytes ×3 | uint16 | `maj.min.patch`, e.g. `1.4.0` |
| Build year | 6 | 2 bytes | uint16 | direct |
| Build month | 8 | 1 byte | uint8 | direct |
| Build day | 9 | 1 byte | uint8 | direct |
| ASCII strings | 10 | NUL-terminated | ASCII | two `MODEL-Vx.y` strings: 1st = hardware rev, 2nd = firmware rev |

Example payload decodes to: model `T12100`, HW `V1.2`, FW `V1.4`, built `2024-03-31`. Note this BMS-application firmware (`V1.4`) is distinct from the Beken BLE **module** firmware (`BK-BLE-1.0`, FW `6.1.2`).

### Protection State Flags (offset 76, 8 bytes)

- 0x00000004 — Over Charge Protection
- 0x00000020 — Over-discharge Protection
- 0x00000040 — Charging Over Current Protection
- 0x00000080 — Discharging Over Current Protection
- 0x00000100 — High-temp Protection (charge)
- 0x00000200 — High-temp Protection (discharge)
- 0x00000400 — Low-temp Protection (charge)
- 0x00000800 — Low-temp Protection (discharge)
- 0x00004000 — Short Circuit Protection

## BLE Connection Notes

- The Beken BLE module drops the device from scan cache after a connection/disconnection cycle. Always do a fresh `BleakScanner.find_device_by_address()` before connecting.
- If connections fail with `le-connection-abort-by-local`, reset the adapter: `bluetoothctl power off && sleep 2 && bluetoothctl power on`
- `bluetoothctl connect` is unreliable for these devices — use `bleak` (Python) instead.
- Only one BLE client can connect to a battery at a time. If the Redodo phone app is connected, the PC cannot connect and vice versa.
- The BLE module AT command set (on FFE3) only supports `AT+NAME?` and `AT+BAUD?`. All other AT commands return `+ER`.
- **Query batteries one at a time, not rapidly back-to-back or in parallel.** Each query runs its own BLE scan; firing several in quick succession (e.g. a shell loop over all batteries) causes scan-cache contention and most queries return "not found" even though the devices are present and healthy. Querying the same device individually then succeeds. This is worse on cheap/flaky USB BT adapters. To status multiple batteries, query them sequentially in separate invocations and let the adapter settle between each.

## Hardware Context

Tested with 6x Redodo 12V 100Ah LiFePO4 batteries:

| MAC Address | Name | Notes |
|-------------|------|-------|
| C8:47:80:15:67:44 | R-12100BNNA70-A02214 | |
| C8:47:80:15:25:9A | R-12100BNNA70-A03727 | |
| C8:47:80:15:DB:13 | R-12100BNNA70-A03902 | |
| C8:47:80:15:07:DE | R-12100BNNA70-A02285 | |
| C8:47:80:15:62:1B | R-12100BNNA70-A02345 | |
| C8:47:80:15:25:01 | R-12100BNNA70-A02402 | Primary test unit |

OUI `C8:47:80` = Beken Corporation. All batteries share the same firmware (BK-BLE-1.0, FW 6.1.2, SW 6.3.0).

## Architecture

Single-file script (`bmsmon.py`) with no packaging. Only external dependency is `bleak`.

Key flow: `main()` → `scan_batteries()` or `query_battery(address)` → `parse_telemetry(data)` → `print_telemetry(dict)` or JSON output.

- `query_battery()`: Finds device via BleakScanner, connects with BleakClient, subscribes to FFE1 notifications, writes QUERY_STATUS to FFE2, collects response fragments until ≥80 bytes
- `parse_telemetry()`: Decodes raw bytes into a dict using struct unpacking at fixed offsets (little-endian)
- `is_compatible()`: Filters BLE scan results by `KNOWN_PREFIXES` tuple
- No tests, no linting, no packaging — run directly with `python3 bmsmon.py`

## Android App (`android/`)

Kotlin/Jetpack Compose GUI front-end (see `android/README.md`). Same read-only protocol and
safety rules. Dynamic "main stage" shows the in-use base; a rotating sampler covers the rest.

**Background monitoring (foreground service):** BLE polling + usage logging run in a
process-lifetime `MonitorEngine` (held by the `BmsApp` Application), kept alive by
`MonitoringService` (a `connectedDevice`-type foreground service with an ongoing notification +
Stop action). The `BatteryViewModel` no longer owns the BLE work — it delegates to the engine
and mirrors `engine.state` into the UI, so monitoring survives the Activity/ViewModel being
destroyed. Stage resolution and settings stay in the ViewModel. Clean shutdown (cancels BLE
jobs → each `BleSession.close()` disconnects the GATT) happens on explicit Stop (in-app toggle
or notification action) and on `onTaskRemoved` (app swiped from Recents) — so closing the app
never leaves a zombie connection blocking the phone app. Just backgrounding (Home) keeps it
running. Needs `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CONNECTED_DEVICE` + runtime
`POST_NOTIFICATIONS` (requested opportunistically; never gates monitoring).

**Usage logging is intentionally ON right now — do not turn it off.** It records every
telemetry sample to `…/Android/data/dev.joely.bmsmon/files/usage_log.csv` (columns incl.
`current_a`, `power_w`, `regen`) so we can collect **real-world data to calibrate the UI later**:
- the inner power ring's full scale `POWER_RING_FULL_W` (Fleet.kt, currently an 80 W placeholder),
- the regen detection thresholds `REGEN_EPS` / `REGEN_WINDOW_MS` (Fleet.kt).

Steady charging is being captured now as a baseline (`regen=0`); regen bursts while driving
will log as `regen=1`. Pull the CSV (`adb pull …usage_log.csv`), find peak discharge `power_w`,
and set the calibration constants. Logging + monitoring both persist across restarts.

The inner power ring full-scale `POWER_RING_FULL_W` (Fleet.kt) has been **calibrated to 300 W
per pack** from real 2012-daily-driver logging. A fuller cumulative log (~96 k samples, ~5.5 k
discharge) reads per-pack discharge p50 ~53 W, p90 ~127 W, p95 ~164 W, p99 ~341 W; brief
hard-pull spikes still ~882 W / 67 A. (The earlier, sparser log read p99 ~259 W → 250 W; the
heavier-loaded fuller dataset pushed p99 up, hence 300 W ≈ the new p98.) The log also records
BLE link events (`state` column = `Connected`/`Disconnected`, telemetry columns blank) so a
transient disconnect is distinguishable from a real low/idle reading. `REGEN_EPS`/
`REGEN_WINDOW_MS` are now **validated** against 34 captured regen bursts (1.0–22.3 A, up to
~297 W) — cleanly separated from the noise floor, so the 0.1 A threshold / 30 s window are
left as-is.

Garbage-frame guard: `parseTelemetry` realigns to the `01 93 55 AA` status header (BLE
notification fragments can prepend stale bytes, which previously decoded as soc=0/37.6 V and
tripped a false critical alarm) and rejects implausible readings (SOC 0–100, voltage 4–70 V).
The main stage shows a pack that isn't reachable as **DISCONNECTED** (dimmed ring, no %, no
alert) rather than a misleading 0%.

## Development

```bash
# Dependencies (Arch/CachyOS)
sudo pacman -S python-bleak

# Scan for batteries
python3 bmsmon.py --scan

# Query a single battery
python3 bmsmon.py --address C8:47:80:15:25:01

# Query all known batteries
python3 bmsmon.py --all

# Live monitoring (default 1s poll interval)
python3 bmsmon.py -a C8:47:80:15:25:01 --watch

# JSON output
python3 bmsmon.py -a C8:47:80:15:25:01 --json
```

## Related Projects

- [aiobmsble](https://github.com/patman15/aiobmsble) — Python async BLE BMS library (has `redodo_bms.py`)
- [BMS_BLE-HA](https://github.com/patman15/BMS_BLE-HA) — Home Assistant integration (supports Redodo)
- [LiTime_BMS_bluetooth](https://github.com/calledit/LiTime_BMS_bluetooth) — Web Bluetooth implementation
- [litime-bluetooth-battery](https://github.com/chadj/litime-bluetooth-battery) — Another JS implementation
- [Litime_BMS_ESP32](https://github.com/mirosieber/Litime_BMS_ESP32) — ESP32 Arduino library
