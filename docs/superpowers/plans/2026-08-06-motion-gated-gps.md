# Motion-Gated GPS Pause Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop the parked-GPS pause from switching GNSS off during vehicle transit, by requiring the phone to be *confidently still* as well as the chair to be non-discharging before pausing.

**Architecture:** All decision logic stays in the existing pure Kotlin file `model/BatterySaver.kt` (no Android imports, JVM-unit-tested). A new `motion/MotionSource.kt` wraps Play Services periodic Activity Recognition and produces a `MotionReading`, mirroring the existing `location/LocationSource.kt`. `MonitorEngine` remains the single writer of `MonitorState.gpsActive`, and `applyGpsGate` remains the one call site.

**Tech Stack:** Kotlin, Jetpack Compose, Google Play Services Location (already a dependency), JUnit 4.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-08-06-motion-gated-gps-design.md`. Read it before starting.
- Repo root `/home/joely/bmsmon`; gradle working directory `/home/joely/bmsmon/android`.
- Build: `./gradlew :app:assembleDebug` · Unit tests: `./gradlew :app:testDebugUnitTest` (**358 tests currently pass — must stay green**).
- Commit messages must contain **no** reference to AI, Claude, or automated generation. Hard repo rule.
- **`MonitorEngine` must remain the single writer of `MonitorState.gpsActive`.**
- **The gate may only ever SUBTRACT from `gpsWanted`.** No combination of inputs may turn GPS on when the cloud settings do not want it.
- **Fail open:** every unusable-signal path (permission denied, AR unavailable, subscription lapsed, no reading yet, stale reading) must result in GPS staying **on**. This is an explicit user decision.
- Permission is `android.permission.ACTIVITY_RECOGNITION` — **not** `ACCESS_ACTIVITY_RECOGNITION`, which does not exist.
- This app is read-only over BLE. Do not modify anything under `ble/`. Never send a BMS write command.
- Do not change existing defaults (`lockLowRefresh` true, `lockDimScreen` false, `lockDimLevel` 0.30f, `gpsPauseParked` true).
- **Device protocol — the phone is the user's live wheelchair battery monitor:**
  - `adb install -r` only. **NEVER `adb uninstall dev.joely.bmsmon`** (~400 MB of irreplaceable field telemetry).
  - `install -r` leaves the app stopped — always relaunch with `adb -s <serial> shell am start -n dev.joely.bmsmon/.MainActivity`, then confirm with `ps -A | grep bmsmon`. `monkey ... LAUNCHER` reports success but does **not** start it.
  - Re-derive tap coordinates from a fresh `uiautomator dump` for **every** tap.
  - Serial: `adb-1C091FDF6003V0-RQzUxy._adb-tls-connect._tcp`

## File Structure

| File | Responsibility |
|---|---|
| `app/src/main/java/dev/joely/bmsmon/model/BatterySaver.kt` (modify) | `MotionReading`, `confidentlyStill()`, thresholds, and the extended `gpsShouldRun()`. Pure, no Android types. |
| `app/src/main/java/dev/joely/bmsmon/motion/MotionSource.kt` (create) | Wraps Play Services periodic Activity Recognition; produces `MotionReading`. Mirrors `location/LocationSource.kt`. |
| `app/src/main/java/dev/joely/bmsmon/monitor/MonitorEngine.kt` (modify) | Owns the `MotionSource` lifecycle; feeds its reading into `applyGpsGate`. |
| `app/src/main/AndroidManifest.xml` (modify) | Declares `ACTIVITY_RECOGNITION`. |
| `app/src/main/java/dev/joely/bmsmon/ui/App.kt` (modify) | Opportunistic permission request. |
| `app/src/main/java/dev/joely/bmsmon/ui/settings/SettingsScreen.kt` (modify) | Read-only "motion sensing" mode line. |
| `app/src/test/java/dev/joely/bmsmon/BatterySaverTest.kt` (modify) | Tests for the two pure functions. |

---

### Task 1: Pure motion policy

**Files:**
- Modify: `app/src/main/java/dev/joely/bmsmon/model/BatterySaver.kt` (append; do not alter `gpsShouldRun` yet)
- Test: `app/src/test/java/dev/joely/bmsmon/BatterySaverTest.kt` (append)

**Interfaces:**
- Consumes: nothing.
- Produces: `data class MotionReading(val still: Boolean, val confidence: Int, val atMs: Long)`, `confidentlyStill(reading: MotionReading?, nowMs: Long): Boolean`, `STILL_CONFIDENCE_MIN = 75`, `MOTION_STALE_MS = 150_000L`.

This task adds new pure code only. `gpsShouldRun` is deliberately **not** changed here — changing it before the engine can supply a real reading would disable the pause outright, so signature and wiring land together in Task 3.

- [ ] **Step 1: Write the failing test**

Append to `app/src/test/java/dev/joely/bmsmon/BatterySaverTest.kt`:

```kotlin
    // ── confidentlyStill ─────────────────────────────────────────────────────
    // Every "no usable signal" path must return false, because false means GPS
    // STAYS ON. That is the explicit fail-open decision: losing an outing is
    // worse than losing the battery saving.

    private fun reading(still: Boolean, conf: Int, age: Long, now: Long = 10_000_000L) =
        MotionReading(still = still, confidence = conf, atMs = now - age)

    @Test fun noReadingIsNotConfidentlyStill() {
        assertFalse(confidentlyStill(null, 10_000_000L))
    }

    @Test fun movingIsNotConfidentlyStill() {
        assertFalse(confidentlyStill(reading(still = false, conf = 99, age = 0), 10_000_000L))
    }

    @Test fun lowConfidenceStillIsNotConfidentlyStill() {
        assertFalse(confidentlyStill(reading(still = true, conf = 74, age = 0), 10_000_000L))
    }

    // Threshold fires AT its value, matching the alert-ladder convention.
    @Test fun exactlyAtConfidenceThresholdIsStill() {
        assertTrue(confidentlyStill(reading(still = true, conf = STILL_CONFIDENCE_MIN, age = 0), 10_000_000L))
    }

    @Test fun staleReadingIsNotConfidentlyStill() {
        assertFalse(confidentlyStill(reading(still = true, conf = 99, age = MOTION_STALE_MS + 1), 10_000_000L))
    }

    // Boundary is inclusive: exactly at the staleness bound still counts.
    @Test fun exactlyAtStalenessBoundIsStill() {
        assertTrue(confidentlyStill(reading(still = true, conf = 99, age = MOTION_STALE_MS), 10_000_000L))
    }

    @Test fun freshConfidentStillIsStill() {
        assertTrue(confidentlyStill(reading(still = true, conf = 99, age = 1_000), 10_000_000L))
    }

    @Test fun motionThresholdsAreSeventyFiveAndTwoAndAHalfMinutes() {
        assertEquals(75, STILL_CONFIDENCE_MIN)
        assertEquals(150_000L, MOTION_STALE_MS)
    }
