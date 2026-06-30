# Configurable Alert Ladder + Configurable Critical Level

**Date:** 2026-06-30
**Status:** Approved (design)
**Scope:** Android app (`android/`) — read-only low-battery alert UI only.

## Motivation

For real-world wheelchair testing, the user wants fine-grained low-battery warnings on
the way *down*, starting high (95%, 90%, 85%, …) so they can observe behaviour at each
step and not run a pack too low. The app already has a configurable alert ladder
(`[30,25,20,15,10,5]`) with per-threshold enable/disable, acknowledge, charging
suppression, and a hardcoded "critical" tier at `≤15%`. This change **extends the ladder
upward to a full 5% ladder** and **makes the critical tier user-configurable**.

Non-goal: any change to BLE, the protocol, opcodes, or the read-only safety model. This is
purely additive UI/state work on top of the existing alert machinery.

## Decisions (locked with user)

- **Ladder range/granularity:** full 5% ladder, 95% → 5% (19 levels).
- **Defaults:** the new high thresholds are **OFF** by default; a fresh install enables
  only the original low set `[30,25,20,15,10,5]`. Existing installs keep their saved set.
- **Critical tier:** **configurable**, single-select from the full ladder, default `15%`.
- **Delivery:** build, install over Tailscale ADB, and live-verify on the Pixel 6.

## Current state (as found)

- `BatteryViewModel.kt:59` — `val ALERT_THRESHOLDS = listOf(30, 25, 20, 15, 10, 5)`.
- `BatteryViewModel.kt:107` — `enabledThresholds: Set<Int> = ALERT_THRESHOLDS.toSet()`.
- `BatteryViewModel.kt:177-192` — `stageAlert()`; line 190 hardcodes
  `val critical = activeThreshold != null && activeThreshold <= 15`.
- `BatteryViewModel.kt:243` — persisted-state reducer maps `enabledThresholds`.
- `BatteryViewModel.kt:693-698` — `toggleThreshold(t)` updates state + `store.setThresholds`.
- `SettingsStore.kt` — `Persisted.enabledThresholds`, `K.THRESHOLDS`
  (`stringSetPreferencesKey("alert_thresholds")`), `load()`, `setThresholds()`.
- `SettingsScreen.kt:587-617` — `AlertsContent` renders the threshold chip grid via
  `ALERT_THRESHOLDS.chunked(3)` and a "next alert" hint box. `AlertCritical` color already
  imported/used here.
- `App.kt:232` — wires `onToggleThreshold = vm::toggleThreshold` into the settings screen.

## Changes

### 1. Threshold constants — `BatteryViewModel.kt`

```kotlin
/** Every selectable low-battery alert level, most severe last (full 5% ladder). */
val ALERT_THRESHOLDS =
    listOf(95, 90, 85, 80, 75, 70, 65, 60, 55, 50, 45, 40, 35, 30, 25, 20, 15, 10, 5)

/** Levels enabled on a fresh install (the original low set; high marks default OFF). */
val DEFAULT_THRESHOLDS = listOf(30, 25, 20, 15, 10, 5)

/** Default critical tier (red / fast pulse). User-configurable. */
const val DEFAULT_CRITICAL_THRESHOLD = 15
```

### 2. UiState — `BatteryViewModel.kt`

- `enabledThresholds` default changes from `ALERT_THRESHOLDS.toSet()` →
  `DEFAULT_THRESHOLDS.toSet()`.
- Add `val criticalThreshold: Int = DEFAULT_CRITICAL_THRESHOLD`.

### 3. stageAlert() — `BatteryViewModel.kt`

Replace the hardcoded comparison:

```kotlin
// before
val critical = activeThreshold != null && activeThreshold <= 15
// after
val critical = activeThreshold != null && activeThreshold <= _state.value.criticalThreshold
```

(Read `criticalThreshold` from the current state the same way `stageAlert()` already reads
the rest of UiState.)

### 4. ViewModel setter — `BatteryViewModel.kt`

