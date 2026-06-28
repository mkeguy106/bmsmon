# Lock Main Stage (Kiosk Lock) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a top-bar lock control that pins the bmsmon Android app to the main stage (kiosk-style) until deliberately unlocked, using Android Lock Task Mode.

**Architecture:** Lock is Activity-level UI state. A `locked` flag lives in `UiState`, is persisted via `SettingsStore`, and is toggled by a press-and-hold lock button. `App.kt` reacts to `locked` by calling `startLockTask()`/`stopLockTask()` (device-owner-aware: ordinary install → screen pinning; device owner → true kiosk) and by forcing the screen on. `HomeScreen` freezes the pager on page 0 and hides the other controls while locked. The `MonitorEngine`/foreground service is untouched.

**Tech Stack:** Kotlin, Jetpack Compose, Material3, AndroidX DataStore, JUnit4 (pure-JVM unit tests). Gradle wrapper at `android/gradlew`.

## Global Constraints

- Module root for all Gradle commands: `/home/joely/bmsmon/android` (wrapper: `./gradlew`).
- `minSdk = 26`, `compileSdk = 34`, `targetSdk = 34`. All APIs used (`startLockTask`, `DevicePolicyManager.isDeviceOwnerApp`, `setLockTaskPackages`) are available since API 21 — safe.
- Package root: `dev.joely.bmsmon`. App package id: `dev.joely.bmsmon`.
- Unit tests in this repo are **pure JVM** (junit only — no Robolectric, no Compose UI test, no instrumentation). Only pure functions get unit tests; Android-framework / Compose / Lock-Task wiring is verified by a successful `./gradlew assembleDebug` plus the manual steps each task lists. Do **not** invent fake unit tests for Android-framework code.
- Commit messages must NOT mention AI/Claude/automated generation (repo rule in CLAUDE.md).
- Work happens on branch `feature/lock-main-stage` (already created; the design spec is already committed there).

---

### Task 1: Long-press hold pure helper

The press-and-hold timing logic, extracted as pure functions so it can be unit-tested in the repo's existing JVM style. The lock button (Task 6) drives its progress ring and completion off these.

**Files:**
- Create: `android/app/src/main/java/dev/joely/bmsmon/model/Lock.kt`
- Test: `android/app/src/test/java/dev/joely/bmsmon/LockHoldTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `const val LOCK_HOLD_MS: Long` (= 1500) in package `dev.joely.bmsmon.model`
  - `fun lockHoldFraction(elapsedMs: Long): Float` — clamped 0f..1f
  - `fun lockHoldComplete(elapsedMs: Long): Boolean`

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/dev/joely/bmsmon/LockHoldTest.kt`:

```kotlin
package dev.joely.bmsmon

import dev.joely.bmsmon.model.LOCK_HOLD_MS
import dev.joely.bmsmon.model.lockHoldComplete
import dev.joely.bmsmon.model.lockHoldFraction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockHoldTest {

    @Test fun fractionIsZeroAtStart() {
        assertEquals(0f, lockHoldFraction(0L), 0.0001f)
    }

    @Test fun fractionIsHalfwayAtHalfTime() {
        assertEquals(0.5f, lockHoldFraction(LOCK_HOLD_MS / 2), 0.0001f)
    }

    @Test fun fractionClampsToOne() {
        assertEquals(1f, lockHoldFraction(LOCK_HOLD_MS), 0.0001f)
        assertEquals(1f, lockHoldFraction(LOCK_HOLD_MS * 5), 0.0001f)
    }

    @Test fun fractionClampsAtZeroForNegative() {
        assertEquals(0f, lockHoldFraction(-100L), 0.0001f)
    }

    @Test fun completeOnlyOnceHeldLongEnough() {
        assertFalse(lockHoldComplete(0L))
        assertFalse(lockHoldComplete(LOCK_HOLD_MS - 1))
        assertTrue(lockHoldComplete(LOCK_HOLD_MS))
        assertTrue(lockHoldComplete(LOCK_HOLD_MS + 1))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/joely/bmsmon/android && ./gradlew testDebugUnitTest --tests 'dev.joely.bmsmon.LockHoldTest'`
Expected: FAIL — compilation error, unresolved reference `LOCK_HOLD_MS` / `lockHoldFraction` / `lockHoldComplete`.

- [ ] **Step 3: Write minimal implementation**

Create `android/app/src/main/java/dev/joely/bmsmon/model/Lock.kt`:

```kotlin
package dev.joely.bmsmon.model

/** How long the lock/unlock icon must be held (ms) before the action commits. */
const val LOCK_HOLD_MS = 1500L

/** Progress (0f..1f) of a press-and-hold given how long it has been held. */
fun lockHoldFraction(elapsedMs: Long): Float =
    (elapsedMs.toFloat() / LOCK_HOLD_MS).coerceIn(0f, 1f)

/** True once the hold has lasted long enough to commit the lock/unlock. */
fun lockHoldComplete(elapsedMs: Long): Boolean = elapsedMs >= LOCK_HOLD_MS
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /home/joely/bmsmon/android && ./gradlew testDebugUnitTest --tests 'dev.joely.bmsmon.LockHoldTest'`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
cd /home/joely/bmsmon
git add android/app/src/main/java/dev/joely/bmsmon/model/Lock.kt android/app/src/test/java/dev/joely/bmsmon/LockHoldTest.kt
git commit -m "Add long-press hold helper for lock control"
```

---

### Task 2: Persist the locked flag in SettingsStore

Add a `locked` boolean to the persisted settings so a process kill re-enters lock on next launch.

**Files:**
- Modify: `android/app/src/main/java/dev/joely/bmsmon/data/SettingsStore.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `Persisted.locked: Boolean` (new field, default `false` in `load()`)
  - `SettingsStore.setLocked(on: Boolean)` — suspend

- [ ] **Step 1: Add the DataStore key**

In `SettingsStore.kt`, inside `private object K`, after the `AUTO_LUX` line, add:

```kotlin
        val LOCKED = booleanPreferencesKey("locked")
```

- [ ] **Step 2: Add the field to `Persisted`**

In the `data class Persisted(...)`, after `val autoLuxThreshold: Float?,` add:

```kotlin
    val locked: Boolean,
```

- [ ] **Step 3: Populate it in `load()`**

In `load()`, in the `Persisted(...)` constructor call, after `autoLuxThreshold = p[K.AUTO_LUX],` add:

```kotlin
            locked = p[K.LOCKED] ?: false,
```

- [ ] **Step 4: Add the setter**

After the `setTempFahrenheit` setter line, add:

```kotlin
    suspend fun setLocked(on: Boolean) = context.dataStore.edit { it[K.LOCKED] = on }.let {}
```

- [ ] **Step 5: Verify it compiles**

Run: `cd /home/joely/bmsmon/android && ./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
cd /home/joely/bmsmon
git add android/app/src/main/java/dev/joely/bmsmon/data/SettingsStore.kt
git commit -m "Persist lock state in settings"
```

---

### Task 3: Add `locked` to UiState and `setLocked` to the ViewModel

Wire the lock flag into UI state, load it on startup, and expose a setter that persists it.

**Files:**
- Modify: `android/app/src/main/java/dev/joely/bmsmon/BatteryViewModel.kt`

**Interfaces:**
- Consumes: `SettingsStore.setLocked` and `Persisted.locked` (Task 2).
- Produces:
  - `UiState.locked: Boolean` (default `false`)
  - `BatteryViewModel.setLocked(on: Boolean)`

- [ ] **Step 1: Add the field to `UiState`**

In `UiState`, after `val tempFahrenheit: Boolean = true,` add:

```kotlin
    val locked: Boolean = false,
```

- [ ] **Step 2: Load it on startup**

In the `init { ... }` block's first `_state.update { s -> ... s.copy(...) }`, add inside that `copy(...)` (e.g. after `tempFahrenheit = p.tempFahrenheit,`):

```kotlin
                    locked = p.locked,
```

- [ ] **Step 3: Add the setter**

After the `setTempFahrenheit(...)` function in the ViewModel, add:

```kotlin
    fun setLocked(enabled: Boolean) {
        _state.update { it.copy(locked = enabled) }
        viewModelScope.launch { store.setLocked(enabled) }
    }
```

- [ ] **Step 4: Verify it compiles**

Run: `cd /home/joely/bmsmon/android && ./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
cd /home/joely/bmsmon
git add android/app/src/main/java/dev/joely/bmsmon/BatteryViewModel.kt
git commit -m "Add lock state to UI state and view model"
```

---

### Task 4: Device-owner receiver and manifest plumbing

Add the minimal device-admin receiver so the app can later be provisioned as device owner (`adb shell dpm set-device-owner ...`) to upgrade screen pinning into a true, escape-proof kiosk. No runtime effect until that command is run.

