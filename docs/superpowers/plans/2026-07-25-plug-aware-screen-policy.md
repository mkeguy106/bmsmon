# Plug-Aware Screen Policy + Monitoring Wakelock Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Hold the phone's screen on only while it is on external power and above a low-battery floor, and add the `PARTIAL_WAKE_LOCK` that keeps BLE poll cadence identical once the screen is allowed to sleep.

**Architecture:** A new `PowerMonitor` (Android `ACTION_BATTERY_CHANGED` receiver) feeds a new **pure** `PowerPolicy` that holds a hysteretic low-battery latch. `MonitorEngine` — which is process-lifetime and already owns `LocationSource` and a Bluetooth receiver — owns the monitor, folds each reading through the pure policy, publishes the result on `MonitorState`, and applies the GPS half directly. `BatteryViewModel` mirrors the screen half into `UiState`, and `ui/App.kt` gates its existing `FLAG_KEEP_SCREEN_ON` on it. Separately, `MonitoringService` acquires a wakelock for the monitoring session.

**Tech Stack:** Kotlin, Jetpack Compose, Kotlin coroutines/`StateFlow`, JUnit 4 (`app/src/test/`, flat package `dev.joely.bmsmon`), Gradle (`./gradlew`).

**Spec:** `docs/superpowers/specs/2026-07-25-plug-aware-screen-policy-design.md`

## Global Constraints

