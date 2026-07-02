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

### What the official Redodo app does (verified by full HCI capture, 2026-06-29)

We captured the Redodo Android app (`com.redodopower.ble`) connecting to all 8 packs, via the
Android **Bluetooth HCI snoop log** (`adb bugreport` → `btsnoop_hci.log`, decoded with `tshark`).
Findings — these are the **reference behavior** to model the Android app's BLE on:

- **It holds all 8 packs connected *simultaneously*** (persistent links, 8 concurrent GATT
  connections held continuously for minutes). It does **not** cycle/poll-then-disconnect, and it
  does not fake "connected." A Pixel 6 held 8 concurrent LE connections fine — the oft-cited
  Android "~7 connection" cap is a soft default, not a wall here.
- **It sends the byte-identical commands we send, and only safe reads:**
  `00 00 04 01 13 55 AA 17` (the `0x13` status query — same as our `STATUS_FRAME`) and
  `00 00 04 01 16 55 AA 1A` (`0x16` firmware), plus standard CCCD notification-enable writes.
  **No `0x60`, no `0x0A–0x0D`, no unknown opcodes.** Confirms the protocol is correct AND that
  our read-only app does exactly what the official app does — we are not stressing the BMS in any
  way Redodo doesn't. (Only diff: Redodo uses ATT Write Request *with* response; we use Write
  Command *without*. Functionally equivalent.)
- **Flaky GATT establishment is normal and is solved by patient retry, then hold.** Marginal packs
  failed to establish (connect, then GATT drops ~0.1–0.3 s later — the `GATT_CONN_FAILED_ESTABLISHMENT`
  / status-133 signature) and were retried with spacing until they stuck (one pack took ~8 tries
  over 26 s). Once connected, the link is **kept open**.
- **Two-tier polling rate (measured):** on the **actively-viewed single battery** (live detail page) it polls `0x13` status **every ~1.5 s** (mean 1.487 s, range 1.43–1.53 s, rock-steady) — this is the rate we mirror for the **main stage** (`STAGE_POLL_MS = 1500`). For **background** packs it's far slower (~17 reads across 8 packs over ~3 min). Fast on the one you're watching, slow on the rest.

**Implication for our Android app:** holding persistent connections + slow polling + patient
retry-then-hold is the proven-gentle model; our rotating connect→read→disconnect sampler is the
*more* stressful pattern on these finicky Beken modules. Full write-up, the connection timeline,
and the capture/decode commands are in `docs/ble-connectivity-investigation.md`.

## Hardware Context

Tested with 8x Redodo 12V 100Ah LiFePO4 batteries (grouped into bases; see `BATTERY_ALIASES` in `bmsmon.py`):

| MAC Address | Name | Group / alias |
|-------------|------|---------------|
| C8:47:80:15:67:44 | R-12100BNNA70-A02214 | 2012-A (current daily driver) |
| C8:47:80:15:62:1B | R-12100BNNA70-A02345 | 2012-B (current daily driver) |
| C8:47:80:15:DB:13 | R-12100BNNA70-A03902 | 2016-A |
| C8:47:80:15:25:9A | R-12100BNNA70-A03727 | 2016-B |
| C8:47:80:46:0A:D6 | R-12100BNNA70-B02371 | 2023-A |
| C8:47:80:45:90:FB | R-12100BNNA70-B02375 | 2023-B |
| C8:47:80:15:07:DE | R-12100BNNA70-A02285 | 2024-A |
| C8:47:80:15:25:01 | R-12100BNNA70-A02402 | 2024-B (primary test unit) |

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

**Alerts (capacity + temperature):** the stage flashes a `DangerOverlay` that *names* the alert
type (`BATTERY CAPACITY` / `TEMPERATURE`) and fires headless notifications via `AlertNotifier`
(critical channel = sound+vibration). Pure logic in `model/Alerts.kt` (SOC bands; a threshold of
N% fires **at** N%, `<=`) and `model/TempAlerts.kt` (cold→hot zone ladder: caution/warning/
critical/cutoff, **critical fires before the BMS cutoff**). The unified `stageAlert()` shows the
**worst** of the two. Capacity/temperature settings live in `Settings › Alerts` and
`Settings › Temperature`; the stage's worst pack drives the overlay + `AlertNotifier` dedup.

