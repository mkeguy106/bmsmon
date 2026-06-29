# BLE Connectivity Investigation — "stuck" packs & connection cycling

**Date:** 2026-06-28
**Status:** Investigation complete; batteries confirmed healthy. No code change made yet — mitigation options proposed at the end.
**Scope:** Why two battery packs (2016·A and 2023·B) became unreachable in the bmsmon app, whether our app could be harming the BMS, and how to prevent recurrence.

---

## TL;DR

- **The batteries are fine.** All 8 packs are powered, advertising, and *connectable*. Nothing is bricked, shut down, or damaged.
- **Our app did not change the BMS.** It is provably read-only — the only thing it ever transmits is the `0x13` status-read query. The destructive opcodes (MOSFET toggles `0x0A–0x0D`, shutdown `0x60`) **do not exist anywhere in the codebase**, and the type system makes them unsendable.
- **What actually happened:** two packs intermittently failed to *connect* (not advertise) from the phone. The most likely contributors are (a) edge-of-range RF from the phone's fixed position and (b) our app's aggressive reconnect behavior contending for each BMS's **single BLE client slot** and stressing the finicky Beken BLE module.
- **DEFINITIVE (HCI capture of the official app, 2026-06-29):** the Redodo Android app **holds all 8 packs connected simultaneously** (persistent links, ~3 min continuous), and it sends the **byte-identical `0x13` status query we send** (`00 00 04 01 13 55 AA 17`) plus a `0x16` firmware read — and *nothing else*. No destructive opcodes. So **we are doing exactly what the official app does**; we are not harming the BMS. (This corrects an earlier guess that it "cycles because the phone can't hold 8" — on the Pixel it held all 8.)
- **The main stage works great** and produced 2 full days of excellent real-world data. The problem is confined to how we poll the **off-stage** packs.

---

## System background (relevant bits)

bmsmon is a read-only BLE monitor for Redodo/LiTime-class LiFePO4 packs. Each pack uses a **Beken BK-BLE-1.0** UART-to-BLE bridge module.

**Hard BLE constraints (from the modules):**
- A BMS allows **only one BLE client connected at a time**. If the Redodo app holds it, we can't connect, and vice-versa.
- The Beken module is **finicky**: it drops out of the host's scan cache after a connect/disconnect cycle, and is prone to `le-connection-abort-by-local`. Rapid back-to-back connects cause scan-cache contention and spurious "not found"s.

**How the app polls today** (`ble/BmsRepository.kt`, `model/Fleet.kt`):
- **Main stage** = the 1–2 packs of the in-use base. Each gets a **persistent** GATT connection, polled fast: `STAGE_POLL_MS = 1650 ms`.
- **Everything else** = a **rotating sampler**: connect → read one `0x13` status → disconnect → next pack, in priority order (discharging > charging > idle).
  - `SAMPLER_CONCURRENCY = 2` (two sampled at once)
  - `SAMPLER_GAP_MS = 3000 ms` between sampler batches
  - `SAMPLE_FAIL_LIMIT = 3` consecutive failures before a pack is shown "out of range"
  - `gate = Semaphore(2)` caps simultaneous *connection attempts*
- **Critically:** a pack that keeps failing is **retried on every sampler loop indefinitely** — roughly every 3–4 s, forever. There is no backoff. This is the core behavioral problem (see Mitigations).

