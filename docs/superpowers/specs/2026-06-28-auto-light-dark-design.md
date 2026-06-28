# Auto Light/Dark via Ambient Light Sensor — Design

**Date:** 2026-06-28
**Component:** Android app (`android/`)
**Status:** Approved (design); ready for implementation plan

## Problem

The app's theme is currently **Dark / Light** only, chosen in the Settings
`AppearanceCard` and via the top-bar moon/sun toggle. There is an *implicit*
"follow the system theme" state that exists only until the user first taps a
mode (after which `manualMode` is latched true forever, with no UI path back).

We want the app to pick light or dark **automatically from the ambient light**:
light theme in bright surroundings, dark theme in dim ones, using the phone's
ambient light sensor (`Sensor.TYPE_LIGHT`, reported in lux).

## Decisions (from brainstorming)

- **Appearance becomes a 4-way explicit choice:** `Dark / Light / System / Auto`
  (this also makes today's implicit follow-system an explicit option).
- **Auto is driven by the light sensor** with **hysteresis + debounce** so the
  theme never flickers near the boundary.
- **The Auto trigger is user-adjustable:** Settings shows a **live lux readout**
  and a **threshold slider** (only when Auto is selected).
- The top-bar moon/sun quick-toggle keeps its "force it now" behavior: tapping
  it sets an explicit `Dark` or `Light` (exiting `System`/`Auto`).

## Architecture

### 1. Appearance model

Add an explicit user choice, separate from the *effective* rendered theme:

```kotlin
enum class Appearance { Dark, Light, System, Auto }
```

`UiState` keeps `mode: Mode` (`Dark`/`Light`) as the **effective** theme that
renders — `isDark` and every downstream theming consumer stay unchanged. Add
`appearance: Appearance` (the user's choice) and `autoLuxThreshold: Float`.

A resolver maps `Appearance` → effective `Mode`:

| Appearance | Effective `mode` |
|------------|------------------|
| `Dark`     | `Dark` |
| `Light`    | `Light` |
| `System`   | `systemDark ? Dark : Light` (the current follow-system behavior, now explicit) |
| `Auto`     | sensor-driven (section 2) |

The existing `applySystemMode(dark)` continues to feed the OS theme signal in
and only takes effect when `appearance == System`. The top-bar toggle
(`toggleMode`) sets `appearance` to explicit `Dark`/`Light`.

### 2. Sensor + switching logic

A small `AmbientLightSensor` wrapper (`ble`-sibling `sensor/` package, or
`data/`) registers a `TYPE_LIGHT` `SensorEventListener` and emits lux values
(e.g. via a callback or `StateFlow<Float?>`, `null` = no sensor / not running).
It exposes `hasSensor(): Boolean`, `start(onLux)`, `stop()`.

The light/dark decision is a **pure, unit-tested** function (hysteresis):

```kotlin
const val AUTO_BAND = 1.3f   // multiplicative deadband around the threshold

fun resolveAutoMode(lux: Float, thresholdLux: Float, current: Mode): Mode {
    val high = thresholdLux * AUTO_BAND
    val low  = thresholdLux / AUTO_BAND
    return when {
        lux >= high -> Mode.Light
        lux <= low  -> Mode.Dark
        else        -> current        // in the deadband → hold, no flip
    }
}
```

A **debounce** prevents transient flips (hand shadow, walking under a lamp): a
candidate mode different from the current effective mode must persist for
`AUTO_DEBOUNCE_MS` (≈2500 ms) before it is applied. This is implemented as a
small testable helper that tracks the candidate and the time it first appeared:

```kotlin
const val AUTO_DEBOUNCE_MS = 2500L

/** Returns the mode to commit now: [candidate] only once it has been stable
 *  since [candidateSince] for at least [debounceMs]; otherwise [current]. */
fun debouncedMode(current: Mode, candidate: Mode, candidateSince: Long,
                  now: Long, debounceMs: Long = AUTO_DEBOUNCE_MS): Mode =
    if (candidate == current) current
    else if (now - candidateSince >= debounceMs) candidate
    else current
```

The ViewModel owns the small amount of mutable state these pure functions need
(current effective mode, the pending candidate + its first-seen timestamp) and
calls them on each lux sample.

### 3. Settings UI

`AppearanceCard` becomes a **4-way selector**: `Dark · Light · System · Auto`
(extend the existing two-button row to four; reuse `AppearanceButton`). Icons:
Dark = `DarkMode`, Light = `LightMode`, System = `BrightnessAuto`/`PhoneAndroid`,
Auto = `BrightnessAuto`/`Brightness6` (pick distinct existing Material icons).

When `appearance == Auto`, reveal directly below the selector:
- a **live readout**: `Current: NNN lux` (updates from the sensor), and
- a **threshold slider**: lux range ~**20–2000**, **log-scaled** (so low-lux
  resolution is usable), default **~300 lux**, bound to `autoLuxThreshold`.
  Show the chosen threshold value next to the slider.

When Auto is not selected, the readout and slider are not shown and the sensor
is stopped.

### 4. Lifecycle, persistence, edge cases

- **Sensor runs only when** `appearance == Auto` **and the app is
  foregrounded.** Hook start/stop into the existing `onAppForeground()` /
  `onAppBackground()` and into the appearance setter. Use a slow sample rate
  (`SENSOR_DELAY_NORMAL` or `SENSOR_DELAY_UI`); the light sensor is very low
  power and the debounce smooths sampling.
- **Persistence (DataStore):** add `appearance` (string) and
  `autoLuxThreshold` (float). Keep writing the legacy `manual_mode`/`dark_mode`
  keys is **not** required; instead **migrate on load**:
  - new `appearance` key present → use it.
  - else derive from legacy: `manualMode == false` → `System`;
    `manualMode == true` → `Dark` if `darkMode` else `Light`.
  No existing user loses their setting. Default for brand-new installs:
  `System` (matches today's default of following the system until tapped).
- **No light sensor present** (rare; the Pixel 6 test unit has one): the
  **Auto** option renders **disabled** with a small "No light sensor" note, and
  cannot be selected. All other modes behave normally. `AmbientLightSensor`
  returns `hasSensor() == false`.

### 5. Components & boundaries

| Unit | Responsibility | Depends on |
|------|----------------|------------|
| `model/Appearance.kt` (or in VM) | `Appearance` enum; `resolveAutoMode`, `debouncedMode`, constants | — |
| `sensor/AmbientLightSensor.kt` (new) | register/unregister `TYPE_LIGHT`, emit lux, `hasSensor()` | Android `SensorManager` |
| `BatteryViewModel.kt` | `appearance` + `autoLuxThreshold` + live `currentLux` in `UiState`; resolve effective `mode`; own sensor lifecycle + debounce state; `setAppearance`, `setAutoLuxThreshold` | AmbientLightSensor, resolvers, SettingsStore |
| `data/SettingsStore.kt` | persist/load + migrate appearance & threshold | — |
| `ui/settings/SettingsScreen.kt` | 4-way `AppearanceCard`; Auto readout + slider | ViewModel |
| `ui/App.kt` / `ui/home/HomeScreen.kt` | top-bar toggle sets explicit Dark/Light | ViewModel |

### 6. Testing

- **Unit (pure JVM):**
  - `resolveAutoMode`: rising through `high` → `Light`; falling through `low`
    → `Dark`; values inside the deadband → returns `current` unchanged
    (both directions).
  - `debouncedMode`: candidate == current → current; candidate sustained
    < debounce → current; candidate sustained ≥ debounce → candidate;
    candidate that changes back before debounce → never commits.
  - Migration: legacy `manualMode/darkMode` combinations → expected
    `Appearance`; new key wins when present.
- **Manual (on-device):** select Auto; cover/uncover the light sensor and
  confirm the theme switches after the debounce, not instantly; confirm the
  live lux readout updates and the slider shifts the cutover; confirm the
  sensor stops when leaving Auto or backgrounding; confirm top-bar toggle
  exits Auto to an explicit mode.

## Out of scope

- Scheduled (time-of-day) theming.
- Per-screen or partial theming changes.
- Smoothing/animating the theme transition beyond Compose's default recompose.

## Risks

- **Flicker / over-sensitivity** — mitigated by the hysteresis band + debounce;
  the pure functions are unit-tested for exactly this.
- **Sensor quirks** — some sensors report coarse/quantized lux or saturate in
  sun; the log-scaled slider + deadband keep the cutover usable. Foreground-only
  operation avoids any background-sampling cost.
- **Persistence migration** — must not strand existing users on a wrong theme;
  covered by a migration unit test.