- Thresholds are exactly `LOW_ENTER_PCT = 5` and `LOW_EXIT_PCT = 15`. The latch **sets** when `levelPct < 5`, **clears** when `levelPct >= 15`, and **holds its previous value** in between.
- `holdScreen = onExternal && !lowPower`. `gpsBalanced = lowPower`.
- External power means **any** of `BatteryManager.BATTERY_PLUGGED_AC`, `BATTERY_PLUGGED_USB`, `BATTERY_PLUGGED_WIRELESS`.
- `model/PowerPolicy.kt` must contain **no Android imports** — it is pure so it unit-tests on the JVM, like `model/Alerts.kt` and `model/Fleet.kt`.
- GPS request parameters: normal = `PRIORITY_HIGH_ACCURACY`, interval `5_000`, min `2_000` (today's values, unchanged). Low power = `PRIORITY_BALANCED_POWER_ACCURACY`, interval `20_000`, min `10_000`.
- Degenerate battery extras (`EXTRA_SCALE <= 0`) must fail safe: `onExternal = false`, `levelPct = 100`.
- Wakelock tag is exactly `bmsmon:monitoring`. Release must be idempotent (`if (isHeld) release()`).
- Single-writer discipline: only `MonitorEngine` writes `holdScreen`/`gpsBalanced`/`lowPower` into `MonitorState`, matching how `etaFullMin` and `range` are handled. The ViewModel only mirrors.
- Never weaken existing behavior: monitoring, BLE polling, alerts, logging and cloud upload must be unaffected in every power state.
- Commit messages must not mention Claude, AI, or automated generation (repo rule in `CLAUDE.md`).

## File Structure

| File | Responsibility |
|---|---|
| `android/app/src/main/java/dev/joely/bmsmon/model/PowerPolicy.kt` | **Create.** Pure latch + decision. No Android types. |
| `android/app/src/test/java/dev/joely/bmsmon/PowerPolicyTest.kt` | **Create.** JVM tests for the latch. |
| `android/app/src/main/java/dev/joely/bmsmon/power/PowerMonitor.kt` | **Create.** `ACTION_BATTERY_CHANGED` receiver → `PowerStatus`. |
| `android/app/src/main/java/dev/joely/bmsmon/location/LocationSource.kt` | **Modify.** Add `setBalanced()`; re-issue request on change. |
| `android/app/src/main/java/dev/joely/bmsmon/monitor/MonitorEngine.kt` | **Modify.** Own the monitor, fold the policy, publish + apply. |
| `android/app/src/main/java/dev/joely/bmsmon/monitor/MonitoringService.kt` | **Modify.** Acquire/release the wakelock. |
| `android/app/src/main/AndroidManifest.xml` | **Modify.** Add `WAKE_LOCK`. |
| `android/app/src/main/java/dev/joely/bmsmon/BatteryViewModel.kt` | **Modify.** `UiState.screenHoldAllowed` + mirror. |
| `android/app/src/main/java/dev/joely/bmsmon/ui/App.kt` | **Modify.** Gate `keepOn` on the mirrored flag. |

Task order is dependency order: the pure core first (Task 1), then the Android sensor (Task 2), then the two consumers (Tasks 3 and 4), then the wakelock (Task 5), then the UI gate that makes it user-visible (Task 6).

---

### Task 1: Pure power policy + latch

**Files:**
- Create: `android/app/src/main/java/dev/joely/bmsmon/model/PowerPolicy.kt`
- Test: `android/app/src/test/java/dev/joely/bmsmon/PowerPolicyTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `LOW_ENTER_PCT: Int`, `LOW_EXIT_PCT: Int`, `data class PowerDecision(val holdScreen: Boolean, val gpsBalanced: Boolean, val lowPower: Boolean)`, and `fun powerDecision(onExternal: Boolean, levelPct: Int, wasLowPower: Boolean): PowerDecision` — all in package `dev.joely.bmsmon.model`. Tasks 3 and 6 depend on these exact names.

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/dev/joely/bmsmon/PowerPolicyTest.kt`:

```kotlin
package dev.joely.bmsmon

import dev.joely.bmsmon.model.LOW_ENTER_PCT
import dev.joely.bmsmon.model.LOW_EXIT_PCT
import dev.joely.bmsmon.model.powerDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerPolicyTest {

    @Test fun thresholdsAreFiveAndFifteen() {
        assertEquals(5, LOW_ENTER_PCT)
        assertEquals(15, LOW_EXIT_PCT)
    }

    @Test fun unpluggedNeverHoldsScreen() {
        for (level in intArrayOf(0, 4, 5, 14, 15, 50, 100)) {
            assertFalse(powerDecision(onExternal = false, levelPct = level, wasLowPower = false).holdScreen)
        }
    }

    @Test fun pluggedAndHealthyHoldsScreen() {
        val d = powerDecision(onExternal = true, levelPct = 50, wasLowPower = false)
        assertTrue(d.holdScreen)
        assertFalse(d.gpsBalanced)
        assertFalse(d.lowPower)
    }

    // Walking down from 20%, the latch must set at 4 and NOT before.
    @Test fun latchSetsBelowFiveAndNotAbove() {
        var low = false
        for (level in intArrayOf(20, 16, 15, 14, 9, 5)) {
            low = powerDecision(onExternal = true, levelPct = level, wasLowPower = low).lowPower
            assertFalse("latch set early at $level", low)
        }
        val d = powerDecision(onExternal = true, levelPct = 4, wasLowPower = low)
        assertTrue(d.lowPower)
        assertFalse(d.holdScreen)
        assertTrue(d.gpsBalanced)
    }

    // Once set, climbing through the 5..14 band must NOT clear it.
    @Test fun latchHoldsThroughHysteresisBand() {
        var low = true
        for (level in intArrayOf(4, 5, 8, 10, 14)) {
            low = powerDecision(onExternal = true, levelPct = level, wasLowPower = low).lowPower
            assertTrue("latch cleared early at $level", low)
        }
    }

    @Test fun latchClearsAtFifteen() {
        val d = powerDecision(onExternal = true, levelPct = 15, wasLowPower = true)
        assertFalse(d.lowPower)
        assertTrue(d.holdScreen)
        assertFalse(d.gpsBalanced)
    }

    // Oscillating inside the band produces zero transitions in either starting state.
    @Test fun noFlappingInsideBand() {
        var low = true
        for (level in intArrayOf(5, 14, 5, 14, 6, 13)) {
            low = powerDecision(onExternal = true, levelPct = level, wasLowPower = low).lowPower
            assertTrue(low)
        }
        var high = false
        for (level in intArrayOf(14, 5, 14, 5, 13, 6)) {
            high = powerDecision(onExternal = true, levelPct = level, wasLowPower = high).lowPower
            assertFalse(high)
        }
    }

    @Test fun gpsBalancedTracksLatchExactly() {
        assertTrue(powerDecision(onExternal = true, levelPct = 4, wasLowPower = false).gpsBalanced)
        assertTrue(powerDecision(onExternal = false, levelPct = 10, wasLowPower = true).gpsBalanced)
        assertFalse(powerDecision(onExternal = false, levelPct = 10, wasLowPower = false).gpsBalanced)
        assertFalse(powerDecision(onExternal = true, levelPct = 15, wasLowPower = true).gpsBalanced)
    }

    // Level is clamped, so a malformed reading can never fabricate a low-power state.
    @Test fun outOfRangeLevelsAreClamped() {
        assertFalse(powerDecision(onExternal = true, levelPct = 200, wasLowPower = true).lowPower)
        assertTrue(powerDecision(onExternal = true, levelPct = -3, wasLowPower = false).lowPower)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/joely/bmsmon/android && ./gradlew :app:testDebugUnitTest --tests 'dev.joely.bmsmon.PowerPolicyTest'`
Expected: FAIL — compilation error, `Unresolved reference: PowerPolicy` / `powerDecision`.

- [ ] **Step 3: Write minimal implementation**

Create `android/app/src/main/java/dev/joely/bmsmon/model/PowerPolicy.kt`:

```kotlin
package dev.joely.bmsmon.model

/** Battery level (%) at or below which the phone is treated as in trouble. */
const val LOW_ENTER_PCT = 5

/** Battery level (%) at which it is considered recovered. */
const val LOW_EXIT_PCT = 15

/**
 * What the phone's power situation means for the app, derived by [powerDecision].
 *
 * [lowPower] is the hysteretic latch and must be fed back in as `wasLowPower` on the next call —
 * it is both an input and an output, which is what makes the band between [LOW_ENTER_PCT] and
 * [LOW_EXIT_PCT] stable instead of flapping.
 */
data class PowerDecision(
    val holdScreen: Boolean,
    val gpsBalanced: Boolean,
    val lowPower: Boolean,
)

/**
 * Fold a power reading into the app's screen/GPS policy.
 *
 * The screen is the phone's dominant drain (measured ~136 mAh/h against ~22 for GNSS and ~1.6 for
 * BLE), so it is held only while on external power AND out of the low-battery latch. The latch
 * exists because holding the screen at very low charge can out-draw the charger, which is how the
 * phone ends up in a shutdown/reboot loop at 0%; it must bank real capacity before the display
 * load returns. The same latch drops GPS to balanced power — that emergency window only, so the
 * still-converging Wh/mile band never learns from coarse fixes.
 *
 * Pure and total: the same inputs always yield the same decision. No clock, no Android types.
 */
fun powerDecision(onExternal: Boolean, levelPct: Int, wasLowPower: Boolean): PowerDecision {
    val level = levelPct.coerceIn(0, 100)
    val lowPower = when {
        level < LOW_ENTER_PCT -> true
        level >= LOW_EXIT_PCT -> false
        else -> wasLowPower  // inside the band: hold, so the latch cannot flap
    }
    return PowerDecision(
        holdScreen = onExternal && !lowPower,
        gpsBalanced = lowPower,
        lowPower = lowPower,
    )
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /home/joely/bmsmon/android && ./gradlew :app:testDebugUnitTest --tests 'dev.joely.bmsmon.PowerPolicyTest'`
Expected: PASS, 9 tests.

- [ ] **Step 5: Commit**

```bash
cd /home/joely/bmsmon
git add android/app/src/main/java/dev/joely/bmsmon/model/PowerPolicy.kt \
        android/app/src/test/java/dev/joely/bmsmon/PowerPolicyTest.kt
git commit -m "feat(android): pure power policy with hysteretic low-battery latch

Latch sets below 5% and clears at 15%, holding its value in between so it
cannot flap. Drives holdScreen (external power AND not low) and gpsBalanced
(low only)."
```

---

### Task 2: PowerMonitor battery receiver

**Files:**
- Create: `android/app/src/main/java/dev/joely/bmsmon/power/PowerMonitor.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `data class PowerStatus(val onExternal: Boolean, val levelPct: Int)`, `class PowerMonitor(context: Context)` with `val status: StateFlow<PowerStatus>`, `fun start()`, `fun stop()`, and `internal fun readPowerStatus(intent: Intent?): PowerStatus` — package `dev.joely.bmsmon.power`. Task 3 depends on these exact names.

This task has no unit test: it is a thin Android-framework adapter (`BroadcastReceiver` + intent extras) with no logic worth mocking the framework for — all the decision logic lives in Task 1, which is fully tested. Its parsing is verified by the manual device check in Task 6, Step 7.

- [ ] **Step 1: Write the implementation**

Create `android/app/src/main/java/dev/joely/bmsmon/power/PowerMonitor.kt`:

```kotlin
package dev.joely.bmsmon.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The phone's own power situation — not to be confused with any BMS pack state. */
data class PowerStatus(val onExternal: Boolean, val levelPct: Int)

/**
 * Watches the phone's charger and battery level via ACTION_BATTERY_CHANGED.
 *
 * That broadcast is sticky, so [start] gets the current state back from registerReceiver
 * immediately — there is nothing to poll. Follows the same register/unregister shape as the
 * engine's Bluetooth adapter receiver.
 */
class PowerMonitor(private val context: Context) {

    private val _status = MutableStateFlow(SAFE_DEFAULT)
    val status: StateFlow<PowerStatus> = _status.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            _status.value = readPowerStatus(intent)
        }
    }

    @Volatile private var registered = false

    fun start() {
        if (registered) return
        runCatching {
            // Sticky broadcast: this returns the current battery intent, so the first status is
            // live rather than the safe default.
            val sticky = context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            registered = true
            _status.value = readPowerStatus(sticky)
        }
    }

    fun stop() {
        if (!registered) return
        registered = false
        // runCatching: unregistering an already-unregistered receiver throws IllegalArgumentException.
        runCatching { context.unregisterReceiver(receiver) }
        _status.value = SAFE_DEFAULT
    }

    companion object {
        /**
         * Fails safe: not plugged in, battery full. Screen is not held and GPS stays high
         * accuracy, so a missing or malformed reading can never fabricate a low-power state.
         */
        val SAFE_DEFAULT = PowerStatus(onExternal = false, levelPct = 100)

        internal fun readPowerStatus(intent: Intent?): PowerStatus {
            if (intent == null) return SAFE_DEFAULT
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            val onExternal = plugged and (
                BatteryManager.BATTERY_PLUGGED_AC or
                    BatteryManager.BATTERY_PLUGGED_USB or
                    BatteryManager.BATTERY_PLUGGED_WIRELESS
                ) != 0
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val pct = if (level < 0 || scale <= 0) 100 else level * 100 / scale
            return PowerStatus(onExternal = onExternal, levelPct = pct)
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `cd /home/joely/bmsmon/android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
cd /home/joely/bmsmon
git add android/app/src/main/java/dev/joely/bmsmon/power/PowerMonitor.kt
git commit -m "feat(android): PowerMonitor reads phone charger + battery level

Sticky ACTION_BATTERY_CHANGED receiver, so the first read is live with no
polling. AC, USB and wireless all count as external power. Malformed extras
fall back to unplugged/100% so they cannot fabricate a low-power state."
```

---

### Task 3: Engine owns the policy and applies GPS

**Files:**
- Modify: `android/app/src/main/java/dev/joely/bmsmon/location/LocationSource.kt` (request builder around line 48-54; add `setBalanced`)
- Modify: `android/app/src/main/java/dev/joely/bmsmon/monitor/MonitorEngine.kt` (`MonitorState` ~line 83; fields near `locationSource` ~line 129; `start()` ~line 231; `stop()` ~line 285)

**Interfaces:**
- Consumes: `powerDecision(...)`, `PowerDecision` (Task 1); `PowerMonitor`, `PowerStatus` (Task 2).
- Produces: `MonitorState.holdScreen: Boolean`, `MonitorState.gpsBalanced: Boolean`, `MonitorState.lowPower: Boolean` (all default `false`); `LocationSource.setBalanced(balanced: Boolean)`. Task 6 depends on `MonitorState.holdScreen`.

- [ ] **Step 1: Add `setBalanced` to LocationSource**

In `location/LocationSource.kt`, replace the `start()` method and the trailing part of the class body. The existing comment at line 48 states a premise that is now conditional, so it is rewritten.

Replace:

```kotlin
    @SuppressLint("MissingPermission") // guarded by hasLocationPermission
    fun start() {
        if (requesting || !hasLocationPermission(context)) return
        requesting = true
        client.lastLocation.addOnSuccessListener { loc ->
            loc?.let { cache.set(GpsFix(it.latitude, it.longitude, if (it.hasAccuracy()) it.accuracy else null, it.time)) }
        }
        // Always-on GNSS (2026-07-13): balanced-power WiFi/cell fixes averaged ~90 m and
        // spawned the phantom map spikes; the phone rides the chair on constant USB power,
        // so there is no battery reason to accept coarse fixes — high accuracy, always.
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000L)
            .setMinUpdateIntervalMillis(2_000L)
            .build()
        client.requestLocationUpdates(req, callback, null)
    }
```

with:

```kotlin
    @SuppressLint("MissingPermission") // guarded by hasLocationPermission
    fun start() {
        if (requesting || !hasLocationPermission(context)) return
        requesting = true
        client.lastLocation.addOnSuccessListener { loc ->
            loc?.let { cache.set(GpsFix(it.latitude, it.longitude, if (it.hasAccuracy()) it.accuracy else null, it.time)) }
        }
        requestUpdates()
    }

    /**
     * Switch between high-accuracy and balanced-power fixes.
     *
     * High accuracy is the norm (2026-07-13): balanced-power WiFi/cell fixes averaged ~90 m and
     * spawned the phantom map spikes, and the phone normally rides the chair on USB power. The
     * ONLY time coarse fixes are accepted is the sub-5% emergency window (2026-07-25), where the
     * phone must claw its way back to a safe charge — brief and rare, so the still-converging
     * Wh/mile band is never fed a meaningful amount of coarse data.
     */
    fun setBalanced(balanced: Boolean) {
        if (balanced == this.balanced) return
        this.balanced = balanced
        if (!requesting) return  // will pick up the new mode on the next start()
        client.removeLocationUpdates(callback)
        requestUpdates()
    }

    @SuppressLint("MissingPermission") // callers guard on hasLocationPermission
    private fun requestUpdates() {
        val req = if (balanced) {
            LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 20_000L)
                .setMinUpdateIntervalMillis(10_000L)
                .build()
        } else {
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000L)
                .setMinUpdateIntervalMillis(2_000L)
                .build()
        }
        client.requestLocationUpdates(req, callback, null)
    }
```

Then add the backing field next to the existing `private var requesting = false` (around line 31):

```kotlin
    private var balanced = false
```

- [ ] **Step 2: Add the three fields to MonitorState**

In `monitor/MonitorEngine.kt`, in `data class MonitorState`, add after `val gpsActive: Boolean = false,`:

```kotlin
    // Phone power policy (2026-07-25), single-writer: only the engine sets these. holdScreen gates
    // FLAG_KEEP_SCREEN_ON in the UI; gpsBalanced downgrades GPS in the sub-5% emergency window;
    // lowPower is the hysteretic latch, fed back into powerDecision on the next reading.
    val holdScreen: Boolean = false,
    val gpsBalanced: Boolean = false,
    val lowPower: Boolean = false,
```

- [ ] **Step 3: Own the PowerMonitor and fold readings**

In `monitor/MonitorEngine.kt`, add the imports:

```kotlin
import dev.joely.bmsmon.model.powerDecision
import dev.joely.bmsmon.power.PowerMonitor
```

Add the field next to `private val locationSource = LocationSource(appContext)` (around line 129):

```kotlin
    private val powerMonitor = PowerMonitor(appContext)
    private var powerJob: Job? = null
```

Add this method next to `setGpsActive` (around line 411):

```kotlin
    /**
     * Fold each phone power reading into the screen/GPS policy. Started with monitoring so the
     * receiver's lifetime matches the engine's, like the Bluetooth one.
     *
     * The screen is the phone's dominant drain by a wide margin, so it is held only on external
     * power and out of the low-battery latch (see PowerPolicy for why the latch exists). Note the
     * GPS half is applied unconditionally — LocationSource.setBalanced is a no-op while GPS is
     * inactive and remembers the mode for the next start(), so this never fights setGpsActive.
     */
    private fun startPowerLoop() {
        powerJob?.cancel()
        powerMonitor.start()
        powerJob = scope.launch {
            powerMonitor.status.collect { ps ->
                val d = powerDecision(
                    onExternal = ps.onExternal,
                    levelPct = ps.levelPct,
                    wasLowPower = _state.value.lowPower,
                )
                _state.update {
                    it.copy(holdScreen = d.holdScreen, gpsBalanced = d.gpsBalanced, lowPower = d.lowPower)
                }
                locationSource.setBalanced(d.gpsBalanced)
            }
        }
    }

    private fun stopPowerLoop() {
        powerJob?.cancel()
        powerJob = null
        powerMonitor.stop()
        locationSource.setBalanced(false)
    }
```

- [ ] **Step 4: Wire into start() and stop()**

In `start()`, immediately after the existing `registerBtReceiver()` line, add:

```kotlin
        startPowerLoop()  // phone power → screen-hold + GPS priority policy
```

In `stop()`, immediately after the existing `unregisterBtReceiver()` line, add:

```kotlin
        stopPowerLoop()
```

`stop()` already rebuilds `MonitorState(...)` from scratch, so `holdScreen`/`gpsBalanced`/`lowPower` return to their `false` defaults with no further change — the screen is not held while monitoring is off, which is correct.

- [ ] **Step 5: Verify it compiles and existing tests still pass**

Run: `cd /home/joely/bmsmon/android && ./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all existing tests pass (`PowerPolicyTest` included).

- [ ] **Step 6: Commit**

```bash
cd /home/joely/bmsmon
git add android/app/src/main/java/dev/joely/bmsmon/location/LocationSource.kt \
        android/app/src/main/java/dev/joely/bmsmon/monitor/MonitorEngine.kt
git commit -m "feat(android): engine folds phone power into screen/GPS policy

MonitorEngine owns the PowerMonitor and publishes holdScreen/gpsBalanced/
lowPower on MonitorState as the single writer. LocationSource gains
setBalanced, dropping to balanced-power fixes only in the sub-5% window so
the converging Wh/mile band is not fed coarse data."
```

---

### Task 4: Monitoring wakelock

**Files:**
- Modify: `android/app/src/main/AndroidManifest.xml` (permission block, lines 6-36)
- Modify: `android/app/src/main/java/dev/joely/bmsmon/monitor/MonitoringService.kt` (fields ~line 41, `onStartCommand` ~line 62, `stopCleanly` ~line 103)

**Interfaces:**
- Consumes: nothing from earlier tasks. Independent of Tasks 1-3 — it can be reviewed and reverted on its own.
- Produces: nothing other tasks reference.

This is the task that makes Task 6 safe. The poll loop is `delay(pollMs)` (`ble/BmsRepository.kt:396`), which is backed by a scheduled executor rather than `AlarmManager` and so does not fire while the CPU is suspended. Today `FLAG_KEEP_SCREEN_ON` keeps the CPU awake as a side effect; once the screen is allowed to sleep, only this wakelock preserves poll cadence.

- [ ] **Step 1: Add the permission**

In `android/app/src/main/AndroidManifest.xml`, add alongside the other `uses-permission` entries (next to `FOREGROUND_SERVICE` on line 27):

```xml
    <!-- Keeps the CPU awake for the BLE poll loop while the screen sleeps: the loop is a
         coroutine delay(), which does not fire during CPU suspend. Held only for the
         duration of a monitoring session. -->
    <uses-permission android:name="android.permission.WAKE_LOCK" />
```

- [ ] **Step 2: Acquire and release in the service**

In `monitor/MonitoringService.kt`, add the imports:

```kotlin
import android.content.Context
import android.os.PowerManager
```

Add the field after `private val engine get() = (application as BmsApp).engine` (line 43):

```kotlin
    // BLE poll cadence depends on the CPU being awake: the loop is a coroutine delay(), which
    // does not fire in suspend. Screen-on used to provide this incidentally; now that the screen
    // is allowed to sleep on battery, this wakelock is what keeps polling, alerting and GPS
    // capture at full cadence. Held for exactly the monitoring session.
    private var wakeLock: PowerManager.WakeLock? = null
```

Add these two methods next to `stopCleanly()` (around line 103):

```kotlin
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        // runCatching: a wakelock failure must never take monitoring down with it — worst case
        // cadence degrades to the old screen-dependent behavior.
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG)
            wl.setReferenceCounted(false)
            wl.acquire()  // no timeout: its lifetime is the monitoring session
            wakeLock = wl
        }
    }

    private fun releaseWakeLock() {
        val wl = wakeLock ?: return
        wakeLock = null
        runCatching { if (wl.isHeld) wl.release() }
    }
