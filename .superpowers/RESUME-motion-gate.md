# Resume: motion-gated GPS pause (option "c") — started 2026-08-06

Single source of truth for this work. Read this before doing anything; it exists so nobody
re-derives what is already settled.

---

## The decision, and why it changed

`CLAUDE.md` (see the "parked gate also switches GPS off during vehicle transit" section) records a
**2026-08-04 analysis that quantified this and chose option (a), keep 5 min**:

- Of 357.5 moving miles since 2026-07-13 the gate drops **256.5 (71.7%)**, including **205.5 of
  227.7 vehicle-speed miles (90%)**.
- Hold-length trade-off (GNSS-off duty / moving miles lost): 5 min **68.4% / 256.5** ·
  10 min 60.6% / 212.9 · 15 min 55.5% / 180.2 · 20 min 51.7% / 150.5 · 30 min 46.7% / 113.2.
- Reasoning for (a): the saving is real and the lost miles are ones the range learner discards
  anyway (no discharge ⇒ no learning).
- It explicitly flagged the revisit trigger as **"a UX judgement rather than a data one."**

**2026-08-06: the user made that judgement — build (c).** What tipped it, and what the earlier
analysis had not captured: the trade-off was reasoned about as a *learner* cost, but the practical
cost is the *map record*. Three real outings (08-04 15:00–16:15, 08-05 09:00–10:05,
08-06 09:35–10:45 — user-confirmed as vehicle trips with coffee-shop stops) are **entirely
invisible**. Each shows **0% discharge for 65–75 minutes** and returns to within 2–10 m of its
start, so the chair drew nothing from leaving to getting back. It is not "a dashed line instead of
the traced route" — the map cannot distinguish the outing from a nap at home.

Option (b) is **dead**: no hold length covers a 70-minute outing.

### Corroborating evidence (natural experiment in the user's own data)

The gate went live ~2026-08-03 21:47. Either side of that:

| | vehicle-speed fixes |
|---|---|
| **Before** (08-01, 08-03) | tracked to **71 mph**, 110 km and 66 km legs, out to **81 miles** from home |
| **After** (08-04 → 08-06) | **zero fixes above 5 m/s.** Nothing above wheelchair pace. |

Query used (prod Postgres, read-only, via `ssh joely@ddnas02` →
`docker exec bmsmon-db psql -U bmsmon -d bmsmon`): 5-minute buckets of `samples` with
`avg(lat)/avg(lon)`, `count(lat)`, and discharge share, then haversine between consecutive buckets.
Note `/web/*` endpoints are behind Authentik (302) and **cannot** be queried from the dev machine —
go via the NAS.

---

## What "c" means

Gate the parked pause on **phone motion**, not on chair discharge alone:

```
parked  =  no base discharged for 5 min  AND  phone reports not moving
```

The pure function already exists and takes the new input cleanly:

```kotlin
fun gpsShouldRun(wanted, pauseEnabled, lastDischargeMs, nowMs, phoneMoving, holdMs) =
    wanted && !(pauseEnabled && gpsParked(lastDischargeMs, nowMs, holdMs) && !phoneMoving)
```

**Key insight that makes this safe:** activity recognition only has to answer *"is the phone moving
while nothing is discharging?"* When the chair drives under its own power it **is** discharging, so
the existing signal already keeps GPS on — AR misclassifying the chair is harmless. Both error
directions land on already-accepted states: AR wrongly says STILL in a vehicle → today's behavior;
AR wrongly says moving while parked → the pre-feature behavior. **No new failure mode.**

Constraints inherited from the shipped feature (do not break these):
- `MonitorEngine` stays the **single writer** of `MonitorState.gpsActive`.
- The gate may only ever **subtract** from what the cloud settings want.
- Full stop, not balanced accuracy — coarse fixes caused the 2026-07-13 phantom map spikes.

---

## The measurement probe — status and hard-won gotchas

Module `:arprobe` on branch `experiment/ar-power-probe`, installed as `dev.joely.arprobe`
(appId 10318). Own uid, no location/BLE/network/storage/FGS, so attribution is unambiguous.

```bash
adb shell am broadcast -a dev.joely.arprobe.SUBSCRIBE          -n dev.joely.arprobe/.ControlReceiver
adb shell am broadcast -a dev.joely.arprobe.UNSUBSCRIBE        -n dev.joely.arprobe/.ControlReceiver
adb shell am broadcast -a dev.joely.arprobe.STATUS             -n dev.joely.arprobe/.ControlReceiver
adb shell am broadcast -a dev.joely.arprobe.DUMP               -n dev.joely.arprobe/.ControlReceiver
adb shell am broadcast -a dev.joely.arprobe.SUBSCRIBE_SAMPLED  -n dev.joely.arprobe/.ControlReceiver
adb shell run-as dev.joely.arprobe cat /data/data/dev.joely.arprobe/files/arprobe_log.txt
```