**Proof the app is read-only** (`ble/BmsProtocol.kt`, `ble/BleSession.kt`):
- The complete set of commands the app can send is a Kotlin `enum class ReadCommand` containing only reads: `STATUS(0x13)`, `SERIAL(0x10)`, `CONFIG(0x15)`, `FW_VERSION(0x16)`, `SOH_SOC(0x41)`, `CAPACITY(0x43)`.
- The only characteristic write in the entire codebase writes `STATUS_FRAME` (`0x13`).
- `frame()` can only be called with a `ReadCommand` — the compiler rejects anything else. There is **no** `0x60`/`0x0A–0x0D` opcode anywhere.
- Nothing is ever written to FFE3 (the module's AT-command interface). "Disconnect" simply closes the GATT link; no payload reaches the BMS.

---

## Battery roster (resolved during this investigation)

Mapped from advertised names + last-known capacity signatures + logged telemetry:

| Group·Pack | MAC | Advertised name | Notes |
|---|---|---|---|
| 2012·A | `C8:47:80:15:67:44` | R-12100BNNA70-A02214 | daily-driver stage pair (most-polled) |
| 2012·B | `C8:47:80:15:62:1B` | R-12100BNNA70-A02345 | daily-driver stage pair |
| 2016·A | `C8:47:80:15:DB:13` | R-12100BNNA70-A03902 | **went dark 06-27 21:54** |
| 2016·B | `C8:47:80:15:25:9A` | R-12100BNNA70-A03727 | |
| 2023·A | `C8:47:80:46:0A:D6` | R-12100BNNA70-B02371 | |
| 2023·B | `C8:47:80:45:90:FB` | R-12100BNNA70-B02375 | **"out of range" 06-28 ~19:31** |
| 2024·A | `C8:47:80:15:07:DE` | R-12100BNNA70-A02285 | |
| 2024·B | `C8:47:80:15:25:01` | R-12100BNNA70-A02402 | |

OUI `C8:47:80` = Beken Corporation.

---

## What was reported (symptom timeline)

1. **2023·B** showed **"Out of range"** in the app and would not connect.
2. The user physically went to 2023·B and **could not connect with the official Redodo app** (on an **iPhone** — a *different* device from the monitoring Pixel) at that time.
3. A **second** pack, a **2016** battery, had been in a "weird state since yesterday."
4. The user's concern: *"I fear we are doing something to the Bluetooth on the BMS."* (Reasonable — the project's own history documents that scanning unknown command bytes once caused an unrecoverable BMS shutdown.)
5. **Later update (important):** the official Redodo app **can** connect to all 8 — but it appears to **cycle** through them, because the iPhone can't sustain 8 simultaneous BLE connections.
6. **The main stage works great.** The app ran for 2 days in the real world and produced excellent data.

---

## Investigation & evidence

### 1. Code audit — is the app sending anything harmful? → **No (proven).**
Audited every BLE write path. Only `0x13` is ever transmitted; destructive opcodes don't exist in the code (type-enforced). See "Proof the app is read-only" above. **We are not altering MOSFETs, BMS config, or shutting anything down.**

### 2. The dark pack's final moments (telemetry DB).
`C8:47:80:15:DB:13` (2016·A) stopped reporting at **2026-06-27 21:54:30** and never returned; every other pack has data through 06-28 ~20:55. Its **last 16 samples were completely normal** — `Charging`, 86% SOC, 13.53 V, +8.2–8.5 A — with **no protection flag, no voltage anomaly, and no `Disconnected` event**. It went from steady, healthy charging to silence in one step. (Likely trigger: the charger finished, the BMS transitioned toward idle/sleep, and the Beken module hung on that transition.)

### 3. Pixel BLE logs for 2023·B (logcat).
Repeating failure pattern every ~3–4 s:
```
LE Enhanced Connection Complete … conn_cnt: 4     ← link-layer connection succeeds
BluetoothQualityReport … C8:47:80:45:90:FB        ← controller flags poor link quality
DisConnectCompleteEvent … conn_cnt: 3             ← drops ~280 ms later
gatt_cleanup_upon_disc: … GATT_CONN_FAILED_ESTABLISHMENT
onClientConnectionState() status=133 connected=false
```
The radio **accepts** the connection, then **GATT discovery never completes**. `conn_cnt: 4` shows only 4 concurrent links — well under the device limit, so it is **not** a connection-budget problem.

### 4. Phone connection-limit context.
Android's Bluetooth stack caps concurrent BLE/GATT connections around **7** (the long-standing `GATT_MAX_PHY_CHANNEL` default), shared device-wide across all apps. Not the bottleneck here (we use 1–3), but relevant background: **no phone can hold all 8 packs at once** — cycling is mandatory.