```

In `onStartCommand`, on the foreground path, add immediately after the existing `startForegroundCompat(...)` call (line 62):

```kotlin
        acquireWakeLock()
```

In `stopCleanly()`, add as the first line of the method body (before `collectorJob?.cancel()`):

```kotlin
        releaseWakeLock()
```

Every teardown path — the notification Stop action, `onTaskRemoved`, and the collector seeing `!monitoring` — already routes through `stopCleanly()`, so this one call covers them all. `setReferenceCounted(false)` plus the `isHeld` guard makes release idempotent.

- [ ] **Step 3: Verify it compiles**

Run: `cd /home/joely/bmsmon/android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
cd /home/joely/bmsmon
git add android/app/src/main/AndroidManifest.xml \
        android/app/src/main/java/dev/joely/bmsmon/monitor/MonitoringService.kt
git commit -m "feat(android): hold a partial wakelock for the monitoring session

The BLE poll loop is a coroutine delay(), which does not fire while the CPU
is suspended, so keep-screen-on has been load-bearing for poll cadence by
accident. Acquire on the foreground path, release in stopCleanly (which
every teardown path already routes through)."
```

---

### Task 5: Mirror holdScreen into UiState

**Files:**
- Modify: `android/app/src/main/java/dev/joely/bmsmon/BatteryViewModel.kt` (`UiState` ~line 162; engine mirror block ~line 476-486)

**Interfaces:**
- Consumes: `MonitorState.holdScreen` (Task 3).
- Produces: `UiState.screenHoldAllowed: Boolean` (default `false`). Task 6 depends on this exact name.

- [ ] **Step 1: Add the UiState field**

In `BatteryViewModel.kt`, in `data class UiState`, add immediately after `val keepScreenOn: Boolean = true,` (line 162):

```kotlin
    // Mirrored from MonitorState (engine is the single writer): is the phone on external power and
    // above the low-battery latch? Gates keepScreenOn AND locked in App.kt — see PowerPolicy.
    val screenHoldAllowed: Boolean = false,
