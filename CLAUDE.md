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
- **FFE2** (write, with or without response — the Android app uses Write Request like the official app; the legacy `bmsmon.py` uses Write Command): Commands to BMS (UART TX to BMS MCU)
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
  way Redodo doesn't. (Write type: Redodo uses ATT Write Request *with* response, and so does the
  Android app — `writeWithResponse = true` in `ble/profile/Profiles.kt` → `WRITE_TYPE_DEFAULT`. Only
  the legacy `bmsmon.py` CLI sends Write Command *without* response (`response=False`); the module
  accepts both.)
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

### The Pixel is a dedicated telemetry device, not the user's phone

This changes how to reason about almost every power and connectivity decision, so read it before
touching either.

- The Pixel 6 is **MagSafe-mounted to the wheelchair frame** and does nothing but run this app. The
  user's daily phone is an **iPhone**, so nobody reads the Pixel's screen for messages, takes calls
  on it, or is left uncontactable if it loses connectivity. **But they do read it constantly for
  battery state** — see below; the screen is the product.
- **Networking is Wi-Fi only, by design.** Home Wi-Fi at home; away from home it associates with the
  **iPhone's hotspot**, which is how OTA telemetry keeps uploading on the road. There is no scenario
  in which this device needs cellular.
- **Cellular is deliberately off.** The SIM reads `OUT_OF_SERVICE` on both voice and data (Verizon,
  `registrationState=DENIED`, emergency-only), so the modem hunts for a network it can never join
  and burns **~28 mA** doing it — measured 299 mAh across one 12.5 h night. **Airplane mode ON is
  the correct steady state for this device**, with Wi-Fi re-enabled on top of it.

**⚠ Enabling airplane mode also switches Bluetooth off, which kills BLE monitoring of the chair.**
Always restore both, in order, and verify rather than assume:

```bash
adb shell cmd connectivity airplane-mode enable
# then re-enable Wi-Fi and Bluetooth, and confirm:
#   Wi-Fi associated (home SSID or the iPhone hotspot)
#   BLE reconnected to all 8 packs
#   telemetry uploading again
```

Never toggle radios immediately before the user leaves the house — a Bluetooth link that does not
come back cleanly costs a whole outing's monitoring.

**The screen is the opposite of overhead — it is the reason this project exists.** The modem is
dead weight and can go; the display must not be traded away for battery.

The chair's **R-net controller gauge is not calibrated for the LiFePO4 voltage profile** — LiFePO4
holds a nearly flat voltage across most of its usable range, so a gauge built for lead-acid reads
"full" almost to the point of cutout. It lies. The Pixel is **MagSafe-mounted to the wheelchair
frame** at glance height, and this app is the user's **only accurate reading of real remaining
charge while out in the world** — the thing standing between them and being stranded.

Consequences that bind any future power work:

- **Readability is a safety property, not a preference.** Never trade it for battery life. This is
  why *Dim screen while locked* defaults **OFF** by explicit user decision, and why the dim slider
  is floored at 5% — a display that cannot be read at a glance has failed at its only job.
- Savings must come from things nobody looks at: the modem, GNSS while genuinely parked, the
  refresh rate (60 Hz is invisible on a stage that redraws every 1.5 s). Not from the display's
  legibility.
- "Nobody needs to see this screen" is **never** a valid argument on this device. An earlier
  revision of this section asserted exactly that and was wrong.

**Regression that cost a night (2026-08-07 → 08):** during an unrelated on-device mishap an agent
accidentally toggled airplane mode ON, then "restored" it to OFF, and the controller confirmed that
as correct remediation. OFF was not the user's setting — they had deliberately enabled it on
2026-08-03 for the reason above. The modem then hunted all night. When restoring device state after
an incident, restore what the **user chose**, not the platform default.

## Architecture

**Legacy CLI.** `bmsmon.py` is the original diagnostic tool, frozen since 2026-06-27 and deliberately
not ported forward (review XC-5). The protocol reference is the Android app's `ble/BmsProtocol.kt`
(`expectedStatusResponseLen`, header realignment, plausibility guard); the CLI has none of the
response-handling fixes. Use it for a quick look, not as ground truth, and don't model new work on it.

Single-file script (`bmsmon.py`) with no packaging. Only external dependency is `bleak`.

Key flow: `main()` → `scan_batteries()` or `query_battery(address)` → `parse_telemetry(data)` → `print_telemetry(dict)` or JSON output.

- `query_battery()`: Finds device via BleakScanner, connects with BleakClient, subscribes to FFE1 notifications, writes QUERY_STATUS to FFE2 (Write Command), collects response fragments until ≥80 bytes — **the old BLE-1 defect, left in place only because the CLI is legacy**: a full 0x13 response is ~105 bytes, so this can return early with a truncated buffer, and `parse_telemetry()` raises `struct.error` on a 93–95-byte one. The Android app completes on the frame's own length (`expectedStatusResponseLen`) and realigns to `01 93 55 AA`.
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
destroyed. **The engine owns stage resolution too** (T1.2, 2026-10-02): `resolveStage` — the
low-pack seize included — runs inside `MonitorEngine.reevaluate()` (pure core:
`engineDecision()` in `model/StageControl.kt`) on every BLE event, every config push and a 10 s
tick, so a headless (sticky/boot) restore follows the chair and seizes exactly like the
foreground app; the ViewModel pushes `StageConfig` (pin, dynamic, hold, daily driver, seize
threshold) and mirrors `MonitorState.stageTarget`/`stagePinned`. Settings stay in the ViewModel.
**Restore covers reboots and updates** (BLE-17): `BootRestoreReceiver` (`BOOT_COMPLETED`,
`MY_PACKAGE_REPLACED`) starts the service through the same `restoreFromPersisted()` path as a
sticky restart, only when monitoring was on and BLE is granted; it never relaunches the Activity.
`BOOT_COMPLETED` is delivered after the first unlock, so on a phone with a lock-screen credential
the reboot restore waits for that unlock; `LOCKED_BOOT_COMPLETED` is deliberately not handled,
because the credential-encrypted storage holding the settings and database is still locked at that
point. The service promotes to foreground FIRST and only then checks the BLE grant (BLE-26), so a
`startForegroundService()` caller without the grant can't hit
`ForegroundServiceDidNotStartInTimeException`; a refused re-promotion of an already-running service
does not tear it down. Clean shutdown (cancels BLE jobs → each `BleSession.close()` disconnects the
GATT) happens on explicit Stop (in-app toggle or notification action) and on `onTaskRemoved` (app
swiped from Recents) — so closing the app never leaves a zombie connection blocking the phone app.
A user Stop (notification or in-app; both route through `ACTION_STOP`) also persists
`monitoring = false` (`MonitorEngine.persistMonitoringOff()`, BLE-30), so a reboot or update cannot
undo it. A Recents swipe (`onTaskRemoved`) deliberately does NOT persist false: it means "close the
app", and the next open or reboot resumes monitoring. Just backgrounding (Home) keeps it running.
Needs `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CONNECTED_DEVICE` + runtime
`POST_NOTIFICATIONS` (requested opportunistically; never gates monitoring).

**Screen policy is plug-aware, and monitoring holds a wakelock.** The display is the phone's
dominant drain — measured on the Pixel 6 at ~136 mAh/h against ~22 for GNSS and ~1.6 for
Bluetooth, with the app the top consumer at 600 mAh over 4 h — so `FLAG_KEEP_SCREEN_ON` is held
only while the phone is on external power AND above a low-battery latch. External power is any
nonzero `EXTRA_PLUGGED` (AC, USB, wireless **and dock** — never a single-constant equality test).
The latch (`model/PowerPolicy.kt`, pure + unit-tested) **sets below 5% and clears at 15%**, holding
its value in between so it cannot flap; it exists because holding the screen at very low charge
out-draws the charger and puts the phone in a shutdown/reboot loop at 0%. The gate wraps the
whole expression in `ui/App.kt` — **lock mode is gated too**, so unplugging always lets the
display sleep. `power/PowerMonitor.kt` (sticky `ACTION_BATTERY_CHANGED`) feeds it;
`MonitorEngine` is the single writer of `holdScreen`/`gpsBalanced`/`lowPower` on `MonitorState`.

**"Plugged in" and "charging" are different questions, and the codebase answers both — do not
unify them.** `PowerMonitor`'s `onExternal` uses `EXTRA_PLUGGED` and is correct: deciding whether
to hold the screen depends on a source being *present*. The lock strip's battery icon
(`ui/LockStatusBar.kt`) asks whether the battery is *gaining charge*, and for that `EXTRA_PLUGGED`
is the wrong signal — it now calls the pure `batteryCharging(status)` in `model/PowerPolicy.kt`,
which keys on `EXTRA_STATUS` alone (`CHARGING` or `FULL`; FULL counts, because at 100% on a
charger Android reports FULL rather than CHARGING). The two functions sit together with the
distinction documented, so nobody "helpfully" makes them consistent.

This was a real shipped bug (2026-08-06): the icon OR'd in `plugged != 0`, so a wireless pad that
had drifted **out of alignment** — `dc_online=1`, delivering **6.4 mA**, `EXTRA_STATUS=NOT_CHARGING`
— displayed a charging icon while the phone lost **~225 mA**. The indicator whose entire purpose is
catching a dead charger was blind to exactly that case. Re-seating the phone took `dc_in` from
6.4 mA to **692 mA** (~108x), confirming alignment rather than the thermal throttle seen at 43 °C on
2026-08-03. Regression test: `PowerPolicyTest.pluggedIntoADeadChargerIsNotCharging`.

**The screen-hold gate still has this blind spot** and is deliberately left alone for now: it holds
the display (~139 mA measured) whenever `EXTRA_PLUGGED` is nonzero, including on a connected-but-dead
charger — the worst case for holding it. Fixing that means gating on current actually flowing, which
needs hysteresis so it cannot flap between charging and not, so it is a more careful change than the
icon was.

Because the BLE poll loop is a coroutine `delay()` — which does NOT fire while the CPU is
suspended — keep-screen-on had been load-bearing for poll cadence *by accident*.
`MonitoringService` now holds a `PARTIAL_WAKE_LOCK` (`bmsmon:monitoring`) for the monitoring
session, so cadence, alerts, logging and GPS capture are identical with the screen dark. **Do not
remove that wakelock without replacing the timer with an `AlarmManager`-backed one.**

The same latch drops GPS to `PRIORITY_BALANCED_POWER_ACCURACY` (20 s) — **only** in that
low-battery window (entered below 5%, held until 15% — on a charging chair-mounted phone that can
run 15-30 minutes), never in normal unplugged use. Coarse fixes are what caused the 2026-07-13
phantom map spikes, and the Wh/mile band is still converging off seed, so it must not learn from
them at scale. Pitfall found on-device: `requestLocationUpdates` with a null `Looper` means "use
the calling thread's Looper," so invoking the balanced-GPS switch from a `Dispatchers.Default`
coroutine (no Looper) threw `NullPointerException("invalid null looper")` and killed the
process — always pass `Looper.getMainLooper()` explicitly in `LocationSource`.

The screen-hold policy only runs while monitoring is active — the power loop lives inside the
monitoring session (started in `MonitorEngine.start()`), so with monitoring stopped the display
sleeps normally even on external power.

**The latch is seeded conservatively on every (re)start, and that is load-bearing.** It lives in
memory only, so a fresh power loop has no previous value to carry — and the case that matters is
exactly the one the latch exists for: the phone dies at 0%, reboots, and you open the app at 8%
on the charger. Seeding `false` there would read 8% as "inside the hold band", leave the latch
clear, and put the display load straight back on. So the FIRST reading of each power loop seeds
from `seedLowPower(levelPct)` (`= levelPct < LOW_EXIT_PCT`) instead — anything below 15% starts
**latched**, and clears normally at 15%. Every later reading carries the previous `lowPower`. Two
consequences worth knowing: a monitoring stop→start inside the 5-14% band re-seeds (harmless — it
only ever biases toward screen-off), and if you ever persist the latch, keep the seed as the
fallback for a missing value.

**In-app battery saver (`Settings › Battery saver`).** Three toggles, each sized from an on-device
measurement *before* it was designed (Pixel 6, `dumpsys batterystats` over a 6 h 33 m / 2 580 mAh
session, 2026-08-03): screen **908 mAh ≈ 139 mA**, cpu 275 mAh ≈ 42 mA, mobile_radio 186 mAh
≈ **28.5 mA** burned while `OUT_OF_SERVICE` (fixed out-of-band with airplane mode, not by the app —
see "The Pixel is a dedicated telemetry device" for why cellular is off and the Bluetooth gotcha when
re-enabling airplane mode),
gnss **143 mAh ≈ 22 mA**, wifi 108 mAh ≈ 16.5 mA, GPU 57.3, bluetooth **19.3 mAh ≈ 3 mA**, TPU 18.4.
The trigger was finding the phone net-discharging at **−174 mA while sitting on its wireless
charger** at 11% SOC, ~3 h from dead. Pure logic in `model/BatterySaver.kt` — `lockRefreshRate` /
`lockBrightness` / `gpsParked` / `gpsShouldRun`, no Android imports, unit-tested in
`BatterySaverTest.kt`, same pure-and-total shape as `PowerPolicy`:

- **Lower refresh rate on lock** — default **ON**. `preferredRefreshRate = LOCK_REFRESH_HZ` (60f)
  on the activity window while locked. **90 → 60 Hz measured ~18 mA**: the raw net delta was
  28.7 mA, but 11 mA of that was the charging pad opening up as the phone cooled (237 → 248 mA
  input), so track pad input separately or the thermal feedback loop gets miscredited to the
  refresh rate. **60 → 30 Hz measured NO gain — it came out slightly *worse*, within noise**, with
  pad input and temperature flat: Android's idle frame-rate override was already dropping the
  render rate on a stage that only redraws every 1.5 s, so nearly every frame is idle and capping
  the *peak* is where the whole 18 mA lives. 30 Hz *is* reachable on this panel (it shows up as a
  `renderFrameRate` of 30.0, an override inside mode 1 rather than a mode switch) — it is rejected for lack of benefit, not
  lack of capability, and rejecting it also spares a `compileSdk` bump to 35 for
  `View.setRequestedFrameRate`. **Do not go sub-60 without a fresh measurement showing otherwise.**
  Deliberately **not** gated on `screenHoldAllowed`: that latch exists to stop the screen being
  *held on*, whereas a lower refresh rate is a saving in every power state, so gating it could only
  ever cost battery.
- **Dim screen while locked** — default **OFF by explicit decision**: reading pack state at a
  glance outdoors outranks the saving, so this is opt-in. A slider (`lockDimLevel`, default
  `DEFAULT_DIM_LEVEL` 0.30) rather than a fixed level, because the right value depends on the
  daylight the user actually rides in — floored at `MIN_DIM_LEVEL` **5%** so a slider dragged to
  zero can never black out a chair-mounted display. The slider persists once on release
  (`onValueChangeFinished`), keyed on the stored value so the ~1.5 s telemetry recompositions on
  that screen can't reset a drag.
- **Pause GPS while parked** — default **ON**. The chair cannot move without discharging a pack —
  the same fact the range learner's discharge gate already rests on — so a parked chair's fixes
  cost 22 mA to produce data the learner throws away. Parked indoors the fixes are junk anyway:
  **53.79% location-failure rate**, 4 satellites, mean C/N₀ 24 dB-Hz, last fix accuracy 39 m.
  `gpsParked()` reads the newest entry of the engine's **existing** `lastDischargeAt` map (no new
  discharge threshold is introduced) and calls it parked after `PARKED_HOLD_MS` (5 min), boundary
  inclusive. `groupActivity()`'s 0.05 A epsilon needs no tuning: the BMS's ~1.04 A reporting
  deadband means any epsilon in (0, 1.04) is equivalent, the same structural guarantee the regen
  detector relies on. **Full stop, not a drop to balanced accuracy** — coarse fixes are what caused
  the 2026-07-13 phantom map spikes, so we would rather capture nothing than capture noise that
  gets uploaded and drawn before being discarded. Accepted cost: reacquisition, **TTFF 292 s mean
  indoors** (outdoor TTFF is far better, and the learner's 0.5-mi outing gate is well above the
  error a lost first minute introduces). **Saving quantified 2026-08-04: the gate holds GNSS off
  68.4% of wall-clock time, so expected saving ≈ 0.684 × 22 mA ≈ 15 mA (~360 mAh/day, ~3.8% of the
  2 580 mAh/6.5 h baseline).** That closes the "never measured end-to-end" caveat — the 22 mA was
  always measured, the duty cycle was the missing half (it is a duty-cycle-derived estimate, not a
  power measurement). **The "a spare discharging on a charger at home holds GNSS on" caveat is
  RETIRED as unfounded**: across 38 days there are **0 minutes** where only a non-daily-driver
  discharged (lifetime discharge rows: 2016-B 8, 2016-A 2, the other four 0, and both spare events
  fell inside minutes a daily driver was also discharging). The gate is driven entirely by the
  chair. Transit cost is also now measured — see the `PARKED_HOLD_MS` note below.

Both display effects are window-scoped `WindowManager.LayoutParams` set in a `DisposableEffect` in
`ui/App.kt`, so they revert on focus loss or process death — **nothing writes the system-wide
`peak_refresh_rate` or the system brightness.** (If a device ever seems to ignore the app's
preference, check for leftover `settings system peak_refresh_rate`/`min_refresh_rate` overrides from
manual testing; those mask it.)

**Bluetooth was deliberately excluded.** Slowing the BLE poll cadence is the intuitive lever and it
is worth ~3 mA — **1.7% of drain**. It would degrade the monitoring this app exists for to save
nothing. Do not pull it.