```kotlin
fun setCriticalThreshold(t: Int) {
    _state.update { it.copy(criticalThreshold = t) }
    viewModelScope.launch { store.setCriticalThreshold(t) }
}
```

Persisted-state reducer (~line 243): add
`criticalThreshold = p.criticalThreshold ?: s.criticalThreshold`.

### 5. Persistence — `SettingsStore.kt`

- `Persisted`: add `val criticalThreshold: Int?`.
- `K`: add `val CRITICAL_THRESHOLD = intPreferencesKey("alert_critical_threshold")`.
- `load()`: `criticalThreshold = p[K.CRITICAL_THRESHOLD]`.
- New setter: `suspend fun setCriticalThreshold(t: Int) =
  context.dataStore.edit { it[K.CRITICAL_THRESHOLD] = t }.let {}`.

### 6. Settings UI — `SettingsScreen.kt` + `App.kt`

- The existing threshold chip grid needs no structural change — iterating the now-19-element
  `ALERT_THRESHOLDS` renders all levels automatically (~7 rows of 3 chips).
- **Critical chip tint:** in the threshold grid, chips whose value is `<= state.criticalThreshold`
  get the `AlertCritical` tint so the amber→red boundary is visible at a glance.
- **New "Critical level" single-select row:** beneath the thresholds card, a single-select
  chip row over `ALERT_THRESHOLDS`; the selected chip is `state.criticalThreshold`. Tapping a
  chip calls `onSetCriticalThreshold(t)`. Short helper text:
  "Alerts at or below this level flash red and pulse faster."
- `AlertsContent` gains an `onSetCriticalThreshold: (Int) -> Unit` parameter.
- `App.kt`: wire `onSetCriticalThreshold = vm::setCriticalThreshold`.

## Data flow

Poll → `parseTelemetry` (unchanged) → `stageAlert()` reads `enabledThresholds` +
`criticalThreshold` from UiState → `StageAlert{flashing, critical, …}` → `DangerOverlay`
renders amber vs. red / slow vs. fast pulse (unchanged downstream). Settings writes go
through the ViewModel setters → `SettingsStore` (DataStore) → re-loaded into UiState on
next launch via the existing reducer.

## Backward compatibility

- Existing users: their saved `enabledThresholds` set is read as-is, so behaviour is
  unchanged until they opt into new levels. `criticalThreshold` is absent in their store →
  falls back to `DEFAULT_CRITICAL_THRESHOLD` (15), matching today's hardcoded value.
- Fresh installs: low-only defaults enabled; critical = 15.

## Error / edge handling

- Critical is independent of which thresholds are enabled. If only high levels are enabled,
  `activeThreshold` (min crossed) may never be `<= criticalThreshold`, so no critical fires —
  acceptable: critical is meaningful only relative to the levels you've enabled.
- `criticalThreshold` is always one of `ALERT_THRESHOLDS` (chosen via single-select), so no
  range validation needed. The persisted int, if somehow stale/out of set, still works as a
  plain numeric comparison.

## Testing / verification

- Existing parser/safety unit tests (`:app:testDebugUnitTest`) must stay green; no protocol
  change, so they should be unaffected. Run them after the change.
- Build `:app:assembleDebug` (JDK 17 + ANDROID_HOME).
- Install over Tailscale: `adb connect 100.102.146.11:5555` then `adb install -r` (never
  uninstall — preserves app data per standing rule).
- Live-verify on the Pixel 6:
  - Enable the 95% chip; confirm a live pack crossing 95% triggers the amber flash + ack bar.
  - Change the critical selector and confirm the red/fast-pulse boundary moves; chips ≤
    selection show red tint.
  - Confirm existing low alerts still behave (no regression to the default ladder).

## Files touched

- `android/app/src/main/java/dev/joely/bmsmon/BatteryViewModel.kt`
- `android/app/src/main/java/dev/joely/bmsmon/data/SettingsStore.kt`
- `android/app/src/main/java/dev/joely/bmsmon/ui/settings/SettingsScreen.kt`
- `android/app/src/main/java/dev/joely/bmsmon/ui/App.kt`
