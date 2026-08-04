# In-App Battery Saver Settings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `Settings › Battery saver` section with three measured power toggles — lower refresh rate on lock, dim screen while locked, pause GPS while parked — plus a read-only local-database size row.

**Architecture:** All decision logic lives in one new pure Kotlin file (`model/BatterySaver.kt`) with no Android imports, unit-tested on the JVM like `model/PowerPolicy.kt`. The display effects are applied as window-scoped `WindowManager.LayoutParams` in `ui/App.kt` (self-reverting; never touches system settings). The GPS gate is applied inside `MonitorEngine`, which remains the single writer of `gpsActive`.

**Tech Stack:** Kotlin, Jetpack Compose, DataStore Preferences, Room, JUnit 4.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-08-03-battery-saver-settings-design.md`. Read it before starting.
- Branch: `feat/battery-saver-settings` (already created, spec already committed).
- Working directory for all gradle commands: `/home/joely/bmsmon/android`.
- Build: `./gradlew :app:assembleDebug` · Unit tests: `./gradlew :app:testDebugUnitTest`
- Install to device: `adb install -r app/build/outputs/apk/debug/app-debug.apk` — **`-r` in-place only. NEVER uninstall; that would delete the user's telemetry database.**
- Commit messages must contain **no** reference to AI, Claude, or automated generation (repo rule in `CLAUDE.md`).
- Defaults are fixed by user decision and must not be changed: refresh-rate toggle **ON**, dim-screen toggle **OFF**, GPS-pause toggle **ON**.
- This app is read-only over BLE. No task here sends any BMS command. Do not modify anything under `ble/`.
- The device under test is a Pixel 6 on wireless ADB, already connected as `adb-1C091FDF6003V0-RQzUxy._adb-tls-connect._tcp`.

---

### Task 1: Pure battery-saver policy functions

**Files:**
- Create: `app/src/main/java/dev/joely/bmsmon/model/BatterySaver.kt`
- Test: `app/src/test/java/dev/joely/bmsmon/BatterySaverTest.kt`

**Interfaces:**
- Consumes: nothing (pure, first task).
- Produces: `lockRefreshRate(locked: Boolean, enabled: Boolean): Float`, `lockBrightness(locked: Boolean, enabled: Boolean, level: Float): Float`, `gpsParked(lastDischargeMs: Long?, nowMs: Long, holdMs: Long = PARKED_HOLD_MS): Boolean`, and constants `LOCK_REFRESH_HZ = 60f`, `SYSTEM_REFRESH_RATE = 0f`, `PARKED_HOLD_MS = 300_000L`, `MIN_DIM_LEVEL = 0.05f`, `DEFAULT_DIM_LEVEL = 0.30f`, `BRIGHTNESS_RELEASE = -1f`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/dev/joely/bmsmon/BatterySaverTest.kt`:

```kotlin
package dev.joely.bmsmon

import dev.joely.bmsmon.model.BRIGHTNESS_RELEASE
import dev.joely.bmsmon.model.DEFAULT_DIM_LEVEL
import dev.joely.bmsmon.model.LOCK_REFRESH_HZ
import dev.joely.bmsmon.model.MIN_DIM_LEVEL
import dev.joely.bmsmon.model.PARKED_HOLD_MS
import dev.joely.bmsmon.model.gpsParked
import dev.joely.bmsmon.model.lockBrightness
import dev.joely.bmsmon.model.lockRefreshRate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatterySaverTest {

    // ── lockRefreshRate ──────────────────────────────────────────────────────
    // 60 Hz measured ~18 mA cheaper than 90 on a Pixel 6; 30 Hz measured no
    // further gain, so 60 is the floor we ask for. 0f means "system default".

    @Test fun refreshRateIsSixtyOnlyWhenLockedAndEnabled() {
        assertEquals(LOCK_REFRESH_HZ, lockRefreshRate(locked = true, enabled = true), 0f)
        assertEquals(60f, lockRefreshRate(locked = true, enabled = true), 0f)
    }

    @Test fun refreshRateIsSystemDefaultOtherwise() {
        assertEquals(0f, lockRefreshRate(locked = true, enabled = false), 0f)
        assertEquals(0f, lockRefreshRate(locked = false, enabled = true), 0f)
        assertEquals(0f, lockRefreshRate(locked = false, enabled = false), 0f)
    }

    // ── lockBrightness ───────────────────────────────────────────────────────

    @Test fun brightnessReleasesWhenOff() {
        assertEquals(BRIGHTNESS_RELEASE, lockBrightness(true, false, 0.3f), 0f)
        assertEquals(BRIGHTNESS_RELEASE, lockBrightness(false, true, 0.3f), 0f)
        assertEquals(BRIGHTNESS_RELEASE, lockBrightness(false, false, 0.3f), 0f)
    }

    @Test fun brightnessAppliesLevelWhenLockedAndEnabled() {
        assertEquals(0.30f, lockBrightness(true, true, DEFAULT_DIM_LEVEL), 0.0001f)
        assertEquals(0.75f, lockBrightness(true, true, 0.75f), 0.0001f)
    }

    // A slider dragged to zero must not produce a black screen: this display is
    // mounted on a power wheelchair and unreadable is a safety problem.
    @Test fun brightnessNeverGoesBelowTheFloor() {
        assertEquals(MIN_DIM_LEVEL, lockBrightness(true, true, 0f), 0.0001f)
        assertEquals(MIN_DIM_LEVEL, lockBrightness(true, true, -5f), 0.0001f)
    }

    @Test fun brightnessClampsAboveOne() {
        assertEquals(1f, lockBrightness(true, true, 2f), 0.0001f)
    }

    // ── gpsParked ────────────────────────────────────────────────────────────
    // The chair cannot move without discharging a pack, so "no base has
    // discharged for PARKED_HOLD_MS" means parked and GPS teaches nothing.

    @Test fun neverDischargedIsParked() {
        assertTrue(gpsParked(lastDischargeMs = null, nowMs = 1_000_000L))
    }

    @Test fun recentDischargeIsNotParked() {
        val now = 10_000_000L
        assertFalse(gpsParked(now - 1L, now))
        assertFalse(gpsParked(now - PARKED_HOLD_MS + 1L, now))
    }

    // Boundary: fires AT the threshold, matching the alert-ladder convention.
    @Test fun exactlyAtHoldIsParked() {
        val now = 10_000_000L
        assertTrue(gpsParked(now - PARKED_HOLD_MS, now))
    }

    @Test fun beyondHoldIsParked() {
        val now = 10_000_000L
        assertTrue(gpsParked(now - PARKED_HOLD_MS - 1L, now))
        assertTrue(gpsParked(now - 86_400_000L, now))
    }

    @Test fun holdIsFiveMinutes() {
        assertEquals(5 * 60_000L, PARKED_HOLD_MS)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd /home/joely/bmsmon/android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.BatterySaverTest"
```