### 5. The PC — ruled out.
The desktop's Bluetooth adapter was **powered off**, with no `bleak`/`python`/`bluetoothctl` processes and no battery paired. It cannot have been holding any link.

### 6. Passive scan — all 8 advertising.
A 30 s listen-only scan saw **all 8 packs advertising**, names and all, including both problem packs. A module that was bricked/crashed/radio-off **cannot advertise** — so all 8 modules are alive.

### 7. `btmon` HCI capture — advertising type.
Captured the raw LE Extended Advertising Reports and checked the `Props` Connectable bit. **All 8 advertise as *connectable*, including 2016·A and 2023·B.** This *refuted* the hypothesis that the stuck packs had flipped to non-connectable advertising. At the advertising layer they look identical to the healthy packs — i.e. listening alone cannot reveal a connection-path wedge.

### 8. Official Redodo app — connects to all 8 by cycling.
The decisive real-world signal: the iPhone Redodo app reaches **all 8** by **cycling** (sequential connect/read/disconnect, ~1 held at a time). This confirms the packs are genuinely connectable and that the correct access pattern is **gentle, sequential cycling** — not parallel persistent reconnects.

---

## Current understanding

**Proven:**
- All 8 batteries are healthy, powered, advertising, and connectable.
- The app is strictly read-only; it cannot and did not change BMS state.
- The PC was not involved.

**Most likely explanation (hypothesis, well-supported):**
- The two packs intermittently failed to *connect* (not advertise) from the phone, due to a combination of:
  1. **Edge-of-range RF** from the phone's fixed position (the PC, possibly closer/with a better antenna, saw all 8 advertise fine), and
  2. **Our app's aggressive, no-backoff reconnect loop** repeatedly hammering each unreachable pack every ~3–4 s. On a single-slot, finicky Beken module this (a) contends with / locks out the official app, and (b) plausibly wedges the module's connection/GATT path temporarily via connect/abort churn. The `GATT_CONN_FAILED_ESTABLISHMENT` + Bluetooth Quality Report signature is consistent with a marginal/churned link.
- 2016·A specifically likely hung on a **charge → idle/sleep transition** (it died mid-charge while perfectly healthy), independent of any command — these modules are known to be fragile on state transitions.

**Not what we feared:** there is no BMS shutdown, no MOSFET change, no config change, and no battery damage. The worst case is a temporarily unresponsive *BLE module*, which a power-cycle clears and which harms nothing.

---

## Official Redodo app — full HCI capture (2026-06-29)

We installed the Redodo app (`com.redodopower.ble`) on the **Pixel 6** (with our own bmsmon app stopped) and captured, in parallel, three streams while the user connected all 8 packs:
1. **Android Bluetooth HCI snoop log** (full, unfiltered — ATT payloads present), retrieved via `adb bugreport` and decoded with `tshark`.
2. **A screen recording** (`screenrecord --bugreport`, timestamped frames) of the app UI.
3. **Touch events** (`getevent -lt /dev/input/event3`) for tap timing — 59 taps over ~186 s.

This is the definitive evidence; it answers every open question.

### Finding 1 — it holds all 8 connected *simultaneously* (not cycling, not faking)
The HCI log assigns one connection handle per pack and they are **all open at once**:

| Handle | Battery |
|---|---|
| 0x41 | 2024·A (`…07:DE`) |
| 0x42 | 2024·B (`…25:01`) |
| 0x43 | 2012·A (`…67:44`) |
| 0x44 | 2012·B (`…62:1B`) |
| 0x45 | 2023·A (`…0A:D6`) |
| 0x46 | 2023·B (`…90:FB`) |
| 0x47 | 2016·A (`…DB:13`) |
| 0x48 | 2016·B (`…25:9A`) |

