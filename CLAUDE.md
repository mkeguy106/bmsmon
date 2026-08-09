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
`lockBrightness` / `gpsParked` / `gpsShouldRun`, no Android imports, 15 unit tests in
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
at most one hold. It is **not** a fast path for the motion half: `foldMotion` folds one reading per
call, so this driver alone needs `STILL_DEBOUNCE_N` ticks — ~15 min of sampled confident-STILL — to
close the gate, and a staleness gap in between restarts the run. That is acceptable because it is a
backstop whose failure direction is GPS staying on; `onPoll` is the driver that actually closes the
gate). Teardown
goes through `shutdownGps()`, which drops intent and request together under the same lock —
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

**The fix (option (c)): pausing now requires BOTH no-discharge AND a debounced confident-still
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

`foldMotion(prev, reading, nowMs)` (`model/BatterySaver.kt` — pure, no clock, JVM-tested) folds one
reading at a time into a `MotionGate`, **asymmetrically**, in this branch order:

- **null, or the reading itself older than `MOTION_STALE_MS` (150_000)** → **fails open**, gate reset.
- **already folded** — `reading.atMs == gate.lastConfidentAtMs` → returns `prev` untouched (see the
  dedup note below).
- **confidence < `STILL_CONFIDENCE_MIN` (75)** → **holds `prev` unchanged**, *but only while
  confident evidence is still fresh*: past `MOTION_STALE_MS` since the last confident reading it
  fails open anyway. Uncertainty neither pauses nor resumes, and it cannot postpone fail-open either.
- **confident `STILL`** → run++, gate closes once the run reaches `STILL_DEBOUNCE_N = 3`, and the
  reading's timestamp becomes the new `lastConfidentAtMs`.
- **confident non-`STILL`** → reopens on a **single** reading, so getting into a vehicle resumes GNSS
  at the first solid sample rather than after N of them.

**Folds are deduped by reading identity, and that is what makes N mean anything.** The engine
evaluates the gate on every BLE frame (~80–115×/min across the fleet) while AR broadcasts arrive
~10×/min, and `MotionSource.current()` returns the same cached reading in between — so folding on
every *evaluation* counted one reading ~11 times and collapsed `STILL_DEBOUNCE_N` to an **effective
1**. Invisible while stationary (nothing ever reopens the gate, which is why the on-device 5m18s hold
looked right), but in transit a single spurious `STILL@96` at a stop light closed the gate ~1.5 s
later and the next confident `IN_VEHICLE` reopened it — a GNSS restart and a hole in the track per
misread, i.e. exactly the flapping the debounce exists to remove. Fixed by carrying the last folded
confident reading's timestamp **on `MotionGate` itself** (`lastConfidentAtMs`), so the dedup lives in
the pure function rather than as engine state — and so `shutdownGps()`'s existing gate reset clears
it too. **One field, two jobs, deliberately**: it is both the dedup key and the fail-open deadline,
which works because only confident readings ever change the gate (re-folding an uncertain one is
already idempotent). Staleness is still re-evaluated on **every** call, so the deadline runs off the
wall clock, not off reading arrivals. Corollary worth stating plainly: **this reduces the measured
saving** — the gate now takes three genuine readings (~18 s at the observed cadence), not ~1.5 s, to
close. That is the correct direction.

**Branch order is load-bearing: staleness is checked *before* both the dedup and the uncertainty
hold**, so a dead signal can never hold the gate shut. Every unusable-signal path — permission
denied, AR unavailable on the device, subscription lapsed, process restarted with no reading yet,
updates gone stale, only low-confidence readings arriving — fails open to GPS-on. That is the user's
explicit choice: never lose an outing, even at the cost of the saving. The single-sample
`confidentlyStill()` predicate is **deleted**; the name survives only as `gpsShouldRun`'s parameter,
which the gate's `still` verdict now feeds.

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
**resets the gate**, so a stale debounce run cannot survive a stop. Three build pitfalls, all
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
not yet been confirmed on-device — an owed verification, not a completed one.)*

