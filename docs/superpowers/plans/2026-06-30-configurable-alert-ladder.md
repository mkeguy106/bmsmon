# Configurable Alert Ladder + Configurable Critical Level Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the Android app's low-battery alert ladder to a full 5% set (95%→5%, high levels OFF by default) and make the "critical" tier (red / fast pulse) user-configurable.

**Architecture:** Purely additive changes on the existing alert pipeline. `UiState.stageAlert()` already resolves the alert from `enabledThresholds`; we extend the selectable ladder constant, add a `criticalThreshold` field to `UiState` (replacing the hardcoded `<= 15`), persist it via DataStore, and surface both in the existing Alerts settings page. No BLE/protocol/opcode changes — the read-only safety model is untouched.

**Tech Stack:** Kotlin, Jetpack Compose, AndroidX DataStore (Preferences), JUnit4 (`:app:testDebugUnitTest`). Build with JDK 17 + `ANDROID_HOME`. Device verify over Tailscale ADB.

## Global Constraints

- Read-only protocol only — no new BLE writes, opcodes, or destructive surface. Do not touch `ble/`.
- `ALERT_THRESHOLDS` is the full selectable ladder: `95,90,85,80,75,70,65,60,55,50,45,40,35,30,25,20,15,10,5` (19 levels, most severe last).
- `DEFAULT_THRESHOLDS` (fresh-install enabled set): `30,25,20,15,10,5` (high levels default OFF).
- `DEFAULT_CRITICAL_THRESHOLD` = `15` (matches today's hardcoded behavior).
- Existing installs keep their persisted `enabledThresholds`; absent `criticalThreshold` falls back to 15.
- Critical level is single-select from the full ladder; chips `<=` it tint with `AlertCritical`.
- Never uninstall the app on device — `adb install -r` only (preserves app data).
- Tailscale ADB target: `adb connect 100.102.146.11:5555` (Pixel 6). Direct Wi-Fi fallback `192.168.0.16:5555`.

---

## File Structure

- `android/app/.../BatteryViewModel.kt` — threshold constants, `UiState.criticalThreshold`, `stageAlert()` critical logic, persisted-state reducer, `setCriticalThreshold()`.
- `android/app/.../data/SettingsStore.kt` — persistence (`Persisted` field, DataStore key, `load()`, setter).
- `android/app/.../ui/settings/SettingsScreen.kt` — `SelectChip` tint param, threshold-grid tint, new "Critical level" row, `AlertsContent` + `SettingsScreen` params.
- `android/app/.../ui/App.kt` — wire `onSetCriticalThreshold = vm::setCriticalThreshold`.
- `android/app/src/test/.../AlertLogicTest.kt` — new unit test for the configurable-critical logic.

---

### Task 1: Threshold constants + configurable critical logic (with unit test)

**Files:**
- Create: `android/app/src/test/java/dev/joely/bmsmon/AlertLogicTest.kt`
- Modify: `android/app/src/main/java/dev/joely/bmsmon/BatteryViewModel.kt` (constants ~59, `UiState` ~107-108, `stageAlert()` line 190)

**Interfaces:**
- Consumes: `UiState.stageAlert(): StageAlert` (existing); `StageAlert{flashing, critical, lowSoc, activeThreshold, ackEffective}`; `Telemetry`, `BatteryStatus`, `StageTarget.Base`, `DEFAULT_ROSTER`, `groupById` from `dev.joely.bmsmon.model`.
- Produces: top-level `val ALERT_THRESHOLDS`, `val DEFAULT_THRESHOLDS`, `const val DEFAULT_CRITICAL_THRESHOLD`; `UiState.criticalThreshold: Int` field (default `DEFAULT_CRITICAL_THRESHOLD`).

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/dev/joely/bmsmon/AlertLogicTest.kt`:

```kotlin
package dev.joely.bmsmon

import dev.joely.bmsmon.model.BatteryState
import dev.joely.bmsmon.model.BatteryStatus
import dev.joely.bmsmon.model.DEFAULT_ROSTER
import dev.joely.bmsmon.model.StageTarget
import dev.joely.bmsmon.model.Telemetry
import dev.joely.bmsmon.model.groupById
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertLogicTest {

    private fun tel(soc: Float) = Telemetry(
        "x", soc = soc, powerW = 26f, current = -2f, voltage = 13f,
        capacityAh = 50f, cellV = 3.3f, temp = 25f, state = BatteryState.Discharging,
    )

    /** Fleet where every address of base [gid] reports [soc], all reachable. */
    private fun fleetAt(gid: String, soc: Float): Map<String, BatteryStatus> =
        DEFAULT_ROSTER.groupById(gid)!!.targets.associate {
            it.address to BatteryStatus(tel(soc), reachable = true)
        }

    private fun stateAt(gid: String, soc: Float, enabled: Set<Int>, critical: Int) = UiState(
        monitoring = true,
        roster = DEFAULT_ROSTER,
        fleet = fleetAt(gid, soc),
        stageTarget = StageTarget.Base(gid),
        alertsOn = true,
        enabledThresholds = enabled,
        criticalThreshold = critical,
    )

    @Test fun criticalWhenActiveThresholdAtOrBelowCriticalLevel() {
        val a = stateAt("2012", soc = 12f, enabled = setOf(15, 10), critical = 15).stageAlert()
        assertEquals(15, a.activeThreshold)
        assertTrue(a.flashing)
        assertTrue(a.critical)
    }

    @Test fun notCriticalWhenCriticalLevelIsLower() {
        val a = stateAt("2012", soc = 12f, enabled = setOf(15, 10), critical = 10).stageAlert()
        assertEquals(15, a.activeThreshold)
        assertFalse(a.critical)
    }

    @Test fun highThresholdFlashesButIsNotCritical() {
        val a = stateAt("2012", soc = 92f, enabled = setOf(95, 90), critical = 15).stageAlert()
        assertEquals(95, a.activeThreshold)
        assertTrue(a.flashing)
        assertFalse(a.critical)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.AlertLogicTest"`
Expected: FAIL — compilation error, `criticalThreshold` is not a parameter of `UiState`.

- [ ] **Step 3: Add constants and the `criticalThreshold` field, and use it in `stageAlert()`**

In `BatteryViewModel.kt`, replace the single-line `ALERT_THRESHOLDS` (line ~59):

```kotlin
/** Every selectable low-battery alert level, most severe last (full 5% ladder). */
val ALERT_THRESHOLDS =
    listOf(95, 90, 85, 80, 75, 70, 65, 60, 55, 50, 45, 40, 35, 30, 25, 20, 15, 10, 5)

/** Levels enabled on a fresh install (the original low set; high marks default OFF). */
val DEFAULT_THRESHOLDS = listOf(30, 25, 20, 15, 10, 5)

/** Default critical tier (red / fast pulse). User-configurable. */
const val DEFAULT_CRITICAL_THRESHOLD = 15
```

In the `UiState` data class, change the `enabledThresholds` default and add `criticalThreshold` right after `acknowledgedThresholds` (lines ~107-108):

```kotlin
    val enabledThresholds: Set<Int> = DEFAULT_THRESHOLDS.toSet(),
    val acknowledgedThresholds: Set<Int> = emptySet(),
    val criticalThreshold: Int = DEFAULT_CRITICAL_THRESHOLD,
```

In `stageAlert()`, replace the hardcoded critical comparison (line ~190). `stageAlert()` is a member of `UiState`, so reference the property directly:

```kotlin
        val critical = activeThreshold != null && activeThreshold <= criticalThreshold
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.AlertLogicTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/dev/joely/bmsmon/BatteryViewModel.kt \
        android/app/src/test/java/dev/joely/bmsmon/AlertLogicTest.kt
git commit -m "feat(android): full 5% alert ladder + configurable critical level (logic)"
```

---

### Task 2: Persist `criticalThreshold` + ViewModel setter

**Files:**
- Modify: `android/app/src/main/java/dev/joely/bmsmon/data/SettingsStore.kt` (`Persisted` ~34, `K` object ~72, `load()` ~110, setters block ~146)
- Modify: `android/app/src/main/java/dev/joely/bmsmon/BatteryViewModel.kt` (init reducer ~243, alerts setters block ~693)

**Interfaces:**
- Consumes: `SettingsStore` DataStore patterns (`intPreferencesKey`, `context.dataStore.edit`); `Persisted`; `store.setCriticalThreshold`.
- Produces: `Persisted.criticalThreshold: Int?`; `SettingsStore.setCriticalThreshold(t: Int)`; `BatteryViewModel.setCriticalThreshold(t: Int)`.

- [ ] **Step 1: Add the persisted field, key, load, and setter in `SettingsStore.kt`**

Add to the `Persisted` data class, right after `enabledThresholds` (line ~34):

```kotlin
    val criticalThreshold: Int?,
```

Add to the `K` object, after `THRESHOLDS` (line ~72):

```kotlin
        val CRITICAL_THRESHOLD = intPreferencesKey("alert_critical_threshold")
```

Add to the `load()` `Persisted(...)` builder, after the `enabledThresholds = …` line (~110):

```kotlin
            criticalThreshold = p[K.CRITICAL_THRESHOLD],
```

Add a setter next to `setThresholds` (after line ~146):

```kotlin
    suspend fun setCriticalThreshold(t: Int) =
        context.dataStore.edit { it[K.CRITICAL_THRESHOLD] = t }.let {}
```

- [ ] **Step 2: Wire it into the ViewModel**

In `BatteryViewModel.kt` `init` reducer, add after the `enabledThresholds = p.enabledThresholds ?: s.enabledThresholds,` line (~243):

```kotlin
                    criticalThreshold = p.criticalThreshold ?: s.criticalThreshold,
```

Add a setter in the `// --- low-battery alerts ---` block, right after `toggleThreshold` (~699):

```kotlin
    fun setCriticalThreshold(t: Int) {
        _state.update { it.copy(criticalThreshold = t) }
        viewModelScope.launch { store.setCriticalThreshold(t) }
    }
```

- [ ] **Step 3: Compile + run the existing unit tests (must stay green)**

Run: `cd android && ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; all tests pass (incl. `AlertLogicTest` from Task 1). The new persistence/setter code compiles.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/dev/joely/bmsmon/data/SettingsStore.kt \
        android/app/src/main/java/dev/joely/bmsmon/BatteryViewModel.kt
git commit -m "feat(android): persist configurable critical threshold"
```

---

### Task 3: Settings UI — full ladder shown, critical-range tint, critical-level picker

**Files:**
- Modify: `android/app/src/main/java/dev/joely/bmsmon/ui/settings/SettingsScreen.kt` (`SelectChip` ~477, `SettingsScreen` signature ~104-128 + `AlertsContent` call ~142, `AlertsContent` ~576-618)
- Modify: `android/app/src/main/java/dev/joely/bmsmon/ui/App.kt` (`SettingsScreen(...)` call ~223-247)

**Interfaces:**
- Consumes: `AlertCritical` (already imported, used at line ~608); `Color` (already imported); `SelectChip`, `SectionLabel`, `PlainCard`, `ALERT_THRESHOLDS`, `vm::setCriticalThreshold`.
- Produces: `SelectChip(..., tint: Color? = null, ...)`; `AlertsContent(..., onSetCriticalThreshold: (Int) -> Unit)`; `SettingsScreen(..., onSetCriticalThreshold: (Int) -> Unit)`.

- [ ] **Step 1: Add an optional `tint` to `SelectChip`**

Replace the `SelectChip` composable (line ~477) with:

```kotlin
private fun SelectChip(
    label: String,
    selected: Boolean,
    mono: Boolean = false,
    tint: Color? = null,
    onClick: () -> Unit,
) {
    val c = Bm.colors
    val accent = tint ?: Bm.accent
    val border = when {
        selected -> accent
        tint != null -> tint.copy(alpha = 0.4f)
        else -> c.border
    }
    val bg = if (selected) accent.copy(alpha = 0.14f) else Color.Transparent
    val textColor = when {
        selected -> accent
        tint != null -> tint
        else -> c.text2
    }
    Box(
        Modifier.clip(RoundedCornerShape(20.dp)).background(bg).border(1.dp, border, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick).padding(horizontal = 15.dp, vertical = 8.dp),
    ) {
        Text(
            label, color = textColor,
            fontFamily = if (mono) MonoFont else null,
            fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
```

(All existing `SelectChip` callers use the trailing-lambda `onClick` and only positional `label`/`selected` (+ optional `mono =`), so the new defaulted `tint` parameter does not break them.)

- [ ] **Step 2: Tint critical-range threshold chips + add the "Critical level" picker in `AlertsContent`**

Change the `AlertsContent` signature (line ~576) to accept the new callback:

```kotlin
private fun ColumnScope.AlertsContent(
    state: UiState,
    onSetAlertsOn: (Boolean) -> Unit,
    onToggleThreshold: (Int) -> Unit,
    onSetCriticalThreshold: (Int) -> Unit,
) {
```

In the threshold chip grid, tint chips at or below the critical level. Replace the `SelectChip` line inside the grid (line ~598) with:

```kotlin
                        SelectChip(
                            "$t%", t in state.enabledThresholds, mono = true,
                            tint = if (t <= state.criticalThreshold) AlertCritical else null,
                        ) { onToggleThreshold(t) }
```

Then add the critical-level picker. Insert this block between the threshold `PlainCard { … }` (closes ~603) and the existing `val next = …` "next alert" box (~605):

```kotlin
    SectionLabel("Critical level")
    PlainCard {
        Text(
            "Alerts at or below this level flash red and pulse faster.",
            color = c.text2, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(bottom = 12.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (row in ALERT_THRESHOLDS.chunked(3)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { t ->
                        SelectChip("$t%", state.criticalThreshold == t, mono = true, tint = AlertCritical) {
                            onSetCriticalThreshold(t)
                        }
                    }
                }
            }
        }
    }
```

- [ ] **Step 3: Thread the callback through `SettingsScreen`**

In `SettingsScreen(...)` parameters (after `onToggleThreshold: (Int) -> Unit,` ~line 113):

```kotlin
    onSetCriticalThreshold: (Int) -> Unit,
```

In the `SettingsPage.Alerts` branch (line ~142), pass it through:

```kotlin
            AlertsContent(state, onSetAlertsOn, onToggleThreshold, onSetCriticalThreshold)
```

- [ ] **Step 4: Wire the ViewModel method in `App.kt`**

In the `SettingsScreen(...)` call, add after `onToggleThreshold = vm::toggleThreshold,` (line ~232):

```kotlin
                        onSetCriticalThreshold = vm::setCriticalThreshold,
```

- [ ] **Step 5: Compile (assemble the debug APK)**

Run: `cd android && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL; `app/build/outputs/apk/debug/app-debug.apk` produced.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/dev/joely/bmsmon/ui/settings/SettingsScreen.kt \
        android/app/src/main/java/dev/joely/bmsmon/ui/App.kt
git commit -m "feat(android): show full alert ladder + critical-level picker in settings"
```

---

### Task 4: Install on the Pixel and live-verify

**Files:** none (device verification).

**Interfaces:**
- Consumes: `app-debug.apk` from Task 3; Tailscale ADB.

- [ ] **Step 1: Connect to the Pixel over Tailscale**

Run: `adb connect 100.102.146.11:5555` (fallback: `adb connect 192.168.0.16:5555`)
Expected: `connected to 100.102.146.11:5555`. Confirm with `adb devices`.

- [ ] **Step 2: Install in place (preserve app data)**

Run: `cd android && adb -s 100.102.146.11:5555 install -r app/build/outputs/apk/debug/app-debug.apk`
Expected: `Success`. (Never `adb uninstall` — that wipes settings/usage log.)

- [ ] **Step 3: Verify the settings UI**

Open the app → Settings → Alerts. Confirm:
- The thresholds grid now lists every level 95% → 5%; high levels (≥35%) are OFF by default on a fresh install (existing install keeps prior selection).
- A "Critical level" picker appears, defaulting to 15%.
- Chips at/below the selected critical level render with the red `AlertCritical` tint; changing the critical selection moves that boundary.

Drive/screencap if needed: `adb -s 100.102.146.11:5555 exec-out screencap -p > /tmp/claude-1000/-home-joely-bmsmon/c356b58e-7188-4f69-a52c-351a47b273dd/scratchpad/alerts.png`

- [ ] **Step 4: Verify a live alert fires at a high threshold**

With monitoring on and a real pack on stage, enable the next-highest level above the pack's current SOC (e.g. if a pack reads 96%, enable 95%). As it crosses, confirm the screen flashes amber (`LOW BATTERY · NN% · BELOW 95%`) and the Acknowledge bar appears. Set the critical level above that threshold and confirm the flash switches to red / faster pulse. Acknowledge and confirm it silences until the next level.

- [ ] **Step 5: Report results**

Summarize what was observed (screens + live flash). No commit (verification only). If a defect is found, return to the relevant task rather than patching blindly.

---

## Self-Review notes

- **Spec coverage:** ladder extension → Task 1; high-off defaults → Task 1 (`DEFAULT_THRESHOLDS`); configurable critical + persistence → Tasks 1-2; full-ladder single-select picker + red tint → Task 3; build/install/verify over Tailscale → Task 4. All spec sections mapped.
- **Type consistency:** `criticalThreshold: Int` and `setCriticalThreshold(Int)` used identically across UiState, SettingsStore, ViewModel, SettingsScreen, App.kt. `SelectChip` gains `tint: Color?` used by both the threshold grid and the picker.
- **No placeholders:** every code step shows complete code; no TBD/TODO.