```

- [ ] **Step 2: Mirror it from the engine**

In the `engine.state.collect { es -> ... }` block (line 476), add `screenHoldAllowed` to the `mirrored` copy, so it becomes:

```kotlin
                    val mirrored = s.copy(
                        monitoring = es.monitoring,
                        screenHoldAllowed = es.holdScreen,
                        cloudOutboxDepth = es.cloudOutboxDepth,
                        cloudLastUploadMs = es.cloudLastUploadMs,
                        cloudUploadKbps = es.cloudUploadKbps,
                        cloudAuthFailed = es.cloudAuthFailed,
                    )
```

It goes in the unconditional `mirrored` block, not the `if (es.monitoring)` branch, so that when monitoring stops the flag correctly falls back to the engine's `false` and the screen is released.

- [ ] **Step 3: Verify it compiles and tests pass**

Run: `cd /home/joely/bmsmon/android && ./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 4: Commit**

```bash
cd /home/joely/bmsmon
git add android/app/src/main/java/dev/joely/bmsmon/BatteryViewModel.kt
git commit -m "feat(android): mirror holdScreen into UiState.screenHoldAllowed

Unconditional mirror block, so stopping monitoring releases the screen hold
rather than leaving the last value latched."
```

---

### Task 6: Gate the screen flag, then verify on device