### Three gotchas that already cost a full day — do not rediscover them

1. **App Standby silently kills delivery.** The probe sat in standby bucket **50 (NEVER)** because
   screen pinning meant its activity could never launch, so Android classed it never-used and
   throttled it to nothing. Deliveries stopped at the exact minute of a reinstall. Fixed with
   `adb shell am set-standby-bucket dev.joely.arprobe active` (now bucket 10). **`bmsmon` sits at
   bucket 30**, so forcing the probe to a normal bucket makes it *more* representative, not less.
   Re-check the bucket after every reinstall.
2. **`adb start` cannot launch the probe's activity** while `bmsmon` is screen-pinned — it fails
   with `Error: Activity not started, unknown error code 101`. That is why everything is driven by
   broadcast.
3. **logcat rotates.** The first run lost every transition because they were only logged, never
   persisted. The probe now appends to a file, including `LIFECYCLE` markers for
   subscribe/unsubscribe — those matter, because without them "AR reported nothing" is
   indistinguishable from "the subscription had lapsed."

### Power result (settled enough)

~18 h subscribed and demonstrably delivering produced **no detectable cost**: global `sensors` rate
0.00161 → **0.00133 mAh/h** (i.e. down; both ~zero). **Report as a null result, not a measured small
number** — AR runs inside Play Services and nothing accrued to the probe's uid, and the phone was on
the charger for all but 2 minutes of the window. Consistent with negligible; not proof of a number.
`CLAUDE.md`'s "nearly free (sensors measured 0.03 mAh over 6.5 h)" is the *ambient-light* sensor
used as a proxy, not a measurement of AR.

### Efficacy — still UNANSWERED, and it is the thing that matters

Does AR report `IN_VEHICLE` **promptly enough** to keep GPS alive through a transit leg? No valid
data yet: the only window that mattered was the one App Standby had throttled. The probe is armed
and persisting as of 2026-08-06 15:20. **The next vehicle outing answers it.** Look for
`IN_VEHICLE ENTER` and how long after the vehicle actually started moving it landed.

Fallback if transitions prove too sparse: `SUBSCRIBE_SAMPLED` switches to the periodic
confidence-scored API (~30 s). Deliberately **not** the default — the plain Transition API is what
the real feature would use, so the test must stay representative.

---

## Implementation notes for "c"

- `play-services-location:21.3.0` is **already** a dependency (`app/build.gradle.kts:99-100`) — no
  new library. `material-icons-extended` is there too.
- Permission is **`android.permission.ACTIVITY_RECOGNITION`** (NOT `ACCESS_ACTIVITY_RECOGNITION`,
  which does not exist). API 29+; `minSdk` is 26, so 26–28 needs the legacy GMS-defined permission.
  `adb shell pm grant dev.joely.<pkg> android.permission.ACTIVITY_RECOGNITION` works.
- `ActivityTransitionEvent` exposes **`elapsedRealTimeNanos`** (nanos), not `elapsedRealtimeMillis`.
  Verified by decompiling the play-services jar, not from docs.
- The app has an existing sensor-wrapper pattern at `sensor/AmbientLightSensor.kt`; a `MotionSource`
  belongs alongside `location/LocationSource.kt`.
- Persistence class is **`Persisted`** (not `Prefs` — a plan once got this wrong and cost a detour).

### Device protocol — non-negotiable

The phone is the user's live wheelchair battery monitor. Three interruptions have already happened.

- **`adb install -r` only. NEVER `adb uninstall dev.joely.bmsmon`** (~400 MB of irreplaceable field
  telemetry). Uninstalling `dev.joely.arprobe` is fine.
- **`install -r` leaves the app stopped.** Always `adb shell am start -n dev.joely.bmsmon/.MainActivity`
  then confirm with `ps -A | grep bmsmon`. `monkey ... LAUNCHER` reports success but does not start it.
- **Re-derive tap coordinates from a fresh `uiautomator dump` for every tap** — a reused coordinate
  hit the monitoring toggle and stopped monitoring for 90 s.
- Serial: `adb-1C091FDF6003V0-RQzUxy._adb-tls-connect._tcp`

---

## Interim mitigation available right now

*Settings › Battery saver › Pause GPS while parked* is a user toggle. Turning it **off** restores
journey capture immediately at the cost of ~15 mA (measured 2026-08-04: the pause saves ~15 mA and
holds GPS off 68% of the time). Zero work, fully reversible. Offer this if (c) stalls.

## Also still open (unrelated, parked)

The screen-hold gate shares the charging-icon bug's blind spot: it holds the display (~139 mA)
whenever `EXTRA_PLUGGED` is nonzero, including on a connected-but-dead charger — the worst case for
holding it. Needs hysteresis so it cannot flap. See `CLAUDE.md`.