From **14:52:46 to 14:55:47 all eight handles were open continuously** (no drops). The on-screen "8/8" is eight real concurrent BLE links. The app tracks genuine per-pack Online/Offline state and the count climbs as each truly connects (0 → 3 → 4 → 7 → 8). **The "connects to one and fakes the rest" theory is disproven.** (The capacity-only-on-detail observation is just UI: the device list shows SOC %, the detail page shows Ah.)

> Note: this also corrects the earlier "Android caps at ~7 / the phone can't hold 8" assumption — this Pixel 6 held **8** concurrent LE connections fine. The ~7 figure is a soft default, not a hard wall here.

### Finding 2 — connection establishment is flaky and is solved by *patient retry*
Several packs failed to establish and were retried until they held (each failure = connect, then GATT drops ~0.1–0.3 s later — the same `GATT_CONN_FAILED_ESTABLISHMENT` signature as our app):

- **2016·B: ~8 attempts over 26 s** before it stuck
- 2016·A: 3 · 2012·B: 3 · 2023·A: 2 · 2024·A: 2 · (2012·A, 2024·B, 2023·B: first try)

The app never gives up and never hammers blindly — it retries with spacing, and **once a pack connects it keeps the link open.**

### Finding 3 — it sends the *exact bytes we send*, and only safe reads
Every write the Redodo app made over the whole session (complete, nothing omitted):

| Bytes written (to FFE2) | Meaning | Count |
|---|---|---|
| `00 00 04 01 13 55 AA 17` | **0x13 status query — byte-identical to our `STATUS_FRAME`** | 17 |
| `00 00 04 01 16 55 AA 1A` | 0x16 firmware-version query (a documented safe read) | 8 |
| CCCD descriptor writes (`0x2902`) | enable notifications — standard BLE | 16 |

**No `0x60` shutdown, no `0x0A–0x0D` MOSFET commands, no unknown opcodes — nothing else.** Status responses arrive as notifications with the same framing we parse (`01 93 55 AA …` for 0x13; `01 96 55 AA …"T12100-V1.2"` for the firmware). The only protocol difference: Redodo uses ATT **Write Request** (with response, opcode 0x12); we use **Write Command** (no response). Functionally equivalent.

**Conclusion: the official app is read-only and speaks the identical protocol — it literally transmits the same `0x13` frame our app does. We are not doing anything to the BMS that Redodo doesn't do.**

### Finding 4 — two-tier polling: ~1.5 s on the active pack, slow on the rest
- **Active / foreground pack (single battery's live detail page):** a second capture (2026-06-29, pack 2012-A) measured the app polling `0x13` status in a steady request/response loop **every ~1.5 s — mean 1.487 s, range 1.43–1.53 s** over 18 consecutive intervals. (Same rate whether discharging or in Standby — it's a fixed cadence.) **This is the fast rate we mirror for the main stage** (`STAGE_POLL_MS` set to 1500 ms to match).
- **Background packs (held but not foregrounded):** across ~3 min holding all 8 links, only **~17 status reads total** — i.e. each background pack is read very infrequently. Holding persistent connections + slow background reads is the gentle pattern.

So the official model is **two-tier**: fast (~1.5 s) on the one you're watching, slow on the rest, all held persistently.

### What this means for our app (refines the mitigations below)
- **Hold persistent connections instead of churning.** 8 simultaneous persistent links work on Android; our connect→read→disconnect sampler is the *more* stressful pattern. Moving off-stage packs to persistent (or longer-lived) connections, polled slowly, mirrors the official app and eases the finicky modules.
- **Patient retry, then hold** is the established recipe for flaky packs — connect, retry-with-spacing on GATT failure until it sticks, then keep it (pairs naturally with the backoff idea).
- **Poll slowly off-stage** — the official app proves infrequent reads are fine for fleet awareness.

Artifacts saved locally: `/tmp/redodo_1.mp4`, `/tmp/redodo_2.mp4` (screen), `/tmp/taps.txt` (tap log), `/tmp/br.zip` → `btsnoop_hci.log` (HCI).

---

## What we tried