`MonitorEngine` splits GPS **intent** from **effect**: `gpsWanted` (`monitoring && gpsEnabled &&
enrolled && cloudEnabled`, pushed by the ViewModel) is held separately from `applyGpsGate()`, which
folds in the parked state and remains the **single writer** of `gpsActive`. `applyGpsGate` is
`@Synchronized` because several threads drive it — the ViewModel (main), the BLE poll callback
(`Dispatchers.IO`), the range loop — and the read-decide-act must be atomic, or an interleaving can
leave `gpsActive = false` with the fused request still registered: exactly the silent GNSS drain the
gate exists to remove. **Three gate drivers, all load-bearing:** the BLE poll (primary, but it only
fires when a frame arrives), `setDisabled()` (**"Disconnect all"** cancels every worker, so `onPoll`
may never fire again with monitoring still on), and `startRangeLoop()`'s **5-minute tick** (Bluetooth
off or every pack out of range stalls `onPoll` indefinitely, freezing `lastDischargeAt` and pinning
GNSS on; the loop's period equals `PARKED_HOLD_MS`, so the *discharge* half of the gate overshoots by
at most one hold. It **can** now close the motion half on its own: `foldMotion` re-derives the
verdict from the clock on every call, so once a confident STILL reading has started a run — most
often relayed via `onPoll` — a single subsequent tick past `STILL_CLOSE_HOLD_MS` closes the gate
outright, no per-tick accumulation needed. It remains a backstop for STARTING a run, since it can't
manufacture a reading `onPoll` never saw; that backstop's failure direction is GPS staying on, and
`onPoll` is still the driver that usually closes the gate first). Teardown goes through
`shutdownGps()`, which drops intent and request together under the same lock —
`ble.stop()` cancels the control-loop job but cannot preempt an in-flight `onPoll`, so an
unsynchronized teardown lets that call's `locationSource.start()` land *after* `stop()`.
`MonitoringService` re-derives its FGS type from `gpsActive`, adding/removing
`FOREGROUND_SERVICE_TYPE_LOCATION` at runtime as the gate flips; three real 16 ↔ 24 type changes
were observed on-device with no `SecurityException` and a stable pid, though the strict "type
changed while the process was already backgrounded" timing was only confirmed for one of the
three — the settings pipeline resolves faster than an adb tap-then-HOME can beat.

**Discharge alone reads vehicle transit as *parked*, which is why the gate is now motion-gated too.**
The proxy is "no base has discharged for 5 minutes", and in the van or on the train **the chair draws
nothing** (user-confirmed — it is precisely why the range learner's discharge gate excludes vehicle
rides). So transport read as parked and GNSS stopped, degrading two shipped behaviors: **Journey lost
real transit legs** — the "dashed transit legs" described under WebUI v2 below came from GPS moving
while no pack discharged, and with GPS paused the map bridges the hole with a straight `inferred`
dashed line (the Kalman pass's `COAST_MAX_MS` handling) instead of the traced route — and **the live
share marker froze at the departure point for the whole ride**, so "Point me there" would send a
guest where the chair *was*, the sharper problem since following the chair live is the share
feature's entire purpose.

**Quantified 2026-08-04, and those figures stand unchanged.** Of 357.5 moving miles (≥0.4 m/s) since
2026-07-13 the gate drops **256.5 (71.7%)**, including **205.5 of 227.7 vehicle-speed miles (90%)**.
Measured trade-off (GNSS-off duty / moving miles lost): 5 min **68.4% / 256.5** · 10 min 60.6% /
212.9 · 15 min 55.5% / 180.2 · 20 min 51.7% / 150.5 · 30 min 46.7% / 113.2. That analysis chose
option (a) — keep 5 min — because the lost miles are ones the range learner discards anyway (no
discharge ⇒ no learning), and flagged the revisit trigger as "a UX judgement rather than a data one".

**SUPERSEDED 2026-08-06 — the decision changed for a cost those figures never captured.** The
trade-off had been weighed as a *range-learner* cost; the real cost is the **map record**. Three
user-confirmed vehicle outings — 08-04 15:00–16:15, 08-05 09:00–10:05, 08-06 09:35–10:45 — are
**entirely invisible, destinations included**: each shows **0% discharge for 65–75 minutes** and
returns to within **2–10 m** of its start, because the chair drew nothing from leaving to getting
back. The Journey map cannot distinguish those from a nap at home. The natural experiment agrees:
before the gate (08-01, 08-03) vehicle trips tracked to **71 mph** and out to **81 miles** from home;
for the three days after, **zero fixes above 5 m/s**. **Option (b), lengthening `PARKED_HOLD_MS`, is
dead** — no hold length covers a 70-minute outing. Option (d), suppressing the pause while a share is
live, stays **rejected**: the cloud channel is deliberately one-way phone→server and that would
invert the architecture.

**The fix (option (c)): pausing now requires BOTH no-discharge AND a confident-still
verdict** from the phone's own motion. Misclassification is safe in both directions — when the chair
drives under its own power it *is* discharging, so that branch was already covered; AR wrongly saying
"still" in a vehicle is today's behavior (no regression) and wrongly saying "moving" while parked is
the pre-feature behavior (saving lost, nothing broken).

**What the device actually reports, which is the load-bearing part.** Measured on-device 2026-08-07,
phone stationary: it reports `STILL` at confidence **96–100**, interleaved with `UNKNOWN` at
**41–50**, and **never reports confident motion at all**. Readings arrive roughly every **5.7 s** —
far faster than the 30 s requested. The rule that shipped first let **one instantaneous sample**
decide and mapped `UNKNOWN` to "not still", so every low-confidence blip reopened the gate: against
that trace it passed **70%** of readings but **toggled the gate 5 times in 5 minutes**, restarting
GNSS repeatedly — worse than either steady state. The debounced rule closes the gate for **96%** of
the same trace (N=2 → 98%, N=1 → 100%; 3 is the smallest N that still demands genuinely sustained
evidence). The conceptual error is the thing to remember: **`UNKNOWN@41` is absence of evidence, not
evidence of motion.** Treating uncertainty as movement was the entire defect.

`foldMotion(prev, reading, nowMs)` (`model/BatterySaver.kt` — pure, no clock, JVM-tested) was
**reworked 2026-08-09 to silence-as-stillness semantics** (spec:
`docs/superpowers/specs/2026-08-09-silence-as-stillness-motion-gate-design.md`), because the
first night of motion telemetry proved AR delivery is **motion-triggered at the sensor level** —
4 readings in ~18 h while genuinely parked, rich ~5.7 s cadence in vehicles — so the original
debounce-and-staleness rule demanded evidence that never arrives and the gate **never closed in
the recorded telemetry era** (70,779 rows, zero closes, GNSS on all night). Silence after a
confident STILL is stillness evidence, not signal loss. Branch order, still load-bearing:

- **null reading** → fails open, gate reset — every unusable-signal path (permission denied, AR
  unavailable, no reading yet, source stopped) still lands here.
- **already folded** (`reading.atMs == gate.lastConfidentAtMs`) → holds the run and **re-derives
  the verdict from the clock** — this is the branch silence closes through, since gate
  evaluations keep arriving (per BLE frame + the 5-min range tick) while readings do not.
- **uncertain** (confidence < `STILL_CONFIDENCE_MIN` 75) → same as already-folded: uncertainty
  neither starts, breaks, nor ends a run, and there is no longer a staleness deadline.
- **confident STILL** → starts the run if none (`stillSinceMs` = the reading's own `atMs`), else
  keeps its start; the verdict closes once the run is `STILL_CLOSE_HOLD_MS` (**10 min**,
  inclusive) old.
- **confident non-STILL** → reopens on a **single** reading, run cleared (unchanged).

`MOTION_STALE_MS` and `STILL_DEBOUNCE_N` are **deleted**. The hold replaces the debounce's
anti-flap job (a stoplight STILL must survive 10 uncontradicted minutes; in-vehicle delivery
contradicts it in seconds), and 10 min was chosen over 5 so train-station stops rarely close the
gate mid-trip — one that does self-corrects on departure vibration at the cost of one GNSS
restart. The inverted risk — a silently-dead AR subscription holding the gate **closed** while
parked — was explicitly accepted, bounded by the discharge clause (chair outings discharge at the
start, and `gpsShouldRun` still requires BOTH halves to pause) and by
`MotionSource.maybeResubscribe`: the same PendingIntent's update request is re-issued every
`RESUBSCRIBE_MS` (6 h) from `applyGpsGate`. (Field-checked 2026-08-10: a refresh elicits **no**
immediate reading — only fresh register+request cycles produce the burst — so the refresh is
subscription hygiene, not a stillness probe.) Restarts self-heal: the subscribe burst's one
reading starts a run and the gate closes 10 min later, where the old rules left a restarted gate
open forever. **Deployed + field-verified 2026-08-09 evening:** the first close in the telemetry
era landed at exactly reading-age 600 s (silence on the cached burst reading, inclusive boundary)
and GPS fixes went ~47/min → 0 the next minute. **Fully field-verified 2026-08-10 on a real
vehicle outing:** the overnight hold ran ~12 h on one cached reading (GNSS off 03:00–10:00 UTC
straight, zero false reopens, across two silent 6 h refreshes); both vehicle legs reopened the
gate on the FIRST confident `IN_VEHICLE@90` at reading age **1 s** (13:20:34 outbound — GPS was
already on via the discharge clause, the two-clause redundancy working; 15:51:47 return); AR
delivered ~200 readings/h in motion vs 1/night still, and none of them produced a false mid-drive
close; arrival re-closed the gate at exactly the 10-min hold (13:41:57). Every open on-device
verification for this feature is now done.

**Folds are deduped by reading identity, and that dedup still matters.** The engine evaluates the
gate on every BLE frame (~80–115×/min across the fleet) while AR broadcasts arrive ~10×/min, and
`MotionSource.current()` returns the same cached reading in between — so folding on every
*evaluation* used to count one reading ~11 times, which (under the old `STILL_DEBOUNCE_N`-counted
design) collapsed the debounce to an **effective 1**. Invisible while stationary (nothing ever
reopened the gate, which is why the on-device 5m18s hold looked right at the time), but in transit a
single spurious `STILL@96` at a stop light closed the gate ~1.5 s later and the next confident
`IN_VEHICLE` reopened it — a GNSS restart and a hole in the track per misread. Fixed 2026-08-07 by
carrying the last-folded confident reading's timestamp **on `MotionGate` itself**
(`lastConfidentAtMs`), so the dedup lives in the pure function rather than as engine state — and so
`shutdownGps()`'s existing gate reset clears it too. **Under the 2026-08-09 silence-as-stillness
rework the field keeps only its dedup-key job** — matching a fresh reading's `atMs` against the last
one folded — since the fail-open deadline it used to double as died with `MOTION_STALE_MS`; the
run's own clock (`stillSinceMs`, re-derived by `withClock` on every call) is what now decides the
verdict, not this field.

**Branch order is still load-bearing, though the order and its job changed.** Current order: null
reading → dedup → uncertainty → confident STILL → confident non-STILL. The verdict is re-derived
from the clock on both the dedup and uncertainty branches — that is the branch silence closes
through, since gate evaluations keep arriving (per BLE frame plus the 5-min range tick) while
readings do not. Only the null-reading branch still guarantees fail-open: permission denied, AR
unavailable on the device, subscription lapsed, process restarted with no reading yet — all land
there and read GPS-on, the user's explicit choice to never lose an outing even at the cost of the
saving. Two paths that used to fail open no longer do, by design: "updates gone stale" and "a run of
only low-confidence readings" — there is no staleness deadline left to trip, so uncertainty just
holds whatever verdict is already in force, and a gate that has legitimately closed now stays closed
through silence instead of reopening on a clock (the inverted risk the fold-rules block above
accepts). The single-sample `confidentlyStill()` predicate is **deleted**; the name survives only as
`gpsShouldRun`'s parameter, which the gate's `still` verdict now feeds.

Plumbing: `motion/MotionSource.kt` wraps Play Services **periodic** Activity Recognition (~30 s
requested), mirroring `location/LocationSource.kt`, and logs every reading (activity name +
confidence) — permanent instrumentation, not throwaway debug, because `foldMotion` only ever sees the
cached `MotionReading`, never the classification behind it, and that blindness is what made the
original non-firing take three rounds to diagnose. `MonitorEngine` owns the `MotionGate`, starts/stops
`MotionSource` off `gpsWanted` **&& the pause toggle** (with *Pause GPS while parked* off,
`gpsShouldRun` ignores the motion verdict entirely, so the subscription would be pure waste in
exactly the configuration a user picks to keep their track), and folds each reading **inside the same
`@Synchronized applyGpsGate`** that writes `gpsActive` (same lock discipline as `locationSource`, so
a `start()` can't land after a concurrent teardown's `stop()`); `shutdownGps()` stops the source and
**resets the gate**, so a stale stillness run cannot survive a stop. Three build pitfalls, all
**silent** failures rather than crashes: the AR `PendingIntent` must be `FLAG_MUTABLE` (Play Services
fills the `ActivityRecognitionResult` extra into it) **and** explicit
(`Intent(ACTION).setPackage(packageName)`) — Android 14+ throws `IllegalArgumentException` for
mutable + implicit, so motion sensing would simply never have subscribed; `ACTIVITY_RECOGNITION` must
be requested at **both** `ui/App.kt` call sites, because on any install where BLE is already granted —
the real device and every existing user — the monitor toggle takes the `hasBlePermissions` branch and
the `permLauncher` site never fires; and it must be requested **in the same
`RequestMultiplePermissions` call as `POST_NOTIFICATIONS`, never as a second launch in the same
frame**. `ActivityCompat.requestPermissions` does not queue — a request issued while one is in flight
is refused and immediately dispatched back as an *empty cancelled result*, so the notification dialog
appeared and the motion dialog was silently dropped, on the first toggle of a fresh install and on
every toggle by a user who denied notifications. Since monitoring restores across restarts, a user
who starts it once and never toggles again would never have been asked at all. Both results are still
ignored and `vm.startMonitoring()` stays unconditional: neither permission may ever gate BLE
monitoring. `Settings › Battery saver` carries a **read-only**
line, "Motion sensing active" / "Motion sensing unavailable — GPS won't pause", so a denied
permission cannot silently disable the saving while the toggle still reads on. *(Its two states have
not yet been confirmed on-device — an owed verification, not a completed one; tracked in
`docs/checkins/NEXT.md`.)*

**The Activity Transition API was measured and rejected**, reversing the recommendation an earlier
revision of the design carried. Armed with transitions at 13:18 on 2026-08-07 (standby bucket 10,
`SUBSCRIBE SUCCEEDED`), the probe covered two genuine vehicle trips that afternoon at up to **24 mph**
and logged **zero transitions**, while periodic updates arrive every few seconds. Caveat worth
recording: the probe (`:arprobe`, branch `experiment/ar-power-probe`) has no foreground service and
is not resident, whereas the app is, so it may be an **invalid proxy** for in-app delivery — but
nothing supports preferring transitions, and the periodic stream is demonstrably rich enough.

**The 2026-08-08 "partial saving / MOTION_STALE_MS tuning OPEN" question is RESOLVED by the
2026-08-09 rework above** — the first night of motion telemetry showed the saving was not partial
but **zero** (the gate never closed), the staleness window was the wrong knob entirely, and
silence-as-stillness replaced it. AR's true power cost remains unmeasured (its revert condition
stands), and the wire-cost measurement is still owed — both tracked in `docs/checkins/NEXT.md`.

**No server or WebUI change was required** for either half of this: every GPS read path already
filters `lat IS NOT NULL AND lon IS NOT NULL` (`queries.track_series`, `queries.gps_track_all`),
`lat`/`lon`/`gps_accuracy_m` have always been nullable (the phone already uploads null coordinates
whenever GPS is off or no fix is cached), the live marker already greys at 120 s to "last known +
age", and the server suite passed unchanged at the time. Gaps were already first-class on the web
side.

**Android's own Battery Saver is deliberately not relied on.** Read off the device (`dumpsys power`,
Android 17 / SDK 37) rather than off the generic feature list, almost every lever is already pulled
or does not apply: it carries **no refresh-rate flag at all** and the display reports
`lowPowerSupportedModes=[]`; `enable_brightness_adjustment=false`, so it **does not dim** (the
`adjust_brightness_factor=0.5` is inert); `disable_aod` and `enable_night_mode` are already in our
desired state; `enable_quick_doze` only fires with the screen off and we hold it on;
`force_all_apps_standby`/`force_background_check`/`enable_firewall` are real but throttle *other*
apps, and our foreground service is exempt; and `location_mode=3` (foreground-only) **actively
breaks** backgrounded GPS capture. Hence an in-app section doing the specific things that measurably
help this app.

**Local DB size is not a problem and needs no new pruning.** Retention already runs and works:
`SAMPLE_RETENTION_DAYS = 14`, raw frames 7 days / 20 MB (`RAW_FRAME_RETENTION_DAYS` /
`RAW_FRAME_MAX_BYTES`), applied by `TelemetryRepository.prune()` from `maybePrune()` every 200
inserts. Measured 2026-08-03: the SQLite header reads 103 389 pages × 4096 = **423.5 MB with a
freelist of 0 pages** — nothing is reclaimable, `VACUUM` would free nothing, the file is at a
steady-state high-water mark reusing pages rather than growing unbounded — against **212 GB free**
(`/data` 8% used). Shortening retention would actively harm the product: `RangeLearn` reads the
**14-day** window and needs `MIN_LEARN_DAYS = 3`, and the Wh/mile band is still converging off seed.
`Settings › Battery saver` shows the size and row count read-only, resolved together off-main so the
row can't flash a fresh size against a stale count. `TelemetryRepository.dbSizeBytes()` — the main
file **plus its `-wal`/`-shm` sidecars**, since Room's AUTOMATIC journal mode resolves to WAL
on-device and `bms.db` alone undercounts by whatever is uncheckpointed — **replaced** an
`approxSizeBytes()` heuristic (`count() × 80 bytes/row`) that measured logical rows instead of
physical pages and read **~2.2× low** (183.6 MB estimated vs 403.7 MB actual). The 423.5 MB figure above is in decimal units (÷ 1,000,000); 403.7 MB and the app's display are binary (÷ 1,048,576), so the ~19 MB gap is unit convention, not a discrepancy. Both `Data & logging`
and `Battery saver` now call it, so the two pages can never disagree.

**Dev-workflow gotcha, and here it is a real-world one: `adb install -r` stops the app and nothing
relaunches it.** Since 2026-10-02 the `MY_PACKAGE_REPLACED` receiver restores the
*foreground service* headlessly after an install (**VERIFIED on-device 2026-10-03**: about 8 s
after `adb install -r`, before any `am start`, the service was already back), but never the
Activity — the `am start` step below stays mandatory. The phone *is* the wheelchair's battery
monitor, so an install that leaves the process dead is downtime, not a dev inconvenience — during
this build the chair's monitoring sat dead until it was manually restarted. Always follow an
install with `adb shell am start -n dev.joely.bmsmon/.MainActivity` and confirm with
`adb shell 'ps -A | grep bmsmon'`. Note that a `monkey` launcher intent
(`adb shell monkey -p dev.joely.bmsmon -c android.intent.category.LAUNCHER 1`) reports
`Events injected: 1` but does **not** start this app on this device.