**Files:**
- Create: `android/app/src/main/java/dev/joely/bmsmon/BmsDeviceAdminReceiver.kt`
- Create: `android/app/src/main/res/xml/device_admin.xml`
- Modify: `android/app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: nothing.
- Produces: `dev.joely.bmsmon.BmsDeviceAdminReceiver` (referenced by `App.kt` in Task 5 to build the admin `ComponentName`).

- [ ] **Step 1: Create the receiver**

Create `android/app/src/main/java/dev/joely/bmsmon/BmsDeviceAdminReceiver.kt`:

```kotlin
package dev.joely.bmsmon

import android.app.admin.DeviceAdminReceiver

/**
 * Device-admin receiver required to provision bmsmon as a *device owner*. When the app is the
 * device owner, lock-task mode becomes a true kiosk (no system escape gesture). Provision once
 * on a dedicated phone with no accounts:
 *
 *   adb shell dpm set-device-owner dev.joely.bmsmon/.BmsDeviceAdminReceiver
 *
 * With no provisioning this receiver does nothing and lock-task mode falls back to screen pinning.
 */
class BmsDeviceAdminReceiver : DeviceAdminReceiver()
```

- [ ] **Step 2: Create the policy resource**

Create `android/app/src/main/res/xml/device_admin.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<device-admin xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-policies />
</device-admin>
```

- [ ] **Step 3: Register the receiver in the manifest**

In `AndroidManifest.xml`, inside `<application>`, after the existing `<service .../>` block, add:

```xml
        <receiver
            android:name=".BmsDeviceAdminReceiver"
            android:exported="true"
            android:permission="android.permission.BIND_DEVICE_ADMIN">
            <meta-data
                android:name="android.app.device_admin"
                android:resource="@xml/device_admin" />
            <intent-filter>
                <action android:name="android.app.action.DEVICE_ADMIN_ENABLED" />
            </intent-filter>
        </receiver>
```

- [ ] **Step 4: Verify it builds**

Run: `cd /home/joely/bmsmon/android && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL (manifest merges, resource resolves).

- [ ] **Step 5: Commit**

```bash
cd /home/joely/bmsmon
git add android/app/src/main/java/dev/joely/bmsmon/BmsDeviceAdminReceiver.kt android/app/src/main/res/xml/device_admin.xml android/app/src/main/AndroidManifest.xml
git commit -m "Add device-admin receiver for optional kiosk provisioning"
```

---

### Task 5: Lock Task control and force-screen-on in App.kt

React to `state.locked`: enter/exit Lock Task Mode (device-owner-aware) and force the screen on while locked.

**Files:**
- Modify: `android/app/src/main/java/dev/joely/bmsmon/ui/App.kt`

**Interfaces:**
- Consumes: `UiState.locked` (Task 3), `BmsDeviceAdminReceiver` (Task 4).
- Produces: side effects only (no new public symbols consumed by later tasks).

- [ ] **Step 1: Add imports**

In `App.kt`, add these imports alongside the existing ones:

```kotlin
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import dev.joely.bmsmon.BmsDeviceAdminReceiver
```

(`android.app.Activity`, `android.content.Context`, `androidx.compose.runtime.LaunchedEffect`, and `WindowManager` are already imported.)

- [ ] **Step 2: Force the screen on while locked**

In `App.kt`, change the keep-screen-on effect's condition. Replace:

```kotlin
    DisposableEffect(window, state.keepScreenOn) {
        if (state.keepScreenOn) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
```

with:

```kotlin
    // Locking forces the screen on regardless of the setting; unlocking reverts to the setting.
    val keepOn = state.keepScreenOn || state.locked
    DisposableEffect(window, keepOn) {
        if (keepOn) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
```

- [ ] **Step 3: Enter/exit Lock Task Mode on lock changes**

In `App.kt`, immediately after that `DisposableEffect` block, add:

```kotlin
    // Enter/exit Android Lock Task Mode to pin the app while locked. Device-owner-aware:
    // an ordinary install gets screen pinning (escapable via the system gesture); a
    // provisioned device owner gets a true kiosk with no escape gesture.
    val activity = context.findActivity()
    LaunchedEffect(activity, state.locked) {
        val act = activity ?: return@LaunchedEffect
        if (state.locked) startLockTaskCompat(act) else runCatching { act.stopLockTask() }
    }
```

- [ ] **Step 4: Add the device-owner-aware helper**

