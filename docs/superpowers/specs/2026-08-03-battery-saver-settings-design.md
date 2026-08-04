# In-App Battery Saver Settings — Design

**Date:** 2026-08-03
**Status:** Approved by user (design presented in conversation; "all 3, but add dim screen while
locked default disabled. 1 and 3 default on." → "go").

## Goal

Give the app its own **Battery saver** settings section, so the phone can cut its own drain without
relying on Android's system Battery Saver (which, measured on this device, does almost nothing for
our configuration — see "Why not Android's Battery Saver" below).

Three toggles, all measured before being designed:

1. **Lower refresh rate on lock** — default **ON**
2. **Dim screen while locked** — default **OFF**
3. **Pause GPS while parked** — default **ON**

## What was measured first (2026-08-03, Pixel 6, wireless charger)

The phone was found net-**discharging at −174 mA while sitting on its wireless charger** at 11%
SOC, ~3 h from dead. That investigation produced the numbers this design rests on.

### Per-subsystem drain (`dumpsys batterystats`, 6 h 33 m session, 2 580 mAh)

| Subsystem | mAh | ≈ mA | In scope? |
|---|---|---|---|
| screen | 908 | **139** | yes — items 1 & 2 |
| cpu | 275 | 42 | no direct lever |
| mobile_radio | 186 | 28,5 | fixed out-of-band (airplane mode) |
| gnss | 143 | **22** | yes — item 3 |
| wifi | 108 | 16,5 | no |
| GPU | 57,3 | 8,8 | no |
| bluetooth | 19,3 | **3** | **deliberately excluded** |
| TPU | 18,4 | 2,8 | no |

**Bluetooth is explicitly out of scope.** Slowing the BLE poll cadence is the intuitive lever and
it is worth ~3 mA — 1,7% of drain. It would degrade monitoring to save nothing.

### Refresh-rate experiment

Measured as net battery current on the charger, with pad input tracked separately so the thermal
feedback loop doesn't get miscredited to the refresh rate:

| Setting | `current_avg` | sample mean | sample median | pad input |
|---|---|---|---|---|
| 90 Hz (default) | +7,2 mA | — | — | 237 mA |
| **60 Hz** | **+35,9 mA** | +42,3 | +45,8 | 248 mA |
| 30 Hz | +26,6 mA | +31,6 | +33,1 | 249 mA |

- **90 → 60 Hz is worth ~18 mA.** The raw net delta is 28,7 mA, but 11 mA of that is the charging
  pad opening up as the battery cooled (237 → 248 mA), so the load reduction is ~18 mA.
- **60 → 30 Hz is worth nothing.** It measured slightly *worse*, with pad input and temperature
  flat. Individual samples swing −228…+125 mA so this is within noise, but there is no gain to
  find. Most likely Android's idle frame-rate override was already dropping the render rate —
  the stage only updates every 1,5 s, so nearly all frames are idle. Capping the *peak* is what
  stops the 90 Hz bursts during touch and redraw, and that is where the 18 mA lives.

30 Hz *is* achievable on this panel (`renderFrameRate 30.0` while `modeId` stays 1, i.e. a
frame-rate override inside the 60 Hz mode, not a mode switch). It is rejected for lack of benefit,
not lack of capability — which also spares us a `compileSdk` bump to 35 for
`View.setRequestedFrameRate` and any fragile `SurfaceControl` work.

### GNSS quality while parked indoors

| Metric | Value |
|---|---|
| Location failure rate | **53,79%** |
| Satellites / mean C/N₀ | 4 / 24 dB-Hz |
| Last fix accuracy | 39 m |
| TTFF mean | 292 s |

22 mA is being spent to produce fixes the range learner is designed to reject.

## 1 · Lower refresh rate on lock (default ON)

Pure function, unit-tested:

```kotlin
/** Preferred display refresh rate while locked; 0f means "system default". */
fun lockRefreshRate(locked: Boolean, enabled: Boolean): Float =
    if (locked && enabled) 60f else 0f
```

Applied in `ui/App.kt` in a `DisposableEffect` beside the existing `FLAG_KEEP_SCREEN_ON` block:

```kotlin
val rate = lockRefreshRate(state.locked, state.lockLowRefresh)
DisposableEffect(window, rate) {
    window?.let { it.attributes = it.attributes.apply { preferredRefreshRate = rate } }
    onDispose { window?.let { it.attributes = it.attributes.apply { preferredRefreshRate = 0f } } }
}
```

`WindowManager.LayoutParams.preferredRefreshRate` is API 21, needs no permission, is scoped to our
window, and reverts automatically if the process dies — unlike the global `peak_refresh_rate`
setting, which would leak our preference into the whole system.

**Not gated on `screenHoldAllowed`.** The plug-aware power latch exists to stop the screen being
*held on*; a lower refresh rate is strictly a saving in every power state, so gating it would only
ever cost battery.

## 2 · Dim screen while locked (default OFF)

Default OFF **by explicit user decision**: the point of the app is to show battery state clearly at
a glance in the real world, and dimming works against that. This is an opt-in for users who want it.

- `window.attributes.screenBrightness`, a per-window float in `0f..1f`
  (`BRIGHTNESS_OVERRIDE_NONE = -1f` to release). Per-window and self-reverting — it never writes the
  system brightness setting.
- **A slider, not a fixed level**, stored as `lockDimLevel: Float` (default `0.30f`), shown only
  when the toggle is on. A fixed guess would almost certainly be wrong against real daylight; the
  user tunes it against the conditions they actually ride in.

```kotlin
/** Window brightness override while locked; -1f releases to the user's system brightness. */
fun lockBrightness(locked: Boolean, enabled: Boolean, level: Float): Float =
    if (locked && enabled) level.coerceIn(0.05f, 1f) else -1f
```

The `0.05f` floor prevents a slider at zero producing a black, unreadable screen — a real hazard
for a wheelchair-mounted display.

## 3 · Pause GPS while parked (default ON)

The chair cannot move without discharging a pack. GPS movement without discharge therefore teaches
the range learner nothing — this is the same discharge-gate reasoning already used in
`RangeLearn`/`cleanTrack`. So while no pack is discharging, GNSS is pure cost.

```kotlin
/** True when no pack has discharged recently — the chair is parked and GPS teaches nothing. */
fun gpsParked(lastDischargeMs: Long?, nowMs: Long, holdMs: Long = PARKED_HOLD_MS): Boolean =
    lastDischargeMs == null || nowMs - lastDischargeMs >= holdMs

const val PARKED_HOLD_MS = 5 * 60_000L

/** A pack is discharging when it draws more than the BMS's reporting deadband allows to be noise. */
const val DISCHARGE_EPS_A = 0.1
```

- `MonitorEngine`'s effective GPS-active gains `&& !parked`; the engine stays the single writer.
- `lastDischargeMs` updates from the poll loop whenever any pack's current is a discharge — i.e.
  `current <= -DISCHARGE_EPS_A` (0,1 A), the same epsilon the regen detector and the server's
  `resolve_active_group()` already use. The BMS's ~1,04 A reporting deadband (idle reads exactly
  0,000 A) makes this unambiguous: any epsilon in (0, 1,04) is equivalent, so 0,1 A yields neither
  false positives nor misses.
- Resume is immediate on the first discharging sample.

**Full stop, not a drop to balanced accuracy.** The alternative — dropping to
`PRIORITY_BALANCED_POWER_ACCURACY` while parked — keeps a warm fix and cheaper reacquisition, but
feeds the pipeline exactly the coarse fixes that caused the 2026-07-13 phantom map spikes. Since
the discharge gate already discards parked-time data, those fixes would be preserved only to be
thrown away, while still being uploaded and still drawn on the Journey map.

**Known accepted cost:** reacquisition. TTFF measured 292 s indoors, so the first fixes of an outing
may be missed, slightly under-counting that day's miles. Accepted because (a) the learner's outing
gate is 0,5 mi, well above the error a lost first minute introduces, and (b) outdoor TTFF is far
better than the indoor figure that produced 292 s.

## 4 · Read-only database size row

