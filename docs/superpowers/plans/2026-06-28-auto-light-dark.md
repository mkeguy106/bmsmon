# Auto Light/Dark via Ambient Light Sensor — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an `Auto` appearance mode that switches the app between light and dark themes from the phone's ambient light sensor, alongside explicit `Dark` / `Light` / `System` choices.

**Architecture:** Appearance becomes an explicit 4-way enum (`Dark/Light/System/Auto`). A pure, unit-tested resolver + debounce turn lux readings into the effective `Mode` with hysteresis so the theme never flickers. A thin `AmbientLightSensor` wrapper runs only while `Auto` is selected and the app is foregrounded. The existing `mode: Mode` stays as the effective rendered theme, so all downstream theming is untouched.

**Tech Stack:** Kotlin, Jetpack Compose, Material3, AndroidX DataStore (Preferences), Android `SensorManager` (`TYPE_LIGHT`), JUnit 4 (JVM unit tests only — no Robolectric/instrumentation).

## Global Constraints

- **The effective theme stays `UiState.mode: Mode` (`Dark`/`Light`); `isDark` and every theming consumer are unchanged.** Only the *source* of that value changes.
- **No theme flicker:** switching uses a hysteresis deadband (`AUTO_BAND = 1.3`) plus a sustained-change debounce (`AUTO_DEBOUNCE_MS = 2500`).
- **Sensor runs only when `appearance == Auto` AND the app is foregrounded.** Slow sample rate; stopped otherwise.
- **No existing user loses their theme:** migrate legacy `manual_mode`/`dark_mode` → `Appearance` on load. New-install default = `System`.
- **No light sensor present** → the `Auto` option is shown disabled with a note; other modes unaffected.
- **No new test infra.** Unit-test pure JVM logic only (the resolver/debounce/migration/slider-mapping). The sensor wrapper, persistence, and Compose UI are verified by build + manual on-device check (matches this codebase, which doesn't unit-test `SensorManager`/`org.json`/Compose).
- **The ambient light sensor needs no Android permission** — no manifest permission change.
- **Commit messages must not mention AI/Claude/automated generation; no `Co-Authored-By`** (repo CLAUDE.md rule).
- Build from `android/`: `./gradlew :app:testDebugUnitTest` (unit tests), `./gradlew :app:assembleDebug` (compile).

## File Structure

| File | Responsibility |
|------|----------------|
| `Appearance.kt` (new, pkg `dev.joely.bmsmon`) | `Appearance` enum; constants; pure `resolveAutoMode`, `debouncedMode`, `legacyAppearance`, `luxToFraction`, `fractionToLux` |
| `sensor/AmbientLightSensor.kt` (new) | register/unregister `TYPE_LIGHT`, emit lux, `hasSensor()` |
| `data/SettingsStore.kt` (modify) | persist/load `appearance` + `autoLuxThreshold`; legacy keys still read for migration |
| `BatteryViewModel.kt` (modify) | `appearance`/`autoLuxThreshold`/`currentLux`/`hasLightSensor` in `UiState`; effective-mode resolution; sensor lifecycle + debounce glue; `setAppearance`/`setAutoLuxThreshold` |
| `ui/settings/SettingsScreen.kt` (modify) | 4-way `AppearanceCard`; Auto live-lux readout + threshold slider |
| `ui/App.kt` (modify) | wire `onSetAppearance`/`onSetAutoLux` into `SettingsScreen` |
| `test/.../AppearanceTest.kt` (new) | unit tests for the pure functions |

---

### Task 1: Appearance model — enum + pure functions

**Files:**
- Create: `app/src/main/java/dev/joely/bmsmon/Appearance.kt`
- Test: `app/src/test/java/dev/joely/bmsmon/AppearanceTest.kt`

**Interfaces:**
- Consumes: `enum class Mode { Dark, Light }` (already defined in `BatteryViewModel.kt`, package `dev.joely.bmsmon`).
- Produces: `enum class Appearance { Dark, Light, System, Auto }`; constants `AUTO_BAND`, `AUTO_DEBOUNCE_MS`, `DEFAULT_AUTO_LUX`, `AUTO_LUX_MIN`, `AUTO_LUX_MAX`; `resolveAutoMode(lux: Float, thresholdLux: Float, current: Mode): Mode`; `debouncedMode(current: Mode, candidate: Mode, candidateSince: Long, now: Long, debounceMs: Long = AUTO_DEBOUNCE_MS): Mode`; `legacyAppearance(manualMode: Boolean, darkMode: Boolean): Appearance`; `luxToFraction(lux: Float): Float`; `fractionToLux(fraction: Float): Float`.

- [ ] **Step 1: Write the failing test** — `app/src/test/java/dev/joely/bmsmon/AppearanceTest.kt`:

```kotlin
package dev.joely.bmsmon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceTest {

    // --- hysteresis ---
    @Test fun brightGoesLight() {
        // threshold 300 → high = 390; 500 lux is above → Light
        assertEquals(Mode.Light, resolveAutoMode(500f, 300f, Mode.Dark))
    }

    @Test fun dimGoesDark() {
        // threshold 300 → low = ~231; 100 lux is below → Dark
        assertEquals(Mode.Dark, resolveAutoMode(100f, 300f, Mode.Light))
    }

    @Test fun insideDeadbandHoldsCurrent() {
        // 300 lux sits between low(231) and high(390) → keep whatever we are
        assertEquals(Mode.Dark, resolveAutoMode(300f, 300f, Mode.Dark))
        assertEquals(Mode.Light, resolveAutoMode(300f, 300f, Mode.Light))
    }

    // --- debounce ---
    @Test fun debounceHoldsUntilSustained() {
        val t0 = 1_000_000L
        // candidate differs but not sustained long enough → keep current
        assertEquals(Mode.Dark, debouncedMode(Mode.Dark, Mode.Light, candidateSince = t0, now = t0 + 1000, debounceMs = 2500))
        // sustained past the window → commit candidate
        assertEquals(Mode.Light, debouncedMode(Mode.Dark, Mode.Light, candidateSince = t0, now = t0 + 2500, debounceMs = 2500))
    }

    @Test fun debounceNoOpWhenCandidateEqualsCurrent() {
        assertEquals(Mode.Light, debouncedMode(Mode.Light, Mode.Light, candidateSince = 0, now = 9_999_999, debounceMs = 2500))
    }

    // --- migration ---
    @Test fun legacyMigration() {
        assertEquals(Appearance.System, legacyAppearance(manualMode = false, darkMode = false))
        assertEquals(Appearance.System, legacyAppearance(manualMode = false, darkMode = true))
        assertEquals(Appearance.Dark, legacyAppearance(manualMode = true, darkMode = true))
        assertEquals(Appearance.Light, legacyAppearance(manualMode = true, darkMode = false))
    }

    // --- slider log mapping ---
    @Test fun luxFractionRoundTrips() {
        assertEquals(0f, luxToFraction(AUTO_LUX_MIN), 0.001f)
        assertEquals(1f, luxToFraction(AUTO_LUX_MAX), 0.001f)
        // midpoint fraction is the geometric mean (log scale)
        assertEquals(200f, fractionToLux(0.5f), 1f)   // sqrt(20*2000) = 200
        assertEquals(300f, fractionToLux(luxToFraction(300f)), 0.5f)
    }

    @Test fun luxFractionClampsOutOfRange() {
        assertTrue(luxToFraction(1f) in 0f..0.001f)      // below min clamps to 0
        assertTrue(luxToFraction(99_999f) in 0.999f..1f) // above max clamps to 1
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.AppearanceTest"`
Expected: FAIL (unresolved references — `Appearance.kt` doesn't exist).

- [ ] **Step 3: Write `Appearance.kt`**:

```kotlin
package dev.joely.bmsmon

import kotlin.math.log10
import kotlin.math.pow

/** How the user chose the theme. [Auto] is driven by the ambient light sensor. */
enum class Appearance { Dark, Light, System, Auto }

/** Multiplicative hysteresis deadband around the user's lux threshold (prevents flicker). */
const val AUTO_BAND = 1.3f

/** A candidate flip must persist this long before it is applied. */
const val AUTO_DEBOUNCE_MS = 2500L

/** Default auto-switch threshold (lux) and the adjustable slider range. */
const val DEFAULT_AUTO_LUX = 300f
const val AUTO_LUX_MIN = 20f
const val AUTO_LUX_MAX = 2000f

/**
 * Hysteresis resolver: go [Mode.Light] above `threshold * AUTO_BAND`, [Mode.Dark] below
 * `threshold / AUTO_BAND`, and hold [current] inside the deadband (no flip).
 */
fun resolveAutoMode(lux: Float, thresholdLux: Float, current: Mode): Mode {
    val high = thresholdLux * AUTO_BAND
    val low = thresholdLux / AUTO_BAND
    return when {
        lux >= high -> Mode.Light
        lux <= low -> Mode.Dark
        else -> current
    }
}

/**
 * Returns the mode to commit now: [candidate] only once it has been stable since
 * [candidateSince] for at least [debounceMs]; otherwise [current].
 */
fun debouncedMode(
    current: Mode,
    candidate: Mode,
    candidateSince: Long,
    now: Long,
    debounceMs: Long = AUTO_DEBOUNCE_MS,
): Mode = when {
    candidate == current -> current
    now - candidateSince >= debounceMs -> candidate
    else -> current
}

/** Map legacy persisted booleans to the new [Appearance]. */
fun legacyAppearance(manualMode: Boolean, darkMode: Boolean): Appearance = when {
    !manualMode -> Appearance.System
    darkMode -> Appearance.Dark
    else -> Appearance.Light
}

/** Log-scaled slider position [0,1] for a lux value, clamped to [AUTO_LUX_MIN, AUTO_LUX_MAX]. */
fun luxToFraction(lux: Float): Float {
    val clamped = lux.coerceIn(AUTO_LUX_MIN, AUTO_LUX_MAX)
    return (log10(clamped / AUTO_LUX_MIN) / log10(AUTO_LUX_MAX / AUTO_LUX_MIN)).toFloat()
}

/** Inverse of [luxToFraction]: slider position [0,1] → lux (log scale). */
fun fractionToLux(fraction: Float): Float {
    val f = fraction.coerceIn(0f, 1f)
    return AUTO_LUX_MIN * (AUTO_LUX_MAX / AUTO_LUX_MIN).toDouble().pow(f.toDouble()).toFloat()
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.AppearanceTest"`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/joely/bmsmon/Appearance.kt app/src/test/java/dev/joely/bmsmon/AppearanceTest.kt
git commit -m "Add Appearance model: hysteresis resolver, debounce, migration"
```

---

### Task 2: Ambient light sensor wrapper

**Files:**
- Create: `app/src/main/java/dev/joely/bmsmon/sensor/AmbientLightSensor.kt`

**Interfaces:**
- Produces: `class AmbientLightSensor(context: Context)` with `fun hasSensor(): Boolean`, `fun start(onLux: (Float) -> Unit)`, `fun stop()`.

- [ ] **Step 1: Write `AmbientLightSensor.kt`**:

```kotlin
package dev.joely.bmsmon.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Thin wrapper over the ambient light sensor (TYPE_LIGHT). Emits lux via [start]'s callback.
 * Idempotent: [start] re-registers cleanly; [stop] is safe to call when not running.
 */
class AmbientLightSensor(context: Context) {

    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_LIGHT)
    private var listener: SensorEventListener? = null

    fun hasSensor(): Boolean = sensor != null

    fun start(onLux: (Float) -> Unit) {
        val m = manager ?: return
        val s = sensor ?: return
        stop()
        val l = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.values.isNotEmpty()) onLux(event.values[0])
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        listener = l
        m.registerListener(l, s, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun stop() {
        val m = manager ?: return
        listener?.let { m.unregisterListener(it) }
        listener = null
    }
}
```

- [ ] **Step 2: Build**

Run: `cd android && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/dev/joely/bmsmon/sensor/AmbientLightSensor.kt
git commit -m "Add ambient light sensor wrapper"
```

---

### Task 3: Persist appearance + threshold

**Files:**
- Modify: `app/src/main/java/dev/joely/bmsmon/data/SettingsStore.kt`

**Interfaces:**
- Produces: `Persisted.appearance: String?`, `Persisted.autoLuxThreshold: Float?`; `SettingsStore.setAppearance(name: String)`, `SettingsStore.setAutoLuxThreshold(lux: Float)`. Legacy `manualMode`/`darkMode` remain on `Persisted` (read for migration).

- [ ] **Step 1: Add the float-key import** at the top of `SettingsStore.kt` (with the other `androidx.datastore.preferences.core` imports):

```kotlin
import androidx.datastore.preferences.core.floatPreferencesKey
```

- [ ] **Step 2: Add fields to `data class Persisted`** (append before the closing paren):

```kotlin
    val appearance: String?,
    val autoLuxThreshold: Float?,
```

- [ ] **Step 3: Add keys** inside `object K`:

```kotlin
        val APPEARANCE = stringPreferencesKey("appearance")
        val AUTO_LUX = floatPreferencesKey("auto_lux_threshold")
```

- [ ] **Step 4: Populate them in `load()`** — add to the `Persisted(...)` constructor call:

```kotlin
            appearance = p[K.APPEARANCE],
            autoLuxThreshold = p[K.AUTO_LUX],
```

- [ ] **Step 5: Replace the legacy `setMode` setter** with the two new setters. Find:

```kotlin
    suspend fun setMode(dark: Boolean) = context.dataStore.edit {
        it[K.MANUAL] = true
        it[K.DARK] = dark
    }.let {}
```

and replace it with:

```kotlin
    suspend fun setAppearance(name: String) = context.dataStore.edit { it[K.APPEARANCE] = name }.let {}
    suspend fun setAutoLuxThreshold(lux: Float) = context.dataStore.edit { it[K.AUTO_LUX] = lux }.let {}
```

(The legacy `MANUAL`/`DARK` keys are still declared and still read in `load()` for migration; we just stop writing them — appearance is the new source of truth.)

- [ ] **Step 6: Commit** (compiles after Task 4 wires the VM; no standalone build here)

```bash
git add app/src/main/java/dev/joely/bmsmon/data/SettingsStore.kt
git commit -m "Persist appearance and auto-lux threshold"
```

---

### Task 4: ViewModel — appearance state, effective-mode resolution, sensor lifecycle

**Files:**
- Modify: `app/src/main/java/dev/joely/bmsmon/BatteryViewModel.kt`

**Interfaces:**
- Consumes: `Appearance`, `resolveAutoMode`, `debouncedMode`, `legacyAppearance`, `DEFAULT_AUTO_LUX` (Task 1); `AmbientLightSensor` (Task 2); `SettingsStore.setAppearance`/`setAutoLuxThreshold`/`Persisted.appearance`/`Persisted.autoLuxThreshold` (Task 3).
- Produces (UI consumes in Task 5): `UiState.appearance: Appearance`, `UiState.autoLuxThreshold: Float`, `UiState.currentLux: Float?`, `UiState.hasLightSensor: Boolean`; VM methods `setAppearance(a: Appearance)`, `setAutoLuxThreshold(lux: Float)`; `setMode(mode: Mode)` kept as a thin wrapper; `toggleMode()` and `applySystemMode(dark)` updated.

- [ ] **Step 1: Add the imports** at the top of `BatteryViewModel.kt` (with the other imports):

```kotlin
import dev.joely.bmsmon.sensor.AmbientLightSensor
```

(`Appearance`, `resolveAutoMode`, etc. are the same package `dev.joely.bmsmon` — no import needed.)

- [ ] **Step 2: Replace `manualMode` with the new fields in `UiState`.** Find:

```kotlin
    val mode: Mode = Mode.Dark,
    val manualMode: Boolean = false,
```

and replace with:

```kotlin
    val mode: Mode = Mode.Dark,                 // effective (rendered) theme
    val appearance: Appearance = Appearance.System,
    val autoLuxThreshold: Float = DEFAULT_AUTO_LUX,
    val currentLux: Float? = null,
    val hasLightSensor: Boolean = true,
```

(`isDark` / all theming stay as-is.)

- [ ] **Step 3: Add the sensor instance + lifecycle fields** to the `BatteryViewModel` class body, just after `private val logger = TelemetryLogger(app)`:

```kotlin
    private val lightSensor = AmbientLightSensor(app)
    private var foreground = true
    private var lastSystemDark = true
    private var autoCandidate: Mode? = null
    private var autoCandidateSince = 0L
```

- [ ] **Step 4: Update the `init` load block** to resolve appearance (with migration), seed the effective mode, set `hasLightSensor`, and start the sensor if needed. Find:

```kotlin
                val dd = groupById(p.dailyDriverId ?: s.dailyDriverId)
                s.copy(
                    accent = p.accentArgb?.let { Color(it) } ?: s.accent,
                    power = p.powerArgb?.let { Color(it) } ?: s.power,
                    manualMode = p.manualMode,
                    mode = if (p.manualMode) (if (p.darkMode) Mode.Dark else Mode.Light) else s.mode,
```

and replace those lines with:

```kotlin
                val dd = groupById(p.dailyDriverId ?: s.dailyDriverId)
                val appearance = p.appearance?.let { runCatching { Appearance.valueOf(it) }.getOrNull() }
                    ?: legacyAppearance(p.manualMode, p.darkMode)
                val effectiveMode = when (appearance) {
                    Appearance.Dark -> Mode.Dark
                    Appearance.Light -> Mode.Light
                    Appearance.System, Appearance.Auto -> s.mode  // corrected once system/sensor reports
                }
                s.copy(
                    accent = p.accentArgb?.let { Color(it) } ?: s.accent,
                    power = p.powerArgb?.let { Color(it) } ?: s.power,
                    appearance = appearance,
                    autoLuxThreshold = p.autoLuxThreshold ?: s.autoLuxThreshold,
                    hasLightSensor = lightSensor.hasSensor(),
                    mode = effectiveMode,
```

Then, at the **end of the `init` block's first `viewModelScope.launch { ... }`** (right after the `if (p.monitoring && ...) startMonitoring()` line), add:

```kotlin
            updateSensor()
```

- [ ] **Step 5: Replace the theme setters.** Find:

```kotlin
    fun setMode(mode: Mode) {
        _state.update { it.copy(mode = mode, manualMode = true) }
        viewModelScope.launch { store.setMode(mode == Mode.Dark) }
    }
    fun toggleMode() = setMode(if (_state.value.isDark) Mode.Light else Mode.Dark)
    fun applySystemMode(dark: Boolean) = _state.update {
        if (it.manualMode) it else it.copy(mode = if (dark) Mode.Dark else Mode.Light)
    }
```

and replace with:

```kotlin
    /** User picked an explicit appearance (Dark/Light/System/Auto). */
    fun setAppearance(a: Appearance) {
        _state.update { it.copy(appearance = a) }
        viewModelScope.launch { store.setAppearance(a.name) }
        recomputeEffectiveMode()
        updateSensor()
    }

    /** Top-bar quick toggle / legacy two-button picker → an explicit Dark/Light lock. */
    fun setMode(mode: Mode) = setAppearance(if (mode == Mode.Dark) Appearance.Dark else Appearance.Light)
    fun toggleMode() = setMode(if (_state.value.isDark) Mode.Light else Mode.Dark)

    /** OS theme signal (from Compose). Only takes effect while appearance == System. */
    fun applySystemMode(dark: Boolean) {
        lastSystemDark = dark
        _state.update {
            if (it.appearance == Appearance.System) it.copy(mode = if (dark) Mode.Dark else Mode.Light) else it
        }
    }

    fun setAutoLuxThreshold(lux: Float) {
        _state.update { it.copy(autoLuxThreshold = lux) }
        viewModelScope.launch { store.setAutoLuxThreshold(lux) }
        if (_state.value.appearance == Appearance.Auto) recomputeEffectiveMode()
    }

    /** Recompute the effective [Mode] from the current appearance (no debounce — immediate). */
    private fun recomputeEffectiveMode() = _state.update { s ->
        val m = when (s.appearance) {
            Appearance.Dark -> Mode.Dark
            Appearance.Light -> Mode.Light
            Appearance.System -> if (lastSystemDark) Mode.Dark else Mode.Light
            Appearance.Auto -> s.currentLux?.let { resolveAutoMode(it, s.autoLuxThreshold, s.mode) } ?: s.mode
        }
        s.copy(mode = m)
    }

    /** Start the light sensor only while Auto is selected and the app is foregrounded. */
    private fun updateSensor() {
        val s = _state.value
        if (s.appearance == Appearance.Auto && foreground && s.hasLightSensor) {
            lightSensor.start(::onLux)
        } else {
            lightSensor.stop()
            autoCandidate = null
        }
    }

    /** Each lux sample: update the readout, then flip the theme through hysteresis + debounce. */
    private fun onLux(lux: Float) {
        val now = clockMs()
        _state.update { it.copy(currentLux = lux) }
        val s = _state.value
        if (s.appearance != Appearance.Auto) return
        val candidate = resolveAutoMode(lux, s.autoLuxThreshold, s.mode)
        if (candidate == s.mode) { autoCandidate = null; return }
        if (autoCandidate != candidate) { autoCandidate = candidate; autoCandidateSince = now }
        if (debouncedMode(s.mode, candidate, autoCandidateSince, now) != s.mode) {
            autoCandidate = null
            _state.update { it.copy(mode = candidate) }
        }
    }
```

- [ ] **Step 6: Drive the sensor from the foreground hooks.** Find:

```kotlin
    fun onAppForeground() {
        if (_state.value.monitoring) repository.kickAll()
    }
```

and replace with:

```kotlin
    fun onAppForeground() {
        foreground = true
        updateSensor()
        if (_state.value.monitoring) repository.kickAll()
    }
```

Find:

```kotlin
    fun onAppBackground() = persistLastTelemetry()
```

and replace with:

```kotlin
    fun onAppBackground() {
        foreground = false
        updateSensor()
        persistLastTelemetry()
    }
```

- [ ] **Step 7: Stop the sensor in `onCleared`.** Find:

```kotlin
    override fun onCleared() {
        persistLastTelemetry()
        repository.stop()
        super.onCleared()
    }
```

and replace with:

```kotlin
    override fun onCleared() {
        persistLastTelemetry()
        repository.stop()
        lightSensor.stop()
        super.onCleared()
    }
```

- [ ] **Step 8: Build + run unit tests** (module compiles now — `SettingsScreen`/`App.kt` still call `onSetMode = vm::setMode`, which still exists as the wrapper).

Run: `cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; all unit tests pass (`AppearanceTest`, `BmsProtocolTest`, `FleetLogicTest`).

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/dev/joely/bmsmon/BatteryViewModel.kt
git commit -m "Wire appearance + ambient-light auto theming into the ViewModel"
```

---

### Task 5: Settings UI — 4-way selector + Auto controls

**Files:**
- Modify: `app/src/main/java/dev/joely/bmsmon/ui/settings/SettingsScreen.kt`
- Modify: `app/src/main/java/dev/joely/bmsmon/ui/App.kt`

**Interfaces:**
- Consumes: `state.appearance`, `state.autoLuxThreshold`, `state.currentLux`, `state.hasLightSensor` (Task 4); `Appearance`, `luxToFraction`, `fractionToLux` (Task 1); VM `setAppearance`, `setAutoLuxThreshold` (Task 4).
- Produces: `SettingsScreen` signature swaps `onSetMode: (Mode) -> Unit` for `onSetAppearance: (Appearance) -> Unit, onSetAutoLux: (Float) -> Unit`.

- [ ] **Step 1: Update imports** in `SettingsScreen.kt`. Add:

```kotlin
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Slider
import dev.joely.bmsmon.Appearance
import dev.joely.bmsmon.fractionToLux
import dev.joely.bmsmon.luxToFraction
import kotlin.math.roundToInt
```

(`dev.joely.bmsmon.Mode` import may now be unused — remove it if the compiler warns.)

- [ ] **Step 2: Change the `SettingsScreen` signature.** Find:

```kotlin
    onSetMode: (Mode) -> Unit,
) {
```

and replace with:

```kotlin
    onSetAppearance: (Appearance) -> Unit,
    onSetAutoLux: (Float) -> Unit,
) {
```

- [ ] **Step 3: Update the `AppearanceCard` call site.** Find:

```kotlin
            AppearanceCard(state, onSetMode)
```

and replace with:

```kotlin
            AppearanceCard(state, onSetAppearance, onSetAutoLux)
```

- [ ] **Step 4: Replace the `AppearanceCard` composable** (the whole function) with the 4-way version + Auto controls:

```kotlin
@Composable
private fun AppearanceCard(
    state: UiState,
    onSetAppearance: (Appearance) -> Unit,
    onSetAutoLux: (Float) -> Unit,
) {
    val c = Bm.colors
    Card {
        Text("Appearance", color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            AppearanceButton("Dark", Icons.Filled.DarkMode, state.appearance == Appearance.Dark,
                Modifier.weight(1f)) { onSetAppearance(Appearance.Dark) }
            AppearanceButton("Light", Icons.Filled.LightMode, state.appearance == Appearance.Light,
                Modifier.weight(1f)) { onSetAppearance(Appearance.Light) }
        }
        Row(Modifier.padding(top = 9.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            AppearanceButton("System", Icons.Filled.PhoneAndroid, state.appearance == Appearance.System,
                Modifier.weight(1f)) { onSetAppearance(Appearance.System) }
            AppearanceButton("Auto", Icons.Filled.BrightnessAuto, state.appearance == Appearance.Auto,
                Modifier.weight(1f), enabled = state.hasLightSensor) { onSetAppearance(Appearance.Auto) }
        }
        if (!state.hasLightSensor) {
            Text("No light sensor on this device — Auto unavailable.", color = c.text3, fontSize = 11.sp,
                modifier = Modifier.padding(top = 10.dp))
        } else if (state.appearance == Appearance.Auto) {
            val lux = state.currentLux?.roundToInt()
            Text(
                "Current: ${lux?.toString() ?: "—"} lux   ·   switch around ${state.autoLuxThreshold.roundToInt()} lux",
                color = c.text2, fontSize = 12.sp, fontFamily = MonoFont,
                modifier = Modifier.padding(top = 12.dp),
            )
            Slider(
                value = luxToFraction(state.autoLuxThreshold),
                onValueChange = { onSetAutoLux(fractionToLux(it)) },
            )
            Text("Brighter than the cutover → Light; dimmer → Dark.", color = c.text3, fontSize = 11.sp)
        } else {
            Text("System follows your phone's theme.", color = c.text3, fontSize = 11.sp,
                modifier = Modifier.padding(top = 10.dp))
        }
    }
}
```

- [ ] **Step 5: Add an `enabled` parameter to `AppearanceButton`.** Find the existing `AppearanceButton` composable and replace it with:

```kotlin
@Composable
private fun AppearanceButton(
    label: String,
    icon: ImageVector,
    active: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val c = Bm.colors
    val border = if (active) Bm.accent else c.border
    val bg = if (active) Bm.accent.copy(alpha = 0.14f) else Color.Transparent
    val content = if (enabled) c.text else c.text3
    Row(
        modifier
            .clip(RoundedCornerShape(9.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(9.dp))
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(11.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(17.dp), tint = content)
        Text(label, color = content, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 8.dp))
    }
}
```

Add the `alpha` import if not present (top of file):

```kotlin
import androidx.compose.ui.draw.alpha
```

- [ ] **Step 6: Wire the new callbacks in `App.kt`.** Find:

```kotlin
                        onSetMode = vm::setMode,
```

and replace with:

```kotlin
                        onSetAppearance = vm::setAppearance,
                        onSetAutoLux = vm::setAutoLuxThreshold,
```

- [ ] **Step 7: Build**

Run: `cd android && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/dev/joely/bmsmon/ui/settings/SettingsScreen.kt app/src/main/java/dev/joely/bmsmon/ui/App.kt
git commit -m "Settings: 4-way appearance selector with Auto light-sensor controls"
```

---

### Task 6: Manual on-device verification

**Files:** none (verification only).

- [ ] **Step 1: Install + launch**

Run: `cd android && ./gradlew :app:installDebug` then launch on the connected Pixel 6 (wireless ADB).

- [ ] **Step 2: Migration check** — On first launch after install, confirm the theme matches what it was before (an existing user who was on system-follow stays on system; if they'd locked Dark/Light, it stays). Open Settings → Appearance shows the 4-way selector with the right one highlighted.

- [ ] **Step 3: Each mode** —
  - Tap **Dark** / **Light** → theme locks immediately; the top-bar moon/sun toggle still flips between Dark/Light.
  - Tap **System** → matches the phone's theme; toggling the phone's dark mode flips the app.
  - Tap **Auto** → the live "Current: NNN lux" readout updates; the threshold slider appears.

- [ ] **Step 4: Auto switching** — In Auto, **cover the light sensor** (top of the phone, near the earpiece/front camera) for ~3 s → theme goes **Dark** after the debounce (not instantly). **Uncover / point at a bright light** for ~3 s → goes **Light**. Confirm brief shadows do NOT flip it. Slide the threshold and confirm the cutover point shifts.

- [ ] **Step 5: Lifecycle** — Background the app while in Auto, return → still works; switch away from Auto → readout/slider disappear (sensor stopped).

- [ ] **Step 6: Final** — verification only; no commit.

---

## Self-Review

**Spec coverage:**
- 4-way `Dark/Light/System/Auto` — Task 1 (enum) + Task 4 (state) + Task 5 (UI). ✓
- Hysteresis + debounce, no flicker — Task 1 (`resolveAutoMode`/`debouncedMode`, tested) + Task 4 (glue). ✓
- Top-bar toggle forces explicit Dark/Light — Task 4 (`setMode` → `setAppearance(Dark/Light)`). ✓
- Live lux readout + adjustable threshold slider (log-scaled, Auto-only) — Task 1 (`luxToFraction`/`fractionToLux`) + Task 5. ✓
- Sensor only in Auto + foreground, slow rate — Task 2 (`SENSOR_DELAY_NORMAL`) + Task 4 (`updateSensor`, foreground hooks). ✓
- Persistence + legacy migration, default System — Task 3 (store) + Task 4 (`legacyAppearance`, init). ✓
- No light sensor → Auto disabled + note — Task 4 (`hasLightSensor`) + Task 5 (disabled button + note). ✓
- No manifest permission — none added. ✓
- Effective `mode` unchanged downstream — Task 4 keeps `mode`/`isDark`. ✓

**Placeholder scan:** none — every step has concrete code/commands. The Material icon choices (`PhoneAndroid`, `BrightnessAuto`) are concrete, standard `material-icons-extended` symbols already available (the project uses `Icons.Filled.*` elsewhere).

**Type consistency:** `Appearance`, `resolveAutoMode(lux, thresholdLux, current)`, `debouncedMode(current, candidate, candidateSince, now, debounceMs)`, `legacyAppearance(manualMode, darkMode)`, `luxToFraction`/`fractionToLux` defined in Task 1 and consumed with identical signatures in Tasks 4–5. `UiState.appearance/autoLuxThreshold/currentLux/hasLightSensor` defined in Task 4, consumed in Task 5. `AmbientLightSensor.hasSensor/start/stop` defined in Task 2, used in Task 4. `SettingsStore.setAppearance/setAutoLuxThreshold` + `Persisted.appearance/autoLuxThreshold` defined in Task 3, used in Task 4. Consistent.

**Build ordering:** Task 3 (store) commits before its consumer compiles, but the first green build is Task 4 Step 8 — `setMode(Mode)` is kept as a wrapper so the unchanged `SettingsScreen`/`App.kt` still compile through Task 4; Task 5 then swaps the UI to the 4-way API. Intentional, noted.