**ADB authorization lapses after ~7 days unused.** Android revokes the debug authorization; USB then
shows "unauthorized" and wireless TLS fails with `CERTIFICATE_UNKNOWN`. Recover with Developer
options → Wireless debugging → "Pair device with pairing code", then
`adb pair <ip>:<pairing-port> <code>` (`adb mdns services` shows the pairing port), then
`adb connect`. Restarting the adb server while the USB "Allow" dialog is open invalidates that
dialog.

**Upload failure semantics (2026-10-02 review, DATA-14).** `classifyPost(code, fromApi)`
(`cloud/PostResult.kt`) never deletes on a status code alone: Poison is only a 400/413/422 that
carries the server's `X-Bmsmon-Api` marker header. Traefik answers its own unmarked `404` whenever
`bmsmon-api` is starting, unhealthy or stopped (every deploy, every autoheal restart, any DB
outage); that used to read as "server rejects this batch" and erased the outbox ~200 rows per POST.
Now every 4xx other than 401/403 that is not a marked 400/413/422 (so every unmarked 4xx, and e.g.
a marked 404/408/429), every 3xx (the upload client is built with `followRedirects(false)` +
`followSslRedirects(false)` in `uploadHttpClient()`, so a redirect to a login page can never come
back as a 2xx "accept") and every 5xx except a marked non-503 one (below) is Transient; 401/403 are
AuthFailed and hold the rows whether or not the marker is present. Poison then passes a circuit
breaker (`decideUpload`, `cloud/UploadDecision.kt`, pure): the first Poison after a 2xx is skipped,
any further Poison before the next 2xx is held and backed off — genuine poison is one bad batch; a
run of rejects is a server problem. Ingest, the historical import and the config push each have
their own breaker; it lives in memory, so a restart re-arms one skip per stream. The config push
also has its own retry gate (1 s doubling to 60 s, reset on a 2xx or a skip), so a held config is
not re-POSTed on every loop pass. **Deploy order is load-bearing: the server's marker ships before
this APK**, or a genuine app 4xx would be held forever.

**One sample that crashes the server can't block the queue forever (DATA-22).** Precisely: one
deterministic bad row is isolated and skipped per 2xx; two ADJACENT faulting rows, or a fault that hits
every request, are held (backing off) rather than drained — patience over data loss. **Deploy order:
the server's 503 classification ships before this APK**; against an older server a long DB outage
with the API still up returns marked 500s and could cost one good sample. Only a *marked* 5xx
other than 503 is a `ServerFault` (a marked 503 + `Retry-After` is the server's "database
unavailable", and an unmarked 5xx is Traefik — both stay Transient). It backs off like a Transient
and never touches the poison breaker. On the ingest stream only, the pure `stepHeadFault`
(`cloud/UploadDecision.kt`) bisects the head batch: after `FAULT_STREAK` (4) marked faults on the
same head that ALSO span `FAULT_MIN_SPAN_MS` (5 min, on `elapsedRealtime`) the batch limit halves
(`ceil(n/2)`); at one row it skips that row's OUTBOX copy only (the sample stays in Room `samples`
when logging is on), logs id/seq/size (never the payload), and bumps the persisted
`server_fault_skips` counter that `Settings › Cloud sync` shows once it is above 0. **At most one
skip per 2xx** (`skipsSinceOk`, the poison breaker's rule): a second trip at one row before any 2xx
holds and backs off instead, so a server that faults on everything costs one sample, then holds
until fixed, while a genuine bad row is still isolated because bisection's clean halves are 2xxs
that re-arm it. Every trip needs a fresh full span. After a skip the next head goes alone (limit 1)
and only 2xxs double it back to 200. Transient/AuthFailed responses neither reset nor advance a
streak; a changed head resets it but keeps the limit and the breaker. State is in memory, so a
restart starts over at a full batch with one skip re-armed. The import and config streams are
unchanged.

**Bounded history reads (DATA-15/16).** On the History/Review/Timeline and session-rollup paths
nothing reads a pack's or a session's samples as a list. The engine's windowed reads —
`recentSamples` (a 6 h tail of full rows for the charge-tail learner) and `rangeRows` (the 14-day
range-learner projection, still ~140 MB transient every 6 h) — are the known remaining exceptions.
History/Review take per-SOC-bin V/I moments and per-session cell Δ from SQL aggregates and the
V–I cloud from an exact keyset+OFFSET row stride (`stridePoints`); the timeline and every session
rollup (finalize, stop, startup orphan sweep, CSV backfill) stream id-keyset pages through pure
folds (`PeakPooler`, `RollupAccumulator`) that reproduce the old list results — rollups bit for
bit (data-class equality against frozen copies of the old code), fits to the tolerances
`HealthEquivalenceTest` pins (1e-9 on slope and intercept, 1e-5 on R²), scatter points
identically. What stays bounded on these paths is the **Java heap**: History/Review hold
O(bins + sessions + points) and the Timeline O(buckets), each plus one page, with the V–I stride
walk capped at `scatterPointCap()` = 2 × `SCATTER_MAX_POINTS` (1,400 points; ~700 typical); a
rollup holds one page plus ~12 B per discharge row (the `RollupAccumulator` buffers behind the exact
p95/mean and the resistance fit). Each aggregate is still an N-row SQLite sort in *native* memory
(roughly 20–25 MB transient for a daily-driver pack), so do not describe them as bins-sized or
sort-free. SQL aggregates could not do the rollup: energy needs each row's successor (`LEAD`),
and Room 2.6.1's parser has no window-function grammar (minSdk 26's SQLite 3.18 predates them
anyway). Sessions are capped at 24 h (`MAX_SESSION_MS`, inclusive). Startup prunes BEFORE the orphan
sweep, each stub is Throwable-guarded (logged + deleted on failure; the loop is the pure, tested
`sweepOrphanedStubs`), and the writer loop catches Throwable, so no single bad row or stub can
crash-loop launch. The writer's failure log is rate-limited (`FailureLogThrottle`: the first
failure of a burst with its stack, then at most one count line per minute) so a persistent DB fault
cannot flood logcat and evict the motion instrumentation, and the writer's and the sweep's log calls
are themselves guarded. The loaders behind History, Review and Timeline run off Main (IO for the
reads, Default for the residual math); the timeline pager and the V–I stride walk honor cancellation
(`ensureActive`), while the rollup pager deliberately runs to completion (a finalize should finish,
not cancel half-way); and `loadHistory`
(`ui/history/HistoryLoad.kt`) turns any Throwable into a rendered "failed" state instead of an
exception escaping `produceState` — which would kill the process and, with it, the foreground
service and BLE monitoring. The DAO SQL is shared with JVM tests as `const val`s and executed
against the checked-in Room schema via xerial sqlite-jdbc (`SqlTestDb`, test-only) — keep new
history queries on that pattern, and never reintroduce a `SELECT *` over a pack or a session on the
History/Review/Timeline or rollup paths.

**Settings file failures (DATA-21).** `bms_settings` has a `ReplaceFileCorruptionHandler`
(`data/DataStoreSafety.kt`): a corrupt file becomes defaults — **monitoring off, not enrolled**,
default roster and alert ladder — instead of a crash loop on every start. The reset is total:
afterwards monitoring stays off until the user opens the app, re-enables it and re-enrolls (it is
logged at error level, and nothing else surfaces it). One-shot `load()` falls back to defaults on
an IOException; the reporter's long-lived `persisted` flow retries with capped backoff instead,
keeping its last snapshot (emitting defaults there would complete the flow and freeze "cloud off"
until the next restart).

**Alerts (capacity + temperature):** the stage flashes a `DangerOverlay` that *names* the alert
type (`BATTERY CAPACITY` / `TEMPERATURE`) and fires headless notifications via `AlertNotifier`
(critical channel = sound+vibration). Pure logic in `model/Alerts.kt` (SOC bands; a threshold of
N% fires **at** N%, `<=`) and `model/TempAlerts.kt` (cold→hot zone ladder: caution/warning/
critical/cutoff, **critical fires before the BMS cutoff**). The unified `stageAlert()` shows the
**worst** of the two. Capacity/temperature settings live in `Settings › Alerts` and
`Settings › Temperature`; the stage's worst pack drives the overlay + temperature `AlertNotifier`
dedup.

**Capacity alerts are fleet-wide, not stage-only.** Because only one base occupies the stage at a
time, a low pack that isn't on the stage used to be invisible — a pack could drain to damage
unseen. So the engine's decision step (`engineDecision()`, run by `MonitorEngine.reevaluate()` on
**every** BLE frame and reachability change — it used to run only on stage-pack events, which
silenced every notification whenever the stage was dark, BLE-14) evaluates **every alert-driving
pack** (the freshness `decisionView()`: this session's LIVE or STALE readings, never the restored
seed or a silent pack) against the ladder and fires a **per-pack** headless notification, deduped
**per address** (`AlertNotifier` keys `lastByAddr`/`idByAddr` by address, ids from
`NOTIF_CAP_BASE`; per-pack charge-hold latch). The pure `reconcileFleetNotifications()`
(`model/Alerts.kt`) does the fan-out dedup: notify the fresh crossings, cancel
recovered/charging/vanished packs. A second low pack is never masked by the one on stage.
(Temperature notifications stay stage-worst-driven.) Only packs that are actually showing a
notification are ever cancelled (BLE-28).

**Low pack seizes the stage (safety override).** `resolveStage()` (`model/Fleet.kt`) has a
pre-emptive branch — before the manual-pin check — that stages the base of the **lowest
alert-driving pack at/below the seize threshold** (the engine resolves on `decisionView()`), over
the active chair AND a manual pin (daily-driver breaks ties). The seize threshold is
`seizeThresholdFor()` (`model/StageControl.kt`, shared by the ViewModel and the headless restore) =
the **highest enabled capacity threshold** (default ladder top = 30%) when both `alertsOn` and the
new `seizeLowToStage` setting are on (else null). Charging doesn't block the seize (the flash is
still charge-suppressed). On recovery the branch yields and normal pin/auto resolution takes back
over. `Settings › Alerts` gains a **"Pull low packs to stage"** toggle (default ON) gating only the
visual seize — fleet-wide notifications fire regardless. Only roster members can seize, and a stage
target left with no members falls back to the daily driver (then the first populated base) instead
of a blank stage (UI-29). The rule itself — threshold, charging-doesn't-block, lowest-wins,
seize-before-pin — is unchanged.

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
(`Content-Encoding: gzip`; the server verifies the JWT's signature and claims first, and only then
reads and decompresses the body to check its `bh` hash) and **batched**:
the uploader flushes only at ≥`MIN_BATCH` (20) queued rows or a `FLUSH_AGE_MS` (15 s)-old head,
then drains to empty (`shouldFlush()` in `TelemetryReporter.kt`) — never per-sample POSTs, which
paid ~470 B of JWT/header overhead each and defeated gzip on tiny bodies (~9× bandwidth combined
with the GPS dedup below). Every sample is still uploaded; worst-case live-feed latency is ~15 s.

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

**Accuracy check-in — DONE 2026-07-15** (set 2026-07-01; next check ~2026-08-01, items below).
All constants verified against the accumulated cloud dataset (2.0M samples; the new fortnight's
109k discharge rows ≈ 20× the original calibration basis) — **no constant changes needed**:
- **Charge-time ETA** — CC bulk coulomb math is essentially exact (median checkpoint error
  ~0.3 min vs the 1.4-min bar); all visible full-ETA error was the 58-min tail seed. The tail
  EMA **is folding and persisting** (first real folds confirmed byte-for-byte vs DataStore:
  2012-A learned 56.8 from a 54.1-min tail at alpha 0.3). Found + **FIXED same day**: **tail
  re-fold bug** — a single-sample SOC≥98 Charging blip 30 min–6 h after a real cutoff passed
  the wall-clock dedup and `learnTail`'s 6-h lookback re-folded the SAME run (2012-B's 47.3-min
  tail folded twice → 52.554; effective alpha 0.51, benign). Fix: **run-identity dedup** — the
  qualifying run's last-sample ts is its identity; folds happen only for strictly-newer runs,
  with the learned run end persisted per pack (`charge_tail_run_end_by_address`, written
  atomically with the tail minutes), covering blips AND engine restarts. The 30-min in-memory
  wall-clock guard remains as a cheap pre-filter, no longer load-bearing. Pure learn pass =
  `learnTailFold()` (ChargeTailLearn.kt). Upgrade note: packs learned pre-fix have no run-end
  entry, so at most ONE more re-fold can occur before the stamp exists. `SEED_TAIL_MIN=58`/
  alpha 0.3 stay.
- **Gauge calibration** — `POWER_RING_FULL_W=300` KEEP (fortnight p98 = 301.5 W; ring pegs 2.0%
  of discharge samples, by design; new spike record 1065 W). `REGEN_EPS=0.1`/`REGEN_WINDOW_MS=30s`
  KEEP with a structural guarantee discovered: the BMS firmware has a **~1.04 A reporting
  deadband** (idle reads exactly 0.000 A; smallest nonzero current in 1.9M rows = 1.044 A), so
  any EPS in (0, 1.04) is equivalent — zero false positives/misses across 838 regen runs
  (longest 23.2 s < the 30 s window).
- **Range bands** — recompute reproduces `device_range_config` to float precision (whPerDay/
  activeW healthy; background packs correctly seed-fallback via the zero-signal guard).
  whPerMile still on seed 51–85 ONLY because GPS reached local Room at db v4 (2026-07-11) and
  the learner needs `MIN_LEARN_DAYS=3` — expected off-seed at the first learn pass after
  2026-07-15 (≈44–57 initially, widening toward the cloud-derived ~41–74). Seed's 15–25 mi is
  conservative vs learned 17–31 mi — safe direction. All gates validated on real data (0.5-mi
  outing gate rejected a 178 Wh/mi poison day; discharge gate excluded vehicle legs).

**Accuracy check-in — DONE 2026-08-04. Full write-up: `docs/calibration-checkin-2026-08-04.md`.**
Basis 4.81M samples / **300,644 discharge rows** over 38 days (2.5× the July basis). All three
open items from 2026-07-15 are closed; every constant held except one reseed and one bug:
- **Battery flow KEEP.** p98 = 292.5 W so `POWER_RING_FULL_W=300` still pegs by design (1.84% of
  discharge samples); new spike record 1115.7 W. The **1.044 A deadband is reconfirmed** and is
  sharper than recorded — current is quantized in ~63.4 mA steps above it and *nothing* falls in
  (0, 1.0), so any `REGEN_EPS` in (0, 1.044) is identical. 1664 regen runs now (was 838), longest
  still 23.2 s, **zero** ≥ 30 s.
- **whPerMile LEFT SEED — and the 31-mi upper readout is NOT real, it is ~27 mi.** Both daily
  drivers learned ~47–79 Wh/mi (13 outing days each; recompute reproduces `device_range_config`).
  `milesHi` divides by the band's **low** end, which came in at **47**, not the predicted 41 —
  so full charge now reads **~16–27 mi** vs the seed's 15–25. The high end (75–80) is confirmed,
  so ~16–17 mi at the bottom is real. **Seeds are well calibrated; left alone.**