**Files:**
- Modify: `android/app/src/main/java/dev/joely/bmsmon/ui/App.kt:78-87`

**Interfaces:**
- Consumes: `UiState.screenHoldAllowed` (Task 5).
- Produces: nothing.

- [ ] **Step 1: Gate the keepOn expression**

In `ui/App.kt`, replace:

```kotlin
    // Hold the screen on (at the user's brightness) while the app is open, when enabled.
    val activity = context.findActivity()
    val window = activity?.window
    // Locking forces the screen on regardless of the setting; unlocking reverts to the setting.
    val keepOn = state.keepScreenOn || state.locked
```

with:

```kotlin
    // Hold the screen on (at the user's brightness) while the app is open, when enabled.
    val activity = context.findActivity()
    val window = activity?.window
    // Locking forces the screen on regardless of the setting; unlocking reverts to the setting.
    // Both are gated on phone power (2026-07-25): the screen is ~136 mAh/h, far and away the app's
    // largest drain, so it is held only on external power and above the low-battery latch. The
    // gate wraps the WHOLE expression — unplugging always lets the display sleep, lock included.
    // Monitoring is unaffected: MonitoringService's wakelock keeps poll cadence with the screen off.
    val keepOn = state.screenHoldAllowed && (state.keepScreenOn || state.locked)
```