**Temperature monitoring:** a vertical temperature gauge (`ui/gauge/TempGauge.kt`) sits beside the
SOC ring on the stage (toggle + L/R position in settings), plus a `TEMP` stat tile. Thresholds are
**per battery profile** (`BatteryProfile.tempEnvelope`; Redodo defaults cold-caution 5 / hot-caution
45 / cold-crit −12 / hot-crit 53 °C, fixed cutoffs −20/60), stored in `SettingsStore` keyed by
`profileId`, tunable in `Settings › Temperature` with reset-to-defaults. Unit is the app-wide
`tempFahrenheit` pref (°F default; thresholds stored in °C). Debug-only `TempPreviewActivity`
(`app/src/debug/`) renders the gauge/overlay with synthetic packs for emulator screenshots.

**Cloud config push (one-way):** when temp thresholds change (and cloud sync is on), the phone
uploads the profile's threshold config — signed + gzipped like telemetry, durable/latest-wins — to
`POST /api/v1/config`; the WebUI mirrors it read-only. Telemetry uploads are **gzip-compressed**
(`Content-Encoding: gzip`; server decompresses before the JWT body-hash verify).

**Usage logging is intentionally ON right now — do not turn it off.** Every telemetry
sample is recorded to the phone's Room DB (`bms.db`, `samples` table, columns incl.
`current_a`, `power_w`, `regen`) via `TelemetryRepository`, and mirrored to the cloud
Postgres when sync is enrolled, so we keep collecting **real-world data to calibrate the
UI**:
- the inner power ring's full scale `POWER_RING_FULL_W` (Fleet.kt; since **calibrated to 300 W** — see below),
- the regen detection thresholds `REGEN_EPS` / `REGEN_WINDOW_MS` (Fleet.kt).

(The legacy `usage_log.csv` writer no longer exists — that file was one-time imported into
Room; query the phone's `bms.db` or the cloud `samples` table instead of pulling a CSV.)
Steady charging was captured as a baseline (`regen=0`); regen bursts while driving log as
`regen=1`. Logging + monitoring both persist across restarts.

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

**Accuracy check-in — due 2026-07-15** (set 2026-07-01): with ~2 more weeks of accumulated
data, re-verify against fresh telemetry (phone Room DB `bms.db` or cloud Postgres) and adjust
constants if the larger dataset has moved them:
- **Charge-time ETA** — the self-learning time-to-full estimate (physics coulomb for the CC bulk
  ≤98%; a learned per-pack `tailMin` EMA for the 98→100% CV tail, seeded ~45 min). At design time
  only **2 full-arc charge sessions** existed, so the tail prior was weak — confirm whether more
  completed full charges (SOC reaches 100) have tightened it, and re-backtest the CC region
  (was ±4–7 min).
- **Discharge + regen gauge calibration** — re-check `POWER_RING_FULL_W` (300 W) and
  `REGEN_EPS` (0.1 A) / `REGEN_WINDOW_MS` (30 s) in `Fleet.kt` against the larger real-world set.

Garbage-frame guard: `parseTelemetry` realigns to the `01 93 55 AA` status header (BLE
notification fragments can prepend stale bytes, which previously decoded as soc=0/37.6 V and
tripped a false critical alarm) and rejects implausible readings (SOC 0–100, voltage 4–70 V).
The main stage shows a pack that isn't reachable as **DISCONNECTED** (dimmed ring, no %, no
alert) rather than a misleading 0%.

**No demo data (removed).** The old offline "demo" telemetry (`demoFor()`, `UiState.demo`,
`tickDemo` drift loop) was removed — we're past needing it. When monitoring is off, the app keeps
the **last-known fleet marked unreachable** and renders every pack as **DISCONNECTED** (dimmed,
no %) instead of synthetic data; the top-bar status reads **MONITORING OFF**.

**Disconnect semantics.** Per-battery disconnect and **Disconnect all** both drop the BLE link
the same way — they add the pack(s) to the `disabled` set and call `engine.setDisabled(...)`,
which cancels the staged worker so its GATT closes; the engine keeps running. Each disconnected
row shows a **reconnect (link) icon**, and the All Batteries header toggles **Disconnect all ⇄
Reconnect all**. "Disconnect all" is therefore distinct from *stopping monitoring* (the
foreground-service Stop), which tears the engine down entirely.

