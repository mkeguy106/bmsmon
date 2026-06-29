# Battery Profiles & Persistent-Fleet Connection Manager — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce a `BatteryProfile` abstraction holding everything brand/firmware-specific (selection prefixes, BLE UUIDs, prebuilt read-command frames, response layout, cadences, write type, connection budget/backoff), then replace the churny rotating BLE sampler with a persistent-fleet connection manager that holds links up to a budget, polls per-tier, and uses exponential backoff only on the connect-retry edge.

**Architecture:** Phase 1 extracts the scattered brand constants into one immutable `BatteryProfile` + a prefix-keyed `ProfileRegistry`, and makes the parser/scanner/session read from a profile (behavior-preserving). Phase 2 reworks `BmsRepository` into a `FleetConnectionManager`: a per-pack state machine (DISCONNECTED → CONNECTING → CONNECTED-and-held) whose pure decision logic (`planFleet`, `BackoffSpec`) is unit-tested, with thin BLE execution glue. The read-only closed command set (`BmsProtocol.ReadCommand`) is preserved.

**Tech Stack:** Kotlin 1.9.22, AGP 8.2.2, Jetpack Compose, Coroutines/Flow, Android BLE (`BluetoothGatt`), JUnit4 (JVM unit tests under `app/src/test`).

## Global Constraints

- **Read-only safety is absolute.** The command set stays the closed `BmsProtocol.ReadCommand` enum (`STATUS 0x13`, `SERIAL 0x10`, `CONFIG 0x15`, `FW_VERSION 0x16`, `SOH_SOC 0x41`, `CAPACITY 0x43`). No destructive opcodes (`0x0A–0x0D`, `0x60`) anywhere. The profile exposes only **prebuilt frame `ByteArray`s** (`statusFrame`, `firmwareFrame`) built from that enum — never an open `commandFrame(Int)` API.
- **Phase 1 is behavior-preserving** — same UUIDs, offsets, prefixes, cadences. The existing `BmsProtocolTest` and `BleScannerTest` must keep passing unchanged.
- **Profile selection is by BLE name prefix** (`ProfileRegistry.profileFor(name)`); firmware version is metadata only.
- **Connection model:** hold persistent links up to `maxHeldConnections` (default **8**); rotate only true overflow (fleet > cap). Stage packs get slot priority + fast poll.
- **Cadences:** stage **1500 ms** (`STAGE_POLL_MS`, already set), off-stage **`SLOW_POLL_MS` = 10_000 ms**.
- **Backoff:** base **5_000 ms**, factor **2**, cap **120_000 ms**; reset on success; only on the connect-retry edge.
- **Write type:** adopt ATT **Write Request** (with response) to match the official Redodo app (current code uses Write Command / no-response).
- **The manager keeps the existing outward surface** the engine consumes: `start(scope, targets, onPoll, onReachable)`, `setStage`, `setTargets`, `setDisabled`, `kickAll`, `stop`.
- **Commit messages:** no "Generated with Claude Code", no "Co-Authored-By: Claude", no AI references (per `CLAUDE.md`).
- **Build:** `cd android && ./gradlew :app:testDebugUnitTest :app:assembleDebug -q` (benign "SDK XML version" warning is OK). Single test class: `--tests "dev.joely.bmsmon.ClassName"`.
- **Device for on-device checks:** `192.168.0.16:35165` (Pixel 6) — re-run `adb connect` / `adb devices` if it changed.

---

## Phase 1 — Battery Profile abstraction (behavior-preserving)

### Task 1: BatteryProfile types, the Redodo profile, and the registry

**Files:**
- Create: `android/app/src/main/java/dev/joely/bmsmon/ble/profile/BatteryProfile.kt`
- Create: `android/app/src/main/java/dev/joely/bmsmon/ble/profile/Profiles.kt`
- Test: `android/app/src/test/java/dev/joely/bmsmon/BatteryProfileTest.kt`

**Interfaces:**
- Produces (consumed by all later tasks):
  - `data class TelemetryLayout(...)` — the parse offsets + sanity bounds (defaults match the current `parseTelemetry`).
  - `data class BackoffSpec(val baseMs: Long, val factor: Int, val capMs: Long)`.
  - `data class BatteryProfile(id, displayName, namePrefixes, validatedFirmware, serviceUuid, notifyUuid, writeUuid, cccdUuid, writeWithResponse, statusFrame, firmwareFrame, responseHeader, layout, stagePollMs, slowPollMs, maxHeldConnections, connectTimeoutMs, failThreshold, backoff)`.
  - `val RedodoBekenProfile: BatteryProfile` (the one current profile).
  - `object ProfileRegistry { val all: List<BatteryProfile>; fun profileFor(name: String?): BatteryProfile? }`.

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.joely.bmsmon