Expected: FAIL — compilation error, unresolved reference `dev.joely.bmsmon.model.lockRefreshRate` (the file does not exist yet).

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/dev/joely/bmsmon/model/BatterySaver.kt`:

```kotlin
package dev.joely.bmsmon.model

/**
 * Pure policy for the in-app battery saver (Settings › Battery saver).
 *
 * Every constant here was set from on-device measurement on the Pixel 6, not intuition — see
 * docs/superpowers/specs/2026-08-03-battery-saver-settings-design.md for the numbers. Pure and
 * total: no clock, no Android types, same inputs always yield the same answer.
 */

/**
 * Refresh rate (Hz) requested while locked. Measured: capping 90 → 60 saves ~18 mA (the raw net
 * delta was 28.7 mA, but 11 mA of that was the charging pad recovering as the phone cooled).
 * Going 60 → 30 measured NO further gain — Android's idle frame-rate override was already
 * dropping the render rate, because the stage only redraws every 1.5 s. Do not lower this
 * without a measurement showing otherwise.
 */
const val LOCK_REFRESH_HZ = 60f

/** [lockRefreshRate] value meaning "no preference, use the system default". */
const val SYSTEM_REFRESH_RATE = 0f

/** No base discharging for this long means the chair is parked. */
const val PARKED_HOLD_MS = 5 * 60_000L

/**
 * Floor for the dim slider. A slider dragged to zero would black out a display mounted on a
 * power wheelchair, so the floor is a safety limit, not a preference.
 */
const val MIN_DIM_LEVEL = 0.05f

/** Dim level a fresh install starts at, if the user ever enables dimming (it defaults off). */
const val DEFAULT_DIM_LEVEL = 0.30f

/** `WindowManager.LayoutParams.screenBrightness` value that releases to the user's own setting. */
const val BRIGHTNESS_RELEASE = -1f

/**
 * Preferred display refresh rate for the app window.
 *
 * Not gated on the plug-aware screen policy: that latch exists to stop the screen being *held on*,
 * whereas a lower refresh rate is a saving in every power state, so gating it could only ever
 * cost battery.
 */
fun lockRefreshRate(locked: Boolean, enabled: Boolean): Float =
    if (locked && enabled) LOCK_REFRESH_HZ else SYSTEM_REFRESH_RATE

/**
 * Window brightness override while locked, or [BRIGHTNESS_RELEASE] to hand control back to the
 * user's system brightness. Clamped into [MIN_DIM_LEVEL]..1f — see [MIN_DIM_LEVEL].
 */
fun lockBrightness(locked: Boolean, enabled: Boolean, level: Float): Float =
    if (locked && enabled) level.coerceIn(MIN_DIM_LEVEL, 1f) else BRIGHTNESS_RELEASE

/**
 * True when no base has discharged within [holdMs] — the chair is parked, so GNSS is spending
 * ~22 mA to produce fixes the range learner's discharge gate discards anyway.
 *
 * [lastDischargeMs] is the newest entry of `MonitorState.lastDischargeAt`, which the engine
 * already maintains. Boundary is inclusive (`>=`), matching the alert-ladder convention that a
 * threshold fires *at* its value.
 */
fun gpsParked(lastDischargeMs: Long?, nowMs: Long, holdMs: Long = PARKED_HOLD_MS): Boolean =
    lastDischargeMs == null || nowMs - lastDischargeMs >= holdMs
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd /home/joely/bmsmon/android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.BatterySaverTest"
```

Expected: PASS, 10 tests.

- [ ] **Step 5: Commit**

```bash
cd /home/joely/bmsmon && git add android/app/src/main/java/dev/joely/bmsmon/model/BatterySaver.kt android/app/src/test/java/dev/joely/bmsmon/BatterySaverTest.kt && git commit -m "feat(android): pure battery-saver policy functions

