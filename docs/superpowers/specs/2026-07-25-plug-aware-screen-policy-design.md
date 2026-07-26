# Plug-aware screen policy + monitoring wakelock

**Date:** 2026-07-25
**Status:** design, approved for planning

## Problem

The Android app holds the display on unconditionally whenever it is open. `ui/App.kt:82`:

```kotlin
val keepOn = state.keepScreenOn || state.locked
```

`keepScreenOn` defaults to `true` (`SettingsStore.kt:155`) and `locked` independently forces the
same flag. There is **no phone power-source detection anywhere in the app** — a grep for
`BatteryManager`, `ACTION_BATTERY_CHANGED`, `BATTERY_PLUGGED`, `POWER_CONNECTED` returns only BMS
pack-state hits, never the phone's own charger. The intended design was that the screen is held
only while on external power; it was never implemented.

### Measured impact

From `dumpsys batterystats` on the Pixel 6 (`u0a304`), over 4 h 14 m of uptime with the screen on
~100% of it. The app was the single largest consumer at 600 mAh:

| Source | Drain | Rate |
|---|---|---|
| **screen** | 545 mAh | ~136 mAh/h |
| cpu | 129 mAh | ~31 mAh/h |
| mobile_radio | 108 mAh | ~26 mAh/h |
| wifi | 95.7 mAh | ~23 mAh/h |
| **gnss** (4 h 2 m) | 87.5 mAh | ~22 mAh/h |
| **bluetooth** | 6.62 mAh | ~1.6 mAh/h |

The screen costs roughly 6× the GPS and 80× the Bluetooth. The core function — polling 8 packs over
BLE — is very nearly free. The display is the entire problem.

### The hidden dependency

The poll loop is `delay(pollMs)` on a coroutine (`ble/BmsRepository.kt:396`), and the app holds
**no `PARTIAL_WAKE_LOCK`** — the grep over `monitor/` finds only FGS type constants. Coroutine
`delay()` is backed by a scheduled executor, not `AlarmManager`, so it does not fire while the CPU
is suspended. Today the only thing keeping the CPU awake is `FLAG_KEEP_SCREEN_ON` as a side effect.

**Turning the screen off without adding a wakelock would silently degrade poll cadence, alert
latency, and GPS capture.** Fixing the screen policy therefore *requires* the wakelock; they are one
change, not two.

### The low-battery trap

Field-observed: at very low charge, holding the screen on while plugged in draws enough that the
phone cannot climb back up, and it enters a shutdown/reboot loop at 0%. So "on external power" alone
is not a sufficient condition — the policy needs a low-battery floor with hysteresis.

## Approach

A single `PowerMonitor` owned by `MonitorEngine` (process-lifetime), feeding a **pure**
`PowerPolicy`. Rejected alternatives: ViewModel ownership (the engine outlives the ViewModel, so GPS
priority would lose its driver whenever the Activity dies — the exact failure the `MonitorEngine`
refactor fixed), and independent observers in UI and engine (duplicates the hysteresis latch, which
then drifts).

This follows patterns already in the codebase: the engine already owns `LocationSource`
(`MonitorEngine.kt:129`), already publishes a `MonitorState` StateFlow, and already registers a
`BroadcastReceiver` for Bluetooth adapter state (`MonitorEngine.kt:156`).

## Components

### `power/PowerMonitor.kt` (new, Android-coupled)

Registers a receiver for `ACTION_BATTERY_CHANGED` and emits:

```kotlin
data class PowerStatus(val onExternal: Boolean, val levelPct: Int)
```

- `onExternal` = `EXTRA_PLUGGED` is any of `BATTERY_PLUGGED_AC`, `_USB`, `_WIRELESS` (all three
  count — wall charger, USB from the chair, and Qi/MagSafe-style pads alike).
- `levelPct` = `EXTRA_LEVEL * 100 / EXTRA_SCALE`.

`ACTION_BATTERY_CHANGED` is sticky, so the initial `registerReceiver` returns current state with no
polling. Registered on engine start, unregistered on engine stop, mirroring the Bluetooth receiver.

### `model/PowerPolicy.kt` (new, pure — no Android imports)

```kotlin
const val LOW_ENTER_PCT = 5
const val LOW_EXIT_PCT = 15

data class PowerDecision(val holdScreen: Boolean, val gpsBalanced: Boolean, val lowPower: Boolean)

fun powerDecision(onExternal: Boolean, levelPct: Int, wasLowPower: Boolean): PowerDecision
```

The `lowPower` latch:

- **sets** when `levelPct < LOW_ENTER_PCT` (5)
- **clears** when `levelPct >= LOW_EXIT_PCT` (15)
- **holds** its previous value in between — this is the hysteresis, and it is why the latch is both
  an input and an output

Outputs, both driven off that one latch:

- `holdScreen = onExternal && !lowPower`
- `gpsBalanced = lowPower`

The function is total and deterministic: same `(onExternal, levelPct, wasLowPower)` always yields
the same decision. No clock, no Android types.

### `MonitorEngine` changes

- Own a `PowerMonitor`; fold each `PowerStatus` through `powerDecision(...)` carrying the previous
  latch from `MonitorState`.
- Add `holdScreen`, `gpsBalanced`, `lowPower` to `MonitorState`.
- Apply `gpsBalanced` to `LocationSource` (see below).
- Single-writer discipline, as with `etaFullMin` and `range`.

### `location/LocationSource.kt` changes