import dev.joely.bmsmon.ble.BmsProtocol
import dev.joely.bmsmon.ble.profile.ProfileRegistry
import dev.joely.bmsmon.ble.profile.RedodoBekenProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class BatteryProfileTest {
    @Test fun selectsRedodoByPrefix() {
        assertSame(RedodoBekenProfile, ProfileRegistry.profileFor("R-12100BNNA70-A02402"))
        assertSame(RedodoBekenProfile, ProfileRegistry.profileFor("LT-12100"))
        assertSame(RedodoBekenProfile, ProfileRegistry.profileFor("PQ-12100"))
        assertSame(RedodoBekenProfile, ProfileRegistry.profileFor("ss-12100")) // case-insensitive
    }

    @Test fun rejectsUnknownAndBlank() {
        assertNull(ProfileRegistry.profileFor("MyHeadphones"))
        assertNull(ProfileRegistry.profileFor("X-12100"))
        assertNull(ProfileRegistry.profileFor(null))
        assertNull(ProfileRegistry.profileFor(""))
    }

    @Test fun prebuiltFramesMatchTheClosedEnum() {
        // The profile must expose the SAME bytes the closed ReadCommand enum produces — no other opcodes.
        assertEquals(
            BmsProtocol.frame(BmsProtocol.ReadCommand.STATUS).toList(),
            RedodoBekenProfile.statusFrame.toList(),
        )
        assertEquals(
            BmsProtocol.frame(BmsProtocol.ReadCommand.FW_VERSION).toList(),
            RedodoBekenProfile.firmwareFrame.toList(),
        )
    }

    @Test fun connectionDefaultsMatchSpec() {
        assertEquals(8, RedodoBekenProfile.maxHeldConnections)
        assertEquals(1500L, RedodoBekenProfile.stagePollMs)
        assertEquals(10_000L, RedodoBekenProfile.slowPollMs)
        assertEquals(5_000L, RedodoBekenProfile.backoff.baseMs)
        assertEquals(120_000L, RedodoBekenProfile.backoff.capMs)
        assertEquals(true, RedodoBekenProfile.writeWithResponse)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.BatteryProfileTest"`
Expected: FAIL — unresolved references `ProfileRegistry` / `RedodoBekenProfile`.

- [ ] **Step 3: Create `BatteryProfile.kt`**

```kotlin
package dev.joely.bmsmon.ble.profile

import java.util.UUID

/** Field offsets (bytes, little-endian, from the realigned status frame) + plausibility bounds. */
data class TelemetryLayout(
    val minBytes: Int = 100,
    val voltageOff: Int = 12,
    val currentOff: Int = 48,
    val cellTempOff: Int = 52,
    val mosfetTempOff: Int = 54,
    val remainingAhOff: Int = 62,
    val fullAhOff: Int = 64,
    val protOff: Int = 76,
    val stateOff: Int = 88,
    val socOff: Int = 90,
    val sohOff: Int = 92,
    val cyclesOff: Int = 96,
    val cellsBaseOff: Int = 16,
    val cellCount: Int = 16,
    val socMin: Float = 0f,
    val socMax: Float = 100f,
    val voltMin: Float = 4f,
    val voltMax: Float = 70f,
)

/** Exponential backoff schedule for connect-retry. */
data class BackoffSpec(val baseMs: Long, val factor: Int, val capMs: Long)

/**
 * Everything brand/firmware-specific for one battery family. Selected by [namePrefixes]. SAFETY:
 * only prebuilt read-command frames are exposed ([statusFrame], [firmwareFrame]) — there is no
 * open opcode API, so no destructive command can ever be emitted.
 */
data class BatteryProfile(
    val id: String,
    val displayName: String,
    val namePrefixes: List<String>,
    val validatedFirmware: String,
    val serviceUuid: UUID,
    val notifyUuid: UUID,
    val writeUuid: UUID,
    val cccdUuid: UUID,
    val writeWithResponse: Boolean,
    val statusFrame: ByteArray,
    val firmwareFrame: ByteArray,
    val responseHeader: ByteArray,
    val layout: TelemetryLayout,
    val stagePollMs: Long,
    val slowPollMs: Long,
    val maxHeldConnections: Int,
    val connectTimeoutMs: Long,
    val failThreshold: Int,
    val backoff: BackoffSpec,
) {
    fun matches(name: String?): Boolean {
        val n = name?.trim().orEmpty()
        return n.isNotEmpty() && namePrefixes.any { n.startsWith(it, ignoreCase = true) }
    }
}
```

- [ ] **Step 4: Create `Profiles.kt`**

```kotlin
package dev.joely.bmsmon.ble.profile

import dev.joely.bmsmon.ble.BmsProtocol
import java.util.UUID

/** The one validated profile today: Redodo/LiTime/PowerQueen/Starry-Sea on the Beken BK-BLE-1.0. */
val RedodoBekenProfile = BatteryProfile(
    id = "redodo-beken-bk-ble-1.0",
    displayName = "Redodo / LiTime / PowerQueen (Beken BK-BLE-1.0)",
    namePrefixes = listOf(
        "R-12", "R-24", "RO-12", "RO-24",
        "L-12", "L-24", "L-51", "LT-",
        "P-12", "P-24", "PQ-12", "PQ-24",
        "SS-", "S-",
    ),
    validatedFirmware = "BMS app FW V1.4 (model T12100); BLE module BK-BLE-1.0 FW 6.1.2",
    serviceUuid = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb"),
    notifyUuid = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb"),
    writeUuid = UUID.fromString("0000ffe2-0000-1000-8000-00805f9b34fb"),
    cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
    writeWithResponse = true,  // official Redodo app uses ATT Write Request (with response)
    statusFrame = BmsProtocol.frame(BmsProtocol.ReadCommand.STATUS),
    firmwareFrame = BmsProtocol.frame(BmsProtocol.ReadCommand.FW_VERSION),
    responseHeader = byteArrayOf(0x01, 0x93.toByte(), 0x55, 0xAA.toByte()),
    layout = TelemetryLayout(),
    stagePollMs = 1500L,
    slowPollMs = 10_000L,
    maxHeldConnections = 8,
    connectTimeoutMs = 10_000L,
    failThreshold = 3,
    backoff = BackoffSpec(baseMs = 5_000L, factor = 2, capMs = 120_000L),
)

/** Selects a profile from a device's advertised name (by prefix). */
object ProfileRegistry {
    val all: List<BatteryProfile> = listOf(RedodoBekenProfile)
    fun profileFor(name: String?): BatteryProfile? = all.firstOrNull { it.matches(name) }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.BatteryProfileTest"`
Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/dev/joely/bmsmon/ble/profile/ android/app/src/test/java/dev/joely/bmsmon/BatteryProfileTest.kt
git commit -m "Add BatteryProfile abstraction, Redodo profile, and prefix registry"
```

---

### Task 2: Make `parseTelemetry` read offsets from the profile layout

**Files:**
- Modify: `android/app/src/main/java/dev/joely/bmsmon/ble/BmsProtocol.kt`
- Test: existing `android/app/src/test/java/dev/joely/bmsmon/BmsProtocolTest.kt` (must keep passing)

**Interfaces:**
- Consumes: `TelemetryLayout`, `RedodoBekenProfile` (Task 1).
- Produces: `BmsProtocol.parseTelemetry(raw: ByteArray, name: String, layout: TelemetryLayout = RedodoBekenProfile.layout, header: ByteArray = RedodoBekenProfile.responseHeader): Telemetry?` — same 2-arg call site still works via defaults.

- [ ] **Step 1: Add a layout-driven test alongside the existing ones**

Append to `BmsProtocolTest.kt`:

```kotlin
    @Test fun parsesWithExplicitDefaultLayout() {
        // Same real frame the existing test uses, but passing the profile layout/header explicitly.
        val raw = hex(realStatus)
        val a = BmsProtocol.parseTelemetry(raw, "x")
        val b = BmsProtocol.parseTelemetry(
            raw, "x",
            dev.joely.bmsmon.ble.profile.RedodoBekenProfile.layout,
            dev.joely.bmsmon.ble.profile.RedodoBekenProfile.responseHeader,
        )
        org.junit.Assert.assertEquals(a?.soc, b?.soc)
        org.junit.Assert.assertEquals(a?.voltage, b?.voltage)
    }
```

(Reuse the existing `hex(...)` helper and `realStatus` fixture already in this test file.)

- [ ] **Step 2: Run to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.BmsProtocolTest"`
Expected: FAIL — `parseTelemetry` has no 4-arg overload yet (unresolved reference).

- [ ] **Step 3: Refactor `parseTelemetry` and `statusFrameStart` to use the layout/header**

In `BmsProtocol.kt`, change `statusFrameStart` to take the header, and `parseTelemetry` to take the layout + header with defaults. Replace the two functions:

```kotlin
    import dev.joely.bmsmon.ble.profile.RedodoBekenProfile
    import dev.joely.bmsmon.ble.profile.TelemetryLayout

    /** Offset of the status frame start, or null if the [header] marker isn't present. */
    private fun statusFrameStart(d: ByteArray, header: ByteArray): Int? {
        var i = 3
        while (i <= d.size - header.size) {
            var match = true
            for (k in header.indices) if (d[i + k] != header[k]) { match = false; break }
            if (match) return i - 3
            i++
        }
        return null
    }

    fun parseTelemetry(
        raw: ByteArray,
        name: String,
        layout: TelemetryLayout = RedodoBekenProfile.layout,
        header: ByteArray = RedodoBekenProfile.responseHeader,
    ): Telemetry? {
        val base = statusFrameStart(raw, header) ?: return null
        val d = if (base == 0) raw else raw.copyOfRange(base, raw.size)
        if (d.size < layout.minBytes) return null
        val totalV = u16(d, layout.voltageOff) / 1000f
        val current = i32(d, layout.currentOff) / 1000f
        val cellTemp = i16(d, layout.cellTempOff)
        val mosfetTemp = i16(d, layout.mosfetTempOff)
        val remainingAh = u16(d, layout.remainingAhOff) / 100f
        val fullAh = u32(d, layout.fullAhOff) / 100f
        val stateRaw = u16(d, layout.stateOff)
        val soc = u16(d, layout.socOff).toFloat()
        val soh = u32(d, layout.sohOff).toInt()
        val cycles = u32(d, layout.cyclesOff).toInt()
        if (soc < layout.socMin || soc > layout.socMax) return null
        if (totalV < layout.voltMin || totalV > layout.voltMax) return null
        val cells = (0 until layout.cellCount).map { u16(d, layout.cellsBaseOff + it * 2) / 1000f }.filter { it > 0.1f }
        val maxCell = cells.maxOrNull() ?: (totalV / 4f)
        val state = when (stateRaw) {
            1 -> BatteryState.Charging
            2 -> BatteryState.Discharging
            4 -> BatteryState.Disabled
            else -> BatteryState.Idle
        }
        val prot = u32(d, layout.protOff).toInt()
        val protections = PROTECTION_FLAGS.filter { (prot and it.key) != 0 }.values.toList()
        return Telemetry(
            name = name, soc = soc, powerW = totalV * abs(current), current = current, voltage = totalV,
            capacityAh = remainingAh, cellV = maxCell, temp = cellTemp.toFloat(), soh = soh, cycles = cycles,
            state = state, fullChargeAh = fullAh, mosfetTemp = mosfetTemp, cells = cells, protections = protections,
        )
    }
```

Note: `statusFrameOffset(raw)` (the public accessor added earlier, used by `classifyFrame`) must keep working — update it to `fun statusFrameOffset(raw: ByteArray): Int? = statusFrameStart(raw, RedodoBekenProfile.responseHeader)`.

- [ ] **Step 4: Run the full protocol test class**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.BmsProtocolTest" --tests "dev.joely.bmsmon.FrameReasonTest"`
Expected: PASS — all existing assertions plus the new one; `FrameReasonTest` still green (it uses `statusFrameOffset`).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/dev/joely/bmsmon/ble/BmsProtocol.kt android/app/src/test/java/dev/joely/bmsmon/BmsProtocolTest.kt
git commit -m "Drive telemetry parse offsets from the battery profile layout"
```

---

### Task 3: Select the profile in the scanner (replace the local prefix list)

**Files:**
- Modify: `android/app/src/main/java/dev/joely/bmsmon/ble/BleScanner.kt`
- Test: existing `android/app/src/test/java/dev/joely/bmsmon/BleScannerTest.kt` (must keep passing)

**Interfaces:**
- Consumes: `ProfileRegistry.profileFor` (Task 1).
- Produces: `isCompatibleBmsName(name)` unchanged in signature/behavior, now backed by the registry.

- [ ] **Step 1: Repoint `isCompatibleBmsName` to the registry**

In `BleScanner.kt`, delete the local `KNOWN_PREFIXES` list and rewrite the function:

```kotlin
import dev.joely.bmsmon.ble.profile.ProfileRegistry

/** True only for advertised names that match a known battery profile (see ble/profile). */
fun isCompatibleBmsName(name: String?): Boolean = ProfileRegistry.profileFor(name) != null
```

(Leave the rest of `BleScanner` unchanged.)

- [ ] **Step 2: Run the existing scanner test (no edits to the test)**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.BleScannerTest"`
Expected: PASS — the registry's prefixes are the same list, so every assertion (`R-12100…`, `RO-24100`, `L-51100`, `LT-`, `PQ-`, `SS-`, `S-` true; null/blank/`MyHeadphones`/`X-12100` false) still holds.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/dev/joely/bmsmon/ble/BleScanner.kt
git commit -m "Select battery profile by name prefix in the scanner"
```

---

### Task 4: Make `BleSession` profile-driven (UUIDs, status frame, write type)

**Files:**
- Modify: `android/app/src/main/java/dev/joely/bmsmon/ble/BleSession.kt`

**Interfaces:**
- Consumes: `BatteryProfile`, `RedodoBekenProfile` (Task 1).
- Produces: `class BleSession(context: Context, address: String, profile: BatteryProfile = RedodoBekenProfile)` — the session reads UUIDs, the status frame, and the write type from `profile`. Existing callers that construct `BleSession(context, address)` still compile via the default.

**Glue task** — validated by compile; on-device behavior is exercised in Task 8.

- [ ] **Step 1: Add the profile to the constructor and use it**

In `BleSession.kt`:
1. Change the constructor to `class BleSession(private val context: Context, private val address: String, private val profile: BatteryProfile = RedodoBekenProfile)` and add `import dev.joely.bmsmon.ble.profile.BatteryProfile` + `import dev.joely.bmsmon.ble.profile.RedodoBekenProfile`.
2. In `onServicesDiscovered`, replace `BmsProtocol.SERVICE_UUID` → `profile.serviceUuid`, `BmsProtocol.FFE1_NOTIFY` → `profile.notifyUuid`, `BmsProtocol.FFE2_WRITE` → `profile.writeUuid`, `BmsProtocol.CCCD` → `profile.cccdUuid`.
3. Replace `writeStatus`'s frame + write type:

```kotlin
    private fun writeStatus(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        val frame = profile.statusFrame
        val type = if (profile.writeWithResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                   else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(ch, frame, type)
        } else {
            @Suppress("DEPRECATION")
            run {
                ch.writeType = type
                ch.value = frame
                g.writeCharacteristic(ch)
            }
        }
    }
```

- [ ] **Step 2: Verify the whole app still compiles and unit tests pass**

Run: `cd android && ./gradlew :app:testDebugUnitTest :app:assembleDebug -q`
Expected: BUILD SUCCESSFUL; all unit tests pass. (The `BmsProtocol` UUID/`STATUS_FRAME` constants may now be unused by `BleSession`; leave them — `BatteryProfile` references `BmsProtocol.frame`, and removing constants is out of scope. If the compiler warns about an unused `STATUS_FRAME`, that's acceptable here; do not chase it.)

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/dev/joely/bmsmon/ble/BleSession.kt
git commit -m "Drive BleSession UUIDs, status frame, and write type from the profile"
```

---

## Phase 2 — Persistent-fleet connection manager

### Task 5: Backoff schedule (pure logic)

**Files:**
- Create: `android/app/src/main/java/dev/joely/bmsmon/ble/FleetPlanner.kt`
- Test: `android/app/src/test/java/dev/joely/bmsmon/FleetPlannerTest.kt`

**Interfaces:**
- Consumes: `BackoffSpec` (Task 1).
- Produces: `fun BackoffSpec.delayFor(failCount: Int): Long` — the wait before the next connect attempt after `failCount` consecutive failures (`failCount == 0` → 0; 1 → base; grows ×factor, capped).

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.joely.bmsmon

import dev.joely.bmsmon.ble.delayFor
import dev.joely.bmsmon.ble.profile.BackoffSpec
import org.junit.Assert.assertEquals
import org.junit.Test

class FleetPlannerTest {
    private val spec = BackoffSpec(baseMs = 5_000L, factor = 2, capMs = 120_000L)

    @Test fun backoffGrowsThenCaps() {
        assertEquals(0L, spec.delayFor(0))         // never failed → eligible immediately
        assertEquals(5_000L, spec.delayFor(1))     // 5s
        assertEquals(10_000L, spec.delayFor(2))    // 10s
        assertEquals(20_000L, spec.delayFor(3))    // 20s
        assertEquals(40_000L, spec.delayFor(4))    // 40s
        assertEquals(80_000L, spec.delayFor(5))    // 80s
        assertEquals(120_000L, spec.delayFor(6))   // 160s → capped at 120s
        assertEquals(120_000L, spec.delayFor(20))  // stays capped
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.FleetPlannerTest"`
Expected: FAIL — unresolved reference `delayFor`.

- [ ] **Step 3: Implement `delayFor`**

```kotlin
package dev.joely.bmsmon.ble

import dev.joely.bmsmon.ble.profile.BackoffSpec

/** Wait before the next connect attempt after [failCount] consecutive failures (0 → eligible now). */
fun BackoffSpec.delayFor(failCount: Int): Long {
    if (failCount <= 0) return 0L
    var d = baseMs
    repeat(failCount - 1) { d = (d * factor).coerceAtMost(capMs) }
    return d.coerceAtMost(capMs)
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.FleetPlannerTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/dev/joely/bmsmon/ble/FleetPlanner.kt android/app/src/test/java/dev/joely/bmsmon/FleetPlannerTest.kt
git commit -m "Add exponential backoff schedule for connect retry"
```

---

### Task 6: `planFleet` scheduler (pure logic — the heart)

**Files:**
- Modify: `android/app/src/main/java/dev/joely/bmsmon/ble/FleetPlanner.kt`
- Test: `android/app/src/test/java/dev/joely/bmsmon/FleetPlannerTest.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces:
  - `data class FleetPlan(val toConnect: List<String>, val toDisconnect: List<String>)`.
  - `fun planFleet(desired: Set<String>, stage: Set<String>, held: Set<String>, connecting: Set<String>, backoffUntil: Map<String, Long>, heldSince: Map<String, Long>, maxHeld: Int, now: Long): FleetPlan`.

Rules (deterministic):
- `toDisconnect` = packs currently `held` or `connecting` that are **no longer desired** (disabled/removed).
- A pack is **connect-eligible** if it is in `desired`, not in `held`/`connecting`, and `(backoffUntil[a] ?: 0) <= now`. Order eligible **stage-first, then by address** (stable).
- `freeSlots = maxHeld - (held + connecting - toDisconnect).size`.
- If `freeSlots > 0`: `toConnect` = the first `freeSlots` eligible packs.
- **Overflow** (no free slots but an eligible pack is waiting): if every slot is full and a desired pack still wants in, rotate out the **oldest-held non-stage** pack (smallest `heldSince`) to free one slot, and admit the first eligible pack. Stage packs are never rotated out. (For a fleet ≤ `maxHeld` this branch never triggers.)

- [ ] **Step 1: Write the failing tests**

Append to `FleetPlannerTest.kt`:

```kotlin
import dev.joely.bmsmon.ble.FleetPlan
import dev.joely.bmsmon.ble.planFleet

class FleetPlannerPlanTest {
    private fun plan(
        desired: Set<String>, stage: Set<String> = emptySet(), held: Set<String> = emptySet(),
        connecting: Set<String> = emptySet(), backoffUntil: Map<String, Long> = emptyMap(),
        heldSince: Map<String, Long> = emptyMap(), maxHeld: Int = 8, now: Long = 1000,
    ) = planFleet(desired, stage, held, connecting, backoffUntil, heldSince, maxHeld, now)

    @org.junit.Test fun connectsAllDesiredWithinBudget() {
        val p = plan(desired = setOf("A", "B", "C"))
        org.junit.Assert.assertEquals(listOf("A", "B", "C"), p.toConnect)
        org.junit.Assert.assertEquals(emptyList<String>(), p.toDisconnect)
    }

    @org.junit.Test fun stageConnectsFirst() {
        val p = plan(desired = setOf("A", "B", "C"), stage = setOf("C"), maxHeld = 1)
        org.junit.Assert.assertEquals(listOf("C"), p.toConnect)  // stage prioritized over A/B
    }

    @org.junit.Test fun skipsBackedOffPacks() {
        val p = plan(desired = setOf("A", "B"), backoffUntil = mapOf("A" to 5000), now = 1000)
        org.junit.Assert.assertEquals(listOf("B"), p.toConnect)  // A still backed off
    }

    @org.junit.Test fun dropsNoLongerDesired() {
        val p = plan(desired = setOf("A"), held = setOf("A", "B"))
        org.junit.Assert.assertEquals(listOf("B"), p.toDisconnect) // B disabled/removed
        org.junit.Assert.assertEquals(emptyList<String>(), p.toConnect)
    }

    @org.junit.Test fun respectsBudgetNoOverflowRotationWhenFull() {
        // 2 held fill the budget; a 3rd desired waits — with maxHeld=2 it neither connects nor rotates
        // a STAGE pack out. Here both held are stage, so no rotation; toConnect empty.
        val p = plan(desired = setOf("A", "B", "C"), stage = setOf("A", "B"),
            held = setOf("A", "B"), heldSince = mapOf("A" to 1, "B" to 2), maxHeld = 2)
        org.junit.Assert.assertEquals(emptyList<String>(), p.toConnect)
        org.junit.Assert.assertEquals(emptyList<String>(), p.toDisconnect)
    }

    @org.junit.Test fun overflowRotatesOldestNonStageOut() {
        // Budget full with non-stage held A(old),B(new); desired also wants C. Rotate oldest non-stage (A).
        val p = plan(desired = setOf("A", "B", "C"), stage = emptySet(),
            held = setOf("A", "B"), heldSince = mapOf("A" to 1, "B" to 5), maxHeld = 2)
        org.junit.Assert.assertEquals(listOf("A"), p.toDisconnect)
        org.junit.Assert.assertEquals(listOf("C"), p.toConnect)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.FleetPlannerPlanTest"`
Expected: FAIL — unresolved `planFleet` / `FleetPlan`.

- [ ] **Step 3: Implement `planFleet`**

Append to `FleetPlanner.kt`:

```kotlin
data class FleetPlan(val toConnect: List<String>, val toDisconnect: List<String>)

/**
 * Decide this tick's connect/disconnect actions. Pure: no BLE, no clock beyond [now].
 * Holds up to [maxHeld] links; stage packs get slots first; backed-off packs wait; non-desired
 * packs are dropped; true overflow rotates the oldest-held non-stage pack out to admit a waiter.
 */
fun planFleet(
    desired: Set<String>,
    stage: Set<String>,
    held: Set<String>,
    connecting: Set<String>,
    backoffUntil: Map<String, Long>,
    heldSince: Map<String, Long>,
    maxHeld: Int,
    now: Long,
): FleetPlan {
    val toDisconnect = (held + connecting).filter { it !in desired }.toMutableList()
    val activeAfterDrop = (held + connecting).filter { it in desired }
    val eligible = desired
        .filter { it !in held && it !in connecting }
        .filter { (backoffUntil[it] ?: 0L) <= now }
        .sortedWith(compareByDescending<String> { it in stage }.thenBy { it })
    val toConnect = mutableListOf<String>()
    var free = maxHeld - activeAfterDrop.size
    val waiting = eligible.toMutableList()
    while (free > 0 && waiting.isNotEmpty()) { toConnect += waiting.removeAt(0); free-- }
    // Overflow: budget full but a desired pack still waits → rotate oldest-held non-stage out.
    if (waiting.isNotEmpty()) {
        val victim = activeAfterDrop
            .filter { it in held && it !in stage }
            .minByOrNull { heldSince[it] ?: Long.MAX_VALUE }
        if (victim != null) { toDisconnect += victim; toConnect += waiting.removeAt(0) }
    }
    return FleetPlan(toConnect = toConnect, toDisconnect = toDisconnect)
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.FleetPlannerPlanTest" --tests "dev.joely.bmsmon.FleetPlannerTest"`
Expected: PASS (all scenarios).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/dev/joely/bmsmon/ble/FleetPlanner.kt android/app/src/test/java/dev/joely/bmsmon/FleetPlannerTest.kt
git commit -m "Add planFleet scheduler: budget, stage priority, backoff, overflow rotation"
```

---

### Task 7: Rework `BmsRepository` into the persistent-fleet manager

**Files:**
- Modify: `android/app/src/main/java/dev/joely/bmsmon/ble/BmsRepository.kt`

**Interfaces:**
- Consumes: `RedodoBekenProfile`/`ProfileRegistry`, `BleSession(context, address, profile)`, `planFleet`, `FleetPlan`, `BackoffSpec.delayFor` (Tasks 1, 4-6).
- Produces: same outward API — `start(scope, targets, onPoll: (String, ByteArray, Telemetry?) -> Unit, onReachable: (String, Boolean) -> Unit)`, `setStage(Set<String>)`, `setTargets(List<BmsTarget>)`, `setDisabled(Set<String>)`, `kickAll()`, `stop()`.

**Glue task** — the decision logic is already tested via `planFleet`/`delayFor`; this is the BLE execution loop. Validated by `assembleDebug` + the on-device run in Task 8. No new unit test.

- [ ] **Step 1: Replace the rotating sampler with a persistent-fleet loop**

Rewrite `BmsRepository.kt` so that instead of the `stageWorker`/`samplerLoop` connect→read→disconnect rotation, it:
1. Tracks per-address state: `held` (a map `address → BleSession`), `connecting` (set), `failCount` (map), `backoffUntil` (map), `heldSince` (map), `lastPolledAt` (map), plus `stageAddrs`, `disabledAddrs`, and the live `allTargets`.
2. Runs one **control loop** (`scope.launch(Dispatchers.IO)`) that every ~1 s computes `desired = allTargets.addresses − disabledAddrs`, calls
   `planFleet(desired, stageAddrs, held.keys, connecting, backoffUntil, heldSince, profileFor(any).maxHeldConnections, now())`
   (use `RedodoBekenProfile.maxHeldConnections` — all current targets share the profile), then:
   - For each `toDisconnect`: `held.remove(addr)?.close()`; `onReachable(addr, false)`.
   - For each `toConnect`: launch a connect job (gated by the existing `Semaphore(2)`): resolve the per-target profile via `ProfileRegistry.profileFor(target.name) ?: RedodoBekenProfile`, `BleSession(context, addr, profile)`, `connect(profile.connectTimeoutMs)`. On success → move to `held`, `heldSince[addr] = now()`, `failCount[addr] = 0`, `backoffUntil.remove(addr)`, `onReachable(addr, true)`. On failure → `connecting.remove`, `failCount[addr]++`, `backoffUntil[addr] = now() + RedodoBekenProfile.backoff.delayFor(failCount[addr])`, and if `failCount[addr] >= profile.failThreshold` → `onReachable(addr, false)`.
3. Runs **one poll job per held session** (started when the session enters `held`, cancelled on disconnect): loops `while held` → `delay(if (addr in stageAddrs) profile.stagePollMs else profile.slowPollMs)` → `session.poll(4000)` → if non-null, `onPoll(addr, raw, BmsProtocol.parseTelemetry(raw, name))` (forward every frame, as today); on a poll exception or null-from-drop, mark the session dropped: `held.remove(addr)?.close()`, `onReachable(addr, false)`, set a short backoff so it reconnects.
4. `setStage`, `setDisabled`, `setTargets`, `kickAll`, `stop` update the tracked sets and wake the control loop (reuse the existing `wakeSampler`/`CompletableDeferred` wake pattern). `stop()` closes all held sessions.

Keep the existing imports/`Semaphore(2)`/`TAG`. Remove `SAMPLER_GAP_MS`/`SAMPLE_FAIL_LIMIT`/`SAMPLER_CONCURRENCY` if now unused (the budget/backoff/cadence come from the profile). Preserve the `onPoll`/`onReachable` callback contract exactly (Task 10/11 of the earlier DB work depends on it).

> This is the one large glue change. Keep the per-pack state mutations confined to the single control-loop coroutine (and the poll jobs posting back through it) to avoid races — mirror the pattern the TelemetryRepository uses (single writer). If a clean single-threaded structure isn't achievable without more than this task's scope, stop and report DONE_WITH_CONCERNS rather than introducing a subtle concurrency bug.

- [ ] **Step 2: Verify it compiles + unit suite is green**

Run: `cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest -q`
Expected: BUILD SUCCESSFUL; all unit tests pass (the planner/profile tests cover the logic).

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/dev/joely/bmsmon/ble/BmsRepository.kt
git commit -m "Rework BmsRepository into a persistent-fleet connection manager"
```

---

### Task 8: On-device validation + adopt relaxed connection priority

**Files:**
- Modify: `android/app/src/main/java/dev/joely/bmsmon/ble/BleSession.kt` (connection priority)

**Interfaces:**
- Consumes: the reworked manager (Task 7).

- [ ] **Step 1: Request a low-power connection priority on connect**

In `BleSession.kt`'s `onServicesDiscovered` (after `setCharacteristicNotification`, before the CCCD write), add a gentle connection priority to match the official app's relaxed cadence:

```kotlin
        runCatching { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER) }
```

- [ ] **Step 2: Build + install + on-device behavior check**

Run:
```bash
cd android && ./gradlew :app:assembleDebug -q
adb connect 192.168.0.16:35165
adb -s 192.168.0.16:35165 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 192.168.0.16:35165 shell monkey -p dev.joely.bmsmon -c android.intent.category.LAUNCHER 1
```
Then enable monitoring in the app and confirm via logcat that the manager **holds** connections (no rapid connect/disconnect churn) and that flaky packs retry with growing spacing:
```bash
adb -s 192.168.0.16:35165 logcat -d -t 400 | grep -iE "BmsRepository|gatt|90:fb|conn_cnt|FAILED_ESTAB" | tail -40
```
Expected: packs connect and **stay** connected (stage polled ~1.5 s, others slow); a failing pack shows spaced-out retries (5 s → 10 s → …), not every-3-s hammering. If a device is unreachable, report DONE_WITH_CONCERNS and defer the manual check — the green build + planner unit tests are the hard gate.

- [ ] **Step 3: Verify disconnect-all still yields cleanly**

In the app, use **Disconnect all**; confirm in logcat the held GATTs close (no lingering connections), then **Reconnect all** re-establishes them. (This is the official-app-coexistence gesture.)

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/dev/joely/bmsmon/ble/BleSession.kt
git commit -m "Request low-power connection priority to match the official app's gentleness"
```

---

## Self-Review notes (addressed)

- **Spec coverage:** BatteryProfile + registry + selection-by-prefix (Task 1, 3); profile-driven parse/UUIDs/frames/write-type (Tasks 2, 4); prebuilt-frames-only closed command set preserved (Task 1 test `prebuiltFramesMatchTheClosedEnum`); persistent-hold + budget + stage-priority + backoff + overflow rotation (Tasks 5-7); cadences 1.5 s/10 s and cap 8 (Task 1 constants, Task 7 loop); write-request adoption (Task 4); relaxed connection priority — the spec's open item — resolved to LOW_POWER (Task 8). Disconnect-all yield verified (Task 8).
- **Behavior-preservation of Phase 1** is enforced by re-running the unchanged `BmsProtocolTest`/`BleScannerTest` (Tasks 2-3).
- **Type consistency:** `BatteryProfile`/`TelemetryLayout`/`BackoffSpec` fields are used identically across Tasks 1-7; `planFleet(desired, stage, held, connecting, backoffUntil, heldSince, maxHeld, now): FleetPlan` and `BackoffSpec.delayFor(failCount)` signatures match between definition (Tasks 5-6) and use (Task 7); the manager's `onPoll`/`onReachable` surface is unchanged from the existing engine contract.
- **Overflow rotation** (Task 6) is included per the spec but never triggers for the current 8-pack fleet at cap 8; it's the documented graceful-degradation path for larger fleets.
- **Deferred open item:** whether to fold `BmsProtocol` entirely into the profile — left as-is (the closed `ReadCommand` enum + `frame()` stay in `BmsProtocol` for safety; the profile references them). Not needed for this work.