lockRefreshRate/lockBrightness/gpsParked, with the measured constants
and a safety floor under the dim slider."
```

---

### Task 2: Persist the four settings

**Files:**
- Modify: `app/src/main/java/dev/joely/bmsmon/data/SettingsStore.kt` (keys block ~line 95-121, `Prefs` class ~line 38-70, `load()` ~line 155-170, setters ~line 210-220)
- Modify: `app/src/main/java/dev/joely/bmsmon/BatteryViewModel.kt` (`UiState` ~line 164-180, prefs reducer ~line 441-444, setters ~line 1039-1055)

**Interfaces:**
- Consumes: `DEFAULT_DIM_LEVEL` from Task 1.
- Produces: `UiState.lockLowRefresh: Boolean`, `UiState.lockDimScreen: Boolean`, `UiState.lockDimLevel: Float`, `UiState.gpsPauseParked: Boolean`; ViewModel setters `setLockLowRefresh(Boolean)`, `setLockDimScreen(Boolean)`, `setLockDimLevel(Float)`, `setGpsPauseParked(Boolean)`; `Prefs` fields of the same names.

- [ ] **Step 1: Add the DataStore keys**

In `SettingsStore.kt`, inside the `K` object, after `val LOCK_SHOW_BATTERY = booleanPreferencesKey("lock_show_battery")`:

```kotlin
        val LOCK_LOW_REFRESH = booleanPreferencesKey("lock_low_refresh")
        val LOCK_DIM_SCREEN = booleanPreferencesKey("lock_dim_screen")
        val LOCK_DIM_LEVEL = floatPreferencesKey("lock_dim_level")
        val GPS_PAUSE_PARKED = booleanPreferencesKey("gps_pause_parked")
```

`floatPreferencesKey` is already imported (used by `AUTO_LUX`).

- [ ] **Step 2: Add the `Prefs` fields**

In the `Prefs` data class, after `val lockShowBattery: Boolean,`:

```kotlin
    val lockLowRefresh: Boolean,
    val lockDimScreen: Boolean,
    val lockDimLevel: Float,
    val gpsPauseParked: Boolean,
```

- [ ] **Step 3: Map them in `load()`**

After `lockShowBattery = p[K.LOCK_SHOW_BATTERY] ?: true,`:

```kotlin
            lockLowRefresh = p[K.LOCK_LOW_REFRESH] ?: true,
            lockDimScreen = p[K.LOCK_DIM_SCREEN] ?: false,
            lockDimLevel = p[K.LOCK_DIM_LEVEL] ?: DEFAULT_DIM_LEVEL,
            gpsPauseParked = p[K.GPS_PAUSE_PARKED] ?: true,
```

Add `import dev.joely.bmsmon.model.DEFAULT_DIM_LEVEL` at the top of the file.

These defaults are the user's explicit decision: refresh ON, dim OFF, GPS-pause ON.

- [ ] **Step 4: Add the setters**

After `suspend fun setLockShowBattery(...)`:

```kotlin
    suspend fun setLockLowRefresh(on: Boolean) = context.dataStore.edit { it[K.LOCK_LOW_REFRESH] = on }.let {}
    suspend fun setLockDimScreen(on: Boolean) = context.dataStore.edit { it[K.LOCK_DIM_SCREEN] = on }.let {}
    suspend fun setLockDimLevel(level: Float) = context.dataStore.edit { it[K.LOCK_DIM_LEVEL] = level }.let {}
    suspend fun setGpsPauseParked(on: Boolean) = context.dataStore.edit { it[K.GPS_PAUSE_PARKED] = on }.let {}
```

- [ ] **Step 5: Add the `UiState` fields**

In `BatteryViewModel.kt`, after `val lockShowBattery: Boolean = true,`:

```kotlin
    // Settings › Battery saver. Defaults are a user decision: refresh-rate cap and GPS-pause on,
    // dimming OFF — the app's whole purpose is showing pack state clearly at a glance outdoors.
    val lockLowRefresh: Boolean = true,
    val lockDimScreen: Boolean = false,
    val lockDimLevel: Float = DEFAULT_DIM_LEVEL,
    val gpsPauseParked: Boolean = true,
```

Add `import dev.joely.bmsmon.model.DEFAULT_DIM_LEVEL`.

- [ ] **Step 6: Mirror prefs into `UiState`**

In the prefs reducer, after `lockShowBattery = p.lockShowBattery,`:

```kotlin
                    lockLowRefresh = p.lockLowRefresh,
                    lockDimScreen = p.lockDimScreen,
                    lockDimLevel = p.lockDimLevel,
                    gpsPauseParked = p.gpsPauseParked,
```

- [ ] **Step 7: Add the ViewModel setters**

After `fun setLockShowBattery(...)`:

```kotlin
    fun setLockLowRefresh(on: Boolean) {
        _state.update { it.copy(lockLowRefresh = on) }
        viewModelScope.launch { store.setLockLowRefresh(on) }
    }

    fun setLockDimScreen(on: Boolean) {
        _state.update { it.copy(lockDimScreen = on) }
        viewModelScope.launch { store.setLockDimScreen(on) }
    }

    fun setLockDimLevel(level: Float) {
        _state.update { it.copy(lockDimLevel = level) }
        viewModelScope.launch { store.setLockDimLevel(level) }
    }

    fun setGpsPauseParked(on: Boolean) {
        _state.update { it.copy(gpsPauseParked = on) }
        viewModelScope.launch { store.setGpsPauseParked(on) }
    }
```

The engine is deliberately **not** notified here yet — pushing the setting into `MonitorEngine` is
Task 4's job, added once `setGpsPauseParked` exists there. Keeping it out means this task compiles
and commits on its own.

- [ ] **Step 8: Build to verify it compiles**

```bash
cd /home/joely/bmsmon/android && ./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL. Any "no value passed for parameter" error means a `Prefs(...)` construction site was missed — search for `Prefs(` and add the four fields there too.

- [ ] **Step 9: Run the full unit suite (nothing should regress)**

```bash
cd /home/joely/bmsmon/android && ./gradlew :app:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 10: Commit**

```bash
cd /home/joely/bmsmon && git add -A android/app/src/main/java/dev/joely/bmsmon/data/SettingsStore.kt android/app/src/main/java/dev/joely/bmsmon/BatteryViewModel.kt && git commit -m "feat(android): persist battery-saver settings