```

Add these imports to the test file's import block:

```kotlin
import dev.joely.bmsmon.model.MOTION_STALE_MS
import dev.joely.bmsmon.model.MotionReading
import dev.joely.bmsmon.model.STILL_CONFIDENCE_MIN
import dev.joely.bmsmon.model.confidentlyStill
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd /home/joely/bmsmon/android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.BatterySaverTest"
```

Expected: FAIL — compilation error, unresolved reference `confidentlyStill` / `MotionReading`.

- [ ] **Step 3: Write the implementation**

Append to `app/src/main/java/dev/joely/bmsmon/model/BatterySaver.kt`:

```kotlin
/**
 * A single phone-motion sample, as produced by `motion/MotionSource`.
 *
 * [still] is true when the most probable detected activity is STILL; [confidence] is that entry's
 * 0–100 confidence; [atMs] is wall-clock (`System.currentTimeMillis()`), the same clock
 * [confidentlyStill] compares against.
 */
data class MotionReading(val still: Boolean, val confidence: Int, val atMs: Long)

/** Minimum confidence before a STILL reading is trusted enough to pause GNSS. */
const val STILL_CONFIDENCE_MIN = 75

/** A motion reading older than this is treated as no reading at all — 5 missed 30 s polls. */
const val MOTION_STALE_MS = 150_000L