In `App.kt`, after the existing `private tailrec fun Context.findActivity()` function at the bottom of the file, add:

```kotlin
/** Enter lock-task mode; if we're the device owner, allowlist ourselves first for true kiosk. */
private fun startLockTaskCompat(activity: Activity) {
    val dpm = activity.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    if (dpm.isDeviceOwnerApp(activity.packageName)) {
        val admin = ComponentName(activity, BmsDeviceAdminReceiver::class.java)
        runCatching { dpm.setLockTaskPackages(admin, arrayOf(activity.packageName)) }
    }
    // startLockTask throws if another task already holds lock-task mode — never crash the app.
    runCatching { activity.startLockTask() }
}
```

- [ ] **Step 5: Verify it builds**

Run: `cd /home/joely/bmsmon/android && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
cd /home/joely/bmsmon
git add android/app/src/main/java/dev/joely/bmsmon/ui/App.kt
git commit -m "Enter lock-task mode and force screen on while locked"
```

---

### Task 6: LockButton composable (press-and-hold with progress ring)

A reusable lock/unlock icon button. Holding it fills a ring over `LOCK_HOLD_MS`; on completion it fires `onToggle`. Releasing early cancels with no action. Drives its timing off the Task 1 helpers.

**Files:**
- Create: `android/app/src/main/java/dev/joely/bmsmon/ui/home/LockButton.kt`

**Interfaces:**
- Consumes: `LOCK_HOLD_MS`, `lockHoldFraction`, `lockHoldComplete` (Task 1).
- Produces: `@Composable fun LockButton(locked: Boolean, onToggle: () -> Unit, iconTint: Color, ringColor: Color, modifier: Modifier = Modifier)`

- [ ] **Step 1: Create the composable**

Create `android/app/src/main/java/dev/joely/bmsmon/ui/home/LockButton.kt`:

```kotlin
package dev.joely.bmsmon.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.joely.bmsmon.model.LOCK_HOLD_MS
import dev.joely.bmsmon.model.lockHoldComplete
import dev.joely.bmsmon.model.lockHoldFraction

/**
 * Lock/unlock control: press and hold ~LOCK_HOLD_MS to toggle. A ring fills while held and the
 * action only commits on completion, so a stray tap/bump can neither lock nor unlock.
 */
@Composable
fun LockButton(
    locked: Boolean,
    onToggle: () -> Unit,
    iconTint: Color,
    ringColor: Color,
    modifier: Modifier = Modifier,
) {
    var pressed by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(0L) }

    LaunchedEffect(pressed) {
        if (!pressed) { elapsed = 0L; return@LaunchedEffect }
        val start = withFrameMillis { it }
        while (true) {
            val now = withFrameMillis { it }
            elapsed = now - start
            if (lockHoldComplete(elapsed)) {
                onToggle()
                pressed = false
                break
            }
        }
    }

    val progress = lockHoldFraction(elapsed)

    Box(
        modifier
            .size(40.dp)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    pressed = true
                    tryAwaitRelease()
                    pressed = false
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(34.dp)) {
            if (progress > 0f) {
                drawArc(
                    color = ringColor,
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }
        Icon(
            if (locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
            contentDescription = if (locked) "Unlock screen" else "Lock screen",
            modifier = Modifier.size(if (locked) 22.dp else 21.dp),
            tint = iconTint,
        )
    }
}
```

- [ ] **Step 2: Verify it builds**

Run: `cd /home/joely/bmsmon/android && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
cd /home/joely/bmsmon
git add android/app/src/main/java/dev/joely/bmsmon/ui/home/LockButton.kt
git commit -m "Add press-and-hold lock button"
```

---

### Task 7: Wire the lock into HomeScreen — top bar, freeze, and pager

Surface the lock button in the top bar, append `· LOCKED` to the status, hide the other controls and the page dots while locked, and freeze the pager on page 0.

**Files:**
- Modify: `android/app/src/main/java/dev/joely/bmsmon/ui/home/HomeScreen.kt`
- Modify: `android/app/src/main/java/dev/joely/bmsmon/ui/App.kt`

**Interfaces:**
- Consumes: `LockButton` (Task 6), `UiState.locked` and `BatteryViewModel.setLocked` (Task 3).
- Produces: `HomeScreen` gains params `locked: Boolean` and `onToggleLock: () -> Unit`; `TopBar` gains the same two params.

- [ ] **Step 1: Add imports to HomeScreen**

In `HomeScreen.kt`, add alongside the existing imports:

```kotlin
import androidx.compose.runtime.LaunchedEffect
```

(`Bm`, `Color`, layout, and pager imports are already present.)

- [ ] **Step 2: Add params to `HomeScreen`**

In the `fun HomeScreen(` signature, after `onAcknowledge: () -> Unit,` add:

```kotlin
    locked: Boolean,
    onToggleLock: () -> Unit,
```

- [ ] **Step 3: Freeze the pager and hide dots while locked**

In `HomeScreen`'s body, after `val alert = state.stageAlert()`, add:

```kotlin
    // While locked, force the stage (page 0) and keep it there.
    LaunchedEffect(locked) { if (locked) pager.scrollToPage(0) }
```

Then change the `TopBar(...)` call to pass the new params:

```kotlin
            TopBar(state, onCycleAppearance, onSettings, onToggleMonitoring, locked, onToggleLock)
```

Then replace `PageDots(current = pager.currentPage)` with:

```kotlin
            if (!locked) PageDots(current = pager.currentPage)
```

Then change the pager declaration line:

```kotlin
            HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
```

to:

```kotlin
            HorizontalPager(state = pager, userScrollEnabled = !locked, modifier = Modifier.weight(1f)) { page ->
```

- [ ] **Step 4: Update `TopBar` signature and body**

In `HomeScreen.kt`, change the `private fun TopBar(` signature. Replace:

```kotlin
private fun TopBar(
    state: UiState,
    onCycleAppearance: () -> Unit,
    onSettings: () -> Unit,
    onToggleMonitoring: () -> Unit,
) {
```

with:

```kotlin
private fun TopBar(
    state: UiState,
    onCycleAppearance: () -> Unit,
    onSettings: () -> Unit,
    onToggleMonitoring: () -> Unit,
    locked: Boolean,
    onToggleLock: () -> Unit,
) {
```

- [ ] **Step 5: Append `· LOCKED` to the status label**

In `TopBar`, immediately after the `val (label, labelColor, showPin) = when { ... }` assignment, add:

```kotlin
    val statusLabel = if (locked) "$label · LOCKED" else label
```

Then change the status `Text(...)` inside the left Row from `Text(label, ...)` to use `statusLabel`:

```kotlin
            Text(statusLabel, color = labelColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.9.sp, modifier = Modifier.padding(start = 7.dp))
```

- [ ] **Step 6: Disable the monitoring toggle and gate the right-side controls while locked**

In `TopBar`, change the left Row so its click is disabled while locked. Replace:

```kotlin
        Row(Modifier.clickable(onClick = onToggleMonitoring), verticalAlignment = Alignment.CenterVertically) {
```

with:

```kotlin
        Row(
            if (locked) Modifier else Modifier.clickable(onClick = onToggleMonitoring),
            verticalAlignment = Alignment.CenterVertically,
        ) {
```

Then replace the entire right-side controls `Row` — the block:

```kotlin
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clickable(onClick = onCycleAppearance), contentAlignment = Alignment.Center) {
                when (state.appearance) {
                    Appearance.Dark -> Icon(Icons.Filled.DarkMode, "Appearance: Dark", Modifier.size(21.dp), tint = c.icon)
                    Appearance.Light -> Icon(Icons.Filled.LightMode, "Appearance: Light", Modifier.size(21.dp), tint = c.icon)
                    Appearance.System -> Text("S", color = c.icon, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Appearance.Auto -> Text("A", color = c.icon, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
            }
            Box(Modifier.size(40.dp).clickable(onClick = onSettings), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Settings, "Settings", Modifier.size(22.dp), tint = c.icon)
            }
        }
```

with:

```kotlin
        Row(verticalAlignment = Alignment.CenterVertically) {
            // While locked, hide appearance + settings; only the lock control remains (hold to unlock).
            if (!locked) {
                Box(Modifier.size(40.dp).clickable(onClick = onCycleAppearance), contentAlignment = Alignment.Center) {
                    when (state.appearance) {
                        Appearance.Dark -> Icon(Icons.Filled.DarkMode, "Appearance: Dark", Modifier.size(21.dp), tint = c.icon)
                        Appearance.Light -> Icon(Icons.Filled.LightMode, "Appearance: Light", Modifier.size(21.dp), tint = c.icon)
                        Appearance.System -> Text("S", color = c.icon, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Appearance.Auto -> Text("A", color = c.icon, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Box(Modifier.size(40.dp).clickable(onClick = onSettings), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Settings, "Settings", Modifier.size(22.dp), tint = c.icon)
                }
            }
            LockButton(
                locked = locked,
                onToggle = onToggleLock,
                iconTint = if (locked) Bm.accent else c.icon,
                ringColor = Bm.accent,
            )
        }
```