Four DataStore keys mirrored into UiState with the agreed defaults:
refresh cap on, dim off, GPS pause on."
```

---

### Task 3: Apply refresh rate and dim to the window

**Files:**
- Modify: `app/src/main/java/dev/joely/bmsmon/ui/App.kt` (insert after the `FLAG_KEEP_SCREEN_ON` `DisposableEffect`, currently lines 86-91)

**Interfaces:**
- Consumes: `lockRefreshRate`, `lockBrightness`, `BRIGHTNESS_RELEASE`, `SYSTEM_REFRESH_RATE` (Task 1); `UiState.lockLowRefresh`, `.lockDimScreen`, `.lockDimLevel` (Task 2).
- Produces: no new symbols — a side effect only.

- [ ] **Step 1: Add the effect**

In `ui/App.kt`, immediately after the existing `DisposableEffect(window, keepOn) { ... }` block, insert:

```kotlin
    // Settings › Battery saver: cap the refresh rate and optionally dim, while locked.
    // Both are window-scoped LayoutParams, so they revert automatically when this window loses
    // focus or the process dies — unlike the global peak_refresh_rate / system brightness
    // settings, which would leak our preference into the whole phone.
    //
    // Deliberately NOT gated on state.screenHoldAllowed: that latch stops the screen being *held
    // on*, whereas a lower refresh rate is a saving in every power state.
    val lockRate = lockRefreshRate(state.locked, state.lockLowRefresh)
    val lockDim = lockBrightness(state.locked, state.lockDimScreen, state.lockDimLevel)
    DisposableEffect(window, lockRate, lockDim) {
        window?.let { w ->
            w.attributes = w.attributes.apply {
                preferredRefreshRate = lockRate
                screenBrightness = lockDim
            }
        }
        onDispose {
            window?.let { w ->
                w.attributes = w.attributes.apply {
                    preferredRefreshRate = SYSTEM_REFRESH_RATE
                    screenBrightness = BRIGHTNESS_RELEASE
                }
            }
        }
    }
```

Add these imports at the top of `App.kt`:

```kotlin
import dev.joely.bmsmon.model.BRIGHTNESS_RELEASE
import dev.joely.bmsmon.model.SYSTEM_REFRESH_RATE
import dev.joely.bmsmon.model.lockBrightness
import dev.joely.bmsmon.model.lockRefreshRate
```

- [ ] **Step 2: Build and install**

```bash
cd /home/joely/bmsmon/android && ./gradlew :app:assembleDebug && \
adb -s adb-1C091FDF6003V0-RQzUxy._adb-tls-connect._tcp install -r app/build/outputs/apk/debug/app-debug.apk
```

Expected: BUILD SUCCESSFUL, `Success`. **Never `adb uninstall`** — it would destroy the telemetry DB.

- [ ] **Step 3: Verify on-device — unlocked should be 90 Hz**

With the app open and **unlocked**:

```bash
adb -s adb-1C091FDF6003V0-RQzUxy._adb-tls-connect._tcp shell dumpsys display | grep -oE "renderFrameRate [0-9.]+" | head -2
```

Expected: `renderFrameRate 90.0`.

**Precondition:** the system `peak_refresh_rate`/`min_refresh_rate` overrides set during the investigation must be cleared first, or they will mask the app's preference:

```bash
adb -s adb-1C091FDF6003V0-RQzUxy._adb-tls-connect._tcp shell 'settings delete system peak_refresh_rate; settings delete system min_refresh_rate'
```

- [ ] **Step 4: Verify on-device — locked should be 60 Hz**

Press and hold the lock button in the app for 1,5 s (`LOCK_HOLD_MS`), then:

```bash
adb -s adb-1C091FDF6003V0-RQzUxy._adb-tls-connect._tcp shell dumpsys display | grep -oE "renderFrameRate [0-9.]+" | head -2
```

Expected: `renderFrameRate 60.0`. Unlock and confirm it returns to 90.0.

- [ ] **Step 5: Commit**

```bash
cd /home/joely/bmsmon && git add android/app/src/main/java/dev/joely/bmsmon/ui/App.kt && git commit -m "feat(android): apply lock-mode refresh cap and optional dim

Window-scoped LayoutParams so both revert on focus loss or process
death; never writes the system-wide setting."
```

---

### Task 4: Pause GPS while parked

**Files:**
- Modify: `app/src/main/java/dev/joely/bmsmon/monitor/MonitorEngine.kt` (`setGpsActive` at line 425-429; the poll-loop state update ending at line 531; add the new gate function)
- Test: `app/src/test/java/dev/joely/bmsmon/BatterySaverTest.kt` (extend)

**Interfaces:**
- Consumes: `gpsParked`, `PARKED_HOLD_MS` (Task 1); `MonitorState.lastDischargeAt: Map<String, Long>` (existing).
- Produces: `MonitorEngine.setGpsPauseParked(on: Boolean)`; `MonitorEngine.setGpsActive(active: Boolean)` keeps its signature but now records *intent* rather than directly starting GPS.

- [ ] **Step 1: Write the failing test for the gate composition**

Append to `BatterySaverTest.kt`:

```kotlin
    // ── the composed gate the engine applies ─────────────────────────────────
    // Engine truth: GPS runs only when it is wanted AND the chair is not parked.
    // Expressed here as the same boolean the engine computes, so the rule is
    // pinned by a test even though the engine itself needs a device to run.

    private fun gpsShouldRun(wanted: Boolean, pauseEnabled: Boolean, lastDischargeMs: Long?, now: Long) =
        wanted && !(pauseEnabled && gpsParked(lastDischargeMs, now))

    @Test fun gpsRunsWhenWantedAndMoving() {
        val now = 10_000_000L
        assertTrue(gpsShouldRun(wanted = true, pauseEnabled = true, lastDischargeMs = now - 1000L, now = now))
    }

    @Test fun gpsStopsWhenParked() {
        val now = 10_000_000L
        assertFalse(gpsShouldRun(true, pauseEnabled = true, lastDischargeMs = now - PARKED_HOLD_MS, now = now))
    }

    // With the toggle off, parking is irrelevant — this is the opt-out path.
    @Test fun gpsIgnoresParkedWhenPauseDisabled() {
        val now = 10_000_000L
        assertTrue(gpsShouldRun(true, pauseEnabled = false, lastDischargeMs = null, now = now))
    }

    // The parked gate can only ever SUBTRACT from what the cloud settings want.
    @Test fun gpsNeverRunsWhenNotWanted() {
        val now = 10_000_000L
        assertFalse(gpsShouldRun(false, pauseEnabled = false, lastDischargeMs = now, now = now))
        assertFalse(gpsShouldRun(false, pauseEnabled = true, lastDischargeMs = now, now = now))
    }