/**
 * Whether the phone is confidently stationary — the second condition for pausing GNSS.
 *
 * **Every "no usable signal" path returns false, and false means GPS STAYS ON**: permission denied,
 * activity recognition unavailable on the device, subscription lapsed, process restarted with no
 * reading yet, or updates gone stale. That is a deliberate user decision (2026-08-06): losing an
 * outing is worse than losing the battery saving, because a paused GNSS makes a real trip
 * indistinguishable from a nap at home.
 *
 * Kept as one expression on purpose, so the fail-open property cannot drift as callers are added.
 */
fun confidentlyStill(reading: MotionReading?, nowMs: Long): Boolean =
    reading != null &&
        reading.still &&
        reading.confidence >= STILL_CONFIDENCE_MIN &&
        nowMs - reading.atMs <= MOTION_STALE_MS
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd /home/joely/bmsmon/android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.BatterySaverTest"
```

Expected: PASS. Then run the full suite — it must still be green:

```bash
cd /home/joely/bmsmon/android && ./gradlew :app:testDebugUnitTest
```

- [ ] **Step 5: Commit**

```bash
cd /home/joely/bmsmon && git add android/app/src/main/java/dev/joely/bmsmon/model/BatterySaver.kt android/app/src/test/java/dev/joely/bmsmon/BatterySaverTest.kt && git commit -m "feat(android): pure confidently-still motion policy

Fails open in every unusable-signal case, kept as one expression so that
property cannot drift."
```

---

### Task 2: `MotionSource`

**Files:**
- Create: `app/src/main/java/dev/joely/bmsmon/motion/MotionSource.kt`
- Modify: `app/src/main/AndroidManifest.xml` (permission block, near the existing `ACCESS_*_LOCATION` lines at ~18-21)

**Interfaces:**
- Consumes: `MotionReading` (Task 1).
- Produces: `class MotionSource(context: Context)` with `fun start()`, `fun stop()`, `fun current(): MotionReading?`, and `companion object { fun hasPermission(context: Context): Boolean }`.

This task creates the wrapper but wires nothing to it. The app compiles and behaves exactly as before.

- [ ] **Step 1: Declare the permission**

In `app/src/main/AndroidManifest.xml`, after the existing `ACCESS_BACKGROUND_LOCATION` line:

```xml
    <!-- Motion sensing for the parked-GPS gate: distinguishes "chair parked at home" from
         "chair riding in a van", where the chair draws nothing either way. Never gates
         monitoring — a missing grant simply means GNSS is not paused. -->
    <uses-permission android:name="android.permission.ACTIVITY_RECOGNITION" />
```

- [ ] **Step 2: Create the source**

Create `app/src/main/java/dev/joely/bmsmon/motion/MotionSource.kt`:

```kotlin
package dev.joely.bmsmon.motion

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity
import dev.joely.bmsmon.model.MotionReading
import java.util.concurrent.atomic.AtomicReference

/**
 * Phone-motion sampling for the parked-GPS gate, mirroring [dev.joely.bmsmon.location.LocationSource]:
 * start/stop, latest reading held in an [AtomicReference], no Android types leaking upward.
 *
 * Uses the **periodic** Activity Recognition API rather than Activity Transitions. Transitions are
 * cheaper but hinge on catching a single edge — one missed or late `ENTER IN_VEHICLE` loses the
 * outing, which is precisely the failure this feature exists to fix, and a silently lapsed
 * subscription is indistinguishable from "never moved". Periodic updates re-assert current state
 * every cycle, so a missed sample self-corrects on the next one.
 */
class MotionSource(private val context: Context) {