`Settings › Battery saver` gets a non-interactive row showing the local DB's size and row count.
Diagnostic visibility only — no new pruning behavior.

### Why no new pruning was built

The user asked for database pruning. Investigation found it already exists and is working:

- `SAMPLE_RETENTION_DAYS = 14` and `RAW_FRAME_RETENTION_DAYS = 7` + `RAW_FRAME_MAX_BYTES = 20 MB`,
  applied by `TelemetryRepository.prune()`, invoked from `maybePrune()` every 200 inserts.
- **The SQLite header shows freelist = 0 pages** (page size 4096, 103 389 pages = 423,5 MB). There
  is no reclaimable space; `VACUUM` would free nothing. The file is at steady-state high-water mark,
  reusing pages, not growing unbounded.
- 405 MB of databases against **212 GB free** (`/data` 8% used) — 0,2% of the phone.
- Shortening retention would actively harm the product: `RangeLearn` reads the **14-day** window and
  needs `MIN_LEARN_DAYS = 3`, and the Wh/mile band is still converging off seed.

A prior design already covers this ground: `docs/superpowers/specs/2026-07-22-db-retention-strategy-design.md`.

## Why not Android's Battery Saver

Reading the device's actual policy (`dumpsys power`, Android 17 / SDK 37) rather than the generic
feature list, most of its levers are already pulled or do not apply to this app:

| Policy flag | Effect here |
|---|---|
| `disable_aod=true` | AOD already off — nothing |
| `enable_night_mode=true` | Dark theme already on — nothing |
| `enable_brightness_adjustment=false` | **Does not dim** — the `adjust_brightness_factor=0.5` is inert |
| `enable_quick_doze=true` | Only fires with the screen off; we hold it on — nothing |
| `force_all_apps_standby` / `force_background_check` / `enable_firewall` | Real, but throttles *other* apps; our foreground service is exempt |
| `location_mode=3` | **Foreground-only location** — actively breaks backgrounded GPS capture |

It also carries **no refresh-rate flag at all**, and the display reports
`lowPowerSupportedModes=[]` — so it cannot be relied on to deliver the 60 Hz cap. Hence an in-app
section that does the specific things that measurably help this app.

## Settings placement

A new `SettingsPage.BatterySaver`, reached from a `CategoryRow` on the settings index beside the
existing *Display & units* and *Lock screen* rows, with a summary line in the established style
(e.g. `60 Hz on lock · GPS pauses when parked`).

New `SettingsStore` keys following the existing `K.LOCKED` / `setLocked` pattern:

| Key | Type | Default |
|---|---|---|
| `lock_low_refresh` | Boolean | `true` |
| `lock_dim_screen` | Boolean | `false` |
| `lock_dim_level` | Float | `0.30f` |
| `gps_pause_parked` | Boolean | `true` |

Each is mirrored into `UiState` and set through a `BatteryViewModel` setter, matching
`setLockShowTime`/`setLockShowWifi`/`setLockShowBattery`.

## Testing

Pure functions, unit-tested in the existing JVM test suite (no instrumentation):

- `lockRefreshRate()` — locked+enabled → 60f; each other combination → 0f.
- `lockBrightness()` — off → −1f; on → the level; the 0,05f floor clamps a zeroed slider; values
  above 1f clamp down.
- `gpsParked()` — null last-discharge → parked; inside the hold → not parked; **exactly at the
  boundary → parked** (`>=`, matching the alert-ladder convention that a threshold fires *at* its
  value rather than past it); beyond → parked.

Following `PowerPolicyTest.kt`, which covers the analogous latch logic. The window side-effects
themselves are not unit-testable and are verified on-device by reading back
`dumpsys display | grep renderFrameRate` and `dumpsys location`.

## Out of scope

- **Sub-60 Hz refresh rates** — measured as no benefit (above). Revisit only with a measurement
  showing otherwise.
- **BLE poll-rate reduction** — 3 mA; would degrade monitoring for nothing.
- **New DB pruning or downsampling** — already solved; nothing reclaimable.
- **Touching system settings** (`peak_refresh_rate`, system brightness) — every mechanism here is
  window-scoped and self-reverting by design.