**The Activity Transition API was measured and rejected**, reversing the recommendation an earlier
revision of the design carried. Armed with transitions at 13:18 on 2026-08-07 (standby bucket 10,
`SUBSCRIBE SUCCEEDED`), the probe covered two genuine vehicle trips that afternoon at up to **24 mph**
and logged **zero transitions**, while periodic updates arrive every few seconds. Caveat worth
recording: the probe (`:arprobe`, branch `experiment/ar-power-probe`) has no foreground service and
is not resident, whereas the app is, so it may be an **invalid proxy** for in-app delivery — but
nothing supports preferring transitions, and the periodic stream is demonstrably rich enough.

**The saving is PARTIAL, not full, and the tuning fix for it is OPEN.** Play Services' periodic
delivery is genuinely **bursty** — multi-minute silent gaps — and every gap longer than
`MOTION_STALE_MS` (150 s) trips staleness and reopens the gate. Measured on-device 2026-08-07: every
reopen tied to staleness firing at 150.067 s / 150.170 s after the last reading, **zero** instances of
the old reopen-on-`UNKNOWN` bug, and the gate held closed for one unbroken **5m18s** stretch — then
cycled open→closed→open→closed→open over ~16 min and sat open the final 7+ min with no readings at all
(no crash, no subscription error, device awake, not in Doze). **Read the *closing* half of that trace
with care: it was captured while folds still happened per gate evaluation, so the gate was closing
after one reading, not three** — the reopen timings and the sparsity finding stand, the close timings
describe superseded behaviour, and the duty cycle it implies is an over-estimate. Delivery sparsity is
a **different problem** from the flapping the hysteresis fixed, and it bounds the achievable saving.
**Lengthening `MOTION_STALE_MS` (~10–15 min) to ride out the gaps is UNDECIDED** — it is only safe if
AR reliably emits a confident non-`STILL` when motion starts, since reopening would then no longer be
backstopped by staleness, and **that is untested**. Do not record it as settled either way. Also
still open: **AR's true power cost is unmeasured.** The ~18 h probe run produced no detectable cost
(global `sensors` 0.00161 → 0.00133 mAh/h), but that is a **null result, not a measured number** — AR
executes inside Play Services and nothing accrues to the caller's uid, and the phone was on the
charger for all but 2 minutes of the window. If periodic AR costs more than the ~15 mA the pause
saves, the feature is a **net loss and should be reverted**; the check is total phone drain across
comparable days from `batterystats`, not per-uid attribution. **Also still owed: a real vehicle
outing**, confirming GNSS stays active in transit and that fixes above 5 m/s reappear — the metric
that has read zero since 2026-08-03 — and, with it, a fresh duty-cycle trace now that closing takes
three genuine readings. Both owed on-device checks (that one, and the settings line's two permission
states above) are **unperformed**, not pending-and-fine.

**No server or WebUI change was required** for either half of this: every GPS read path already
filters `lat IS NOT NULL AND lon IS NOT NULL` (`server/app/db/queries.py:539`, `:623`),
`lat`/`lon`/`gps_accuracy_m` have always been nullable (the phone already uploads null coordinates
whenever GPS is off or no fix is cached), the live marker already greys at 120 s to "last known +
age", and the server suite passes unchanged (185 tests). Gaps were already first-class on the web
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
relaunches it.** The phone *is* the wheelchair's battery monitor, so an install that leaves the
process dead is downtime, not a dev inconvenience — during this build the chair's monitoring sat
dead until it was manually restarted. Always follow an install with
`adb shell am start -n dev.joely.bmsmon/.MainActivity` and confirm with
`adb shell 'ps -A | grep bmsmon'`. Note that a `monkey` launcher intent
(`adb shell monkey -p dev.joely.bmsmon -c android.intent.category.LAUNCHER 1`) reports
`Events injected: 1` but does **not** start this app on this device.

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
unseen. So `MonitorEngine.evaluateAlerts()` evaluates **every reachable pack** against the ladder
and fires a **per-pack** headless notification, deduped **per address** (`AlertNotifier` keys
`lastByAddr`/`idByAddr` by address, ids from `NOTIF_CAP_BASE`; per-pack charge-hold latch). The
pure `reconcileFleetNotifications()` (`model/Alerts.kt`) does the fan-out dedup: notify the fresh
crossings, cancel recovered/charging/vanished packs. A second low pack is never masked by the one
on stage. (Temperature notifications stay stage-worst-driven.)

