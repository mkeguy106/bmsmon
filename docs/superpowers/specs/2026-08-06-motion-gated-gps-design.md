# Motion-Gated GPS Pause — Design

**Date:** 2026-08-06
**Status:** Approved by user (design presented in conversation; "add the settings line, looks right").

## Goal

Stop the parked-GPS pause from switching GNSS off during vehicle transit, by requiring **phone
stillness** as well as chair non-discharge before pausing.

## Why this is being built, given it was already decided against

`CLAUDE.md` records a **2026-08-04 analysis that quantified this trade-off and chose option (a),
keep the 5-minute hold**. That analysis stands and its numbers are not in dispute:

- Of 357.5 moving miles since 2026-07-13 the gate drops **256.5 (71.7%)**, including **205.5 of
  227.7 vehicle-speed miles (90%)**.
- Hold-length trade-off (GNSS-off duty / moving miles lost): 5 min **68.4% / 256.5** ·
  10 min 60.6% / 212.9 · 15 min 55.5% / 180.2 · 20 min 51.7% / 150.5 · 30 min 46.7% / 113.2.
- It reasoned the lost miles were acceptable because the **range learner discards them anyway**
  (no discharge ⇒ no learning), and explicitly flagged the revisit trigger as **"a UX judgement
  rather than a data one."**

**2026-08-06: the user made that judgement.** What tipped it is a cost the earlier analysis did not
weigh, because it framed the trade-off as a *learner* cost when the practical cost is the *map
record*. Three user-confirmed vehicle outings — 08-04 15:00–16:15, 08-05 09:00–10:05,
08-06 09:35–10:45 — are **entirely invisible**, destinations included. Each shows **0% discharge
for 65–75 minutes** and returns to within 2–10 m of its start, because the chair drew nothing from
leaving to getting back (vehicle transit plus coffee-shop stops). It is not "a dashed line instead
of the traced route" — the Journey map cannot distinguish the outing from a nap at home.

Corroborating natural experiment in production data (gate went live ~2026-08-03 21:47):

| | vehicle-speed GPS fixes |
|---|---|
| **Before** (08-01, 08-03) | tracked to **71 mph**, 110 km and 66 km legs, out to **81 miles** from home |
| **After** (08-04 → 08-06) | **zero fixes above 5 m/s** — nothing above wheelchair pace |

**Option (b) — lengthening `PARKED_HOLD_MS` — is dead.** No hold length covers a 70-minute outing.

## The design

### Core rule

```
pause GPS  =  no base discharged for 5 min  AND  the phone is confidently still
```

Today it is only the first clause. The second is the whole change.

**Why misclassification is safe.** Activity recognition only has to answer *"is the phone moving
while nothing is discharging?"* When the chair drives under its own power it **is** discharging, so
the existing signal already keeps GPS on — AR misreading the chair itself is harmless, that branch
is already covered. Both error directions land on already-accepted states:

| AR is wrong | Result |
|---|---|
| Reports still while actually in a vehicle | today's behavior — no regression |
| Reports moving while actually parked | the pre-feature behavior — saving lost, nothing broken |

No new failure mode is introduced.

### 1 · `motion/MotionSource.kt`

New, sibling to `location/LocationSource.kt` and following its shape: `start()`/`stop()`, holds the
latest reading, no Android types leaking upward. Wraps Play Services **periodic** Activity
Recognition (`requestActivityUpdates`) at ~30 s.

```kotlin
data class MotionReading(val still: Boolean, val confidence: Int, val atMs: Long)
```

**How the reading is derived from the API result, precisely.** `ActivityRecognitionResult` carries a
list of `DetectedActivity`, each with a type and a confidence 0–100 that sum across the list. Map it
as:

- `still` = the result's **most probable** activity is `DetectedActivity.STILL`
- `confidence` = that STILL entry's confidence
- `atMs` = wall-clock time the reading was received (`System.currentTimeMillis()`), since it is
  compared against `nowMs` from the same clock

Any other most-probable activity — `IN_VEHICLE`, `WALKING`, `ON_FOOT`, `ON_BICYCLE`, `TILTING`, or
`UNKNOWN` — yields `still = false`, which keeps GPS on. `UNKNOWN` deliberately falls on the
GPS-stays-on side: not knowing is not the same as knowing it is still.

`com.google.android.gms:play-services-location:21.3.0` is **already** a dependency
(`app/build.gradle.kts:100`) — no new library.

**Periodic, not the Activity Transition API.** Transitions are cheaper and event-driven, but they
hinge on catching a single edge: one missed or late `ENTER IN_VEHICLE` loses the outing, which is
precisely the failure being fixed, and a silently lapsed subscription is indistinguishable from
"never moved". Periodic updates re-assert current state every cycle, so a missed sample
self-corrects on the next one and silence is detectable.

### 2 · The gate — one new input to an existing pure function

```kotlin
fun gpsShouldRun(
    wanted: Boolean, pauseEnabled: Boolean,
    lastDischargeMs: Long?, nowMs: Long,
    confidentlyStill: Boolean,
    holdMs: Long = PARKED_HOLD_MS,
): Boolean = wanted && !(pauseEnabled && gpsParked(lastDischargeMs, nowMs, holdMs) && confidentlyStill)
```