---

## UPDATE 2026-08-06 20:31 — the measurement came back, and it stops the build

**The design as specified cannot fire.** Tasks 1-3 are implemented correctly and reviewed clean;
the defect is in the spec, not the code.

**Measured** on the Pixel 6, periodic Activity Recognition, requested interval **30 s**:

```
19:11:28 · 19:23:00 · 19:32:33 · then NOTHING for 59 minutes
```

The logging build was installed at 19:32:28, so the 19:32:33 delivery is just the subscribe-time
callback. An independent second subscription (`arprobe`, sampled mode, 19:22) delivered **zero**.

**Root cause:** periodic Activity Recognition is **not a heartbeat**. Play Services suppresses
redundant updates while the detected activity is unchanged. The spec's `MOTION_STALE_MS = 150 s`
assumed a heartbeat, so in practice the cached reading is *always* stale → `confidentlyStill()` is
always false → GPS never pauses → the ~15 mA saving never materialises. Meanwhile GPS runs
continuously, so the current build is strictly worse than before on battery.

**Controller error, corrected for the record:** an earlier claim of mine — "23 broadcasts including
recent ones" — was wrong. 23 was a grep count over a history buffer that lists each broadcast about
three times, and `#504/#533/#534` are list indices, not recency indicators. Freshness was inferred
from list position. The implementer's "one delivery then silence" was correct and mine was not.

**Still unknown, and it is the hopeful reading:** whether AR responds *promptly to a genuine
activity change*. Suppression while stationary may be precisely why it is quiet, and entering a
vehicle might deliver at once. **Only a real vehicle outing tests this.**

### Options (user decision required — (i) reverses a documented spec decision)

- **(i) Switch to the Activity Transition API.** Edge-triggered, so "no event" legitimately means
  "no change" and the staleness concept disappears entirely. The spec explicitly *rejected*
  transitions on the grounds that one missed edge loses the outing — but periodic has turned out
  to be less reliable, not more, so that reasoning no longer holds.
- **(ii) Keep periodic, drop or greatly lengthen staleness.** Simplest change, but a dead
  subscription then becomes indistinguishable from "still stationary", which fails **closed** —
  GPS pauses during transit. That is the exact behaviour the user rejected when choosing fail-open.
- **(iii) Both** — transitions for edges, periodic as a slow sanity check that the subscription is
  alive. Most robust, most work.
- **(iv) Abandon (c).** The existing *Settings › Battery saver › Pause GPS while parked* toggle
  already lets the user trade the saving for journey capture, with zero further work.

### State

Branch `feat/motion-gated-gps` at `4c2c29b`, 371 tests green, lint 0 errors. Tasks 1-3 complete and
reviewed; **Tasks 4-6 deliberately not started.** `main` is untouched. The phone is running the
branch build — functional and fail-open (GPS stays on), so no safety issue, just no saving.

## UPDATE 2026-08-07 13:20 — periodic AR is definitively dead; transitions were never fairly tested

**Today's telemetry (last 18 h, prod DB):** 216 of 217 five-minute buckets carry GPS (the broken
gate never closes, so GNSS runs continuously). **Peak speed 2.06 m/s = 5 mph** — wheelchair pace.
No vehicle-speed fix at all. The two real movements were chair trips under their own power:

```
09:35 -> 09:40   617 m   2.06 m/s   discharge 33->40%
10:20 -> 10:25   590 m   1.97 m/s   discharge 45->50%
```

**Motion broadcasts delivered today: exactly three — 12:11:43, 12:14:45, 12:34:54.** None during
either movement window. So on this device the periodic API delivers a handful of updates per day at
moments unrelated to actual motion.

**Option (ii) — "keep periodic, drop the staleness check" — is now DEAD.** Removing staleness does
not help when the readings themselves arrive ~3x/day and do not track movement. The problem is not
the freshness rule; it is that the signal has no relationship to the thing being sensed.

**Correction to the 2026-08-06 write-up: option (i) (Activity TRANSITIONS) has never been fairly
tested, and must not be written off with periodic.** Timeline of why:
  - 08-05 13:39 → 08-06 15:20: arprobe subscribed to transitions but sat in standby bucket 50
    (NEVER) — throttled silent, so that whole window is void.
  - 08-06 15:20 → 19:22: bucket fixed to 10 and transitions re-armed, but the user was stationary
    at a desk the whole time. No transitions were *expected*; STILL→STILL has no edge.
  - 08-06 19:22 → 08-07 13:18: switched to SAMPLED for the confidence-value experiment, so
    transitions were not even subscribed during this morning's trip.
  Net: transitions have had **zero** hours of fair exposure to a real vehicle trip.