    private val client = ActivityRecognition.getClient(context)
    private val cache = AtomicReference<MotionReading?>(null)
    private var requesting = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val result = ActivityRecognitionResult.extractResult(intent) ?: return
            val top = result.mostProbableActivity
            // UNKNOWN, IN_VEHICLE, WALKING, ON_FOOT, ON_BICYCLE and TILTING all yield still=false,
            // which keeps GPS on. Not knowing is not the same as knowing it is stationary.
            cache.set(
                MotionReading(
                    still = top.type == DetectedActivity.STILL,
                    confidence = top.confidence,
                    atMs = System.currentTimeMillis(),
                ),
            )
        }
    }

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    @Synchronized
    fun start() {
        if (requesting || !hasPermission(context)) return
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(ACTION), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        runCatching { client.requestActivityUpdates(INTERVAL_MS, pendingIntent()) }
            .onFailure { runCatching { context.unregisterReceiver(receiver) }; return }
        requesting = true
    }

    /** Latest reading, or null when none has arrived — null fails open to "not still". */
    fun current(): MotionReading? = cache.get()

    @Synchronized
    fun stop() {
        if (!requesting) return
        requesting = false
        runCatching { client.removeActivityUpdates(pendingIntent()) }
        runCatching { context.unregisterReceiver(receiver) }
        cache.set(null)
    }

    companion object {
        private const val ACTION = "dev.joely.bmsmon.MOTION_UPDATE"

        /** ~30 s. Fast enough to notice a van pulling away, slow enough to stay cheap. */
        private const val INTERVAL_MS = 30_000L

        fun hasPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
                PackageManager.PERMISSION_GRANTED
    }
}
```

**Note on `stop()`:** it clears the cache, so a stale pre-stop reading can never be read after a restart. That matters — a stale "still" would pause GNSS on bad data.

- [ ] **Step 3: Build**

```bash
cd /home/joely/bmsmon/android && ./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL. If `FLAG_MUTABLE` is unresolved, the compileSdk is below 31 — check `app/build.gradle.kts` (it is 34) rather than substituting a different flag.

- [ ] **Step 4: Run the full suite**

```bash
cd /home/joely/bmsmon/android && ./gradlew :app:testDebugUnitTest
```

Expected: PASS, unchanged count.

- [ ] **Step 5: Commit**

```bash
cd /home/joely/bmsmon && git add android/app/src/main/java/dev/joely/bmsmon/motion/MotionSource.kt android/app/src/main/AndroidManifest.xml && git commit -m "feat(android): MotionSource wrapping periodic activity recognition

Periodic rather than transitions: an edge-triggered API loses the outing
on a single missed event, which is the failure being fixed."
```

---

### Task 3: Wire the gate

**Files:**
- Modify: `app/src/main/java/dev/joely/bmsmon/model/BatterySaver.kt` (`gpsShouldRun`)
- Modify: `app/src/main/java/dev/joely/bmsmon/monitor/MonitorEngine.kt` (`locationSource` declaration ~line 140; `applyGpsGate` ~478-488; `shutdownGps` ~506-511; `setGpsActive`)
- Modify: `app/src/test/java/dev/joely/bmsmon/BatterySaverTest.kt`

**Interfaces:**
- Consumes: `confidentlyStill()`, `MotionReading` (Task 1); `MotionSource` (Task 2).
- Produces: `gpsShouldRun(wanted, pauseEnabled, lastDischargeMs, nowMs, confidentlyStill, holdMs)` — the `confidentlyStill: Boolean` parameter is **new and has no default**, so every call site must pass it explicitly.

This is the behavior change. It lands atomically with the engine wiring.

- [ ] **Step 1: Write the failing tests**

Append to `app/src/test/java/dev/joely/bmsmon/BatterySaverTest.kt`:

```kotlin
    // ── gpsShouldRun with the motion gate ────────────────────────────────────
    // Pausing now needs BOTH conditions: chair not discharging AND phone still.

    @Test fun pausesOnlyWhenParkedAndStill() {
        val now = 10_000_000L
        assertFalse(
            gpsShouldRun(
                wanted = true, pauseEnabled = true,
                lastDischargeMs = now - PARKED_HOLD_MS, nowMs = now, confidentlyStill = true,
            ),
        )
    }

    // THE TRANSIT CASE: chair drew nothing for an hour (it is in a van), but the phone is
    // moving, so GPS must stay on. This is the entire point of the feature.
    @Test fun parkedButMovingKeepsGpsOn() {
        val now = 10_000_000L
        assertTrue(
            gpsShouldRun(
                wanted = true, pauseEnabled = true,
                lastDischargeMs = now - 3_600_000L, nowMs = now, confidentlyStill = false,
            ),
        )
    }

    @Test fun recentDischargeKeepsGpsOnRegardlessOfStillness() {
        val now = 10_000_000L
        for (still in booleanArrayOf(true, false)) {
            assertTrue(
                gpsShouldRun(
                    wanted = true, pauseEnabled = true,
                    lastDischargeMs = now - 1_000L, nowMs = now, confidentlyStill = still,
                ),
            )
        }
    }

    @Test fun pauseDisabledIgnoresBothConditions() {
        val now = 10_000_000L
        assertTrue(
            gpsShouldRun(
                wanted = true, pauseEnabled = false,
                lastDischargeMs = null, nowMs = now, confidentlyStill = true,
            ),
        )
    }

    // The gate can still only ever SUBTRACT from what the cloud settings want.
    @Test fun neverRunsWhenNotWantedWhateverTheMotionState() {
        val now = 10_000_000L
        for (still in booleanArrayOf(true, false)) {
            for (pause in booleanArrayOf(true, false)) {
                assertFalse(
                    gpsShouldRun(
                        wanted = false, pauseEnabled = pause,
                        lastDischargeMs = now, nowMs = now, confidentlyStill = still,
                    ),
                )
            }
        }
    }
```

The pre-existing `gpsShouldRun` tests from the original feature must also be updated to pass the new argument — they described a world with no motion input. For each, pass `confidentlyStill = true` (the old implicit behavior, where parked alone was enough to pause) so their original intent is preserved.

- [ ] **Step 2: Run to verify it fails**

```bash
cd /home/joely/bmsmon/android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.BatterySaverTest"
```

Expected: FAIL — `gpsShouldRun` has no parameter `confidentlyStill`.

- [ ] **Step 3: Extend the pure function**

In `app/src/main/java/dev/joely/bmsmon/model/BatterySaver.kt`, replace `gpsShouldRun` with:

```kotlin
/**
 * Whether GPS capture should actually run: what the cloud settings want, minus the parked gate.
 *
 * [wanted] is `monitoring && gpsEnabled && enrolled && cloudEnabled`, decided elsewhere. The gate
 * can only ever SUBTRACT from it — it never turns GPS on.
 *
 * Pausing needs **both** conditions: the chair has not discharged recently **and** the phone is
 * confidently still. The chair draws nothing in a van or on a train, so discharge alone reads
 * transit as "parked" and switches GNSS off for the whole journey — measured 2026-08-06 as three
 * outings that were entirely invisible on the map, destinations included.
 */
fun gpsShouldRun(
    wanted: Boolean,
    pauseEnabled: Boolean,
    lastDischargeMs: Long?,
    nowMs: Long,
    confidentlyStill: Boolean,
    holdMs: Long = PARKED_HOLD_MS,
): Boolean = wanted && !(pauseEnabled && gpsParked(lastDischargeMs, nowMs, holdMs) && confidentlyStill)
```

- [ ] **Step 4: Wire the engine**

In `MonitorEngine.kt`, beside the existing `private val locationSource = LocationSource(appContext)` (~line 140):

```kotlin
    private val motionSource = MotionSource(appContext)
```

In `applyGpsGate`, pass the reading through the pure policy:

```kotlin
    private fun applyGpsGate(now: Long) {
        val active = gpsShouldRun(
            wanted = gpsWanted,
            pauseEnabled = gpsPauseParked,
            lastDischargeMs = _state.value.lastDischargeAt.values.maxOrNull(),
            nowMs = now,
            confidentlyStill = confidentlyStill(motionSource.current(), now),
        )
        if (_state.value.gpsActive == active) return
        _state.update { it.copy(gpsActive = active) }
        if (active) locationSource.start() else locationSource.stop()
    }
```

The `MotionSource` must run whenever the pause could apply — i.e. whenever GPS is wanted at all, since it is what decides whether to pause. Start it in `setGpsActive` alongside recording intent, and stop it in `shutdownGps`:

```kotlin
    /** Record whether GPS capture is wanted at all; the parked gate decides if it actually runs. */
    fun setGpsActive(active: Boolean) {
        gpsWanted = active
        if (active) motionSource.start() else motionSource.stop()
        applyGpsGate(System.currentTimeMillis())
    }
```

and inside the existing `@Synchronized shutdownGps()`, after `locationSource.stop()`:

```kotlin
        motionSource.stop()
```

Add imports: `dev.joely.bmsmon.model.confidentlyStill`, `dev.joely.bmsmon.motion.MotionSource`.

**Do not** add a second writer of `gpsActive`; `applyGpsGate` and `shutdownGps` remain the only two.

- [ ] **Step 5: Run the tests**

```bash
cd /home/joely/bmsmon/android && ./gradlew :app:testDebugUnitTest
```

Expected: PASS, including `MonitorRestoreTest`.

- [ ] **Step 6: Build, install, verify on-device**

```bash
cd /home/joely/bmsmon/android && ./gradlew :app:assembleDebug && \
adb -s adb-1C091FDF6003V0-RQzUxy._adb-tls-connect._tcp install -r app/build/outputs/apk/debug/app-debug.apk && \
adb -s adb-1C091FDF6003V0-RQzUxy._adb-tls-connect._tcp shell am start -n dev.joely.bmsmon/.MainActivity && \
adb -s adb-1C091FDF6003V0-RQzUxy._adb-tls-connect._tcp shell 'ps -A | grep bmsmon'
```

Grant the permission (it will not have been requested yet — that is Task 4):

```bash
adb -s adb-1C091FDF6003V0-RQzUxy._adb-tls-connect._tcp shell pm grant dev.joely.bmsmon android.permission.ACTIVITY_RECOGNITION
```

Then, with the chair stationary for 5+ minutes, confirm the foreground-service type drops to `0x00000010` (GPS paused) as it does today:

```bash
adb -s adb-1C091FDF6003V0-RQzUxy._adb-tls-connect._tcp shell 'dumpsys activity services dev.joely.bmsmon' | grep -oE "types=0x[0-9a-f]+"
```

Expected `types=0x00000010`. If it stays `0x00000018`, motion readings are not arriving — check the permission grant and logcat before assuming the gate is wrong.

- [ ] **Step 7: Commit**

```bash
cd /home/joely/bmsmon && git add android/app/src/main/java/dev/joely/bmsmon/model/BatterySaver.kt android/app/src/main/java/dev/joely/bmsmon/monitor/MonitorEngine.kt android/app/src/test/java/dev/joely/bmsmon/BatterySaverTest.kt && git commit -m "feat(android): require phone stillness before pausing GPS

The chair draws nothing in a van, so discharge alone read transit as
parked and switched GNSS off for whole journeys."
```

---

### Task 4: Request the permission

**Files:**
- Modify: `app/src/main/java/dev/joely/bmsmon/ui/App.kt` (beside `askNotificationPermission`, ~lines 155-165)

**Interfaces:**
- Consumes: `MotionSource.hasPermission` (Task 2).
- Produces: no new symbols.

- [ ] **Step 1: Add the launcher and request function**

In `ui/App.kt`, following the existing `notifLauncher` / `askNotificationPermission` pattern exactly:

```kotlin
    val motionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* result ignored: a denial just means GNSS is never paused */ }
    fun askMotionPermission() {
        if (Build.VERSION.SDK_INT >= 29 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            motionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        }
    }
```

Call it from the same place `askNotificationPermission()` is called after the BLE permission result (~line 171):

```kotlin
            askNotificationPermission()
            askMotionPermission()
```

**It must never gate monitoring.** A denial is fine — it simply means GNSS is never paused, which is the fail-open behavior.

- [ ] **Step 2: Build and run the suite**

```bash
cd /home/joely/bmsmon/android && ./gradlew :app:assembleDebug :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL, tests green.

- [ ] **Step 3: Commit**

```bash
cd /home/joely/bmsmon && git add android/app/src/main/java/dev/joely/bmsmon/ui/App.kt && git commit -m "feat(android): request activity-recognition permission opportunistically

Never gates monitoring: a denial only means GNSS is never paused."
```

---

### Task 5: Settings mode line

**Files:**
- Modify: `app/src/main/java/dev/joely/bmsmon/ui/settings/SettingsScreen.kt` (`BatterySaverContent`, after the "Pause GPS while parked" `ToggleRow` at ~1353-1359)

**Interfaces:**
- Consumes: `MotionSource.hasPermission` (Task 2).
- Produces: no new symbols.

The chosen fallback means a denied permission silently disables the battery saving while the toggle still reads as on — the same shape as the 2026-08-06 charging-icon bug, where the UI asserted something reality contradicted. This line makes the mode visible. It changes no behavior.

- [ ] **Step 1: Add the line**

Immediately after the `ToggleRow` for "Pause GPS while parked", inside the same `GroupedCard`:

```kotlin
        RowHairline(inset = 0.dp)
        val motionOk = MotionSource.hasPermission(LocalContext.current)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (motionOk) "Motion sensing active"
                else "Motion sensing unavailable — GPS won't pause",
                color = if (motionOk) c.text2 else Bm.accent,
                fontSize = 12.sp, lineHeight = 16.sp,
            )
        }
```

Add imports: `dev.joely.bmsmon.motion.MotionSource`, `androidx.compose.ui.platform.LocalContext` (add only if not already present — check first).

**Read-only.** Do not make it tappable and do not add a second toggle.

- [ ] **Step 2: Build, install, verify both states on-device**

```bash
cd /home/joely/bmsmon/android && ./gradlew :app:assembleDebug && \
adb -s adb-1C091FDF6003V0-RQzUxy._adb-tls-connect._tcp install -r app/build/outputs/apk/debug/app-debug.apk && \
adb -s adb-1C091FDF6003V0-RQzUxy._adb-tls-connect._tcp shell am start -n dev.joely.bmsmon/.MainActivity
```

Open `Settings › Battery saver` and screenshot it — read the screenshot, do not infer from a `uiautomator` dump alone. With the permission granted it must read **"Motion sensing active"**. Then revoke and re-check:

```bash
adb -s adb-1C091FDF6003V0-RQzUxy._adb-tls-connect._tcp shell pm revoke dev.joely.bmsmon android.permission.ACTIVITY_RECOGNITION
```

Re-open the page; it must read **"Motion sensing unavailable — GPS won't pause"**. **Re-grant it afterwards** — leaving it revoked would disable the feature:

```bash
adb -s adb-1C091FDF6003V0-RQzUxy._adb-tls-connect._tcp shell pm grant dev.joely.bmsmon android.permission.ACTIVITY_RECOGNITION
```

Leave the app running, unlocked, on Home, monitoring active.

- [ ] **Step 3: Commit**

```bash
cd /home/joely/bmsmon && git add android/app/src/main/java/dev/joely/bmsmon/ui/settings/SettingsScreen.kt && git commit -m "feat(android): show whether motion sensing is available