| Action | Result |
|---|---|
| Audited all BLE write paths in the code | Confirmed read-only; only `0x13` ever sent |
| Force-stopped the Pixel app | Removed it as a variable (zero BLE activity confirmed in logcat) |
| Checked the PC for BLE holders | Adapter off, nothing running — ruled out |
| Pulled & analyzed the telemetry DB | Found 2016·A went dark 06-27 21:54 mid-charge, healthy |
| Passive BLE scan from the PC | All 8 advertising |
| `btmon` capture + Props analysis | All 8 advertise *connectable*; no advertising-level anomaly |
| (Deliberately did **not** open a GATT connection) | Per user's request to stay listen-only |
| **Full HCI capture of the official Redodo app on the Pixel** | **Definitive:** holds 8 simultaneous persistent links; sends the identical `0x13` (+ `0x16`) reads, nothing destructive; flaky packs solved by patient retry (see section above) |

---

## Prevention & mitigation — how to stop this recurring

The unifying principle: **be a gentler, more patient BLE citizen for off-stage packs**, and **yield gracefully** so a pack (and the official app) is never starved. The main stage is already good and should be left as-is.

### A. Exponential backoff on failing packs (highest priority, smallest change)
Today a pack that keeps failing is retried every ~3–4 s forever. Instead, after `SAMPLE_FAIL_LIMIT` failures, **back the pack off** to a long interval (e.g. 30 s → 60 s → 2 min, capped), resetting on a successful read. Benefits:
- Stops hammering a marginal/finicky module (less churn → fewer wedges).
- Frees the single BLE slot far more often, so the official app — or our own next attempt — can get in.
- Costs almost nothing in data quality: an out-of-range pack has no new data to miss.

*Where:* `BmsRepository` sampler loop — track per-address `nextEligibleAt` and skip until due; grow the delay on each failure.

### B. Slow the off-stage cadence way down (the user's instinct — recommended)
The off-stage packs don't need 3 s polling. They're parked/idle context; a reading every **30–60 s** (or even minutes) is plenty for fleet awareness. Raise `SAMPLER_GAP_MS` substantially for the off-stage pool (keep the stage fast). This directly matches "slow things way down with the packs not on the main page."
- Optionally make it adaptive: idle packs polled rarely, discharging/charging packs a bit more often.