- **`learned_days` was NOT cosmetic — FIXED.** All six background packs reported 12–13 learned
  days with pure seed bands (`learnedDays = whPerDay.size` counted *coverage*-qualifying days,
  ignoring `bandOf`'s seed fallback). Consumer: `efficiency.ts:88` reads `learnedDays === 0` to
  label the chip **"vs seed est."**, so seed bands were presented as real comparisons. Now derived
  from whether the band actually learned; two tests that had locked in the old values updated.
- **Charge ETA: EMA converged (open item closed), but its target is high-variance.** Run-identity
  dedup held; predicted-at-SOC-70 grew 267 → 296 min, i.e. the learned tail moved 58 → ~79.
  Bulk is excellent (SOC 70→98 = **217.1 min, SD 7.8**) and 98→99 is a rock-steady **7.7–8.1 min**.
  The tail is **real charging, not idle time**: flat ~7.95 A right past the BMS's rated
  `full_charge_ah` (absorbing 7–9 Ah, `remaining_ah` reaching 111–113) then a genuine ~6-min
  taper to cutoff — but it runs **40.6–129.6 min** (mean 70.6, SD 25.8). Hence ETA MAE ~22 min,
  biased directionally (+33…+39 min on shallow top-ups, −28…−52 on deep overnight charges).
  **`SEED_TAIL_MIN` reseeded 58 → 70** (fresh installs only). Safe: the `remaining_ah` overshoot
  is Charging-only, clamps to exactly 105.00 at Idle, and `estimatePackRange` returns null while
  charging, so it never reaches the readout.
- **GPS KEEP across the board.** The learner's 50 m gate appears to reject half of all fixes, but
  that is **history, not a problem**: 46.5% of all fixes ever read *exactly* 100.0 m (the fused
  **network** accuracy) from before the 2026-07-13 high-accuracy GNSS switch; since then **97–98%
  pass**, which is *why* whPerMile finally learned. `GPS_ACCURACY_MAX_M=250` gates 0.13%
  post-switch (p99 = 124.8 m, worst accepted 247 m); `COAST_MAX_MS=30 s` fires on 0.04% of gaps
  (~3× the p99 gap); the 120 s marker staleness is exceeded by 33 of 337k gaps;
  `CHAIR_MAX_SPEED_MPS=4.5` sits well above the p99 of 3.04 m/s (0.09% exceed).

- **Server/WebUI audit: NO code change needed**, every calibration constant on that side verified —
  `GPS_ACCURACY_MAX_M=250`, `DISCHARGE_EPS=0.1` (share.py + share dock), `STATUS_STALE_MS`/
  `LIVE_STALE_MS=120s`, `PREDICT_MAX_MS=10s` (p99 fix gap 9.5 s sits just under it),
  `COAST_MAX_MS=30s`, the `cleanTrack` speed bounds, and **`PAIR_FLOW_FULL_W=600`**, which is
  independently right rather than just 2×300: base-total p98 = 569.9 W, pegging 1.68% of base
  ticks — the same design point the per-pack ring hits. `DEGRADED_SOH=80` is untestable here (every
  pack reports SOH 100 or **105** — two read above 100, matching `full_charge_ah` 105 on a
  nominally 100 Ah pack; a "105% health" readout is odd but harmless).
- **⚠ The learner and the WebUI disagree about what "discharging" means — the WebUI is right.**
  `efficiency.ts` `outingWh` gates on the **current sign** (`current_a < -DISCHARGE_EPS`);
  `RangeLearn.accumulate` gates on the BMS **`state` field**. On this hardware those differ:
  **40,069 rows carry ≥1.05 A of real current while `state` reads `Idle`**, and **85% of them sit
  directly adjacent to a `Discharging` row** — the state field lags the current field at the
  boundaries of discharge runs. (`current_a` is signed in the cloud, negative = discharge, so
  `share.py`'s rung-1 `current_a < -DISCHARGE_EPS` is correct as well.) Over the live 14-day
  window the learner therefore **misses 342.1 Wh against 4,438.3 counted — understating discharge
  by 7.16%**. Recomputed under the web's gate, whPerMile goes 47.3–77.9 → **50.0–85.4** (2012-B)
  and 48.4–80.0 → **51.0–84.7** (2012-A), i.e. **the shipped range readout is ~6–10% optimistic**
  (~16–27 mi where the corrected basis gives ~15–26) — the unsafe direction for a wheelchair — and
  the EfficiencyCard compares a correct cost against an understated band, so normal outings read
  "above band". Note the corrected band lands almost exactly on the **original seed 51–85**.
  **FIXED + DEPLOYED same day:** `RangeRow` and the Room projection now carry `currentA` and **no
  longer carry `state` at all**, so the defect is unrepresentable rather than merely corrected —
  one `RangeRow.isDischarging` (`(currentA ?: 0f) < -DISCHARGE_EPS`) is the single definition, used
  by both `accumulate` and `bucketedFixes` (the fixes flag undercounted *miles* too, which is why
  the band moves less than the 7.16% energy figure alone). On-device after install the learn pass
  pushed **49.9–84.0** (2012-B) and **50.5–82.1** (2012-A) Wh/mi, matching prediction — the range
  readout at full charge went **~16–27 mi → ~15–26 mi**, in the safe direction.

- **A depth-aware charge tail was considered and DECIDED AGAINST.** It is the one change that
  would materially improve ETA (tail length correlates r = **+0.67** with session length, −0.48
  with start SOC; a scalar EMA captures none of it, so a 2-parameter fit would roughly halve the
  ~22 min error). Rejected because **the error lands where nobody reads it**: every charge in the
  dataset is overnight — all 14 sessions start **19:54–00:43** and **24 of 28 finish 00:00–07:59**.
  The only sessions finishing while the user is awake (22:57, 23:22) are the two shallow top-ups,
  and those are exactly the ones the ETA **over**-predicts (+33…+39 min, i.e. ready sooner than
  promised — the harmless direction); the under-predicting deep sessions all finish 01:18–06:24.
  Against that: a scalar EMA would become a per-pack regression needing more observations to
  converge, new persistence, and a fresh interaction with the run-identity dedup that took a bug
  to get right — real new surface on the charge path, for ~15 min residual (R² ≈ 0.45) instead of
  22. The cheap 80% is already banked in the 58 → 70 reseed. **Revisit trigger: daytime charging**
  — a pre-outing top-up is the one case where 30–50 min matters, and a *deep* daytime charge is
  where the error runs unsafe. That is a usage change, not a code change, so just re-run the
  finish-hour histogram at each check-in and reopen if sessions start finishing 08:00–23:59 from a
  low start SOC.

**Next check — re-dated 2026-10-16 (slipped from ~2026-09); tracked with due dates in
`docs/checkins/NEXT.md`.**
That file is the single list of open check-in items — AR power-cost keep/revert, motion-column wire
cost, GNSS-off duty re-quantification under the silence-as-stillness gate, whPerMile re-verify on the
current-sign basis, and the charge finish-hour histogram. Add new items there, not here; write each
pass up as a dated `docs/calibration-checkin-YYYY-MM-DD.md` and fold constant changes into this file.

Garbage-frame guard: `parseTelemetry` realigns to the `01 93 55 AA` status header (BLE
notification fragments can prepend stale bytes, which previously decoded as soc=0/37.6 V and
tripped a false critical alarm) and rejects implausible readings (SOC 0–100, voltage 4–70 V).
The main stage shows a pack that isn't reachable as **DISCONNECTED** (dimmed ring, no %, no
alert) rather than a misleading 0%.

**Freshness model (UI-16 / UI-23 / BLE-18, 2026-10-02).** One definition of "is this reading
live?", in `model/Freshness.kt`. The engine stamps `BatteryStatus.lastFrameAtElapsedMs`
(`elapsedRealtime`, never persisted) and `frameIntervalMs` on every **parsed** frame; `freshness()`
= LIVE while age ≤ poll interval + 10 s (two missed polls plus the round-trip of the poll that
answers), STALE beyond that, and DISCONNECTED when unreachable or silent > 60 s (engine-side:
≤ 60 s, plus up to one 10 s tick when no BLE event arrives). The restored seed has no stamp, so
it is never LIVE: it renders as DISCONNECTED (no %) until the first frame. **Alert rule:** this session's
readings drive alerts, the seize and stage activity (LIVE, or STALE with a known age — a STALE
reading mostly *holds* an alert its own LIVE frame raised, though one ≤ 60 s old can raise a first
notification or the seize once the 30 s charge latch expires, which is accepted as erring toward
alerting); the seed and DISCONNECTED packs never do (`decisionView()`). STALE stage packs render
muted with "UPDATED Ns AGO" (the ring keeps the accent hue at 45 % alpha and the number and age use
`text2` — the dark theme's `segEmpty` is lighter than `text3`, so a grey fill read inverted; the
stage header's activity reads `decisionView()`, and REGEN shows only for a LIVE pack); All
Batteries and Detail dim every non-LIVE row with "Updated … / Connecting… / Out of range · seen … /
Last seen … / Last known" ("Disconnected" first for a pack the user disconnected), monitoring off
included; the All Batteries "Reachable" filter uses the same LIVE-and-not-disconnected test as the
row. A frame that never decodes — a parser throw included — is a poll miss (drops at 5 like a
timeout, keeps the normal cadence, and is still logged as `decode_fail`), and a link that drops
between polls fails the next poll at once (`linkLost`; a non-success status write = link error,
except BUSY = miss). The ViewModel's UI clock (`nowElapsedMs`) advances on every engine emission;
its 1 s freshness ticker (foreground only) publishes only when a rendered label, the charge hold or
an ack set changes.

**No demo data (removed).** The old offline "demo" telemetry (`demoFor()`, `UiState.demo`,
`tickDemo` drift loop) was removed — we're past needing it. When monitoring is off, the app keeps
the **last-known fleet marked unreachable** and renders every pack as **DISCONNECTED** (dimmed,
no % on the stage; All Batteries/Detail dimmed with "Last seen …") instead of synthetic data; the
top-bar status reads **MONITORING OFF**.

**Disconnect semantics.** Per-battery disconnect and **Disconnect all** both drop the BLE link
the same way — they add the pack(s) to the `disabled` set and call `engine.setDisabled(...)`,
which cancels the staged worker so its GATT closes; the engine keeps running. Each disconnected
row shows a **reconnect (link) icon**, and the All Batteries header toggles **Disconnect all ⇄
Reconnect all**. "Disconnect all" is therefore distinct from *stopping monitoring* (the
foreground-service Stop), which tears the engine down entirely. A frame or connect already in
flight when a pack is disconnected never touches the engine's fleet state (`onPoll`/`onReachable`
check the disabled set inside their state update), so the pack can't flash back to connected — or
drive an alert or the seize — while its worker tears down. (A frame that lands in that instant may
still be logged and uploaded as an ordinary sample.)

**Low-battery alerts (configurable ladder + critical tier).** `ALERT_THRESHOLDS`
(BatteryViewModel.kt) is the full selectable 5% ladder **95%→5%**; `DEFAULT_THRESHOLDS`
(`30/25/20/15/10/5`) is what a fresh install enables (high marks default OFF). The **critical**
tier (red / faster pulse) is user-configurable via `criticalThreshold` (`UiState` +
`DEFAULT_CRITICAL_THRESHOLD = 15`), replacing the old hardcoded `≤15`. The Alerts settings page
shows the full ladder (chips ≤ critical tint red), a single-select **Critical level** picker, and a
**Reset to defaults** button. `stageAlert()` resolves the in-app flash from the lowest pack on
stage; charging suppresses the flash; acknowledged thresholds silence until SOC drops to the next
level, and **re-arm** (UI-15): a rung stays acknowledged while the stage's lowest alert-driving
pack reads below rung + `ACK_REARM_MARGIN_PCT` (2 %), and acks clear when the stage target changes
or the stage starts charging. The margin exists because BMS SOC is an integer percent: a 1 % regen
uptick at a rung boundary would otherwise clear the ack, and the next downtick would re-flash the
rung mid-drive. A pack inside its regen window does not count as "charging" for that re-arm either:
production data showed 86 of 531 regen samples (16 %) carry BMS `state=Charging`, so regen braking
would re-flash an acknowledged rung about 30 s later, mid-drive. The flash-suppression latch is
unchanged. ACKNOWLEDGE acks the alert the overlay displayed, never one re-derived at tap time
(UI-25), and an ACKNOWLEDGE that lands after the stage changed is ignored (`StageAlert.target`).
The **highest enabled** ladder rung doubles as the stage-seize threshold (see "Low pack seizes the
stage" above), and headless notifications are **fleet-wide/per-pack** (see "Capacity alerts are
fleet-wide") — the ladder is the single source of truth for all three.

**GPS telemetry (cloud upload).** When cloud sync is enrolled, the app captures the phone's
location (`location/LocationSource.kt`, fused provider) and attaches `lat`/`lon`/`gps_accuracy_m`
to uploaded telemetry samples — **only when the fix is new for that pack** (deduped per address on
`GpsFix.timeMs` = `Location.getTime()`, never coordinate equality — stationary fixes jitter;
`isNewFixForPack()` in `MonitorEngine.kt`), coordinates rounded to 6 dp (~0.11 m) on the upload
path only (`CloudJson.roundCoord`). Local Room logging keeps full precision on every sample. GPS
rides the same offline-durable outbox, so offline driving is buffered and synced on reconnect. `gpsEnabled` defaults **on with cloud sync**
(reducer `p.gpsEnabled ?: p.cloudEnabled`), toggled in Cloud sync settings ("Send GPS location").
The engine's effective GPS-active = `monitoring && gpsEnabled && enrolled && cloudEnabled`.
Needs `ACCESS_FINE/COARSE_LOCATION` + `ACCESS_BACKGROUND_LOCATION` + a `location` FGS type
(`MonitoringService` ORs `FOREGROUND_SERVICE_TYPE_LOCATION` only when GPS-active AND location is
granted — required to avoid an Android-14 SecurityException). Background-location was the
explicit design choice for pocket/driving capture.

**Main-stage upload indicator.** The Home top bar shows a small glanceable cloud-upload status
next to the stage label, only when cloud sync is enrolled: `↑ X.X KB/s` (green) while uploading,
`↑ synced` when caught up, `↑ N queued` (amber) when buffering/offline. The rate comes from
`cloud/UploadRate.kt` (a pure, unit-tested 5 s rolling window of gzipped wire bytes →
smoothed KB/s) surfaced through the reporter's `onStatus` into `UiState.cloudUploadKbps`.

**Discharge estimate (miles + time remaining).** The stage shows a base-level learned
high/low line — `~37–50 mi · ~9–13h use · ~5–9 days` — under the rings whenever the staged
packs are connected and not charging (charging shows the recharge ETA instead). Pure math in
`model/RangeEstimate.kt` (estimate + live tilt + formatting) and `model/RangeLearn.kt`
(per-day p20/p80 bands: Wh/day, active W, and **outing-day Wh/mile** — a day's TOTAL discharge
divided by its chair miles, counted only on days with ≥0.5 mi of driving, so indoor/idle
overhead lands in the per-mile cost and the estimate converges on lived range, not
smooth-cruise physics. Chair miles are **windowed**: one fix per 30-s bucket, displacement
between buckets at 0.4–4.5 m/s — NEVER consecutive-sample distances, because the fused
provider refreshes fixes every ~5–10 s (measured 2026-08-04: p50 5.5 s, p99 9.5 s; it was
~10–30 s in the pre-2026-07-13 balanced-power era, when this was written) while telemetry
samples at 1.5 s — still the faster of the two, so raw pairs read
freeze-then-teleport (a real 4.8 mi outing measured 0.02 mi pairwise). **Vehicle rides are
excluded by the discharge gate**: in the van/train the chair draws nothing (user-confirmed),
so GPS movement without discharge teaches no miles — no speed-context heuristics. The chair
tops out ~9 mph, hence the 4.5 m/s ceiling. Bucketed fixes additionally pass **out-and-back
spike rejection** (impossible speed in AND out at the context bound — 4.5 m/s discharging /
45 m/s otherwise, 60 m/s absurd cap — while the neighbors agree; the dropped fix's window is
bridged so real distance survives). The TS sibling `web/src/v2/model/cleanTrack.ts` adds
idle-excursion collapse (an out-and-back that leaves a spot and returns to it while no pack
discharges is elevator/indoor multipath — those fixes CLAIM 2–32 m accuracy, so no accuracy
gate can catch them; the chair can't move itself without discharging, and vehicle rides end
elsewhere) + stay-point snapping + smoothing for the v2 Journey map. Known residual: fixes
biased ~40–90 m sideways while the chair is genuinely driving indoors next to the building
(claimed-good accuracy, chair-plausible speed) are indistinguishable at render time — fixing
those would need map-matching/geofencing (backtest: the Jul-12 raw track's
9.78 mi cleaned to 5.38 — see docs/range-backtest-2026-07.md Addendum 4). Location capture is
**PRIORITY_HIGH_ACCURACY GNSS** (5 s) in all normal use — the phone rides the chair on USB power;
it drops to balanced power (20 s) ONLY inside the low-battery latch window (below 5% until 15%),
see the screen-policy section)
with a line-for-line TS twin in `web/src/range.ts` (no tilt on web — documented divergence).
The engine learns every 6 h from the local 14-day Room history (GPS now stored locally —
samples db v4), refreshes today's tilt inputs every 5 min, computes the per-pack estimate once
per poll onto `BatteryStatus.range` (same single-writer pattern as `etaFullMin`), persists
params in SettingsStore, and pushes them over the one-way config channel (optional `ranges`
list on the `POST /api/v1/config` body) into `device_range_config`, mirrored read-only by
`GET /web/range-config` for the WebUI's MainStage strip. Seeds until ≥3 qualifying days:
130 Wh/day ±40%, 75 W ±30%, and whPerMile 51–85 (a conservative 15–25 practical miles at full
charge — user-facing miles are OUTING semantics, "how far will it actually take me", not
continuous-cruise physics). Wh/day and active-W were validated against the real fleet history
in docs/range-backtest-2026-07.md (daily drivers learn real bands ~81–213 Wh/day; background
packs stay seeded until they get stage time, by design). Wh/mile is learnable only from
outdoor GPS outing days — indoor driving is invisible to GPS at wheelchair speeds.
**Wh/mile left seed on 2026-08-04** (see the check-in above): both daily drivers learned
~47–79 Wh/mi off 13 outing days, so full charge reads **~16–27 mi** against the seed's 15–25 —
the seeds proved well calibrated and were left alone. Note `milesHi` divides by the band's **low**
end, so it is the low end that sets the headline upper mileage.

## Development

The commands below drive the **legacy** `bmsmon.py` CLI (see Architecture) — fine for a quick look at
a pack, not ground truth. The one-at-a-time rule in "BLE Connection Notes" still applies.

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
mirror) + `GET /web/alert-config` (the read-only capacity-seize mirror) + `GET /web/track` (GPS
history, viewer-gated and span-bounded — see "Server access control & request hardening") +
`GET /web/map-config` (the runtime CARTO basemap key — see "CARTO basemap key"), plus
admin-gated
`GET /web/samples`, `GET /web/devices`, `POST /web/enroll-codes`,
`DELETE /web/devices/{id}` — which **revokes** the device — and `POST /web/devices/{id}/restore`, which undoes a revoke without re-enrolling). The temperature
config lives in the `device_temp_config` table
(per device+profile, latest-wins); the WebUI mirror (`web/src/temp.ts` + `TempGauge`/`TempBanner`/
`TempOverlay`/`BatteryProfilePanel`) re-evaluates the same zone ladder read-only. The **capacity
seize threshold** rides the same `POST /api/v1/config` body (optional flat `seize_soc`/`alerts_on`
fields on `TempConfigBody`) into the device-level `device_alert_config` table (latest-wins); the
WebUI reads it via `GET /web/alert-config` and seizes its main stage for the lowest fresh pack
`≤ (alerts_on ? seize_soc ?? 30 : ∅)` — over pins and auto-selection, with a **"LOW"** marker,
no audible alarm: v2 (`/`) via `useV2Configs` + `web/src/v2/model/stageBase.ts`
`selectStageBase` (Command stage, Journey and the Fleet Health hero; LOW chip in
`CommandStage.tsx`; no seize until the config's first answer, and the 30 default only if
that fetch fails, so a guessed threshold can't leave the stage parked on a seized base),
v1 (`/v1/`) via `web/src/stage.ts` `selectStageItems` (`MainStage.tsx`).
Only the seize is synced — v2's capacity ALERT ladder is still the fixed 30…5 / critical 15.
Schema is idempotent SQL in `server/app/db/schema.sql`, run on pool creation — so **schema
changes apply automatically on container start; there is no separate migration step**.
Columns are added with `SELECT pg_temp.add_column_if_missing('<table>', '<column>', '<type>')`
(dropped with `pg_temp.drop_column_if_present`), **never a bare `ALTER TABLE … ADD/DROP
COLUMN`**: the helpers read the catalog first, so a boot against an up-to-date database takes
no table lock and never waits for the nightly `pg_dump` (`tests/test_schema_apply.py` holds
ROW EXCLUSIVE on every table and allows zero retries). A change that a concurrent schema run
made first counts as done. Any other `ALTER TABLE` there sits behind its own catalog check.
An index on `samples` is only **declared** there —
`CREATE INDEX samples_<name> ON ONLY samples …` inside an `IF to_regclass(…) IS NULL` guard —
and the hourly maintenance pass builds it on every existing partition with
`CREATE INDEX CONCURRENTLY` and attaches it (`app/db/online_index.py`); partitions created
later get it automatically. A guard test refuses a plain `CREATE INDEX` in `schema.sql`.

Device admin: `DELETE /web/devices/{id}` only **revokes** (the row and its key stay), and a
revoked phone's re-enroll is refused with 403 "device revoked; restore it first".
`POST /web/devices/{id}/restore` (admin-gated) clears the revoke flag and keeps the key, so the
same phone uploads again without re-enrolling; it answers 200 `{"restored": "<id>"}`, 404
`{"detail": "device not found"}`, and the standard 422 for a non-UUID id. When the WebUI is not
reachable, use the CLI:

```bash
docker exec -it bmsmon-api python -m tools.device_admin list
docker exec -it bmsmon-api python -m tools.device_admin restore <id>
docker exec -it bmsmon-api python -m tools.device_admin delete <id> [--yes]
```

`list` shows no key material. `delete` asks for confirmation (or takes `--yes`) and removes only
the device row: samples keep their `device_id`, and an enrollment code the device claimed stays
used but is detached from it.

The `samples` table mirrors the phone's telemetry (soc, current, power, voltage, temp, cells,
cycles, regen, link_event, …) plus **GPS** columns `lat`/`lon` (`double precision`) and
`gps_accuracy_m` (`real`), all nullable. The WebUI shows a header **"GPS" pill** (green when
recent samples carry coordinates) and a browser-local **light/dark toggle** (sun/moon in the
header; default dark; persisted in `localStorage["bmsmon-theme"]`; light mode is a
`:root[data-theme="light"]` CSS-variable override in `web/src/theme.css`). The page declares
`<meta name="darkreader-lock">` so the Dark Reader extension never alters it in either mode.