Add `setBalanced(balanced: Boolean)`. When the flag changes while a request is active, remove and
re-issue the request:

- normal: `Priority.PRIORITY_HIGH_ACCURACY`, interval `5_000`, min `2_000` (unchanged)
- low power: `Priority.PRIORITY_BALANCED_POWER_ACCURACY`, interval `20_000`, min `10_000`

Update the `2026-07-13` comment at `LocationSource.kt:48` — its premise ("rides the chair on
constant USB power, so there is no battery reason to accept coarse fixes") is now conditional, and
the new comment must say coarse fixes are accepted **only** in the sub-5% emergency window.

### `monitor/MonitoringService.kt` changes

Acquire a `PARTIAL_WAKE_LOCK` (tag `bmsmon:monitoring`) on the foreground path of
`onStartCommand`, release it in `stopCleanly()`. Acquired without timeout — its lifetime is exactly
the monitoring session, and every existing teardown path (notification Stop, `onTaskRemoved`,
`!monitoring` from the collector) already routes through `stopCleanly()`. Release must be
idempotent (`if (wl.isHeld) wl.release()`).

Requires `android.permission.WAKE_LOCK` in the manifest.

### `BatteryViewModel` / `ui/App.kt` changes

Mirror `holdScreen` into `UiState` (alongside the existing `keepScreenOn`). `App.kt:82` becomes:

```kotlin
val keepOn = state.screenHoldAllowed && (state.keepScreenOn || state.locked)
```

The power gate wraps the **whole** expression, so lock mode is gated too — unplugging always lets
the display sleep. Existing `DisposableEffect` and its `onDispose` clear are unchanged.

## Data flow

```
ACTION_BATTERY_CHANGED
  → PowerMonitor → PowerStatus(onExternal, levelPct)
    → MonitorEngine folds with previous latch
      → powerDecision(...) [pure]
        ├→ MonitorState.holdScreen  → UiState.screenHoldAllowed → App.kt FLAG_KEEP_SCREEN_ON
        └→ MonitorState.gpsBalanced → LocationSource.setBalanced()
```

## Behavior matrix

| Plugged | Level | Latch | Screen held | GPS |
|---|---|---|---|---|
| yes | ≥15% | clear (forced) | yes (if setting or lock on) | high accuracy 5 s |
| yes | 5–14% | clear | yes | high accuracy 5 s |
| yes | 5–14% | set | **no** | **balanced 20 s** |
| yes | <5% | set (forced) | **no** | **balanced 20 s** |
| no | any | clear | **no** | high accuracy 5 s |
| no | any | set | **no** | **balanced 20 s** |

"Forced" means the level alone determines the latch at that boundary; in the 5–14% band the latch
retains whatever it already held.

Monitoring, BLE polling, alerts, logging and cloud upload are unaffected in every row — the
wakelock keeps cadence identical with the screen dark.

## Error handling

- **Missing/absent battery extras** (`EXTRA_SCALE` ≤ 0): treat as `onExternal = false`,
  `levelPct = 100`. Fails safe — screen not held, GPS stays high accuracy, no false low-power state.
- **Receiver not yet fired:** `MonitorState` defaults to `holdScreen = false`. The display may sleep
  for the fraction of a second before the sticky broadcast lands, rather than latching on wrongly.
- **Wakelock acquire throws** (`SecurityException`, missing permission): log and continue. Monitoring
  still runs; cadence degrades to today's screen-dependent behavior rather than crashing the service.
- **Monitoring off entirely:** no engine, no `PowerMonitor`, no wakelock, no screen hold.

## Testing

`PowerPolicyTest.kt` in `app/src/test/java/dev/joely/bmsmon/` (flat, alongside `AlertLogicTest`,
`PollPolicyTest`, `LockHoldTest`). Pure JVM, no instrumentation:

- unplugged at any level → `holdScreen = false`
- plugged at 50% → `holdScreen = true`, `gpsBalanced = false`
- **latch entry:** plugged, level walks 20 → 4, latch sets, `holdScreen` flips false at 4 and not before
- **latch does not clear early:** from set, level walks 4 → 14, latch stays set the whole way
- **latch exit:** level reaches 15, latch clears, `holdScreen` returns true
- **no flapping:** oscillating 5 ↔ 14 with the latch set produces zero transitions
- `gpsBalanced` tracks `lowPower` exactly, in both directions
- degenerate input (`scale <= 0` path) fails safe

Manual verification on the Pixel 6, which is presently at 4% and charging — the exact latch-set
state:

1. With monitoring on and phone plugged above 15%, confirm the screen holds.
2. Unplug: screen times out; confirm via `adb shell dumpsys batterystats` that BLE polling continues
   at cadence and telemetry rows keep landing.
3. Re-plug below 5%: screen must stay off until the phone crosses 15%.

## Out of scope

Deliberately not included:

- **Phone-level settings** (30-min screen timeout, 90 Hz refresh rate, `wifi_scan_always_enabled`,
  `ble_scan_always_enabled`). These are device settings, not app behavior, and are the user's call
  separately.
- **Dark mode as a power measure.** `appearance = Auto` already switches on lux; forcing dark would
  change look for a secondary saving.
- **Doze whitelist / battery-optimization exemption.** Would not save power; it only removes system
  restrictions the foreground service is already exempt from.
- **Reducing BLE poll rate.** Bluetooth is ~1.6 mAh/h; there is nothing meaningful to win, and
  `STAGE_POLL_MS = 1500` deliberately mirrors the official Redodo app.