The `DisposableEffect(window, keepOn)` below it is unchanged — it already re-runs whenever `keepOn` flips and clears the flag in `onDispose`.

- [ ] **Step 2: Verify the whole project builds and every test passes**

Run: `cd /home/joely/bmsmon/android && ./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 3: Install on the phone**

The device is a Pixel 6 over USB (`adb devices` shows `1C091FDF6003V0`).

Run: `cd /home/joely/bmsmon/android && ./gradlew :app:installDebug`

**IMPORTANT — never uninstall.** Use in-place install only; the app holds the Room `bms.db` history and DataStore settings that the range/tail learners depend on. `installDebug` replaces in place. Do not run `adb uninstall`.

- [ ] **Step 4: Confirm the wakelock is held while monitoring**

With monitoring on, run:

```bash
adb shell dumpsys power | grep -i "bmsmon:monitoring"
```

Expected: a `PARTIAL_WAKE_LOCK` line containing `bmsmon:monitoring`.

- [ ] **Step 5: Confirm screen-hold on external power**

With the phone plugged in and above 15%, open the app.
Expected: the display does not time out (unchanged from today's behavior).

- [ ] **Step 6: Confirm screen release on battery**

Unplug the phone (above 15%).
Expected: the display times out normally. Then confirm monitoring survived it:

```bash
adb shell dumpsys power | grep -i "bmsmon:monitoring"   # still held
```

Wake the phone and confirm the fleet timestamps advanced while the screen was off — packs still read as connected with fresh values, not `DISCONNECTED`.

- [ ] **Step 7: Confirm the low-battery latch**

The phone was at 4% on AC during design, so this state is reachable by letting it drain.
With the phone plugged in and **below 5%**:
Expected: the display is NOT held on despite being plugged in, and it stays that way until the level reaches 15%, at which point the hold returns.

If waiting on a real drain is impractical, verify the boundary logic via `PowerPolicyTest` (already covering 4/5/14/15 in both latch directions) and spot-check the live reading matches the phone:

```bash
adb shell dumpsys battery | grep -E "level|AC powered|USB powered|Wireless powered"
```

- [ ] **Step 8: Commit**

```bash
cd /home/joely/bmsmon
git add android/app/src/main/java/dev/joely/bmsmon/ui/App.kt
git commit -m "feat(android): hold the screen only on external power