**Low-battery alerts (configurable ladder + critical tier).** `ALERT_THRESHOLDS`
(BatteryViewModel.kt) is the full selectable 5% ladder **95%→5%**; `DEFAULT_THRESHOLDS`
(`30/25/20/15/10/5`) is what a fresh install enables (high marks default OFF). The **critical**
tier (red / faster pulse) is user-configurable via `criticalThreshold` (`UiState` +
`DEFAULT_CRITICAL_THRESHOLD = 15`), replacing the old hardcoded `≤15`. The Alerts settings page
shows the full ladder (chips ≤ critical tint red), a single-select **Critical level** picker, and
a **Reset to defaults** button. `stageAlert()` resolves the alert from the lowest pack on stage;
charging suppresses the flash; acknowledged thresholds silence until SOC drops to the next level.

**GPS telemetry (cloud upload).** When cloud sync is enrolled, the app captures the phone's
location (`location/LocationSource.kt`, fused provider) and attaches `lat`/`lon`/`gps_accuracy_m`
to **every** uploaded telemetry sample — it rides the same offline-durable outbox, so offline
driving is buffered and synced on reconnect. `gpsEnabled` defaults **on with cloud sync**
(reducer `p.gpsEnabled ?: p.cloudEnabled`), toggled in Cloud sync settings ("Send GPS location").
The engine's effective GPS-active = `monitoring && gpsEnabled && enrolled && cloudEnabled`.
Needs `ACCESS_FINE/COARSE_LOCATION` + `ACCESS_BACKGROUND_LOCATION` + a `location` FGS type
(`MonitoringService` ORs `FOREGROUND_SERVICE_TYPE_LOCATION` only when GPS-active AND location is
granted — required to avoid an Android-14 SecurityException). Background-location was the
explicit design choice for pocket/driving capture.

**Main-stage upload indicator.** The Home top bar shows a small glanceable cloud-upload status
next to the stage label, only when cloud sync is enrolled: `↑ X.X KB/s` (green) while uploading,
`↑ synced` when caught up, `↑ N queued` (amber) when buffering/offline. The rate comes from
`cloud/UploadRate.kt` (a pure, unit-tested 5 s rolling window of actual POST body bytes →
smoothed KB/s) surfaced through the reporter's `onStatus` into `UiState.cloudUploadKbps`.

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

# Live monitoring (--watch takes the poll interval in seconds)
python3 bmsmon.py -a C8:47:80:15:25:01 --watch 1