```

- [ ] **Step 2: Run to verify it fails**

```bash
cd /home/joely/bmsmon/android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.BatterySaverTest"
```

Expected: FAIL — `gpsShouldRun` references compile, but the tests fail only if the helper is wrong. If they pass immediately, that is acceptable here: the helper encodes the rule, and Step 3 makes the engine match it. Confirm all 14 tests are listed.

- [ ] **Step 3: Split intent from effect in the engine**

In `MonitorEngine.kt`, replace the existing `setGpsActive`:

```kotlin
    /** Start/stop GPS capture; cached fixes are attached to each upload while active. */
    fun setGpsActive(active: Boolean) {
        _state.update { it.copy(gpsActive = active) }
        if (active) locationSource.start() else locationSource.stop()
    }
```

with:

```kotlin
    // What the cloud settings WANT (monitoring && gpsEnabled && enrolled && cloudEnabled), before
    // the parked gate subtracts from it. Kept separate so the gate can flip GPS off and back on
    // without losing the user's intent.
    private var gpsWanted = false
    private var gpsPauseParked = true

    /** Record whether GPS capture is wanted at all; the parked gate decides if it actually runs. */
    fun setGpsActive(active: Boolean) {
        gpsWanted = active
        applyGpsGate(System.currentTimeMillis())
    }

    /** Settings › Battery saver: pause GNSS while no base has discharged recently. */
    fun setGpsPauseParked(on: Boolean) {
        gpsPauseParked = on
        applyGpsGate(System.currentTimeMillis())
    }

    /**
     * Fold intent + parked state into the actual GPS run state. The engine stays the single
     * writer of [MonitorState.gpsActive].
     *
     * The chair cannot move without discharging a pack, so a parked chair's fixes teach the range
     * learner nothing (its discharge gate discards them) while GNSS costs ~22 mA. Full stop rather
     * than a drop to balanced accuracy: coarse fixes are what produced the 2026-07-13 phantom map
     * spikes, so we would rather capture nothing than capture noise.
     */
    private fun applyGpsGate(now: Long) {
        val parked = gpsPauseParked && gpsParked(_state.value.lastDischargeAt.values.maxOrNull(), now)
        val active = gpsWanted && !parked
        if (_state.value.gpsActive == active) return
        _state.update { it.copy(gpsActive = active) }
        if (active) locationSource.start() else locationSource.stop()
    }
```

Add `import dev.joely.bmsmon.model.gpsParked` at the top.

- [ ] **Step 4: Re-evaluate the gate each poll**

In the poll-loop function, immediately after the `_state.update { ... }` block that ends with `peakCurrentA = peakC,` and its closing `)` / `}` (around line 531) — i.e. after `lastDischargeAt` has been recomputed for this sample and before `val fix = ...` — insert:

```kotlin
        // lastDischargeAt just moved; re-derive whether the chair still counts as driving.
        applyGpsGate(now)
```

This is the only periodic driver the gate needs: `lastDischargeAt` only ever changes here, and the poll loop runs continuously while monitoring.

- [ ] **Step 5: Push the setting from the ViewModel into the engine**

Now that `MonitorEngine.setGpsPauseParked` exists, complete the setter deferred in Task 2. In
`BatteryViewModel.kt`:

```kotlin
    fun setGpsPauseParked(on: Boolean) {
        _state.update { it.copy(gpsPauseParked = on) }
        viewModelScope.launch { store.setGpsPauseParked(on) }
        engine.setGpsPauseParked(on)
    }
```

The engine also needs the persisted value at startup, not only when the toggle is touched. Find
where the ViewModel pushes other persisted config into the engine on load (the same reducer that
calls `engine.setGpsActive(...)` / `setAlertConfig(...)`) and add alongside it:

```kotlin
                    engine.setGpsPauseParked(p.gpsPauseParked)