Gates keep-screen-on AND lock mode on the phone being plugged in and above
the low-battery latch. The screen measured ~136 mAh/h against ~22 for GNSS
and ~1.6 for BLE, so this is the app's dominant drain; the service wakelock
keeps poll cadence identical with the display off."
```

---

### Task 7: Update project documentation

**Files:**
- Modify: `CLAUDE.md` (Android App section)
- Modify: `~/GoogleDrive/obsidian/notes/Bmsmon.md`

**Interfaces:**
- Consumes: the finished behavior from Tasks 1-6.
- Produces: nothing.

`CLAUDE.md` requires that meaningful architecture changes be recorded there and mirrored in the Obsidian note. The always-on GNSS claim in the existing text is now conditional and would mislead a future reader.

- [ ] **Step 1: Add a section to CLAUDE.md**

In `/home/joely/bmsmon/CLAUDE.md`, in the `## Android App (android/)` section, add after the "Background monitoring (foreground service)" paragraph:

```markdown
**Screen policy is plug-aware, and monitoring holds a wakelock.** The display is the phone's
dominant drain — measured on the Pixel 6 at ~136 mAh/h against ~22 for GNSS and ~1.6 for
Bluetooth, with the app the top consumer at 600 mAh over 4 h — so `FLAG_KEEP_SCREEN_ON` is held
only while the phone is on external power (AC/USB/wireless) AND above a low-battery latch. The
latch (`model/PowerPolicy.kt`, pure + unit-tested) **sets below 5% and clears at 15%**, holding
its value in between so it cannot flap; it exists because holding the screen at very low charge
out-draws the charger and puts the phone in a shutdown/reboot loop at 0%. The gate wraps the
whole expression in `ui/App.kt` — **lock mode is gated too**, so unplugging always lets the
display sleep. `power/PowerMonitor.kt` (sticky `ACTION_BATTERY_CHANGED`) feeds it;
`MonitorEngine` is the single writer of `holdScreen`/`gpsBalanced`/`lowPower` on `MonitorState`.

Because the BLE poll loop is a coroutine `delay()` — which does NOT fire while the CPU is
suspended — keep-screen-on had been load-bearing for poll cadence *by accident*.
`MonitoringService` now holds a `PARTIAL_WAKE_LOCK` (`bmsmon:monitoring`) for the monitoring
session, so cadence, alerts, logging and GPS capture are identical with the screen dark. **Do not
remove that wakelock without replacing the timer with an `AlarmManager`-backed one.**

The same latch drops GPS to `PRIORITY_BALANCED_POWER_ACCURACY` (20 s) — **only** in that sub-5%
emergency window, never in normal unplugged use. Coarse fixes are what caused the 2026-07-13
phantom map spikes, and the Wh/mile band is still converging off seed, so it must not learn from
them at scale.
```