Without it a denied permission silently disables the pause while the
toggle still reads as on."
```

---

### Task 6: Documentation

**Files:**
- Modify: `CLAUDE.md` (the parked-gate/transit section, ~lines 384-415)
- Modify: `~/GoogleDrive/obsidian/notes/Bmsmon.md` (outside the repo — edit in place, do **not** commit)

**Interfaces:**
- Consumes: everything above.
- Produces: no code.

- [ ] **Step 1: Update `CLAUDE.md`**

The existing section documents the transit problem as an **open decision resolved as option (a), keep 5 min**. That is now superseded. Rewrite it to record:

- The 2026-08-04 quantified analysis **stands and its numbers are unchanged** (357.5 moving miles, 256.5 dropped, 205.5 of 227.7 vehicle-speed miles, and the hold-length table). Do not delete those figures — they are the evidence base.
- **What changed on 2026-08-06:** the trade-off had been weighed as a *range-learner* cost, but the real cost is the *map record*. Three user-confirmed outings (08-04 15:00–16:15, 08-05 09:00–10:05, 08-06 09:35–10:45) were entirely invisible, destinations included: 0% discharge for 65–75 minutes, returning within 2–10 m of the start.
- The natural experiment: before the gate (08-01, 08-03) vehicle trips tracked to **71 mph** and **81 miles** from home; for the three days after, **zero fixes above 5 m/s**.
- The fix: pausing now requires **both** no-discharge **and** `confidentlyStill()`. Periodic Activity Recognition at 30 s, confidence ≥ 75, readings stale after 150 s.
- **Every unusable-signal path fails open to GPS-on** by explicit user decision, kept as one expression so it cannot drift. A denied `ACTIVITY_RECOGNITION` therefore disables the pause — which is why `Settings › Battery saver` shows the mode.
- **The open risk:** AR's true power cost is unmeasured. The probe's ~18 h run produced no detectable cost (`sensors` 0.00161 → 0.00133 mAh/h) but that is a **null result, not a measured number** — AR runs inside Play Services and nothing accrued to the probe's uid. If periodic AR costs more than the ~15 mA the pause saves, this feature is a net loss and should be reverted; the check is total phone drain across comparable days.
- Option (b) (lengthening the hold) is **dead** — no hold covers a 70-minute outing.

- [ ] **Step 2: Update the Obsidian note**

Add a `## Recent activity (as of 2026-08-06)` entry, demoting the current newest heading to `## Previous (as of <its date>)`. Match the note's established voice: plain-language outcomes with measured numbers, **no function names or file paths**. Read the existing entries first.

Cover: outings were disappearing from the map entirely because the chair draws no power in a vehicle, so the app thought it was parked; it now also checks whether the phone itself is moving; and the honest caveat that the motion sensing's own power cost has not been measured yet.

This file is outside the git repo — edit in place, do not commit it.

- [ ] **Step 3: Verify no doc contradicts the change**

```bash
cd /home/joely/bmsmon && grep -rn "option (a)\|keep 5 min\|open decision" CLAUDE.md docs/ | grep -v superpowers/specs
```

Expected: no stale claim that the transit problem remains an open decision resolved as (a). Fix anything found.

- [ ] **Step 4: Commit**

```bash
cd /home/joely/bmsmon && git add CLAUDE.md && git commit -m "docs: record the motion-gated GPS pause

Supersedes the 2026-08-04 decision, preserving its figures and recording
what changed: the cost is the map record, not the learner."
```

---

## Verification before calling this done

- [ ] `./gradlew :app:testDebugUnitTest` passes in full
- [ ] `./gradlew :app:assembleDebug` succeeds
- [ ] On device, stationary 5+ min with permission granted → `types=0x00000010` (GPS pauses as before)
- [ ] On device, permission revoked → GPS never pauses, Settings reads "Motion sensing unavailable"
- [ ] Permission re-granted before finishing
- [ ] App left running, unlocked, unpinned, monitoring active
- [ ] `adb uninstall` was never run against `dev.joely.bmsmon`
- [ ] **Deferred to a real vehicle outing:** GPS stays active in transit, and journey data shows fixes above 5 m/s — the metric that has read zero since 2026-08-03