```

Without this, a fresh process would run the engine on its `gpsPauseParked = true` field default —
which happens to match the setting's default, so the bug would be invisible until someone turned
the toggle off and restarted the app.

- [ ] **Step 6: Run the tests**

```bash
cd /home/joely/bmsmon/android && ./gradlew :app:testDebugUnitTest
```

Expected: PASS, including `MonitorRestoreTest` (which exercises `restorePlan`/`gpsActive`).

- [ ] **Step 7: Build, install, and verify on-device**

```bash
cd /home/joely/bmsmon/android && ./gradlew :app:assembleDebug && \
adb -s adb-1C091FDF6003V0-RQzUxy._adb-tls-connect._tcp install -r app/build/outputs/apk/debug/app-debug.apk
```

With monitoring running and the chair stationary for 5+ minutes, confirm the GPS provider request is gone:

```bash
adb -s adb-1C091FDF6003V0-RQzUxy._adb-tls-connect._tcp shell dumpsys location | grep -A3 "gps provider"
```

Expected: no `WorkSource{10304 dev.joely.bmsmon}` request listed while parked. Drive the chair (or discharge a pack) and confirm the request reappears within one poll.

**Watch for:** `MonitoringService.fgsType()` ORs `FOREGROUND_SERVICE_TYPE_LOCATION` based on `gpsActive`, so this gate now changes the FGS type at runtime. Confirm the ongoing notification survives a park→drive→park cycle without the service dying, and check logcat for any `SecurityException` or `ForegroundServiceTypeException`:

```bash
adb -s adb-1C091FDF6003V0-RQzUxy._adb-tls-connect._tcp logcat -d -t 300 | grep -iE "foregroundservice|securityexception|bmsmon"
```

- [ ] **Step 8: Commit**

```bash
cd /home/joely/bmsmon && git add android/app/src/main/java/dev/joely/bmsmon/monitor/MonitorEngine.kt android/app/src/main/java/dev/joely/bmsmon/BatteryViewModel.kt android/app/src/test/java/dev/joely/bmsmon/BatterySaverTest.kt && git commit -m "feat(android): pause GPS capture while the chair is parked

Splits GPS intent from effect so the parked gate can subtract without
losing the user's setting. Reuses the existing lastDischargeAt map
rather than introducing a second discharge threshold."
```

---

### Task 5: `Settings › Battery saver` page

**Files:**
- Modify: `app/src/main/java/dev/joely/bmsmon/ui/Actions.kt` (add `BatterySaverActions` after `LockActions`, ~line 99)
- Modify: `app/src/main/java/dev/joely/bmsmon/ui/settings/SettingsScreen.kt` (enum line 120; `SettingsScreen` params line 123-134; the category-row list ~line 244-252; new content composable)
- Modify: `app/src/main/java/dev/joely/bmsmon/ui/App.kt` (build the actions object next to `lockActions`, ~line 259)

**Interfaces:**
- Consumes: Task 2's `UiState` fields and ViewModel setters; `MIN_DIM_LEVEL` (Task 1).
- Produces: `BatterySaverActions(onSetLockLowRefresh, onSetLockDimScreen, onSetLockDimLevel, onSetGpsPauseParked)`; `SettingsPage.BatterySaver`.

- [ ] **Step 1: Add the actions holder**

In `ui/Actions.kt`, after the `LockActions` data class:

```kotlin
/** Settings › Battery saver. */
data class BatterySaverActions(
    val onSetLockLowRefresh: (Boolean) -> Unit,
    val onSetLockDimScreen: (Boolean) -> Unit,
    val onSetLockDimLevel: (Float) -> Unit,
    val onSetGpsPauseParked: (Boolean) -> Unit,
)
```

- [ ] **Step 2: Add the page to the enum and the screen signature**

In `SettingsScreen.kt`, line 120:

```kotlin
private enum class SettingsPage { Monitoring, Alerts, Temperature, Groups, Appearance, Display, Lock, BatterySaver, Data, About, Cloud }
```

Add a parameter to `SettingsScreen(...)` after `lock: LockActions,`:

```kotlin
    batterySaver: BatterySaverActions,
```

- [ ] **Step 3: Add the category row**

After the existing *Lock screen* `CategoryRow` block (~line 250-252), add:

```kotlin
                RowHairline()
                CategoryRow(
                    Icons.Filled.BatterySaver, CatBlue, "Battery saver", batterySaverValue(state),
                ) { onOpen(SettingsPage.BatterySaver) }
```

And add this helper next to the existing `lockValue(state)` helper:

```kotlin
private fun batterySaverValue(state: UiState): String = buildList {
    if (state.lockLowRefresh) add("60 Hz on lock")
    if (state.lockDimScreen) add("dim on lock")
    if (state.gpsPauseParked) add("GPS pauses when parked")
}.ifEmpty { listOf("off") }.joinToString(" · ")
```

Add `import androidx.compose.material.icons.filled.BatterySaver`. The project depends on
`androidx.compose.material:material-icons-extended` (`app/build.gradle.kts:86`), so this icon
resolves; `Icons.Filled.BatteryFull` is already taken by the *Battery groups* row.

- [ ] **Step 4: Route the page to its content**

The dispatch and the page title are the same construct — a `when (page)` whose branches wrap the
content in `DetailScaffold`. Add a branch next to `SettingsPage.Lock ->` (line 167), matching the
existing Title Case convention (`"Display & Units"`, `"Lock Screen"`):

```kotlin
        SettingsPage.BatterySaver -> DetailScaffold("Battery Saver", { page = null }) {
            BatterySaverContent(
                state,
                batterySaver.onSetLockLowRefresh,
                batterySaver.onSetLockDimScreen,
                batterySaver.onSetLockDimLevel,
                batterySaver.onSetGpsPauseParked,
            )
        }
```

- [ ] **Step 5: Write the content composable**

Add a new section at the end of `SettingsScreen.kt`, following the existing numbered-section comment style:

```kotlin
// ────────────────────────────────────────────────────────────────────────────
// 7 · Battery saver
// ────────────────────────────────────────────────────────────────────────────