`samples` also carries **motion state** (deployed 2026-08-08 19:55): `motion_activity` (text —
the phone's Activity Recognition reading, e.g. `STILL`/`IN_VEHICLE`/`UNKNOWN`),
`motion_confidence` (smallint, 0-100, that reading's confidence), and `motion_still` (boolean —
the motion **gate's own verdict**, `MotionGate.still`), all nullable.

A fourth column, `motion_at_ms` (bigint — `MotionReading.atMs`, the reading's own wall-clock
timestamp, same device clock as `ts_ms`), was added 2026-08-08 and deployed 2026-08-09 (spec:
`docs/superpowers/specs/2026-08-08-motion-staleness-telemetry-design.md`): `ts_ms - motion_at_ms` is
the reading's age — under the original design this separated "gate failed open on staleness" from
"debounce not yet met" (both retired by the 2026-08-09 rework described above) — and distinct
`motion_at_ms` values still identify individual readings, making the Play Services delivery-gap
distribution measurable from prod SQL, which remains the column's enduring purpose. It is null
exactly when `motion_activity`/`motion_confidence` are; garbage values clamp to null server-side
(`_clip_motion_at`, mirroring `_clip_conf`).

**The verdict is stored as its own column rather than derived from the reading** because the gate
carries state across readings that a single row can't reconstruct. Under the current (2026-08-09
silence-as-stillness) design, that state is a hold clock: one confident STILL reading starts a run
(`stillSinceMs`), and the verdict closes once the run has stood **10 min** (`STILL_CLOSE_HOLD_MS`)
uncontradicted — including by silence, since the verdict is re-derived from the clock on every fold,
not only on fresh readings. (The design this column originally shipped against — superseded the very
next day — instead closed on `STILL_DEBOUNCE_N = 3` consecutive confident-STILL readings and
reopened on a single confident non-STILL.) An uncertain reading (confidence `<
STILL_CONFIDENCE_MIN`) still **holds the previous verdict** instead of resetting it — so a single
row's activity+confidence still can't reconstruct what the gate was doing without replaying its
whole fold history. Recording only the reading would show `STILL@100` without revealing whether the
10-min hold had already elapsed; recording only the verdict would show the gate closed without
revealing which reading, and when, started the run. This is the whole justification for three
columns instead of two. `MotionReading` (`model/BatterySaver.kt`)
now carries the activity name alongside `still`/`confidence`/`atMs` — previously mapped only for a
log line and discarded — via `MotionSource.activityName()` (widened from private to `internal` so
the upload path reuses the same mapping instead of duplicating it). The wire class is `SampleJson`
(`android/.../cloud/CloudJson.kt`); `SampleIn` (`server/app/models.py`) is the server-side Pydantic
twin — both exist, both carry the three fields, and neither should be conflated with the other.
All three columns are nullable end to end for backward compatibility — an older client omits all
three and still ingests. On this branch's client, though, `motion_still` is always populated: it
comes from `motionGate.still`, a non-null `Boolean`, so a fail-open verdict uploads as `false`
rather than being omitted. Only `motion_activity`/`motion_confidence` go null together, and only
when there is no motion reading at all (AR unavailable, permission denied, or motion sensing not
currently running). So `motion_activity IS NULL AND motion_still = false` reads as "gate failed
open with no signal" — not as "no motion data was sent". The per-sample row dict is assembled
generically in
`server/app/db/queries.py`'s `sample_row()` off a `_COLS` list — the same mechanism
`gps_accuracy_m`/`eta_full_min` use — **not** in `routers/api_device.py`.

Motion is a **device-level** fact (one phone, one Activity Recognition reading) written onto
**every pack's** row, so with 8 packs each reading is stored 8 times. Accepted because the
duplicated values are identical within an upload batch and the whole batch is already gzipped,
which should collapse them — **the actual wire-cost delta has not been measured yet** (a later
task); if it turns out material, the documented fallback is to populate the fields only on the
staged base's rows.

This closes an observability gap that cost three days to diagnose by hand: the phone is reachable
over ADB only on home Wi-Fi, and `logcat`'s default 256 KiB ring buffer had to be hand-raised to
32 MiB to survive one outing — the evidence rotated away twice before that. With these columns the
same diagnosis is a SQL query:

```sql
-- What does AR actually report while stationary?
SELECT motion_activity, motion_confidence, count(*)
FROM samples WHERE ts_ms > … GROUP BY 1,2 ORDER BY 3 DESC;

-- Does the gate's verdict track the readings, or is the hold timing wrong?
SELECT motion_activity, motion_confidence, motion_still, count(*)
FROM samples WHERE ts_ms > … GROUP BY 1,2,3;

-- Was GPS on during transit, and what did the phone think it was doing?
SELECT to_timestamp(ts_ms/1000), lat IS NOT NULL AS has_gps, motion_activity, motion_still, current_a
FROM samples WHERE ts_ms BETWEEN … AND … ORDER BY ts_ms;
```

That last query is precisely the question that cost three days.

**WebUI v1 layout (`web/src/App.tsx`, served at `/v1/` since 2026-08-04):** the dashboard is the **main stage** + **All Batteries**; a
header **⚙ toggle** opens a **Settings** view (battery-profile panel + device admin — kept off the
main page). Header also has a **°C/°F** unit toggle (`localStorage["bmsmon-temp-unit"]`, default the
phone's synced unit). **Pin to stage:** a pin icon on every card/stage pack; pinned packs (by
address, `localStorage["bmsmon-pins"]`) become the main stage, else it auto-selects the active base
(the header shows `PINNED · AUTO OFF` vs `AUTO`). **Disconnected packs keep their last-known
telemetry, muted** (dimmed ring/gauge + muted stats + `DISCONNECTED · updated <ago>`), and stop
driving live temperature alerts — like the Android All-Batteries view. A dev-only preview harness
(`web/preview.html` → `src/preview.tsx`) renders the components with mock data for Playwright
screenshots; it is **not** in the production bundle (it is not a rollup input, so `vite build`
emits only the two shells).

### WebUI v2 — the default UI (all six views live)

**v2 is what `/` serves (since 2026-08-04); v1 is kept, demoted to `/v1/`.** Both are React
bundles from one Vite build with two rollup inputs — `web/index.html` (v2, entry `src/v2/`) →
`dist/index.html`, and `web/v1/index.html` (entry `src/`) → `dist/v1/index.html` — sharing a
single `web/dist/assets` chunk pool. Each input key names its entry chunk (`assets/v2-*.js`,
`assets/v1-*.js`). **Neither bundle has client-side routing and both build with base `/`**, so a
shell does not care which directory it sits in; that is what made the swap a pure build change.
The server still just mounts `dist` at `/` with `html=True`. Two things carry the flip:
`server/app/main.py` keeps narrow `/v2` + `/v2/` → `/` **307** redirects (temporary, so it stays
reversible; deliberately not a `/v2/{path}` catch-all, which would swallow `/v2/assets/*`), and
`/`'s existing `Cache-Control: no-cache` is what let the UI at `/` change without a cache-bust
step. All v2 `localStorage`/`sessionStorage` keys are origin-scoped, so pins, theme, TRAIL and
unit prefs survived the move with no migration. Phases 1–4 are all **merged to `main` and
deployed to prod** (`bmsmon.covert.life`), landing all six planned views: **Command** (fleet rail, stage, range/recharge, aside, bound to
`/web/fleet` + `/ws`, plus a per-cell-voltage pipeline android `cells[]` → server `samples.cellN_v`
→ fleet snapshot `cells` → web), **Fleet Health** (tiles + 8-pack board + 24h sparkline off
`GET /web/history`; **offline packs show their LAST-KNOWN SOC/capacity, muted + "last seen
<ago>"** — same rule as v1 and the Command rail and stage, because a pack out of BLE range still
holds its charge and a blank "—" hid it. **Every summary tile counts offline packs on their last-known
reading too** — `PACKS READY`, `NEED RECHARGE` and `FLEET CAPACITY` each footnote how much of
their figure is stale ("incl. N offline · last known", from `readyStale`/`needRechargeStale`/
`staleCounted`), so being away from the spares no longer reads as 0 ready / 0% capacity. The
hero card's heading follows the base's real status instead of a hardcoded "In use now"),
**Alerts** (capacity ladder + temp zones + cell imbalance; in-memory acknowledge that
re-arms when the condition worsens and is forgotten when it clears — see below), **Settings** (units/map trail/theme segmented toggles, editing the App's one settings instance so a change applies everywhere at once; Distance MI/KM converts every v2 distance and Wh-per-distance readout while the models stay in miles; v2's °C/°F defaults to °F and does not follow the
phone's synced unit the way v1 does, review WEB-35), **History** (per-base
capacity-fade/cell-imbalance/temperature trend charts with A/B breakdown, a charge-session log, and
editable per-base notes, backed by `GET /web/trends`, `GET /web/charge-sessions`, and the
**WebUI's first write path** `GET`/`POST /web/notes`), and **Journey** (GPS trip visualization —
date nav, a Leaflet base map with CARTO dark/light tiles, a discharge-colored trail
green/amber/red by |power|, dashed transit legs, hotspot markers, an **efficiency card**, and an
energy-over-distance chart, backed by the new read-only `GET /web/track` endpoint — 15 s-bucketed
per-pack GPS + discharge series; both this and the share feed gate out coarse fixes with
`gps_accuracy_m > 250` server-side (`GPS_ACCURACY_MAX_M`, queries.py) — a post-reboot fused
network fix (363–636 m accuracy, 433 m off) drew a phantom jump on 2026-07-14; real fixes ran
≤200 m even in a vehicle pre-GNSS, and raw samples keep every fix). `/web/track` also returns
each bucket's mean accuracy radius as `acc`. **Track cleaning is now four passes, the last one
a Kalman smoother:** `rejectSpikes → collapseIdleExcursions → snapStays → smoothKalman`
(`web/src/v2/model/cleanTrack.ts` wires them; the smoother lives in
`model/kalmanTrack.ts`) — an accuracy-weighted constant-velocity filter (measurement variance
from `acc`, floored/defaulted) with **innovation gating** (a fix inconsistent with the motion
model is rejected, prediction stands instead) and **`COAST_MAX_MS`** (30 s) gap breaks: a hole
longer than that restarts the filter and marks the point after it `inferred`, which the map
draws **dashed/faded** instead of a confident line. Backtested against real production data —
2026-07-29 (train ride, 70–145 km/h, coarse cell-fallback fixes, pinned to the closed
00:00–13:00 UTC window since the day was still in progress at measurement time) and 2026-07-12
(a full, normal continuous-GPS outing day) — see `docs/range-backtest-2026-07.md` Addendum 5:
miles drop slightly after the Kalman pass on both (jitter shrinking, not movement being
fabricated), the train day correctly produces over a dozen inferred segments (bridging dead
zones up to 47 minutes long) and the outing day produces none during actual driving (its lone
inferred segment is an overnight stationary gap, zero distance). Journey goes **live** when the selected window includes now:
the trail re-polls every 15 s (`useTrack` refreshMs), a pulsing ♿ marker tracks the chair off
the live WS fleet feed (hidden when the freshest fix is >120 s old) — between fixes it
**dead-reckons** along the last known heading/speed, capped at `PREDICT_MAX_MS` (10 s) or
`PREDICT_MAX_M` (200 m), whichever binds first (`model/live.ts` `predictPosition`) — the
camera follows until
the user pans (dragstart breaks follow unconditionally — Leaflet dragstart is user-only; a
persistent crosshair **re-center** button re-locks — on both platforms, replacing the old
⌖ FOLLOW), and map fit is keyed to the selected window so live refreshes never yank pan/zoom
(`web/src/v2/model/live.ts` + `cleanTrack` still applies). **Journey date default is
session-scoped (2026-07-20):** a fresh page session (new tab / first open) always lands on
**today, live**; the date nav is backed by `sessionStorage` (key `bmsmon-v2-journey`), so a
**refresh keeps whatever day you're on** but a new session resets to today. The **TRAIL** toggle
stays cross-session in `localStorage` (same key, `{showTrail}` — the two live in separate storage
namespaces; the local codec also migrates `showTrail` out of the legacy combined blob). Backed by
the `kind: "local" | "session"` param added to `useLocalStorage`/`readStored`. **Mobile Journey is map-first**
(2026-07-13, from the user's design handoff): a non-scrolling 100dvh column — toolbar, map
filling everything, and a compact line dock (`JourneyDock.tsx` + tested `model/dock.ts`):
trip line (DIST·ACT·TRN·PEAK), pair CAP bar (weaker pack, alert-band colors), and a
single-direction FLOW bar (|Σ power| vs 600 W; amber→red = OUT, green = REGEN/CHG). The
efficiency card, energy chart, and the side dock are desktop-only; mobile gets on-map overlays
instead (TRAIL·metric chip, LIVE·GPS badge, legend). `settings.mapMetricPref` now actually colors the
trail (`socColor`, alert bands) so the chip is honest. The TRAIL chip is a **persisted toggle**
(both platforms; off hides trail/transit/hotspots/legend, keeping the live marker). When no
fix is fresher than 120 s the chair marker goes **grey/un-pulsed at the LAST KNOWN position**
with its age in the badge (amber) instead of vanishing; Command mirrors this with "last seen"
ages on offline bases. **Efficiency card (2026-07-16, desktop, replaced the playback scrubber):**
the old play/scrub bar only animated a dot along the visible track, so it's gone. In its slot
`EfficiencyCard.tsx` (pure `model/efficiency.ts` + tests) shows the viewed outing's real
cost-per-mile — `outingWh` (∫|power| over discharging buckets, Δt capped at 60 s; a bucket that
fewer of the base's packs reported in is scaled up to the whole base, since the series pair carries
one current) ÷ `summary.activeMiles` — against the learned `whPerMile` band (summed across the base's packs to
match the merged track's base-total power basis). Live "today" window → **"CAN YOU MAKE IT?"**
with `~X mi left at today's rate · ~Y at your usual` (the base's usable energy ÷ each rate: pack
count × the weaker pack's remaining Ah × 12.8 V, live or last known, because the pair is in series).
**Today's rate shows only while every pack of the base is live AND the merged track covers every
pack** (`trackCoversEveryPack`): with a pack out of range its share of the cost was missing from
the track while the energy still counted it, so the figure read up to 2× high. Otherwise the card
shows `~Y mi left at your usual rate` and says why today's rate is missing. Every projected figure
is floored, never rounded up;
past day → **"THIS OUTING"** with DRIVEN/USED/DRAINED. Gated below `MIN_OUTING_MI` (0.5),
projection suppressed while charging, and the band chip reads **"vs seed est."** (never a false
comparison) until a pack has `learnedDays > 0`. Point inspection survives as **hover** on the
energy chart (`onHover` → nearest point → map cursor marker + SOC/DRAW/DIST/STATE readouts); no
slider, no auto-play. Mobile Journey (dock-based) is unchanged. Spec:
`docs/superpowers/specs/2026-07-16-journey-efficiency-card-design.md`.
**CRITICAL mobile lesson (2026-07-13): the v2 shell — `web/index.html` since the 2026-08-04
flip, `web/v2/index.html` before it — carries the
viewport meta tag** — without it, phones rendered a virtual 980 px scaled to ~40% (microscopic
text) AND `innerWidth` defeated the <820 px auto-mobile detection; `web/v1/index.html` deliberately
has NO viewport meta (it has no mobile layout, so scaled-desktop is the better fallback). Each tag
belongs to its own shell file, so the flip moved them correctly by construction — but this is the
one line to re-check after any future shell shuffle.
Bottom tabs are 68 px + home-indicator safe-area (`BAR_H` exported; App pads by it); Command
stacks pack cards vertically on mobile. The roadmap's deferred Phase-4 Command bits are wired:
**DRIVEN TODAY** (cleaned today-track driven miles, 60 s refresh) and a tile-free SVG
**route sketch** (`RouteSketch.tsx`) in the aside; cell-voltage bars fade below a 10 mV spread. `leaflet` is now a `web/` dependency, and since the 2026-07-14
perf sprint `JourneyView` (and leaflet+its CSS with it) is a `React.lazy` chunk loaded only when
Journey opens — v1 and Command-only v2 sessions carry zero leaflet; `qrcode` is likewise a dynamic
import inside the enroll-QR mint paths. Live Journey re-polls are **incremental** (`useTrack`
fetches `[lastBucketT, now)` and splices via the tested `appendTrack`; unchanged responses keep the
previous array identity so the map effect no-ops; 10-min full-refetch safety net), the trail
renders as one polyline per same-color run (`interactive: false`), and every REST poller is
visibility-gated (`web/src/visiblePoll.ts` — hidden tabs skip ticks, refocus catches up). **Device admin** (enroll-code QR, device list,
revoke, confirmed first and naming the device and what stops, plus **Restore** for a revoked device
(`POST /web/devices/{id}/restore`) — a port of v1's `AdminDevices`, reusing the admin `/web/devices` + `/web/enroll-codes`
endpoints) lives as a **Devices section inside Settings** (`DevicesPanel.tsx`), not a separate nav
entry — so there is no longer any "SOON" item. Roadmap/spec:
`docs/superpowers/specs/2026-07-12-webui-v2-roadmap.md`.

**CARTO basemap key (2026-10-03).** CARTO answers keyless raster tile requests with "API KEY
REQUIRED" placeholders, so the Journey map and the guest share page need a key. The repo and
the GHCR image are public, so the key is **runtime-only**: it comes from `BMSMON_CARTO_KEY` in
the bmsmon stack's environment on the NAS (optionally a stack-local `map.env`), and is never
committed and never baked into the image or the Vite builds (no `VITE_*` env; the build cannot
know it). `Settings.carto_key` (`server/app/config.py`) strips whitespace and quotes and
accepts only 8–128 of `[A-Za-z0-9_-]`; anything else becomes null with one startup warning
that names the variable and the reason, never the value. An unset variable logs one INFO line
at startup (`BMSMON_CARTO_KEY not set: …`), so a forgotten `map.env` shows in the log. Browsers fetch it from two
`Cache-Control: no-store` endpoints, both returning `{"carto_key": "<key>" | null}`:
`GET /web/map-config` (viewer-gated; v2 Journey reads it through one fetch chain per page
session, `useCartoKey`) and `GET /share/{token}/map-config` (the feed's token gate — see
Location sharing). Both clients go through `fetchCartoKey` (`web/src/v2/basemap.ts`): each
request times out at 8 s; a 200 (key or null) and 401/403/404/410 are final; a network error,
timeout, 408, 429 or 5xx is retried after 5 s, 15 s and 45 s, then given up as null. The share
page aborts a pending retry on unmount and as soon as its poller reports the link ended or
expired. `JourneyMap`'s `tileKey` prop is `undefined` while the key is pending: the tile layer
waits for it up to `KEY_WAIT_MS` (3 s) so a fresh page doesn't flash keyless placeholders, then
`tileUrl` appends `?key=` and a key that lands later re-points the existing layer with `setUrl`.
No key, or a failed fetch, means placeholder tiles and nothing else. The key is necessarily
visible to a browser that loads the tiles; the point is keeping it out of public source and
artefacts and handing it only to viewers and live share links.

**v2 stage selection, alert acks, the guest page's connection state and the Journey RANGE clamp
(2026-10-02 review, WEB-12/13/17/20/25, XC-1/XC-2).** One pure `selectStageBase()`
(`web/src/v2/model/stageBase.ts`), owned once by the v2 App through `useStageBase()` and shared
by Command, Journey and the Fleet Health hero, replaces the old hardcoded `DAILY_DRIVER_BASE`
staging. Ladder, first match wins: **(1) seize** — a fresh pack ≤ the synced seize threshold
(`/web/alert-config`) stages its base with a LOW chip, overriding the pin as on Android and v1;
**(2) pin** — a fleet-rail tap, persisted in `localStorage["bmsmon-v2-stage-pin"]`, outranks the
base in use for 30 min (android `PIN_HOLD_MS`; PINNED chip + AUTO to release; a pin dated 30 min
or more ahead of the clock has expired too); **(3) in use** — deepest draw wins; **(4) hold** —
the newest discharge seen this session within 15 min; **(5) parked** — stay on the previous base
while it still reports; **(6) default** — the daily driver if it reports, else the reporting
base with the newest sample, so a cold load away from home opens on the chair rather than on the
daily driver sitting offline at home; with nothing reporting (phone offline), the base with the
newest sample at all, stale included, and the daily driver only when there is no sample to go
on. Android's "charging base takes over" rung is not ported (same as `share.py`). v2 alert acks
map `id → {rank, address}`: ids are per condition (`cap:<addr>`, `temp:<addr>:<side>`,
`cell:<addr>`); an ack holds while the rank stays at or below the acked rank, re-arms on a worse
one (next capacity rung, worse temperature zone, cell warning → critical), and is pruned when
the condition clears on a pack that is still reporting — a pack that has merely gone stale keeps
its ack, so a BLE flap can't re-nag. The **guest page** polls single-flight
(`web/share/src/poll.ts` `createFeedPoller`), aborts each request at 8 s (`FETCH_TIMEOUT_MS`),
and derives everything it claims from `guestView()`: fix staleness is measured against the
server's clock advanced by client time since the last success, so a fix ages while polls fail;
**CONNECTION LOST · last update 18 s ago** (red dot, grey marker, dimmed dock) after 2 failed
polls, or 1 failure with no success for 15 s — never on a tab returning from the background with
no failure. Guest-page ages read in seconds under a minute, then minutes (`ageLabel`; the shared
`relAgo`'s "just now" would contradict CONNECTION LOST), and only the state word is the badge's
`role="status"` live region, so a screen reader announces the change, not the ticking age. With
the server's 48 h `last` lookback, after midnight the page shows yesterday's position as
**LAST KNOWN · 10h ago** instead of "Waiting for GPS…"; "Point me there" distinguishes
"No recent location from the chair" from "Locating you…" and labels a non-live target
"last known · Xm ago". Journey's RANGE mode is clamped client-side (`clampTrackWindow`,
`web/src/v2/model/journey.ts`) to `/web/track`'s span cap of 31 d + 1 h, keeping the most recent
days and saying **LAST 31 DAYS SHOWN** instead of rendering a rejected request as a blank map.

**v2 base view-model, freshness and connection state (2026-10 review, Tier 2: WEB-14/15/16/18/19/21/22/26/27/29/31).**
`baseView()` (`web/src/v2/model/baseView.ts`, pure, tested) decides what the Command stage, the
range card and the Journey efficiency card show about the staged base:
- Every pack stays on the stage. A pack that is not live keeps its last-known reading, muted, with
  "DISCONNECTED · last seen".
- Flow, charging, time-to-full and temperature come from live packs only.
- The weaker pack bounds range and runtime over live **and** last-known readings, and the UI names
  a last-known pack inside the bound ("incl. A · last seen 3m ago"). A pack that dropped off BLE is
  still in the series circuit.
- A pack at 0 Ah bounds the base to zero, and a regen burst (the phone's `regen` flag) is not
  charging.
- The range card says which empty case it is in: charging, base offline, or no capacity reading.
- The Journey projection's usable energy is pack count × the weaker pack's remaining Ah × 12.8 V.
- The stage thermal banner follows the phone-synced temperature zones on both sides (`tempZone`,
  rank ≥ 1, live packs).
- The Journey dock's CAP line follows the same bound (`model/dock.ts`, shown as "≤NN%" when the
  bound is a last-known reading). With no live pack, CAP is muted (never an alert colour) and FLOW
  reads "—", not "0 W IDLE".
- The stage's time to full is flagged "partial" while a pack is not live (its own time to full is
  not in the figure), and its flow label (DRAW NOW / CHARGE IN / REGEN IN / FLOW) comes from the
  same live packs as the watts.
- Range, runtime and mileage figures are floored everywhere (`floorFixed`/`floorBand` in
  `web/src/range.ts`), never rounded up: 37.6 mi reads "37", 0.6 h of use "0.6h". The phone's
  formatter still rounds to nearest.
- The recharge plan (`model/recharge.ts`) anchors each "ready by" to its sample's `ts_ms`. It keeps
  a charging spare that went out of range, muted, as "last seen … · est. full …", never implying it
  is full.
- The fleet rail shows an offline pack's last-known SOC, muted.

**Pack freshness** is one function, `web/src/freshness.ts` (`STALE_MS` 90 s, shared by v1 and v2).
A BLE link event never refreshes a pack's freshness, and a "Disconnected" newer than the newest
telemetry reads stale at once. `store.ts` keeps `link_event`/`link_ts_ms` only while they are not
older than the telemetry (a tie at the same ms goes to "gone"). Staleness is judged in the same
render as the fleet (`web/src/useStaleAddrs.ts`), so no stale pack paints live, not even for a frame.

**The live link** (`web/src/ws.ts` + pure `web/src/liveLink.ts`):
- It reports LIVE from its first snapshot, not on socket open.
- It reconnects with exponential backoff and jitter (1.5 s, doubling to 60 s; the count resets only once a socket has stayed healthy for 30 s, so a snapshot-then-die server keeps backing off).
- A 4401/4403 close, or three silent sockets followed by a `GET /web/alert-config` probe that does
  not follow redirects, becomes a session verdict. A probe that answers after a socket has
  delivered a snapshot is dropped: the link recovered while it was in flight.
- v2 then shows a banner under the TopBar on every layout: "Session expired" / "Not authorized"
  with Reload, "The server is having trouble" (a marked 503), or "Can't reach the server".

The header's SYNCED pill means at least one pack has fresh telemetry.

Settings has one owner, the App. `web/src/v2/singleOwner.test.ts` pins `useV2Settings`,
`useFleetData`, `useV2Configs` and `useStageBase` to `App.tsx`, as a call and as an import under
any name.

Notes hold the server's 4000-character cap (`maxLength` and a counter), and a 422 from notes, share
links or API keys reads as a validation message, never "retry" or "check the connection"
(`web/src/v2/model/formErrors.ts`).

Admin revokes (device, share link, API key) ask first, naming the target and what stops
(`web/src/adminConfirm.ts`). A revoked device can be restored from Settings › Devices, and the share
dialog no longer closes on a backdrop tap once its shown-once link exists.

### Server access control & request hardening (2026-10 review, T1.6)

Enforced in the app itself (not delegated to Traefik/Authentik) and pinned by tests:

- **`X-Bmsmon-Api: 1` on every app-generated response** (`app/middleware.py`
  `ApiMarkerMiddleware`, the outermost user middleware, plus the `marked_internal_error`
  500 handler): 2xx, every 4xx (incl. 404 for unknown routes, 413, 422, 429), redirects,
  static files and the WebSocket 101. Traefik's own 404/502 while `bmsmon-api` is down
  never carries it. That is how the phone tells "the app rejected this batch" from "the
  app isn't there" (DATA-14). Keep `ApiMarkerMiddleware` the last `add_middleware` call.
  A marked `503` (+ `Retry-After: 30`) means the database is unavailable (transient, retry);
  a marked `500` means a crash (`is_db_unavailable` in `app/middleware.py` decides which).
- **One request-body cap for every route** (`BodySizeLimitMiddleware`,
  `BMSMON_MAX_BODY_BYTES`, default 1 MiB). A `Content-Length` over the cap gets a 413
  before the route, and any rate limiter, runs. Bodies without or lying about
  `Content-Length` are counted as they stream and cut off at the cap. This is what bounds
  `/api/v1/enroll` (SEC-17). `_read_body`'s own caps on ingest/config stay as a second
  line, plus the gunzip ceiling.
- **Fail-closed group gate on `/web/*` and `/ws`** (`auth/authentik.py` `authorize`).
  Being authenticated is not enough. Viewers need `BMSMON_VIEWER_GROUP` (default
  `Covert.Life - Full App Access - User Group`). Admin routes (`/web/samples`, devices,
  enroll codes, shares, API keys) need `BMSMON_ADMIN_GROUP` (default
  `Covert.Life - bmsmon - Admin Group`, owner only), and an admin also counts as a viewer.
  Matching is exact and case-sensitive against `X-Authentik-Groups`, which is split on
  `|` only (a group name may contain a comma). An empty setting matches nobody, and
  surrounding whitespace in either setting is ignored. A non-member gets 403 (`/ws`
  accepts, then closes with **4403**); no identity stays 401/4401. Non-member denials are
  logged at WARNING, once per user per 5 min. Dev-trust's synthetic user is in both
  groups unless `BMSMON_DEV_GROUPS` overrides it. Tests build headers from
  `server/tests/identities.py`; never hardcode a group spelling in a test.
- **`/web/track` span ≤ 31 days + 1 h** (`TRACK_MAX_SPAN_MS`). A wider span gets a 400,
  not a clamp, so a map is never silently truncated; out-of-range timestamps get a 422.
  The legitimate callers send one local day (≤ 25 h), the live day's incremental tail,
  or Journey RANGE mode (whole local days; a calendar month across a DST change is
  31 d + 1 h). The Journey RANGE picker should cap itself at 31 days.
- **`/ws` checks Origin before `accept()`** (`routers/ws.py` `origin_allowed`). The Origin
  must be in `BMSMON_WS_ALLOWED_ORIGINS` (default `https://bmsmon.covert.life`), or the
  socket is closed with 4403. Uvicorn turns a pre-accept close into an HTTP 403 handshake
  rejection. This blocks cross-site WebSocket hijacking from any same-site
  `*.covert.life` page (SEC-19). Dev-trust mode also allows `http://localhost:5173`,
  `http://127.0.0.1:5173` and a missing Origin (the Vite proxy and the smoke test), plus
  a same-origin page whose Origin host matches the request's `Host` (the built bundle
  served by the local API, e.g. `http://localhost:8000`).
- **Device requests authenticate before their body is touched** (SEC-18). `_authenticate`
  runs first: bearer, device row, ES256 signature + required claims + exp/iat/aud
  (`device_jwt.verify_token`), replay probe, per-device budget. Only then is the body read
  and gunzipped. `verify_body` binds it (`bh`) and **only then burns the jti**, so a
  request whose body fails (bad gzip, 413, hash mismatch) leaves its token reusable.
  Unauthenticated callers never cause a body read or an inflate. The budget
  (`ingest_limiter`, `INGEST_MAX_PER_MIN` = 3000/min, shared by ingest + config) is keyed
  per **device after the signature verifies**, never per IP, so nobody without the device
  key can spend it. A serial outbox drain tops out around 10–16 POST/s, well under it.
  The `sub` is canonicalised once (`str(uuid.UUID(...))`), so every spelling of a device's
  UUID shares one budget. Also fixed: a non-string or `{braced}`/`urn:uuid:` `sub`
  (pre-auth), or a non-string `aud`, used to escape as a 500.
- **Samples are validated one at a time** (C3/SRV-18). Only a malformed envelope is a
  422: not JSON, not an object, no `samples` list, or a bad `batch_seq`. Each sample goes
  through `SampleIn` on its own (`models.validate_each`), and invalid ones are dropped and
  logged at WARNING: the first error's location + type only, never values (samples carry
  GPS), at most once per device per kind per minute (schema, ts-window and address drops
  each have their own window). The response is
  `{"accepted", "dropped", "last_seq"}`, where `dropped` = schema + ts-window + address
  drops. `/api/v1/config` does the same for `ranges[]` rows and answers
  `{"ok", "dropped"}`. A value that validates but that its column cannot store (int4 or
  float4 overflow, NaN/±inf, a NUL byte) never fails the batch either: optional fields
  store NULL and NUL bytes are stripped (`models.py` `Int4OrNone`/`RealOrNone`/
  `TextOrNone`; positional `cells` are nulled in place), while a required config field
  with such a value drops its range row or 422s the config envelope. The `app` logger hierarchy logs at INFO with time/level/name
  (`main.configure_logging`), so rollup, scrub and drop lines are visible in `docker logs`.
- **Garbage input is a 422, never a 500.** A NUL byte in a browser or enroll body string
  (`models.NulFreeStr`), a query `address` outside the ingest rule (`^[!-~]{1,32}$`), a
  non-UUID id in `DELETE /web/devices/{id}` or `/web/api-keys/{id}`, and a `/web/trends`
  or `/web/samples` epoch outside `[0, 2100-01-01)`. Device telemetry still *strips* NUL
  instead, because the phone deletes a 4xx'd batch.
- **Device-auth failures say why** (DATA-20). Every 401 from `/api/v1/ingest` and
  `/api/v1/config` carries `X-Bmsmon-Auth-Reason`: `missing_bearer`, `bad_token`,
  `unknown_or_revoked_device`, `bad_signature`, `clock_skew`, `replay` or
  `body_mismatch` (the body's `detail` text is unchanged). A known device's failure is
  logged at WARNING with the signed clock skew (`server − iat`) and the app's User-Agent,
  once per device per reason per minute; unknown device ids at most once a minute in all.
  Every app-generated `/api/` response carries `X-Bmsmon-Server-Time-Ms`, so the phone can
  show "server clock off by N s" itself. The app's User-Agent is kept on
  `devices.user_agent` and shown on the admin device list (DATA-28).
- **Clock tolerance is ±10 minutes** (`IAT_LEEWAY_S` = `EXP_LEEWAY_S` = 600 in
  `auth/device_jwt.py`): a token passes while `iat ≤ server_now + 600 s` and
  `server_now ≤ exp + 600 s`. The 585 s NAS clock step of 2026-09-16 now passes, and
  `/api/v1/health/detail` pages on any verified skew over 120 s. Replay stays bounded:
  the jti cache remembers a token until `exp + 600 s`, and the body-hash binding means a
  replay can only resubmit the identical body.
- **A database outage is answered inside the app.** A marked 503 + `Retry-After: 30`, one
  WARNING per exception class per 10 s, no traceback (`middleware.db_error_handler`);
  `/ws` closes with 1011. Real crashes stay a marked 500 with uvicorn's traceback. Every
  `/share/*` error carries `Cache-Control: no-store` and `Referrer-Policy: no-referrer`.
- **Successful timer polls stay out of the access log** (`main.QuietAccessLogFilter`):
  2xx/3xx lines for `/api/v1/health`, `/api/v1/health/detail`, `/api/v1/groups` and
  `/share/<token>/feed`. Their failures, and every other route, are still logged. An
  invalid ingest/config envelope (422) is logged with its first error's location and type,
  never its contents.

### Read-only API keys (`/api/v1/groups`, desktop widgets)

A third identity path, added 2026-08-25 for the KDE desktop widgets. The other two cannot
serve a headless client: device JWTs authorise the **write** path (`/ingest`), and Authentik
authorises **browsers** (`/web/*`, `/ws`). A widget has no browser session to carry SSO, and
must not be able to write anything.

It lives under `/api/`, which Traefik already routes **past** Authentik — so this needed no
reverse-proxy change, which is the reason for the prefix choice.

```
GET /api/v1/groups          X-API-Key: <key>
```

Returns every battery pair with its packs' latest telemetry:

```jsonc
{"now_ms":…, "stale_ms":90000, "power_full_w":300,
 "groups":[{"id":"2012","label":"Base 2012","status":"in-use","connected":true,
            "last_seen_ms":…,
            "packs":[{"letter":"A","alias":"2012 · A","address":"C8:…","ts_ms":…,
                      "age_ms":1200,"connected":true,"soc":72.0,"voltage_v":13.2,
                      "current_a":-4.1,"power_w":-54.0,"temp_c":22,"soh":100, …}]}]}
```

Security model (`server/app/auth/api_key.py`), mirroring the share-token one:

- **256-bit keys**, `secrets.token_urlsafe(32)`; only the **sha256** is stored, so a dump of
  `api_keys` yields nothing usable. A lost key is re-minted, never recovered.
- Lookup is **by hash**, so no secret is ever compared in Python — there is no string
  comparison to leak timing.
- Unknown and revoked keys return the **same bare 401**, byte-identical, so a prober cannot
  tell "was valid, now revoked" from "never existed" (asserted in `test_api_widget.py`).
- A rate limiter (`app.state.apikey_limiter`, 240/min — four widgets share one desktop
  IP) throttles guessing *before* the database is touched. It is per client IP when the
  request carries the proxy secret: then the first `X-Forwarded-For` hop is trusted
  (`test_proxy_limiter_keys.py`). The Traefik label that injects the secret on the
  `bmsmon-api` router enables this; the enroll limiter works the same way.
- **Read-only by construction.** No route here mutates, and a key presented to `/ingest`
  still gets 401 — there is a test for exactly that.
- **No GPS, no device identity.** `lat`/`lon`/`gps_accuracy_m`/`device_id` are in the fleet
  row and are deliberately withheld; `PACK_FIELDS` is an allow-list, so exposing a new field
  is a deliberate act rather than an accident. `test_gps_is_never_exposed` greps the raw
  response body for those names.
- `Cache-Control: no-store` on every response.

**Staleness and the status ladder are evaluated server-side** (`STALE_MS = 90_000`,
`_status()`), mirroring `web/src/freshness.ts` and `fleet.ts` `baseStatus` on the 90 s age rule. Keep the
three in step if the threshold ever moves. They are not identical: the web also reads a newer
"Disconnected" link event as stale at once, and the server cannot see link events
(`fleet_snapshot` excludes link rows), so the widget can show a pack as live for up to 90 s
after the web already treats it as stale. Disconnected packs keep their last-known telemetry and are
flagged `connected: false` instead of being dropped — the widget dims rather than blanks,
matching the WebUI and Android All-Batteries behaviour.

**Managing keys.** WebUI → Settings → **API keys** (admin-gated, `ApiKeysPanel.tsx`): create,
see last-used, revoke. The plaintext is shown exactly once, at mint. There is also a CLI for
when the WebUI is not reachable:

```bash
docker exec -it bmsmon-api python -m tools.api_key_admin mint "desktop widgets"
docker exec -it bmsmon-api python -m tools.api_key_admin list
docker exec -it bmsmon-api python -m tools.api_key_admin revoke <id>
```

Devices have a sibling CLI, `python -m tools.device_admin list | restore <id> | delete <id>
[--yes]` (run the same way); `delete` removes only the device row and keeps its telemetry.
Every restore is logged at INFO (the WebUI's with the admin's username), and so is every CLI
delete.

### Operational health and background maintenance (2026-10 review, T2.2/T2.4/T2.5)

**`GET /api/v1/health/detail`** (`X-API-Key`, the read-only key type the widgets use) is the
telemetry deadman. `/api/v1/health` stays a bare DB ping, because Docker's healthcheck and
autoheal use it, and a phone that stops uploading must never get the API restarted. Detail
answers **200 when every check passes and 503 otherwise**, `Cache-Control: no-store`:

```jsonc
{"ok": true, "failing": [],            // any of: ingest, rollup, partition, clock
 "server_time_ms": …, "last_ingest_ms": …, "last_ingest_age_s": 12,
 "auth_fail_5m": 0, "last_auth_fail": null, "last_ok_skew_s": 1,
 "rollup_lag_s": 1500, "next_month_partition": true,
 "online_indexes": {"samples_charging_idx": true},
 "limits": {"ingest_age_s": 1800, "rollup_lag_s": 10800, "clock_skew_s": 120}}
```

| Check | Fails when |
|---|---|
| `ingest` | No non-revoked device has uploaded for `BMSMON_DEADMAN_INGEST_S` (default **1800 s**), measured from `devices.last_seen_at`, which only a live upload that stores at least one valid sample refreshes (at most once a minute). A batch whose every sample is dropped, a history import (`batch_seq` < 0) and a config push never refresh it. No upload ever also fails. |
| `rollup` | The 30-min rollup's high-water mark is more than 3 h behind, or it has never run. |
| `partition` | Next month's `samples` partition is missing. |
| `clock` | The last verified token's `server − iat` skew exceeds ±120 s. |

`auth_fail_5m` counts known-device auth failures (at most 100 per device and 5000 in all,
oldest dropped first). It is informational; a stuck phone
trips `ingest` anyway. `?max_ingest_age_s=N` can only **tighten** the ingest limit; it
exists to prove the alarm path end to end.

Uptime Kuma monitor **`bmsmon-telemetry-deadman`** polls
`http://bmsmon-api:8000/api/v1/health/detail` every 60 s with an API key named
"uptime-kuma deadman", wired to the ntfy notification. To rotate that key:
1. mint a new one (`tools.api_key_admin mint`);
2. update the monitor's `headers` (Kuma stopped; see `~/qnap-nas-docker/CLAUDE.md`);
3. revoke the old one.

**Maintenance loop** (`app/maintenance.py`). It runs 60 s after boot, then hourly. Each step
is isolated, so a failing step is logged and the rest still run:
- **Partitions:** every month from now to 31 days ahead, so always next month (the boot
  also covers the 31 days behind). Each CREATE gives up after 0.5 s
  (`PARTITION_LOCK_TIMEOUT`) instead of queueing ingest behind a long reader; that month is
  logged, the later months are still tried, and the next pass retries it. A partition
  created inside an ingest transaction (only for an odd old timestamp) has the same timeout
  and becomes a marked 503.
- **Registry backfill:** a registry row for any `samples` address without one.
- **Online index builds:** see the schema mechanism above. Each index partition is logged
  as it attaches (`maintenance: built and attached index …`). An ATTACH that gives up on its
  0.5 s lock wait is logged and the pass moves on; the next pass attaches the built child.
  A same-named child is rebuilt only when Postgres reports the definitions do not match.

The pool has `command_timeout = 30 s`, so a timed-out request statement is a marked 503.
Background statements pass `MAINTENANCE_TIMEOUT_S` (1 h).

**Fleet-wide windows are read per pack.** The share trail (`queries._GPS_TRACK_ALL`), the
history raw parts (`_HISTORY_RAW`/`_HISTORY_ROUTED`) and the rollup re-roll (`rollup._UPSERT`)
are driven from `batteries` with `CROSS JOIN LATERAL`. `samples`' only index leads with
`address` and PG16 has no skip scan, so a ts-only window otherwise seq-scans the whole
month-to-date partition: a 15 h share trail at month end read 81 k buffers in 437 ms,
against 5 k buffers and 35 ms per pack. Two things are load-bearing:
- `_GPS_TRACK_ALL`'s **`OFFSET 0` fence**. Its inner select has no GROUP BY, and without
  the fence the planner de-correlates back into the full scan.
- **The registry invariant.** Ingest registers every address it writes (`upsert_battery`,
  in the batch's transaction), `insert_samples` does it for every other writer, and the
  maintenance pass backfills.

Windows are expressed on `ts` alone (SRV-28: `ts` is `ts_ms` at ms precision). Two test
files pin all of this:
- `tests/test_query_plans.py` seeds a synthetic month and asserts buffer bounds, a
  ts-only guard and millisecond boundaries;
- `tests/test_rollup.py`'s routed == raw equivalence.

**Charge sessions** read the partial covering index `samples_charging_idx`
(`(address, ts) INCLUDE (ts_ms, soc, temp_c) WHERE current_a > 0.1 AND link_event IS NULL`).
On the measured month that was 673 ms / 90 k buffers before and 160 ms / 4.1 k after
(index-only). `days` is capped at 90; the History view asks for 30.

**GPS retention (SEC-12).** `BMSMON_GPS_RETENTION_DAYS` (default 1095; ≤ 0 disables). The
daily scrub NULLs `lat`/`lon`/`gps_accuracy_m` and never deletes rows. It walks only
`[watermark, cutoff)` (`gps_scrub_state`), per pack, one day per statement, so the daily cost
is one day and a retention change becomes many small transactions. A sample that arrives
*already* past retention (a late outbox drain, a history import) is stored and broadcast
without coordinates.

**Share caches are single-flight** (`TtlCache.get_or_compute`): guests that miss the trail,
last-fix or discharge cache together share one query, and a guest that disconnects
mid-query does not cancel it for the others.

### Location sharing (public /share/ zone)

Time-limited public share links let a named guest follow the chair live:
`https://bmsmon.covert.life/share/<token>` (token = `secrets.token_urlsafe(24)`; only
sha256 stored in `location_shares`, so the database cannot give the link back — it is shown only at
creation). Traefik has a
third zone — `PathPrefix(/share/)`, priority 100, `bmsmon-header` + `bmsmon-proxy-secret`
(no Authentik; the proxy secret is there ONLY so the rate limiter can trust XFF for
per-IP keying — `/share` endpoints never read identity headers, and Traefik blanks any
client-supplied `X-Authentik-*` headers on this zone and on `/api/`, so the secret never travels
with forged identity) — and the guest page is
a **third Vite build** (`web/share/`, `vite.config.share.ts`, `base:"/share/"`) so its
assets stay inside the public zone. Server: `app/routers/share.py` —
`GET /share/{token}` (active → guest shell; expired → friendly "ask for a new link"
page; unknown/REVOKED → identical bare 404) and `GET /share/{token}/feed` (today-only
fleet GPS via `q.gps_track_all`, fields t/lat/lon ONLY — never battery data; day window
clamped server-side in the container TZ; 410 when expired; updates
last_access/access_count; no-store + no-referrer on every response incl. errors; per-IP
`share_limiter` 150/min), and `GET /share/{token}/map-config` (the guest map's CARTO basemap
key — see "CARTO basemap key"; same token gate, limiter and headers as the feed, but not a
view, so it never touches last_access/access_count). Admin CRUD on the Authentik zone: `POST/GET /web/shares`,
`DELETE /web/shares/{id}` (require_admin — a share grants unauthenticated access, same
trust class as enroll codes; listing keeps ended shares 7 days). WebUI: Journey toolbar
↗ opens `ShareDialog` (name + 1h/1d/1w → native share sheet, else clipboard);
`Settings › Location shares` (`SharesPanel`) lists name/remaining/last-opened/×count +
Revoke (kills a live guest within one 4 s poll; the guest page stops polling once
terminally ended/expired). Guest page (`web/share/src/`): map + today's neutral-green
trail + pulsing/stale chair marker + "Following <owner>" (`BMSMON_SHARE_OWNER`, default
"Joely") + countdown, and a "Point me there" panel — geolocation distance/cardinal +
dashed guest→chair map line everywhere, compass-rotated arrow where device orientation
is available (iOS needs the permission tap). **2026-07-14 amendments:** the guest page
defaults to **light** mode with a persisted top-right sun/moon toggle
(`localStorage["bmsmon-share-theme"]`; pre-hydration script in `web/share/index.html`),
and shows a 2-line **guest dock** (CAP/FLOW, twin of the mobile Journey dock) — a
deliberate, minimal relaxation of the no-battery-data rule: the feed's `status` object
carries ONLY the active base's soc/packs/current/power/regen (never voltage, temps,
cells, cycles), aggregated by the pure `pick_guest_status()` in `share.py`
(`fleet_snapshot` rows, 120 s staleness → null; ungrouped packs never merge).
**2026-08-02 fix — the guest dock follows the chair, not the last pack to poll.** The
active base was "group of the freshest sample", which is a race: the phone polls the
staged base every ~1.5 s and rotates through the background packs, so with the spares in
BLE range (at home) a background pack held the newest row ~18–30% of the time and flipped
the dock to an idle spare — **99% CAP, dead FLOW bar** — every few polls (replayed against
75 min of prod: wrong base on 17.6% of polls, 114 flips; after the fix 0 and 0).
`resolve_active_group()` now mirrors the ladder the Android stage (`resolveStage`) and the
v2 WebUI (`selectStageBase`) use: **(1)** a base discharging right now
(`DISCHARGE_EPS` 0.1 A, deepest draw wins — the server has no daily-driver notion),
**(2)** else the base that discharged most recently within `ACTIVE_HOLD_MS` (15 min,
= android `DEFAULT_STAGE_HOLD_MIN`) via `queries.recent_discharge_by_address()` — a
bounded LATERAL walk, ~6 ms on prod, TTL-cached fleet-wide like the trail — keyed off the
BASE so a pack can drop out of BLE range without losing the hold, **(3)** else the base
the dock was already showing (`app.state.share_active_base`, process memory, single
worker), **(4)** else the freshest sample. resolveStage's "a charging base may take over"
rung is deliberately NOT ported — the spares live on chargers, so it would hand the dock
straight back to them.

**2026-08-02 — the guest feed polls every 4 s and polls INCREMENTALLY.** `FEED_POLL_MS`
went 10 s → 4 s, which was only affordable after fixing what a poll costs: the feed used
to re-send the **whole day's trail every time** (measured on prod: 3 868 points, 419 KB
raw / **56 KB gzipped per poll = 19.7 MB per guest-hour**, and 49 MB/h if the interval had
just been lowered). `GET /share/{token}/feed?since=<bucket_ms>` now returns only buckets
at/after `since`, **sliced from the already-cached list in memory** — no extra query, so
DB cost stays flat as the poll rate rises (a 15 s GPS bucket can't be fresher than the
10 s trail cache anyway, so re-querying would buy nothing). Measured end-to-end on the
built bundle: first poll 27 KB, every later poll **469 B**. Three details are load-bearing:
`since` is the client's newest bucket **START, not one past it** (that bucket is still
filling and its averages keep moving, so it is re-sent and replaced — the same seam rule
`useTrack`/`appendTrack` use); **`last` is the newest fix within a bounded 48 h lookback
(`LAST_FIX_LOOKBACK_MS`), not the newest point of the today-only trail** (since 2026-10, review
WEB-20; see the amendment below) and is never computed from the sliced trail, or a no-news poll
would blank the live chair marker; and the response carries `day_start`
so a session open across midnight replaces instead of appending. Garbage/stale `since`
degrades to a full response (`parse_since`) — a share link must never 4xx on a stray query
param. The guest page accumulates `TrackPoint[]` and splices with the existing tested
`appendTrack`, which **preserves array identity when nothing changed** — that is what
makes 4 s free: ~4 of 5 polls skip `cleanTrack` and the Leaflet trail effect entirely.
Rate limit 60 → 150/min per IP (a 4 s poll is 15/min; keeps ~10 guests behind one CGNAT
IP). NOT changed: the phone's upload batching (`FLUSH_AGE_MS` 15 s / `MIN_BATCH` 20)
delivers a batch every ~12 s median, so that — not the poll — is now the freshness floor;
lowering it would roughly triple upload requests (battery + mobile data) and walk back the
deliberate batching win. Net: guest lag ~11 s → ~8 s average, ~35 s → ~17 s worst. Spec:
`docs/superpowers/specs/2026-08-02-share-feed-incremental-polling-design.md`.