Still pure and total, still unit-testable on the JVM, and still **only ever subtracts** from
`wanted` — the invariant reviewers checked repeatedly on the original feature. `MonitorEngine`
remains the single writer of `MonitorState.gpsActive`; `applyGpsGate` remains the one call site.

### 3 · `confidentlyStill()` — the fail-open decision, in one expression

```kotlin
const val STILL_CONFIDENCE_MIN = 75
const val MOTION_STALE_MS = 150_000L      // 5 missed polls at 30 s

fun confidentlyStill(reading: MotionReading?, nowMs: Long): Boolean =
    reading != null &&
    reading.still &&
    reading.confidence >= STILL_CONFIDENCE_MIN &&
    nowMs - reading.atMs <= MOTION_STALE_MS
```

**Every "no usable signal" path returns `false`, and false means GPS stays on** — permission denied,
AR unavailable on the device, subscription lapsed, process restarted with no reading yet, or updates
gone stale. This is the user's explicit choice: never lose an outing, even at the cost of the
saving. Keeping it as a single expression is deliberate, so the fail-open property cannot drift as
call sites are added.

`MOTION_STALE_MS` is 5 missed 30-second polls. Long enough not to trip on ordinary scheduling
jitter or Doze batching; short enough that a genuinely dead signal is noticed within about two and
a half minutes.

### 4 · Permission

`android.permission.ACTIVITY_RECOGNITION` (API 29+; **not** `ACCESS_ACTIVITY_RECOGNITION`, which
does not exist). `minSdk` is 26, so API 26–28 needs the legacy GMS-defined permission name; on those
levels a missing grant simply yields no readings, which fails open exactly like every other unusable
signal.

Requested **opportunistically** alongside the existing `POST_NOTIFICATIONS` request in `ui/App.kt`,
following that established pattern. It must **never gate monitoring** — BLE monitoring is the app's
core function and is unrelated to this.

### 5 · Settings visibility (added at user request)

A **read-only** line in `Settings › Battery saver`, under the "Pause GPS while parked" toggle:

- motion signal usable → `motion sensing active`
- otherwise → `motion sensing unavailable — GPS won't pause`

This changes no behavior. It exists because the chosen fallback means **a denied permission silently
disables the battery saving while the toggle still reads as on** — the same shape as the
2026-08-06 charging-icon bug, where the UI asserted something reality contradicted. Making the mode
visible is the cheap fix for that class of problem.

## Testing

Pure functions, JVM unit tests, following `PowerPolicyTest.kt` / `BatterySaverTest.kt`:

- `confidentlyStill()` — null reading → false; not-still → false; still but below
  `STILL_CONFIDENCE_MIN` → false; still and confident but older than `MOTION_STALE_MS` → false;
  still, confident, fresh → true; **exactly at** the confidence threshold → true (`>=`) and
  **exactly at** the staleness bound → true (`<=`), matching the project's convention that a
  threshold fires *at* its value.
- `gpsShouldRun()` — the existing cases still hold, plus: parked **and** confidently still → paused;
  parked but **not** confidently still → GPS on (the transit case); not parked → GPS on regardless
  of stillness; and `wanted = false` → never on, for every combination of the other inputs.

The `MotionSource` wrapper itself and the window/permission plumbing are not unit-testable and are
verified on-device.

## On-device verification

1. Stationary at home, permission granted → after 5 min of no discharge, the foreground-service type
   drops to `0x00000010` (GPS paused) exactly as today.
2. **A real vehicle outing** → GPS must remain active throughout; check that
   `types=0x00000018` persists and that fixes above 5 m/s appear in the journey data, which is the
   metric that has read **zero** since 2026-08-03.
3. Permission revoked via `adb shell pm revoke` → GPS never pauses, and the Settings line reads
   `motion sensing unavailable`.

## Known risk, and the check that resolves it

**AR's real power cost is unmeasured.** The 2026-08-04 note calling it "nearly free (sensors
measured 0.03 mAh over 6.5 h)" is the *ambient-light* sensor used as a proxy, not a measurement of
AR. A dedicated probe (`:arprobe`, branch `experiment/ar-power-probe`) ran ~18 h and produced **no
detectable cost** — global `sensors` rate 0.00161 → 0.00133 mAh/h — but that is a **null result, not
a measured number**: AR executes inside Play Services and nothing accrued to the probe's uid, and
the phone was on the charger for all but 2 minutes of the window.

If periodic AR at 30 s costs more than the ~15 mA the pause saves, **this feature is a net loss and
should be reverted.** Per-uid attribution will not settle it, so the post-ship check is a comparison
of total phone drain across comparable days from `batterystats` history. This is an explicit
accept-and-verify, not an assumption.

## Out of scope

- **Activity Transition API** — rejected above; revisit only if periodic polling measures too
  expensive, in which case the trade is robustness for power.
- **A separate "use motion sensing" toggle** — YAGNI. It is part of "Pause GPS while parked"; a
  second toggle adds a state with no distinct use.
- **Lengthening `PARKED_HOLD_MS`** — quantified and dead (no hold covers a 70-minute outing).
- **Suppressing the pause while a share link is live** — rejected 2026-08-04; the cloud channel is
  deliberately one-way phone→server and this would invert that architecture.
- **The screen-hold gate's matching blind spot** (holds the display on a connected-but-dead
  charger). Real, documented in `CLAUDE.md`, and unrelated to this change — it needs hysteresis of
  its own.