**Action taken 2026-08-07 13:18:** arprobe returned to TRANSITIONS mode, bucket confirmed 10,
`SUBSCRIBE SUCCEEDED`. The next genuine vehicle trip is now a fair test of option (i).

**Open question put to the user:** whether this morning's "drive for coffee" was the chair or a
vehicle. The data reads unambiguously as a chair trip (5 mph, packs discharging), which would mean
it does not test the transit case at all — the chair discharging keeps GPS on via the existing
signal regardless.

---

## MERGED 2026-08-07 — `a881664` on `main`, pushed

Shipped after a final whole-branch review found three Important defects the per-task reviews could
not see. 378 unit tests / 0 failures, lint 0 errors, server suite 188 passed — all re-run on the
merged result.

**What the final review caught (all fixed in `ed05a04`):**
1. **The N=3 debounce behaved as N=1.** `applyGpsGate` folds per BLE poll (~115/min across 8 packs),
   not per reading (~10/min), so one cached reading was folded ~11 times. Fixed by deduping on
   `MotionGate.lastConfidentAtMs`.
2. **The motion permission dialog was silently dropped.** Two `launch()` calls in one frame — the
   platform refuses concurrent permission requests, so the motion one was cancelled with no dialog.
   On any install with notifications still pending, the feature was dead. Fixed with a single
   `RequestMultiplePermissions`.
3. **Uncertain readings postponed fail-open indefinitely** — `UNKNOWN@41` both held the verdict and
   reset the deadline. Fixed by keying the deadline off the last *confident* reading.

## STILL OWED — do these before trusting the feature

1. **A real vehicle outing** (user planned for the morning of 2026-08-08). This is the actual proof
   that GNSS stays on in transit. Check for GPS fixes above 5 m/s in the journey data — that metric
   read **zero** from 2026-08-04 to 08-06 and is the whole point. Query prod read-only via
   `ssh joely@ddnas02` → `docker exec bmsmon-db psql -U bmsmon -d bmsmon`; `/web/*` is behind
   Authentik (302) and unreachable from the dev machine.
   **Use 1-minute buckets, not 5.** Five-minute bucket centroids averaged a real 24 mph trip down to
   walking pace and produced a badly wrong conclusion on 2026-08-07.
2. **The settings line's two permission states** — confirm it reads "Motion sensing active" with the
   permission granted and the unavailable variant after `pm revoke`, then **re-grant**. Never
   verified; the phone was at its OS PIN lock.
3. **AR's power cost**, with a live revert condition: if it exceeds the ~15 mA the pause saves, this
   feature is a net loss and should be reverted. Per-uid attribution will not settle it (AR runs
   inside Play Services) — compare total phone drain across comparable days.

## Known limitation, recorded not hidden

The saving is **partial, not full**. Play Services delivers in bursts with multi-minute gaps, so
`MOTION_STALE_MS` (150 s) trips and the gate cycles — measured one unbroken 5m18s hold, then
cycling. Lengthening the staleness window is an **open, undecided** tuning question; it is only safe
if AR reliably emits a confident non-STILL when motion starts, which is untested. Do not tune it
before the vehicle outing, and note that the fix wave changed the duty cycle any tuning would rest on.

## The phone

Left running the **pre-merge branch build**, and at its **OS PIN lock screen** (an agent's
`input swipe` was read as a notification-shade drag, toggled airplane mode, and the screen timed
out; monitoring itself never dropped and airplane mode was restored). It needs `main`'s build
installed — `adb install -r` then `am start`, **never** `adb uninstall`.

## Device topology (documented in CLAUDE.md 2026-08-08, read it before touching radios)

The Pixel is a **dedicated telemetry device**, not the user's phone — their daily phone is an
iPhone. It is Wi-Fi only: home Wi-Fi at home, **iPhone hotspot** on the road, which is how OTA
telemetry uploads away from home. Cellular is deliberately off because the SIM reads
`OUT_OF_SERVICE` and the modem burns ~28 mA hunting (299 mAh over one 12.5 h night).
**Airplane mode ON, Wi-Fi re-enabled on top, is the correct steady state.**

**⚠ Airplane mode also switches Bluetooth off, which kills BLE monitoring.** Restore both and
verify: Wi-Fi associated, BLE reconnected to all 8 packs, telemetry uploading. Never toggle radios
just before the user leaves the house.

**RESTORED 2026-08-08 16:07**, after the vehicle outing rather than before it. Verified end to end:
airplane_mode 1, modem `POWER_OFF`, Wi-Fi reassociated (IP 192.168.0.16, RSSI -54), Bluetooth
`State: ON`, and — the check that actually matters — **8 packs reporting to the server**, up from a
baseline of 7. Monitoring never dropped. Verify BLE by packs reaching the server, not by the adapter
flag; the flag can read ON while nothing has reconnected.