**Low pack seizes the stage (safety override).** `resolveStage()` (`model/Fleet.kt`) has a
pre-emptive branch — before the manual-pin check — that stages the base of the **lowest reachable
pack at/below the seize threshold**, over the active chair AND a manual pin (daily-driver breaks
ties). The seize threshold is `StageInputs.seizeThreshold`, set by the ViewModel to the **highest
enabled capacity threshold** (default ladder top = 30%) when both `alertsOn` and the new
`seizeLowToStage` setting are on (else null). Charging doesn't block the seize (the flash is still
charge-suppressed). On recovery the branch yields and normal pin/auto resolution takes back over.
`Settings › Alerts` gains a **"Pull low packs to stage"** toggle (default ON) gating only the
visual seize — fleet-wide notifications fire regardless.

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
(`Content-Encoding: gzip`; server decompresses before the JWT body-hash verify) and **batched**:
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

**Next check (~2026-09).** Open: re-verify whPerMile as outing days accumulate, now on the
corrected current-sign basis.

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
a **Reset to defaults** button. `stageAlert()` resolves the in-app flash from the lowest pack on
stage; charging suppresses the flash; acknowledged thresholds silence until SOC drops to the next
level. The **highest enabled** ladder rung doubles as the stage-seize threshold (see "Low pack
seizes the stage" above), and headless notifications are **fleet-wide/per-pack** (see "Capacity
alerts are fleet-wide") — the ladder is the single source of truth for all three.

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
mirror) + `GET /web/alert-config` (the read-only capacity-seize mirror), plus admin-gated
`GET /web/samples`, `GET /web/devices`, `POST /web/enroll-codes`,
`DELETE /web/devices/{id}`). The temperature config lives in the `device_temp_config` table
(per device+profile, latest-wins); the WebUI mirror (`web/src/temp.ts` + `TempGauge`/`TempBanner`/
`TempOverlay`/`BatteryProfilePanel`) re-evaluates the same zone ladder read-only. The **capacity
seize threshold** rides the same `POST /api/v1/config` body (optional flat `seize_soc`/`alerts_on`
fields on `TempConfigBody`) into the device-level `device_alert_config` table (latest-wins); the
WebUI reads it via `GET /web/alert-config` and `web/src/stage.ts` `selectStageItems` seizes its
main stage for the lowest fresh pack `≤ (alerts_on ? seize_soc ?? 30 : ∅)` — over pins and
auto-selection, with a **"LOW"** marker (`MainStage.tsx`), no audible alarm. Schema is
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

`samples` also carries **motion state** (`feat/motion-telemetry`, 2026-08-08 — **not yet
deployed**: the columns and ingest mapping exist only on that branch until it merges and the
server image is rebuilt/redeployed): `motion_activity` (text — the phone's Activity Recognition
reading, e.g. `STILL`/`IN_VEHICLE`/`UNKNOWN`), `motion_confidence` (smallint, 0-100, that
reading's confidence), and `motion_still` (boolean — the motion **gate's own verdict**,
`MotionGate.still`), all nullable. **The verdict is stored as its own column rather than derived
from the reading** because the gate's debounce (`STILL_DEBOUNCE_N = 3` consecutive confident-STILL
readings to close the gate; one confident non-STILL reading to reopen it) carries state across
readings, and an uncertain reading (confidence `< STILL_CONFIDENCE_MIN`) **holds the previous
verdict** instead of resetting it — so a single row's activity+confidence can't reconstruct what
the gate was doing without replaying its whole fold history. Recording only the reading would show
`STILL@100` without revealing the gate was still mid-debounce; recording only the verdict would
show the gate never closing without revealing a run of `UNKNOWN@41` readings was why. This is the
whole justification for three columns instead of two. `MotionReading` (`model/BatterySaver.kt`)
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