@Composable
private fun ColumnScope.BatterySaverContent(
    state: UiState,
    onSetLockLowRefresh: (Boolean) -> Unit,
    onSetLockDimScreen: (Boolean) -> Unit,
    onSetLockDimLevel: (Float) -> Unit,
    onSetGpsPauseParked: (Boolean) -> Unit,
) {
    val c = Bm.colors
    Text(
        "Measured savings on this phone. The screen is by far the biggest drain, then GPS — " +
            "Bluetooth is under 2% and is deliberately left alone.",
        color = c.text2, fontSize = 12.sp, lineHeight = 17.sp,
    )

    SectionLabel("While locked")
    GroupedCard {
        ToggleRow(
            "Lower refresh rate on lock",
            "Hold the display at 60 Hz instead of 90 while locked. Saves about 18 mA and is " +
                "invisible on a screen that updates once a second.",
            state.lockLowRefresh, onSetLockLowRefresh,
        )
        RowHairline(inset = 0.dp)
        ToggleRow(
            "Dim screen while locked",
            "Off by default: reading pack state at a glance outdoors matters more than the " +
                "saving. Turn on only if you can still read it in daylight.",
            state.lockDimScreen, onSetLockDimScreen,
        )
    }

    if (state.lockDimScreen) {
        SectionLabel("Dim level")
        GroupedCard {
            Column(Modifier.padding(horizontal = 15.dp, vertical = 12.dp)) {
                Text(
                    "${(state.lockDimLevel * 100).toInt()}%",
                    color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                )
                Slider(
                    value = state.lockDimLevel,
                    onValueChange = onSetLockDimLevel,
                    valueRange = MIN_DIM_LEVEL..1f,
                )
            }
        }
    }

    SectionLabel("Location")
    GroupedCard {
        ToggleRow(
            "Pause GPS while parked",
            "The chair can't move without drawing current, so when nothing has discharged for " +
                "five minutes GPS is switched off. Saves about 22 mA and skips the indoor fixes " +
                "the range estimate ignores anyway.",
            state.gpsPauseParked, onSetGpsPauseParked,
        )
    }
}
```

`ToggleRow`, `SectionLabel`, `GroupedCard` and `RowHairline` already exist in this file. `ToggleRow`
is declared at line 588 as `ToggleRow(title: String, subtitle: String, checked: Boolean,
onCheckedChange: (Boolean) -> Unit)` — the calls above match it positionally. Do not invent a new
row composable.

Add imports: `androidx.compose.material3.Slider`, `dev.joely.bmsmon.model.MIN_DIM_LEVEL`.

- [ ] **Step 6: Build the actions object in `App.kt`**

Next to the existing `val lockActions = LockActions(...)` (~line 259):

```kotlin
    val batterySaverActions = BatterySaverActions(
        onSetLockLowRefresh = vm::setLockLowRefresh,
        onSetLockDimScreen = vm::setLockDimScreen,
        onSetLockDimLevel = vm::setLockDimLevel,
        onSetGpsPauseParked = vm::setGpsPauseParked,
    )
```

And pass `batterySaver = batterySaverActions,` at the `SettingsScreen(...)` call site (next to `lock = lockActions,`).

- [ ] **Step 7: Build, install, verify**

```bash
cd /home/joely/bmsmon/android && ./gradlew :app:assembleDebug && \
adb -s adb-1C091FDF6003V0-RQzUxy._adb-tls-connect._tcp install -r app/build/outputs/apk/debug/app-debug.apk
```

On device: open Settings, confirm a **Battery saver** row with the summary `60 Hz on lock · GPS pauses when parked`. Open it, confirm the refresh and GPS toggles are ON and dim is OFF. Toggle dim on and confirm the slider appears and cannot go below 5%.

- [ ] **Step 8: Commit**

```bash
cd /home/joely/bmsmon && git add -A android/app/src/main/java/dev/joely/bmsmon/ui/ && git commit -m "feat(android): Settings > Battery saver page

Three toggles plus a dim-level slider floored at 5%."
```

---

### Task 6: Read-only database size row

**Files:**
- Modify: `app/src/main/java/dev/joely/bmsmon/ui/settings/SettingsScreen.kt` (append to `BatterySaverContent`)

**Interfaces:**
- Consumes: `SampleDao.count(): Long` (exists, `Daos.kt:43`), reached through `BmsApp.db`.
- Produces: no new symbols.

**No repository change is needed.** `TelemetryRepository` is constructed as
`TelemetryRepository(private val db: BmsDatabase)` and holds no `Context`, and `BmsApp` exposes
`db` directly (`BmsApp.kt:19`) alongside `settings`/`reporter`/`engine` — there is no
`BmsApp.repository`. The composable reads the DAO through `BmsApp.db` and the file size through
`Context.getDatabasePath`, so nothing new is plumbed and no second repository is constructed.

- [ ] **Step 1: Render the row**

Append to `BatterySaverContent` in `SettingsScreen.kt`:

```kotlin
    // Diagnostic only. Retention already runs (14-day samples, 7-day/20 MB raw frames) and the
    // SQLite freelist measured 0 pages, so there is nothing to reclaim — this row exists so the
    // size stays visible if that ever stops being true.
    SectionLabel("Local database")
    val context = LocalContext.current
    var dbBytes by remember { mutableStateOf(0L) }
    var dbRows by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        dbBytes = context.getDatabasePath("bms.db").length()
        dbRows = (context.applicationContext as BmsApp).db.samples().count()
    }
    GroupedCard {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Telemetry log", color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(
                    "Kept for 14 days, pruned automatically.",
                    color = c.text2, fontSize = 12.sp, lineHeight = 16.sp,
                )
            }
            Text(
                "%.0f MB · %,d rows".format(dbBytes / 1_048_576.0, dbRows),
                color = c.text2, fontFamily = MonoFont, fontSize = 13.sp,
            )
        }
    }