# JSON output
python3 bmsmon.py -a C8:47:80:15:25:01 --json
```

## Cloud Server & Deployment

The cloud backend lives in `server/` (FastAPI + asyncpg + **Postgres 16**) and the dashboard in
`web/` (React + Vite). The phone (`android/`) enrolls a device and uploads signed telemetry
batches to `POST /api/v1/ingest` (gzipped) + threshold config to `POST /api/v1/config`; the WebUI
reads `GET /web/fleet` + a `/ws` live feed + `GET /web/temp-config` (the read-only temperature
mirror), plus admin-gated `GET /web/samples`, `GET /web/devices`, `POST /web/enroll-codes`,
`DELETE /web/devices/{id}`). The temperature config lives in the `device_temp_config` table
(per device+profile, latest-wins); the WebUI mirror (`web/src/temp.ts` + `TempGauge`/`TempBanner`/
`TempOverlay`/`BatteryProfilePanel`) re-evaluates the same zone ladder read-only. Schema is
idempotent SQL in `server/app/db/schema.sql` (`CREATE TABLE IF NOT EXISTS` + `ALTER TABLE ... ADD
COLUMN IF NOT EXISTS`) run on pool creation — so **schema changes apply automatically on container
start; there is no separate migration step**.

The `samples` table mirrors the phone's telemetry (soc, current, power, voltage, temp, cells,
cycles, regen, link_event, …) plus **GPS** columns `lat`/`lon` (`double precision`) and
`gps_accuracy_m` (`real`), all nullable. The WebUI shows a header **"GPS" pill** (green when
recent samples carry coordinates) and a browser-local **light/dark toggle** (sun/moon in the
header; default dark; persisted in `localStorage["bmsmon-theme"]`; light mode is a
`:root[data-theme="light"]` CSS-variable override in `web/src/theme.css`). The page declares
`<meta name="darkreader-lock">` so the Dark Reader extension never alters it in either mode.

**WebUI layout (`web/src/App.tsx`):** the dashboard is the **main stage** + **All Batteries**; a
header **⚙ toggle** opens a **Settings** view (battery-profile panel + device admin — kept off the
main page). Header also has a **°C/°F** unit toggle (`localStorage["bmsmon-temp-unit"]`, default the
phone's synced unit). **Pin to stage:** a pin icon on every card/stage pack; pinned packs (by
address, `localStorage["bmsmon-pins"]`) become the main stage, else it auto-selects the active base
(the header shows `PINNED · AUTO OFF` vs `AUTO`). **Disconnected packs keep their last-known
telemetry, muted** (dimmed ring/gauge + muted stats + `DISCONNECTED · updated <ago>`), and stop
driving live temperature alerts — like the Android All-Batteries view. A dev-only preview harness
(`web/preview.html` → `src/preview.tsx`) renders the components with mock data for Playwright
screenshots; it is **not** in the production bundle (`vite build` emits only `index.html`).

**Local dev/test:** `docker compose -f server/docker-compose.dev.yml up -d` brings up a Postgres on
`localhost:5432` (user/pw/db all `bmsmon`, matching the default `DATABASE_URL`). Run server tests
with the venv: `cd server && .venv/bin/python -m pytest` (bare `python` lacks the deps).

### Image build (GitHub Actions)

`.github/workflows/build-server.yml` builds the multi-stage image (Node builds `web/dist` → Python
serves API + static) and pushes `ghcr.io/mkeguy106/bmsmon-server:latest` (+ a `:<sha>` tag) on any
push to `main` touching `server/**`, `web/**`, or that workflow. Watch a run with `gh run watch` or
the Actions tab.

### Production deploy (QNAP NAS)

Production is `bmsmon.covert.life` on the QNAP NAS **`ddnas02`** (SSH: `ssh joely@ddnas02`), run from
the **`~/qnap-nas-docker`** infra repo — see **`~/qnap-nas-docker/CLAUDE.md`** for NAS conventions
(docker path, `${CONFDIR}`, the `--env-file ../.env` requirement, Traefik/Authentik). The bmsmon
stack is `~/qnap-nas-docker/bmsmon/docker-compose.yml`: `bmsmon-api`
(`image: ghcr.io/mkeguy106/bmsmon-server:latest`) + `bmsmon-db` (Postgres, data at
`${CONFDIR}/bmsmon/database`). Traefik splits routing: `/api/` → device-JWT auth (no Authentik);
everything else → Authentik SSO.

**Deploying a new server build** (the NAS does **not** auto-pull `:latest` — watchtower is monthly,
and the qnap-nas-docker deploy runner only fires on `docker-compose.yml`/`.env` changes, and
`up -d` alone won't re-pull an unchanged tag). After the image build finishes, pull + recreate just
the API container:

```bash
ssh joely@ddnas02 'bash -lc "cd /share/bsv/docker-compose && \
  docker compose --env-file .env -f bmsmon/docker-compose.yml pull bmsmon-api && \
  docker compose --env-file .env -f bmsmon/docker-compose.yml up -d bmsmon-api"'
curl -fsS https://bmsmon.covert.life/api/v1/health   # expect {"status":"ok"}
```

On startup the new container re-runs `schema.sql`, so additive columns/tables land automatically.
Changes to the **stack** itself (`bmsmon/docker-compose.yml` or the shared `.env`) deploy
differently: push them to the `~/qnap-nas-docker` repo's `master` and its self-hosted runner
(`.github/workflows/deploy.yml`) SSHes in and restarts the changed service.

## Related Projects

- [aiobmsble](https://github.com/patman15/aiobmsble) — Python async BLE BMS library (has `redodo_bms.py`)
- [BMS_BLE-HA](https://github.com/patman15/BMS_BLE-HA) — Home Assistant integration (supports Redodo)
- [LiTime_BMS_bluetooth](https://github.com/calledit/LiTime_BMS_bluetooth) — Web Bluetooth implementation
- [litime-bluetooth-battery](https://github.com/chadj/litime-bluetooth-battery) — Another JS implementation
- [Litime_BMS_ESP32](https://github.com/mirosieber/Litime_BMS_ESP32) — ESP32 Arduino library