-- Does the gate's verdict track the readings, or is the debounce wrong?
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
<ago>"** — same rule as v1 and the Command rail, because a pack out of BLE range still holds its
charge and a blank "—" hid it. **Every summary tile counts offline packs on their last-known
reading too** — `PACKS READY`, `NEED RECHARGE` and `FLEET CAPACITY` each footnote how much of
their figure is stale ("incl. N offline · last known", from `readyStale`/`needRechargeStale`/
`staleCounted`), so being away from the spares no longer reads as 0 ready / 0% capacity. The
hero card's heading follows the base's real status instead of a hardcoded "In use now"),
**Alerts** (capacity ladder + temp zones + cell imbalance, `localStorage`
acknowledge), **Settings** (units/map trail/theme segmented toggles), **History** (per-base
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
cost-per-mile — `outingWh` (∫|power| over discharging buckets, Δt capped at 60 s) ÷
`summary.activeMiles` — against the learned `whPerMile` band (summed across connected packs to
match the merged track's base-total power basis). Live "today" window → **"CAN YOU MAKE IT?"**
with `~X mi left at today's rate · ~Y at your usual` (base-total remaining Wh ÷ each rate);
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
revoke — a port of v1's `AdminDevices`, reusing the admin `/web/devices` + `/web/enroll-codes`
endpoints) lives as a **Devices section inside Settings** (`DevicesPanel.tsx`), not a separate nav
entry — so there is no longer any "SOON" item. Roadmap/spec:
`docs/superpowers/specs/2026-07-12-webui-v2-roadmap.md`.

### Location sharing (public /share/ zone)

Time-limited public share links let a named guest follow the chair live:
`https://bmsmon.covert.life/share/<token>` (token = `secrets.token_urlsafe(24)`; only
sha256 stored in `location_shares`; link recoverable ONLY at creation). Traefik has a
third zone — `PathPrefix(/share/)`, priority 100, `bmsmon-header` + `bmsmon-proxy-secret`
(no Authentik; the proxy secret is there ONLY so the rate limiter can trust XFF for
per-IP keying — `/share` endpoints never read identity headers) — and the guest page is
a **third Vite build** (`web/share/`, `vite.config.share.ts`, `base:"/share/"`) so its
assets stay inside the public zone. Server: `app/routers/share.py` —
`GET /share/{token}` (active → guest shell; expired → friendly "ask for a new link"
page; unknown/REVOKED → identical bare 404) and `GET /share/{token}/feed` (today-only
fleet GPS via `q.gps_track_all`, fields t/lat/lon ONLY — never battery data; day window
clamped server-side in the container TZ; 410 when expired; updates
last_access/access_count; no-store + no-referrer on every response incl. errors; per-IP
`share_limiter` 150/min). Admin CRUD on the Authentik zone: `POST/GET /web/shares`,
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
WebUI (`selectStageItems`) already use: **(1)** a base discharging right now
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
`useTrack`/`appendTrack` use); **`last` is computed from the FULL trail before slicing**,
or a no-news poll would blank the live chair marker; and the response carries `day_start`
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

**Local dev/test:** `docker compose -f server/docker-compose.dev.yml up -d` brings up a Postgres on
`localhost:5432` (user/pw/db all `bmsmon`, matching the default `DATABASE_URL`). Run server tests
with the venv: `cd server && .venv/bin/python -m pytest` (bare `python` lacks the deps).