```

`(context.applicationContext as BmsApp).db` is the same access pattern the rest of the app uses
(`BatteryViewModel.kt:381` does `(app as BmsApp).engine`; `MonitoringService.kt:45` does
`(application as BmsApp).engine`). `count()` is a `suspend` DAO function, so it must be called
from inside `LaunchedEffect` as above — not at composition time.

Add imports: `androidx.compose.runtime.getValue`, `setValue`, `mutableStateOf`, `remember`, `LaunchedEffect`, `androidx.compose.ui.platform.LocalContext`, `dev.joely.bmsmon.BmsApp`.

- [ ] **Step 2: Build, install, verify**

```bash
cd /home/joely/bmsmon/android && ./gradlew :app:assembleDebug && \
adb -s adb-1C091FDF6003V0-RQzUxy._adb-tls-connect._tcp install -r app/build/outputs/apk/debug/app-debug.apk
```

Expected on device: a `Telemetry log` row reading roughly `404 MB · 2,8xx,xxx rows`. Cross-check the size against:

```bash
adb -s adb-1C091FDF6003V0-RQzUxy._adb-tls-connect._tcp shell 'run-as dev.joely.bmsmon stat -c "%s" databases/bms.db'
```

- [ ] **Step 3: Run the full suite**

```bash
cd /home/joely/bmsmon/android && ./gradlew :app:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
cd /home/joely/bmsmon && git add -A android/app/src/main/java/dev/joely/bmsmon/ && git commit -m "feat(android): read-only telemetry database size row

Diagnostic visibility only; retention already prunes and the SQLite
freelist is empty, so there is nothing to reclaim."
```

---

### Task 7: Documentation

**Files:**
- Modify: `CLAUDE.md` (Android App section)
- Modify: `android/README.md` (settings list, if it enumerates pages)

**Interfaces:**
- Consumes: everything above.
- Produces: no code.

- [ ] **Step 1: Document the feature in `CLAUDE.md`**

In the Android App section, after the plug-aware screen-policy paragraphs, add:

```markdown
**In-app battery saver (`Settings › Battery saver`).** Three measured toggles, pure logic in
`model/BatterySaver.kt` (unit-tested like `PowerPolicy`): **lower refresh rate on lock**
(default ON — `preferredRefreshRate = 60f` on the activity window while locked; measured ~18 mA
on a Pixel 6, and **60 → 30 Hz measured NO further gain** because Android's idle frame-rate
override already drops the render rate on a 1.5 s-redraw stage, so do not chase sub-60 without a
fresh measurement), **dim screen while locked** (default **OFF** by explicit decision — reading
pack state at a glance outdoors outranks the saving; slider floored at `MIN_DIM_LEVEL` 5% so it
can never black out a chair-mounted display), and **pause GPS while parked** (default ON — the
chair cannot move without discharging, so `gpsParked()` off the engine's existing
`lastDischargeAt` map stops GNSS after 5 min idle, saving ~22 mA and skipping exactly the coarse
indoor fixes the range learner's discharge gate discards; **full stop, not balanced accuracy** —
coarse fixes caused the 2026-07-13 phantom map spikes. Accepted cost: reacquisition, TTFF
measured 292 s indoors). Both display effects are **window-scoped `LayoutParams`** and revert on
focus loss or process death — nothing writes the system-wide `peak_refresh_rate` or brightness.
`MonitorEngine` splits GPS **intent** (`gpsWanted`, set by the cloud settings) from **effect**
(`applyGpsGate`, re-evaluated each poll), staying the single writer of `gpsActive`.

Android's own Battery Saver is deliberately not relied on: read off the device
(Android 17), its policy carries **no refresh-rate flag** (`lowPowerSupportedModes=[]`),
`enable_brightness_adjustment=false` (it does not dim), AOD/night-mode are already in our
desired state, `enable_quick_doze` never fires while we hold the screen, and `location_mode=3`
(foreground-only) actively breaks backgrounded GPS capture.

**Local DB size is not a problem and needs no new pruning.** Retention already runs
(`SAMPLE_RETENTION_DAYS = 14`, raw frames 7 days / 20 MB, every 200 inserts). Measured
2026-08-03: `bms.db` 423.5 MB with a **freelist of 0 pages** — nothing reclaimable, `VACUUM`
would free nothing — against 212 GB free. Shortening retention would starve `RangeLearn`, which
reads the 14-day window. `Settings › Battery saver` shows the size read-only.
```

- [ ] **Step 2: Verify no other doc contradicts this**

```bash
cd /home/joely/bmsmon && grep -rn "peak_refresh_rate\|Battery saver\|battery saver" CLAUDE.md android/README.md docs/ | grep -v superpowers/specs
```

Expected: only the text just added. Fix any stale claim found.

- [ ] **Step 3: Commit**

```bash
cd /home/joely/bmsmon && git add CLAUDE.md android/README.md && git commit -m "docs: record the in-app battery saver and the DB-size finding"
```

---

## Verification before calling this done

- [ ] `./gradlew :app:testDebugUnitTest` passes in full (not just `BatterySaverTest`)
- [ ] `./gradlew :app:assembleDebug` succeeds
- [ ] On device: locked → `renderFrameRate 60.0`; unlocked → `90.0`
- [ ] On device: parked 5 min → no bmsmon request under `dumpsys location`; discharging → request returns
- [ ] On device: the foreground-service notification survives a park→drive→park cycle (FGS type changes at runtime — see Task 4 Step 6)
- [ ] Defaults on a fresh install: refresh ON, dim OFF, GPS-pause ON
- [ ] The dim slider cannot go below 5%
- [ ] `adb uninstall` was never run