- [ ] **Step 7: Pass the new params from App.kt**

In `App.kt`, in the `Screen.Home -> HomeScreen(` call, after `onAcknowledge = vm::acknowledgeAlert,` add:

```kotlin
                        locked = state.locked,
                        onToggleLock = { vm.setLocked(!state.locked) },
```

- [ ] **Step 8: Verify it builds and unit tests still pass**

Run: `cd /home/joely/bmsmon/android && ./gradlew assembleDebug testDebugUnitTest`
Expected: BUILD SUCCESSFUL; all unit tests pass (including `LockHoldTest`).

- [ ] **Step 9: Manual verification (on device — Pixel 6 over wireless ADB)**

Install: `cd /home/joely/bmsmon/android && ./gradlew installDebug`
Then verify:
1. Top bar shows an open-padlock icon on the right. Hold it ~1.5 s → ring fills → app locks; the label shows `· LOCKED`; appearance + settings icons and the page dots disappear.
2. While locked: swiping left does **not** reach All Batteries; Home/Recents/swipe-away are blocked (Android shows the one-time "Screen pinned" prompt on first lock); the screen does not sleep; toggling the system theme (or covering the light sensor in Auto) still switches dark/light.
3. Hold the closed-padlock icon ~1.5 s → ring fills → unlocks; controls, dots, and swipe return; screen-on reverts to the Settings toggle.
4. A short tap (well under 1.5 s) on the lock icon does nothing.
5. (Optional, dedicated phone) Provision device owner: `adb shell dpm set-device-owner dev.joely.bmsmon/.BmsDeviceAdminReceiver`, relaunch, lock → confirm the Back+Recents escape gesture no longer exits; only the in-app unlock works. Remove with a factory reset.

- [ ] **Step 10: Commit**

```bash
cd /home/joely/bmsmon
git add android/app/src/main/java/dev/joely/bmsmon/ui/home/HomeScreen.kt android/app/src/main/java/dev/joely/bmsmon/ui/App.kt
git commit -m "Wire lock control into top bar and freeze stage while locked"
```

---

## Self-Review

**Spec coverage:**
- Device-owner-aware lock strength → Tasks 4 (receiver) + 5 (`startLockTaskCompat` device-owner branch). ✅
- Freeze on the stage (pager disabled, controls/dots hidden) → Task 7 steps 3, 6. ✅
- Symmetric long-press confirm for lock *and* unlock → Tasks 1 + 6 (same `LockButton` for both states). ✅
- Force screen on while locked, revert on unlock → Task 5 step 2. ✅
- Auto light/dark still honored while locked → no change to the theming effects in `App.kt`/sensor; only the manual cycle button is hidden (Task 7 step 6). ✅ (verified manually in Task 7 step 9.2.)
- Button in top bar, right side → Task 7 step 6. ✅
- Persist locked across process kill → Tasks 2 + 3. ✅
- `· LOCKED` status label → Task 7 step 5. ✅
- Alerts still work while locked (forced to page 0) → Task 7 step 3 keeps page 0, existing `DangerOverlay` gate unchanged. ✅
- Monitoring/foreground service untouched → no task modifies `MonitorEngine`/`MonitoringService`. ✅
- Reboot auto-relaunch is out of scope → not built (noted in spec Non-Goals). ✅

**Placeholder scan:** No TBD/TODO/"handle edge cases"/"similar to" — every code step shows complete code. ✅

**Type consistency:** `locked: Boolean` consistent across `Persisted` (Task 2), `UiState` (Task 3), `HomeScreen`/`TopBar` params (Task 7). `setLocked(on/enabled: Boolean)` — `SettingsStore.setLocked(on)` (Task 2) and `BatteryViewModel.setLocked(enabled)` (Task 3); param names differ but both `Boolean`, no call-site mismatch (called positionally). `LockButton(locked, onToggle, iconTint, ringColor, modifier)` defined in Task 6 and called with named args in Task 7 — names match. `LOCK_HOLD_MS`/`lockHoldFraction`/`lockHoldComplete` defined in Task 1, consumed in Task 6 — names match. `BmsDeviceAdminReceiver` defined Task 4, referenced Task 5 — matches. ✅