**WebUI smoke test (Playwright, local):** seed the dev DB with a synthetic 4-pack fleet
(`server/.venv/bin/python server/scripts/seed_dev.py` — TRUNCATES the dev DB, never point it at
prod), run the API with the built-in local identity (`BMSMON_DEV_TRUST_HEADERS=1
server/.venv/bin/uvicorn app.main:app --port 8000` — dev-trust refuses non-local DATABASE_URLs, and
without it /web/* 401s and the /ws close-after-accept loop starves the REST fallback), start
`npx vite dev --port 5173` in `web/`, then `node scripts/smoke.mjs` (from `web/`). It screenshots
all six v2 views (at `/`) + v1 (at `/v1/`) + preview.html into `web/smoke-shots/` (gitignored) and
exits non-zero on any console error or page crash. Note the smoke test drives `vite dev`, which
serves the shells straight off disk and has **no** `/v2/` → `/` redirect (that lives in FastAPI),
so it must use the real build paths. `playwright` is a web devDependency; browsers via
`npx playwright install chromium`. The server Dockerfile sets `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`
so CI image builds never pull browsers. Re-run the seeder to reset pack staleness (data >90 s old
renders as DISCONNECTED — useful for testing that state deliberately).

### Image build (GitHub Actions)

`.github/workflows/build-server.yml` builds the multi-stage image (Node builds `web/dist` → Python
serves API + static) and pushes `ghcr.io/mkeguy106/bmsmon-server:latest` (+ a `:<sha>` tag) on any
push to `main` touching `server/**`, `web/**`, or that workflow. Watch a run with `gh run watch` or
the Actions tab.

The job sets `DOCKER_BUILD_RECORD_UPLOAD: false`. Without it `docker/build-push-action@v6` uploads a
~63 KB `<owner>~<repo>~XXXXXX.dockerbuild` build record as an Actions artifact on **every** run;
nothing ever reads them and 54 (3.2 MB) had accumulated by 2026-08-03.

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
resolve. It keeps the newest `KEEP` (default 10) tagged versions plus whatever carries `latest`.

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

## Documentation

A high-level summary of this project also lives in the Obsidian vault at
`~/GoogleDrive/obsidian/notes/Bmsmon.md`. Update it alongside this file when
the project's status or architecture changes meaningfully — it's a snapshot
for cross-project reference, not a substitute for this CLAUDE.md's detail.

## Related Projects

- [aiobmsble](https://github.com/patman15/aiobmsble) — Python async BLE BMS library (has `redodo_bms.py`)
- [BMS_BLE-HA](https://github.com/patman15/BMS_BLE-HA) — Home Assistant integration (supports Redodo)
- [LiTime_BMS_bluetooth](https://github.com/calledit/LiTime_BMS_bluetooth) — Web Bluetooth implementation
- [litime-bluetooth-battery](https://github.com/chadj/litime-bluetooth-battery) — Another JS implementation
- [Litime_BMS_ESP32](https://github.com/mirosieber/Litime_BMS_ESP32) — ESP32 Arduino library

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

Still open, unchanged: the saving remains **partial** (Play Services delivers in bursts, so the gate
cycles while parked), the `MOTION_STALE_MS` tuning is still **undecided**, and AR's own power cost is
still **unmeasured** with its revert condition intact — net loss if it exceeds the ~15 mA the pause
saves. Also still unverified: the settings line's two permission states.

**Motion telemetry deployed and confirmed 2026-08-08 19:55.** Server deployed first, then the APK —
that order is load-bearing: a new phone against the old server has its keys silently ignored
(`SampleIn` has no `extra="forbid"`, so Pydantic defaults to `extra="ignore"`) and would read as an
Android failure. The three columns landed automatically on container start from the idempotent
`schema.sql`, with no migration step. Clean cutover in prod at 19:55: zero rows carried motion before
it, essentially every row after.

**Known gap found immediately by using it:** the first production rows read
`motion_activity=STILL, motion_confidence=100, motion_still=false` with zero discharge — a confident
still reading with the gate still open, minutes after restart and well past the 3-reading debounce.
That is consistent with the documented bursty-delivery limitation (a reading older than
`MOTION_STALE_MS` fails open), **but the stored fields cannot distinguish it from "debounce not yet
met"**, because the reading's age is not uploaded. Adding the reading timestamp — or a derived
staleness flag — would close that. Worth doing before the next diagnostic cycle rather than during
one, which is the same lesson that produced this feature.