### C. Copy the official app's model: hold persistent links, poll gently (CONFIRMED by capture)
The HCI capture (section above) settled what the official app does — it does **not** cycle. It **holds all 8 connected simultaneously** and polls slowly (~17 status reads over ~3 min). To mirror it:
- **Hold persistent connections** to off-stage packs (within the controller's limit — the Pixel did 8) instead of connect→read→disconnect churn.
- **Poll infrequently** off-stage (tens of seconds), keeping only the live stage fast.
- **Patient retry on connect failure**, then keep the link (see D / the established recipe).

> The earlier guess that the app "cycles one at a time" was wrong — the capture shows persistent simultaneous links. The over-the-air-sniffer note is moot now that we captured it directly on the Android.

### D. Single-client coexistence / yielding
Because the BMS allows only one client:
- When the user wants the official app, our app should **get out of the way**. The new **Disconnect all** / per-pack **Disconnect** (added earlier) already does this — document it as the "let me use the Redodo app" gesture.
- Consider an explicit **"pause off-stage polling"** toggle, or auto-yield: if connection attempts to a pack keep failing, assume another client may want it and back off hard (ties into A).

### E. Gentler connection parameters
Request a **relaxed connection interval** and a sane **supervision timeout** on connect, so marginal links are more tolerant and the module is under less pressure. (Android exposes `requestConnectionPriority`; default/low-power instead of high.)

### F. Clean-disconnect discipline (verify, likely already OK)
Ensure **every** connection attempt — including failures and cancellations — closes the GATT (`BluetoothGatt.close()`), so we never leave a half-open link occupying the slot. The sampler's `finally { session.close() }` and the stage worker's cancel path appear to do this; worth re-confirming under the failure paths.

### G. Don't aggressively re-stage a flaky pack
If a pack flaps (connects then fails GATT repeatedly), avoid promoting it to a persistent stage connection; keep it on the slow, backed-off sampler until it's stable.

---

## Recommended next steps

1. **Recover the two packs now** (no urgency, both healthy): a BLE-module power-cycle — let the BMS sleep (chair fully off, no charger, no client) so the module re-inits on wake, **or** briefly disconnect/reconnect the pack. (User is currently waiting for natural sleep.)
2. **Implement A + B first** — backoff + slower off-stage cadence. Low risk, high payoff, leaves the great main-stage behavior untouched.
3. **C is now answered by the capture** — copy the official model (persistent links + gentle polling + patient retry). No iPhone sniffer needed; we captured it directly on the Android.
4. Keep usage logging on; verify after the change that off-stage packs still appear (just less frequently) and that flaky packs no longer get hammered.

---

## Appendix — handy commands used

```bash
# Passive scan for the batteries (no connecting)
bluetoothctl power on
bluetoothctl --timeout 30 scan on | grep -i C8:47:80

# HCI capture of advertising type (listen-only; needs sudo)
sudo timeout 28 btmon > /tmp/btmon.txt &
bluetoothctl --timeout 24 scan on >/dev/null
# Props bit 0 = Connectable:
awk '/Props:/{p=strtonum($2)} /Address: C8:47:80/{match($0,/C8:47:80:[0-9A-Fa-f:]+/);
  printf "%s %s\n", substr($0,RSTART,17), (p%2?"CONNECTABLE":"not-conn")}' /tmp/btmon.txt | sort -u

# Pull the telemetry DB from the phone (app should be stopped)
adb -s <phone> exec-out run-as dev.joely.bmsmon cat databases/bms.db > /tmp/bms.db
sqlite3 /tmp/bms.db "PRAGMA wal_checkpoint(TRUNCATE);"

# Per-battery last-seen / capacity (maps addresses to groups, spots a dark pack)
sqlite3 /tmp/bms.db "SELECT address, COUNT(*),
  datetime(MAX(tsMs)/1000,'unixepoch','localtime') last_seen,
  CAST(ROUND(AVG(fullChargeAh)) AS INT) ah
  FROM samples WHERE linkEvent IS NULL GROUP BY address ORDER BY ah;"

# --- Capture the OFFICIAL app's BLE behavior on the Android (definitive) ---
D=<phone-ip:port>
# 1. Stop our app so it doesn't contend / pollute the log
adb -s $D shell am force-stop dev.joely.bmsmon
# 2. Enable HCI snoop (Developer Options -> "Enable Bluetooth HCI snoop log"), then restart BT for a fresh log:
adb -s $D shell svc bluetooth disable; sleep 3; adb -s $D shell svc bluetooth enable
# 3. (optional) screen-record with timestamp overlay + capture taps
adb -s $D shell screenrecord --bugreport --time-limit 180 /sdcard/rec.mp4 &
adb -s $D shell getevent -lt /dev/input/event3 > /tmp/taps.txt &   # touchscreen = the device with ABS_MT_POSITION_X
# 4. <reproduce in the Redodo app>, then pull the snoop log via bugreport:
adb -s $D bugreport /tmp/br.zip
unzip -o /tmp/br.zip "*btsnoop*" -d /tmp/bt
L=/tmp/bt/FS/data/misc/bluetooth/logs/btsnoop_hci.log
# 5. Decode with tshark:
tshark -r "$L" -q -z io,phs                                   # protocol hierarchy (confirm ATT present)
tshark -r "$L" -t ad -Y 'bthci_evt.le_meta_subevent==0x0a or bthci_evt.code==0x05' \
  -T fields -e frame.time -e bthci_evt.bd_addr -e bthci_evt.connection_handle   # connect/disconnect timeline
tshark -r "$L" -Y 'btatt.opcode==0x12' -T fields -e btatt.value | sort | uniq -c   # exact bytes written
```