- [ ] **Step 2: Correct the now-conditional GNSS claim**

Still in `CLAUDE.md`, in the "Discharge estimate" paragraph, find:

```
Location capture is
**always-on PRIORITY_HIGH_ACCURACY GNSS** (5 s) — the phone rides the chair on USB power)
```

and replace with:

```
Location capture is
**PRIORITY_HIGH_ACCURACY GNSS** (5 s) in all normal use — the phone rides the chair on USB power;
it drops to balanced power (20 s) ONLY inside the sub-5% low-battery latch, see the screen-policy
section)
```

- [ ] **Step 3: Update the Obsidian note**

In `~/GoogleDrive/obsidian/notes/Bmsmon.md`, add a line to the Android/status section:

```markdown
- Screen is held only on external power and above a 5%/15% low-battery latch (the display is
  ~136 mAh/h, the app's dominant drain); `MonitoringService` holds a partial wakelock so BLE poll
  cadence is unchanged with the screen off.
```

- [ ] **Step 4: Commit**

```bash
cd /home/joely/bmsmon
git add CLAUDE.md
git commit -m "docs: record plug-aware screen policy and monitoring wakelock"
```

(The Obsidian vault is outside this repo and is not committed here.)

---

## Self-Review

**1. Spec coverage**

| Spec section | Task |
|---|---|
| `power/PowerMonitor.kt` + `PowerStatus` | Task 2 |
| `model/PowerPolicy.kt` + latch, `LOW_ENTER_PCT`/`LOW_EXIT_PCT` | Task 1 |
| `MonitorEngine` owns monitor, folds policy, `MonitorState` fields | Task 3 |
| `LocationSource.setBalanced` + priority/interval values | Task 3 |
| Comment at `LocationSource.kt:48` rewritten | Task 3, Step 1 |
| `MonitoringService` wakelock + `WAKE_LOCK` permission | Task 4 |
| `BatteryViewModel` / `UiState.screenHoldAllowed` mirror | Task 5 |
| `ui/App.kt` gate wrapping the whole expression (lock included) | Task 6 |
| Behavior matrix | Task 6, Steps 5-7 (manual) + Task 1 tests |
| Error handling: degenerate extras fail safe | Task 1 (`outOfRangeLevelsAreClamped`), Task 2 (`SAFE_DEFAULT`) |
| Error handling: receiver not yet fired | Task 3 (`MonitorState` defaults `false`) |
| Error handling: wakelock acquire throws | Task 4 (`runCatching`) |
| Error handling: monitoring off entirely | Task 3, Step 4 (`stop()` rebuilds state) |
| Testing: `PowerPolicyTest` full list | Task 1, Step 1 |
| Testing: manual device verification | Task 6, Steps 3-7 |
| Out-of-scope items | Not implemented, as specified |

No gaps. Task 7 (documentation) is added beyond the spec because `CLAUDE.md` mandates it and the existing always-on-GNSS wording would otherwise become wrong.

**2. Placeholder scan**

No TBD/TODO, no "add error handling", no "similar to Task N". Every code step carries literal code. All eight of the spec's test cases appear as real assertions in Task 1, Step 1.

**3. Type consistency**

- `powerDecision(onExternal, levelPct, wasLowPower)` — defined Task 1, called Task 3 with the same three named arguments.
- `PowerDecision.holdScreen`/`.gpsBalanced`/`.lowPower` — defined Task 1, consumed Task 3.
- `PowerMonitor.status`/`.start()`/`.stop()` — defined Task 2, used Task 3.
- `PowerStatus.onExternal`/`.levelPct` — defined Task 2, destructured Task 3.
- `MonitorState.holdScreen` — added Task 3, mirrored Task 5.
- `UiState.screenHoldAllowed` — added Task 5, read Task 6.
- `LocationSource.setBalanced(Boolean)` — added Task 3, called Task 3 only.
- `PowerMonitor.SAFE_DEFAULT` and `readPowerStatus` — both defined and used within Task 2.

Consistent throughout.