**2026-10 — the marker survives midnight (C5/WEB-20).** `last` is now `{t, lat, lon}` of
the newest accuracy-gated fix in the last 48 h (`LAST_FIX_LOOKBACK_MS`), independent of
the today-only trail. When today's trail has points, `last` is its head (no extra query).
Otherwise it comes from `q.latest_gps_fix`, a per-pack LATERAL `ORDER BY ts DESC LIMIT 1`
driven from `batteries` (never a month-to-date scan; bounded by 48 h of rows; cached
5 min, since any fix stamped today lands in the trail instead). Before this, the
overnight GNSS hold meant a carer opening the link in the morning got "Waiting for GPS…"
until the chair moved. `t` is the fix's 15 s bucket start, like the trail.

**Local dev/test:** `docker compose -f server/docker-compose.dev.yml up -d` brings up a Postgres on
`localhost:5432` (user/pw/db all `bmsmon`, matching the default `DATABASE_URL`). Run server tests
with the venv: `cd server && .venv/bin/python -m pytest` (bare `python` lacks the deps). Test-only
deps are pinned in `server/requirements-dev.lock`; CI runs the suite on Python 3.12 in UTC (the venv
may be newer) — "Image build" has the exact CI-equivalent command.

**WebUI smoke test (Playwright, local):** seed the dev DB with a synthetic 4-pack fleet
(`server/.venv/bin/python server/scripts/seed_dev.py` — TRUNCATES the database in `DATABASE_URL`,
default the local `bmsmon` dev DB, and refuses any non-local host), run the API with the built-in local identity (`BMSMON_DEV_TRUST_HEADERS=1
server/.venv/bin/uvicorn app.main:app --port 8000` — dev-trust refuses non-local DATABASE_URLs, and
without it /web/* 401s and the /ws close-after-accept loop starves the REST fallback; dev-trust is also what lets /ws accept the Vite dev server's http://localhost:5173 Origin), start
`npx vite dev --port 5173` in `web/`, then `node scripts/smoke.mjs` (from `web/`). It screenshots
all six v2 views (at `/`) + v1 (at `/v1/`) + preview.html into `web/smoke-shots/` (gitignored) and
exits non-zero on any console error or page crash. Note the smoke test drives `vite dev`, which
serves the shells straight off disk and has **no** `/v2/` → `/` redirect (that lives in FastAPI),
so it must use the real build paths. `playwright` is a web devDependency; browsers via
`npx playwright install chromium`. The server Dockerfile sets `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`
so CI image builds never pull browsers. Re-run the seeder to reset pack staleness (data >90 s old
renders as DISCONNECTED — useful for testing that state deliberately).

### Image build (GitHub Actions)

`.github/workflows/build-server.yml` is a gated pipeline (review SEC-16 — until 2026-10 it pushed
`:latest` without running a single test):

| Job | When | Does |
|---|---|---|
| `test-server` | every branch push touching the paths below; fork PRs | `check_workflows.py`, then the server suite on Python 3.12 against a `postgres:16-alpine` service |
| `test-web` | same | `npm ci`, `tsc --noEmit -p .` (v2 + v1 + guest page), `vitest run` on Node 20 |
| `build` | `main`, after both test jobs | builds the multi-stage image (Node builds `web/dist` → Python serves API + static) and pushes **only** `ghcr.io/mkeguy106/bmsmon-server:<full sha>`, labelled `org.opencontainers.image.revision=<sha>` |
| `smoke` | `main`, after build | `.github/scripts/smoke-image.sh`: boots that exact image against an empty Postgres — `schema.sql` must apply from scratch, `/api/v1/health` must answer, `/` and `/v1/` must serve their shells, `tools.api_key_admin` must be in the image |
| `promote` | `main`, after smoke | `docker buildx imagetools create` points `:latest` at the same manifest (no rebuild) and verifies the two digests match |

Paths: `server/**`, `web/**`, `.github/workflows/build-server.yml`. Runs are serialized per ref; on
`main` an in-progress run is never cancelled, but a queued run may be superseded by a newer push
(that commit then gets no `:<sha>`). Serializing keeps `:latest` monotonic. A failed run leaves
`:latest` where it was, and a failed smoke leaves an orphan `:<sha>` that nothing deploys — except
when `promote`'s digest check fails: `imagetools create` has already run by then, so `:latest` may
have moved without the proof that it is byte-identical to `:<sha>`. Then deploy by `:<sha>` (see
"Production deploy") and, once the cause is fixed, re-run `promote` only for the newest `main` sha
whose smoke passed. (Re-running an *old* main run's `promote` would move `:latest` backwards —
never use that as a rollback; see "Production deploy".)

`.github/scripts/check_workflows.py` — first step of `test-server`, locally
`python3 .github/scripts/check_workflows.py` — fails if an edit re-opens the gap: `:latest` named
outside `promote`, `build` not needing both test jobs, a publish job whose `if` is not exactly the
main-only expression, any `continue-on-error`, `promote` pointing `:latest` at anything but the
run's own `github.sha`, an action not pinned to a full SHA, a cancellable `main` run, or
`DOCKER_BUILD_RECORD_UPLOAD` back on. Watch a run with `gh run watch` or the Actions tab.

Run what CI runs, locally (dev Postgres up; containers give CI's Python 3.12 / Node 20 / UTC):

```bash
python3 .github/scripts/check_workflows.py
docker run --rm --network host -e PYTHONDONTWRITEBYTECODE=1 -v "$PWD/server:/src:ro" -w /src \
  python:3.12-slim sh -c 'pip install -q -r requirements.lock -r requirements-dev.lock && python -m pytest -q -p no:cacheprovider'
docker run --rm -e PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 -v "$PWD/web:/src:ro" node:20-alpine sh -c \
  'mkdir /w && tar -C /src --exclude=./node_modules --exclude=./dist -cf - . | tar -C /w -xf - && cd /w && npm ci --no-audit --no-fund && npx tsc --noEmit -p . && npx vitest run'
docker build -t bmsmon-server:smoke -f server/Dockerfile . && bash .github/scripts/smoke-image.sh bmsmon-server:smoke
```

The repo-root `.dockerignore` keeps a local build from copying host `web/node_modules`/`dist` over
the image's own `npm ci` output.

The `build` job sets `DOCKER_BUILD_RECORD_UPLOAD: false`. Without it `docker/build-push-action@v6`
uploads a ~63 KB `<owner>~<repo>~XXXXXX.dockerbuild` build record as an Actions artifact on
**every** run; nothing ever reads them and 54 (3.2 MB) had accumulated by 2026-08-03.

### Storage hygiene — the GHCR "untagged" footgun

`.github/workflows/prune-storage.yml` (weekly + `workflow_dispatch`, dry-run by default) retires old
images via `.github/scripts/prune-ghcr.sh`, and sweeps stray Actions artifacts older than 7 days.

**Never prune this package with `delete-only-untagged-versions: true`.** Each build pushes one tag
but creates *three* package versions, because buildx publishes an OCI **index**:

```
:latest  ->  OCI index                          <- the "tagged" version
               |- sha256:9d55f8…  amd64 image        <- listed as UNTAGGED
               `- sha256:7cb69a…  provenance att.    <- listed as UNTAGGED
```

The untagged entries *are* the image. Bulk-deleting them strips the layers out from under `:latest`,
and the NAS `docker compose pull bmsmon-api` then fails with `manifest unknown`. `prune-ghcr.sh` is
manifest-aware: it resolves each surviving index's children from the registry and only deletes an
untagged version once nothing references it, aborting rather than guessing if a manifest won't
resolve. What survives is decided by the pure `.github/scripts/prune-select.jq`, unit-tested by
`test-prune-select.sh` — which the prune job runs first, so a regression fails before anything is
deleted: the newest `KEEP` (default 10) tagged versions, whatever carries `latest`, anything younger
than `MIN_AGE_DAYS` (30 — one day in July produced 12 builds, more than `KEEP`), and the images of
the newest `PROTECT_DEPLOYS` (3) distinct commits named by well-formed `deploy/YYYYMMDDTHHMMZ` git
tags, i.e. the running image and its rollback targets however many builds have landed since (see
"Production deploy"). Any other `deploy/*` ref is ignored, so a stray tag cannot take a slot, and a
redeploy of the same commit counts once, so it cannot evict a rollback target. It refuses to prune
at all if nothing carries `latest` or if a well-formed deploy tag does not resolve to a full commit
sha, and a real (non-dry) run also refuses when the newest deploy tag matches no image version — the
registry cannot know what the NAS runs, so that tag is the only record and pruning without it would
be blind. It re-resolves every survivor and its children
(before a dry run exits, after real deletes). Ad-hoc pins: the `protect` dispatch input
(space-separated image tags).

Two operational notes: `gh api --method DELETE` must **not** be passed `--silent` — it masks the exit
status, so a loop reports success for every failed delete. And bmsmon is a **public** repo whose GHCR
package is public, so none of this draws against billable Actions/Packages quota; the 2026-08-03
audit put bmsmon at 1.8 GB-hours YTD (0.003% of the account) against milwaukee-events' 56,491
(94.6%). Prune here for tidiness, not for quota.

### Production deploy (QNAP NAS)

Production is `bmsmon.covert.life` on the QNAP NAS **`ddnas02`** (SSH: `ssh joely@ddnas02`), run from
the **`~/qnap-nas-docker`** infra repo — see **`~/qnap-nas-docker/CLAUDE.md`** for NAS conventions
(docker path, `${CONFDIR}`, the `--env-file ../.env` requirement, Traefik/Authentik). The bmsmon
stack is `~/qnap-nas-docker/bmsmon/docker-compose.yml`: `bmsmon-api`
(`image: ghcr.io/mkeguy106/bmsmon-server:${BMSMON_TAG:-latest}`) + `bmsmon-db` (Postgres, data at
`${CONFDIR}/bmsmon/database`). Traefik splits routing: `/api/` → device-JWT auth (no Authentik);
`/share/` → public, token-gated; everything else → Authentik SSO. Since 2026-10 (review T1.6/T1.7)
the proxy secret is injected on the `/api/` router too (per-client rate limits), client-supplied
`X-Authentik-*` headers are blanked on `/api/` and `/share/`, `bmsmon-db` sits only on a private
`--internal` `bmsmon-internal` network (no egress, unreachable from the proxy network), and both
services get stack-local secrets and explicit `environment:` entries instead of the NAS master
`.env` (which compose still reads for interpolation via `--env-file`).

**Backups.** A nightly `pg_dump` (07:40 UTC; first dump 230 MB of a 4.2 GB DB in 39 s) lands in
`/share/Media/1_backups/bmsmon/`, is checked daily by the NAS `check_bmsmon` monitor (alarm path
proven end to end through Uptime Kuma to a push notification), and is restore-drilled monthly into
a throwaway Postgres (first drill 99 s, 12 tables OK) — see `~/qnap-nas-docker/CLAUDE.md` for the
job, retention and drill. The phone keeps only
14 days, so these dumps are the only copy of the calibration basis, trends, charge history and the
location record.

**Deploying a new server build — by tag, never by surprise.** CI moves `:latest` only after the test
jobs and the image boot-smoke pass (see "Image build"), so `:latest` is always the newest *tested*
image, and every `main` build that runs also stays pullable as `:<full sha>`. `bmsmon-api` and
`bmsmon-db` are opted out of watchtower (`com.centurylinklabs.watchtower.enable: "false"`), so
nothing recreates them unattended; the qnap-nas-docker deploy runner only fires on
`docker-compose.yml`/`.env` changes, and `up -d` alone never re-pulls. Avoid 07:35–08:45 UTC: the
nightly `pg_dump` runs then. A routine boot no longer waits for it, but a deploy that adds a
column (an ACCESS EXCLUSIVE `ALTER`) would. A normal deploy — once the
`promote` job has succeeded — leaves `BMSMON_TAG` unset and pulls `:latest` (which also keeps the
NAS's cached `:latest` current for any later `up -d`), recreates only the API (`--no-deps`), then
records the deploy (below):

```bash
ssh joely@ddnas02 'bash -lc "cd /share/bsv/docker-compose && \
  docker compose --env-file .env -f bmsmon/docker-compose.yml pull bmsmon-api && \
  docker compose --env-file .env -f bmsmon/docker-compose.yml up -d --no-deps bmsmon-api"'
curl -fsS https://bmsmon.covert.life/api/v1/health   # expect {"status":"ok"}
# telemetry deadman (needs any read-only API key; expect "ok": true within a minute or two)
curl -fsS -H "X-API-Key: $KEY" https://bmsmon.covert.life/api/v1/health/detail
```

**Record every deploy as a git tag** — `deploy/YYYYMMDDTHHMMZ` (UTC, exactly that form; the prune
ignores any other `deploy/*` name) on the commit that went live. The tags are the deploy history
(`git tag -l 'deploy/*'`), and `prune-ghcr.sh` never deletes the images of the newest three deployed
commits, so the running image and its rollback targets survive any number of later builds. Images
built since 2026-10 carry `org.opencontainers.image.revision`, so read the sha off the running
container instead of guessing what `:latest` was at pull time. The snippet records whatever
`bmsmon-api` is running at that moment, not what you meant to deploy: run it only after the health
check passes, because if the recreate did not take it tags the previous commit again. Run it from
the repo root; it is wrapped in `bash -c` so it works from fish too, and it stops with an error if
the container has no such label (an image older than that) rather than tagging a guess:

```bash
bash -c '
set -euo pipefail
SHA=$(ssh joely@ddnas02 "bash -lc \"docker inspect bmsmon-api\"" \
  | jq -r ".[0].Config.Labels[\"org.opencontainers.image.revision\"] // empty")
if [ -z "$SHA" ]; then
  echo "error: bmsmon-api carries no org.opencontainers.image.revision label (image older than 2026-10?) - tag the deployed commit by hand" >&2
  exit 1
fi
TAG="deploy/$(date -u +%Y%m%dT%H%MZ)"
git tag "$TAG" "$SHA" && git push origin "$TAG"
'
```

(A `deploy/*` tag push triggers no workflow: `build-server` filters on branches only.)

**Rollback = pin the previous deploy's sha, persistently.** Find it from the well-formed deploy tags
only (the same rules the GHCR prune uses), taking the newest deployed commit that differs from the
one **running now** — read off the container's revision label, not off the newest tag, so the
snippet stays right after a rollback has itself been recorded as a deploy tag:

```bash
bash -c '
set -euo pipefail
git fetch --tags
cur=$(ssh joely@ddnas02 "bash -lc \"docker inspect bmsmon-api\"" \
  | jq -r ".[0].Config.Labels[\"org.opencontainers.image.revision\"] // empty")
[ -n "$cur" ] || { echo "error: bmsmon-api has no revision label" >&2; exit 1; }
prev=""
for t in $(git tag -l "deploy/*" | grep -E "^deploy/[0-9]{8}T[0-9]{4}Z$" | sort -r); do
  c=$(git rev-list -n1 "$t"); [ "$c" != "$cur" ] && { prev=$c; break; }
done
[ -n "$prev" ] || { echo "error: no earlier deploy/* commit to roll back to" >&2; exit 1; }
echo "live:        $cur"
echo "rollback to: $prev"'
```

Its image survives pruning (see "Storage hygiene"). Add `BMSMON_TAG=<full 40-char sha>` to the NAS's
`/share/bsv/docker-compose/.env` — the file compose interpolates via `--env-file` — then run the
same pull + `up -d --no-deps bmsmon-api` and record the rollback as a deploy tag too. Pin it in that
file, not on one command line: the deploy runner re-runs `down` + `up -d` whenever the bmsmon
compose file changes, and a one-off pin would silently fall back to the cached `:latest`, i.e. the
build you rolled back from. To un-pin, delete the line once the fix has passed CI and deploy
`:latest` again.

**Deploy record.** 2026-10-03: Tier-1 review program deployed. Server `1cbb522` went out by `:latest`
at 16:09Z after CI test, build, smoke and promote all passed (tags `deploy/20261003T1609Z`, plus the
backfilled `deploy/20260825T1130Z` -> `7cec11c`, the previous prod and rollback target). Verified:
`X-Bmsmon-Api: 1` on every response, `/web` gated by Authentik, ingest 200 under per-sample
validation, the widget feed free of GPS fields, and per-client `/api/` rate limits (one client got
190x 401 then 70x 429 while another still got 401). Infra (qnap-nas-docker `cd3ddcb`) went out at
15:58Z: private DB network, stack-local secrets, watchtower opt-out, blanked `X-Authentik-*`
headers, nightly dump + monthly drill, and Uptime Kuma monitors for api/db/backup. The APK from
`1cbb522` was installed 14:50Z. Still owed: a browser sign-out/in of Authentik so the admin pages
pick up the owner-only admin group, and a browser confirmation that WebSocket live updates pass the
Origin check.

On startup the new container re-runs `schema.sql`, so additive columns/tables land
automatically. A boot against an up-to-date database takes no table lock. Only a deploy that
actually adds a column can wait on a dump in progress (retrying for up to ~5 min, then the
container restarts). A newly declared `samples` index is built in the background after boot
by the maintenance pass: `docker logs bmsmon-api 2>&1 | grep maintenance`, then
`SELECT indisvalid FROM pg_index WHERE indexrelid = '<name>'::regclass` reads `t`.
Changes to the **stack** itself (`bmsmon/docker-compose.yml` or the shared `.env`) deploy
differently: push them to the `~/qnap-nas-docker` repo's `master` and its self-hosted runner
(`.github/workflows/deploy.yml`) SSHes in and restarts the changed service.

## Documentation

A high-level summary of this project also lives in the Obsidian vault at
`~/GoogleDrive/obsidian/notes/Bmsmon.md`. Update it alongside this file when
the project's status or architecture changes meaningfully — it's a snapshot
for cross-project reference, not a substitute for this CLAUDE.md's detail.

Open measurement, verification and decision items live in `docs/checkins/NEXT.md`, each with a due
date — check it at the start of any calibration or battery-saver work.

## Related Projects

- [aiobmsble](https://github.com/patman15/aiobmsble) — Python async BLE BMS library (has `redodo_bms.py`)
- [BMS_BLE-HA](https://github.com/patman15/BMS_BLE-HA) — Home Assistant integration (supports Redodo)
- [LiTime_BMS_bluetooth](https://github.com/calledit/LiTime_BMS_bluetooth) — Web Bluetooth implementation
- [litime-bluetooth-battery](https://github.com/chadj/litime-bluetooth-battery) — Another JS implementation
- [Litime_BMS_ESP32](https://github.com/mirosieber/Litime_BMS_ESP32) — ESP32 Arduino library

## Motion gate field log (2026-08-08)

Historical record; the current design is the silence-as-stillness gate in the Android section.

**VERIFIED IN THE FIELD 2026-08-08 — the motion gate works.** The outstanding vehicle-outing proof
was performed: a real round trip, both legs fully traced.

| | before the gate (08-04…08-06) | this outing |
|---|---|---|
| GPS fixes above 5 m/s | **0** | **26** |
| peak speed captured | — | **22.53 m/s = 50 mph** |
| GPS bucket coverage | — | 96% (263/275) |
| furthest from start | — | 5.64 mi |

Discharge read **0→0 across both legs** — the exact condition that used to blank the map, since the
chair draws nothing in a vehicle. Motion readings over the outing: **859 `IN_VEHICLE`**, 687 `STILL`,
451 `UNKNOWN`. The foreground-service type shows the gate never closed mid-drive (last change before
the outbound leg `12:28:31 → 24`, next at `12:53:49 → 16` *after* arrival; same shape on the return),
and all three clauses fired in the right order: chair discharging while loading → GPS on; chair idle
but `IN_VEHICLE` → GPS **stays** on; parked and still on arrival → gate closes.

**This settles periodic-vs-transitions empirically.** The Activity Transition API logged **zero**
transitions across two real vehicle trips on 2026-08-07; the periodic API logged 859 `IN_VEHICLE`
readings across one. Do not revisit transitions without new evidence.

The follow-ups this work left open — AR's own power cost (revert condition intact), the
motion-column wire cost, and the settings line's two permission states — are tracked with due dates
in `docs/checkins/NEXT.md`.

**Motion telemetry deployed and confirmed 2026-08-08 19:55.** Server deployed first, then the APK —
that order is load-bearing: a new phone against the old server has its keys silently ignored
(`SampleIn` has no `extra="forbid"`, so Pydantic defaults to `extra="ignore"`) and would read as an
Android failure. The three columns landed automatically on container start from the idempotent
`schema.sql`, with no migration step. Clean cutover in prod at 19:55: zero rows carried motion before
it, essentially every row after.

The first production rows (`STILL@100` with the gate still open) could not be told apart from
"debounce not yet met" because the reading's age was not uploaded; `motion_at_ms` (deployed
2026-08-09) closed that gap. The debounce/`MOTION_STALE_MS` design it was diagnosing was itself
replaced by the silence-as-stillness gate the same week — see the Android section.
