# Charge-Time Estimate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show a live "time to full" estimate on the Android stage and the WebUI whenever a pack is steadily charging, with a per-pack tail duration that self-learns from completed charges.

**Architecture:** A pure Kotlin function `estimateChargeMinutes(...)` does coulomb counting on live current for the bulk (SOC < 98%) plus a learned per-pack tail constant for the 98→100% CV plateau. A pure `observedChargeTailMinutes(...)` extracts the tail duration from a completed charge and `foldTailEma(...)` folds it into a per-address EMA persisted in `SettingsStore`. The `MonitorEngine` computes the estimate each poll, uploads it as a new nullable `eta_full_min` sample field, and (on reaching 100%) runs the learner. The server stores/returns the field; the WebUI renders it.

**Tech Stack:** Kotlin/Jetpack Compose (Android), Room, kotlinx.serialization; FastAPI + asyncpg + Postgres 16 (server); React + Vite + TypeScript (web). Tests: JUnit 4 (android), pytest (server), vitest (web).

## Global Constraints

- Read-only BLE protocol only — this feature adds **no** new BMS commands. Display/compute only.
- Never break existing on-device data: **no destructive Room migration.** Only additive DAO `@Query` methods and DataStore keys (no `SampleEntity`/`SessionEntity` column changes).
- Server schema changes are additive idempotent SQL (`ALTER TABLE ... ADD COLUMN IF NOT EXISTS`) in `server/app/db/schema.sql`; they apply on container start (no separate migration step).
- Tail tunables (verbatim): `TAIL_START_SOC = 98f`, `TARGET_SOC = 100f`, `SEED_TAIL_MIN = 45f`, `TAIL_EMA_ALPHA = 0.3f`, bulk current floor `0.2f`.
- Commit messages: no "Generated with Claude Code", no "Co-Authored-By: Claude", no AI/Claude references.
- Android unit tests run: `cd android && ./gradlew :app:testDebugUnitTest`. Server: `cd server && .venv/bin/python -m pytest`. Web: `cd web && npm test`.

---

### Task 1: Pure charge-time estimator

**Files:**
- Create: `android/app/src/main/java/dev/joely/bmsmon/model/ChargeEstimate.kt`
- Test: `android/app/src/test/java/dev/joely/bmsmon/ChargeEstimateTest.kt`

**Interfaces:**
- Consumes: `BatteryState` (from `model/Telemetry.kt`).
- Produces: constants `TAIL_START_SOC`, `TARGET_SOC`, `SEED_TAIL_MIN`, `TAIL_EMA_ALPHA`; `fun estimateChargeMinutes(state: BatteryState, soc: Float, currentA: Float, fullChargeAh: Float, remainingAh: Float, regen: Boolean, tailMin: Float): Float?`; `fun formatEtaMinutes(minutes: Float): String`.

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.joely.bmsmon

import dev.joely.bmsmon.model.BatteryState
import dev.joely.bmsmon.model.estimateChargeMinutes
import dev.joely.bmsmon.model.formatEtaMinutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChargeEstimateTest {
    private val charging = BatteryState.Charging

    @Test fun bulkRegionAddsCoulombPlusTail() {
        // 98% of 105Ah = 102.9Ah; minus 85.8 remaining = 17.1Ah; /8.1A*60 = 126.67min; +45 tail.
        val m = estimateChargeMinutes(charging, soc = 81f, currentA = 8.1f,
            fullChargeAh = 105f, remainingAh = 85.8f, regen = false, tailMin = 45f)!!
        assertEquals(171.67f, m, 0.5f)
    }

    @Test fun tailRegionScalesLearnedConstant() {
        // soc 99 -> half of the 98..100 band remains -> 45 * 0.5.
        val m = estimateChargeMinutes(charging, soc = 99f, currentA = 3f,
            fullChargeAh = 105f, remainingAh = 104f, regen = false, tailMin = 45f)!!
        assertEquals(22.5f, m, 0.01f)
    }

    @Test fun nullWhenNotCharging() {
        assertNull(estimateChargeMinutes(BatteryState.Idle, 80f, 8f, 105f, 84f, false, 45f))
    }

    @Test fun nullWhenRegen() {
        assertNull(estimateChargeMinutes(charging, 80f, 8f, 105f, 84f, regen = true, tailMin = 45f))
    }

    @Test fun nullAtOrAboveFull() {
        assertNull(estimateChargeMinutes(charging, 100f, 8f, 105f, 105f, false, 45f))
    }

    @Test fun nullWhenBulkHasNoCurrent() {
        assertNull(estimateChargeMinutes(charging, 80f, 0f, 105f, 84f, false, 45f))
    }

    @Test fun nullWhenCapacityUnknown() {
        assertNull(estimateChargeMinutes(charging, 80f, 8f, 0f, 0f, false, 45f))
    }

    @Test fun formatsHoursAndMinutes() {
        assertEquals("2h 14m", formatEtaMinutes(134f))
        assertEquals("45m", formatEtaMinutes(45f))
        assertEquals("0m", formatEtaMinutes(-3f))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.ChargeEstimateTest"`
Expected: FAIL — `estimateChargeMinutes` / `formatEtaMinutes` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package dev.joely.bmsmon.model

import kotlin.math.roundToInt

/**
 * Charge-time estimate tunables. Design: docs/superpowers/specs/2026-07-01-charge-time-estimate-design.md
 * The 98..100% plateau (CV/absorption) is charger-dependent and not coulomb-estimable, so it is a
 * learned per-pack constant; the bulk below 98% is straight coulomb counting on live current.
 */
const val TAIL_START_SOC = 98f
const val TARGET_SOC = 100f
const val SEED_TAIL_MIN = 45f
const val TAIL_EMA_ALPHA = 0.3f
private const val MIN_CHARGE_CURRENT_A = 0.2f

/**
 * Estimated minutes to reach [TARGET_SOC] (100%), or null when no estimate applies (not steadily
 * charging, regen burst, already full, or implausible/unknown inputs). [remainingAh] is the BMS
 * remaining capacity, [tailMin] the learned per-pack 98->100 duration.
 */
fun estimateChargeMinutes(
    state: BatteryState,
    soc: Float,
    currentA: Float,
    fullChargeAh: Float,
    remainingAh: Float,
    regen: Boolean,
    tailMin: Float,
): Float? {
    if (state != BatteryState.Charging || regen) return null
    if (fullChargeAh <= 0f || soc < 0f || soc >= TARGET_SOC) return null
    val tail = tailMin.coerceAtLeast(0f)
    if (soc >= TAIL_START_SOC) {
        val frac = ((TARGET_SOC - soc) / (TARGET_SOC - TAIL_START_SOC)).coerceIn(0f, 1f)
        return tail * frac
    }
    if (currentA < MIN_CHARGE_CURRENT_A) return null
    val tailStartAh = TAIL_START_SOC / 100f * fullChargeAh
    val bulkAh = (tailStartAh - remainingAh).coerceAtLeast(0f)
    val bulkMin = bulkAh / currentA * 60f
    return bulkMin + tail
}

/** Compact "2h 14m" / "45m" for a minutes estimate (clamped at 0). */
fun formatEtaMinutes(minutes: Float): String {
    val total = minutes.roundToInt().coerceAtLeast(0)
    val h = total / 60
    val m = total % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.ChargeEstimateTest"`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/dev/joely/bmsmon/model/ChargeEstimate.kt \
        android/app/src/test/java/dev/joely/bmsmon/ChargeEstimateTest.kt
git commit -m "feat(android): pure charge-time estimator (coulomb bulk + learned tail)"
```

---

### Task 2: Pure tail learner

**Files:**
- Create: `android/app/src/main/java/dev/joely/bmsmon/model/ChargeTailLearn.kt`
- Test: `android/app/src/test/java/dev/joely/bmsmon/ChargeTailLearnTest.kt`

**Interfaces:**
- Consumes: `TAIL_START_SOC`, `TARGET_SOC`, `TAIL_EMA_ALPHA` (from Task 1).
- Produces: `data class ChargeSample(val tsMs: Long, val soc: Float, val charging: Boolean)`; `fun observedChargeTailMinutes(samples: List<ChargeSample>): Float?`; `fun foldTailEma(prev: Float, observed: Float): Float`.

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.joely.bmsmon

import dev.joely.bmsmon.model.ChargeSample
import dev.joely.bmsmon.model.foldTailEma
import dev.joely.bmsmon.model.observedChargeTailMinutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChargeTailLearnTest {
    private fun climb(startSoc: Int, endSoc: Int, stepMs: Long = 60_000L, t0: Long = 0L) =
        (startSoc..endSoc).mapIndexed { i, s -> ChargeSample(t0 + i * stepMs, s.toFloat(), true) }

    @Test fun measuresMinutesFrom98To100() {
        // 96..100 at 60s steps: 98 at index 2, 100 at index 4 -> 2 minutes.
        assertEquals(2.0f, observedChargeTailMinutes(climb(96, 100))!!, 0.001f)
    }

    @Test fun nullWhenNeverReaches100() {
        assertNull(observedChargeTailMinutes(climb(96, 99)))
    }

    @Test fun nullWhenStartsAlreadyInTail() {
        // No sample below 98% -> not a clean climb-through.
        assertNull(observedChargeTailMinutes(climb(99, 100)))
    }

    @Test fun nullWhenAGapBreaksTheRun() {
        val run1 = climb(96, 98, t0 = 0L)                       // below + 98, no 100
        val run2 = climb(100, 100, t0 = 10 * 60_000L)           // 100 after a 10-min gap
        assertNull(observedChargeTailMinutes(run1 + run2))
    }

    @Test fun nonChargingSampleBreaksTheRun() {
        val a = climb(96, 98)
        val gap = listOf(ChargeSample(a.last().tsMs + 60_000L, 98f, false))
        val b = listOf(ChargeSample(a.last().tsMs + 120_000L, 100f, true))
        assertNull(observedChargeTailMinutes(a + gap + b))
    }

    @Test fun emaFoldsTowardObservation() {
        assertEquals(51.0f, foldTailEma(prev = 45f, observed = 65f), 0.001f)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.ChargeTailLearnTest"`
Expected: FAIL — symbols unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package dev.joely.bmsmon.model

/** One time-ordered charge observation for tail learning. */
data class ChargeSample(val tsMs: Long, val soc: Float, val charging: Boolean)

/** Max gap within a single contiguous charging run before it's treated as broken. */
private const val TAIL_MAX_GAP_MS = 5 * 60_000L

/**
 * Minutes from first reaching [TAIL_START_SOC] (98%) to first reaching [TARGET_SOC] (100%) within
 * the most recent contiguous charging run that cleanly climbed through the tail (had a sample below
 * 98% first). Returns null if no such completed tail exists — unplugged early, started already full,
 * or a gap/non-charging sample broke the run. [samples] must be ascending by tsMs.
 */
fun observedChargeTailMinutes(samples: List<ChargeSample>): Float? {
    var best: Float? = null
    var i = 0
    while (i < samples.size) {
        if (!samples[i].charging) { i++; continue }
        var j = i
        while (j + 1 < samples.size &&
            samples[j + 1].charging &&
            samples[j + 1].tsMs - samples[j].tsMs <= TAIL_MAX_GAP_MS
        ) j++
        val run = samples.subList(i, j + 1)
        val sawBelow = run.any { it.soc < TAIL_START_SOC }
        val t98 = run.firstOrNull { it.soc >= TAIL_START_SOC }?.tsMs
        val t100 = run.firstOrNull { it.soc >= TARGET_SOC }?.tsMs
        if (sawBelow && t98 != null && t100 != null && t100 > t98) {
            best = (t100 - t98) / 60_000f   // most recent qualifying run wins
        }
        i = j + 1
    }
    return best
}

/** Fold a new tail observation into the running per-pack EMA. */
fun foldTailEma(prev: Float, observed: Float): Float =
    TAIL_EMA_ALPHA * observed + (1f - TAIL_EMA_ALPHA) * prev
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.ChargeTailLearnTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/dev/joely/bmsmon/model/ChargeTailLearn.kt \
        android/app/src/test/java/dev/joely/bmsmon/ChargeTailLearnTest.kt
git commit -m "feat(android): pure tail-duration learner + EMA fold"
```

---

### Task 3: Persist learned tail + recent-samples query

**Files:**
- Modify: `android/app/src/main/java/dev/joely/bmsmon/data/SettingsStore.kt` (add key, Persisted field, load, getter/setter + JSON codec — mirror the `TEMP_THRESHOLDS` map pattern at lines 72–115, 117–162, 202–259)
- Modify: `android/app/src/main/java/dev/joely/bmsmon/data/db/Daos.kt` (add `since(...)` query to `SampleDao`, after `telemetryFor`, ~line 20)
- Modify: `android/app/src/main/java/dev/joely/bmsmon/data/TelemetryRepository.kt` (add `recentSamples(...)` facade, near `telemetry(...)` line 128)

**Interfaces:**
- Consumes: `SampleEntity`, `SampleDao` (data/db).
- Produces: `SettingsStore.load().chargeTailMinByAddress: Map<String, Float>`; `suspend fun SettingsStore.setChargeTailMin(address: String, minutes: Float)`; `suspend fun SampleDao.since(address: String, sinceMs: Long): List<SampleEntity>`; `suspend fun TelemetryRepository.recentSamples(address: String, sinceMs: Long): List<SampleEntity>`.

- [ ] **Step 1: Add the DAO query**

In `data/db/Daos.kt`, inside `interface SampleDao`, after the `telemetryFor` query:

```kotlin
    @Query("SELECT * FROM samples WHERE address = :address AND tsMs >= :sinceMs AND linkEvent IS NULL ORDER BY tsMs ASC")
    suspend fun since(address: String, sinceMs: Long): List<SampleEntity>
```

- [ ] **Step 2: Add the repository facade**

In `data/TelemetryRepository.kt`, after `suspend fun telemetry(address: String)` (line 128):

```kotlin
    /** Telemetry rows for one pack since [sinceMs] (link rows excluded), oldest first — for tail learning. */
    suspend fun recentSamples(address: String, sinceMs: Long): List<SampleEntity> =
        db.samples().since(address, sinceMs)
```

- [ ] **Step 3: Add the SettingsStore key, codec, load, getter/setter**

In `data/SettingsStore.kt` — add the key inside `object K` (near line 78, alongside `TEMP_THRESHOLDS`):

```kotlin
    val CHARGE_TAIL_MIN = stringPreferencesKey("charge_tail_min_by_address")
```

Add a field to the `Persisted` data class (alongside `tempThresholdsByProfile`):

```kotlin
    val chargeTailMinByAddress: Map<String, Float> = emptyMap(),
```

In `load()` (near the `tempThresholdsByProfile = ...` line), add:

```kotlin
        chargeTailMinByAddress = p[K.CHARGE_TAIL_MIN]?.let(::decodeChargeTail) ?: emptyMap(),
```

Add the setter + codecs (mirror `setTempThresholds`/`encodeTempThresholds`/`decodeTempThresholds`):

```kotlin
    suspend fun setChargeTailMin(address: String, minutes: Float) =
        context.dataStore.edit { prefs ->
            val cur = prefs[K.CHARGE_TAIL_MIN]?.let(::decodeChargeTail) ?: emptyMap()
            prefs[K.CHARGE_TAIL_MIN] = encodeChargeTail(cur + (address to minutes))
        }.let {}

    private fun encodeChargeTail(map: Map<String, Float>): String {
        val root = org.json.JSONObject()
        map.forEach { (addr, m) -> root.put(addr, m.toDouble()) }
        return root.toString()
    }

    private fun decodeChargeTail(s: String): Map<String, Float> {
        val root = org.json.JSONObject(s)
        return root.keys().asSequence().associateWith { root.getDouble(it).toFloat() }
    }
```

- [ ] **Step 4: Verify it compiles**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/dev/joely/bmsmon/data/SettingsStore.kt \
        android/app/src/main/java/dev/joely/bmsmon/data/db/Daos.kt \
        android/app/src/main/java/dev/joely/bmsmon/data/TelemetryRepository.kt
git commit -m "feat(android): persist per-pack learned tail + recent-samples query"
```

---

### Task 4: Engine — compute estimate, learn on completion, expose learned map

**Files:**
- Modify: `android/app/src/main/java/dev/joely/bmsmon/monitor/MonitorEngine.kt` (constructor ~line 70, `MonitorState` line 49–57, `onPoll` line 239–277, add `start()`-time load + `learnTail`)
- Modify: the single `MonitorEngine(...)` construction site (grep `MonitorEngine(` — expected in `android/app/src/main/java/dev/joely/bmsmon/BmsApp.kt`)

**Interfaces:**
- Consumes: `estimateChargeMinutes`, `observedChargeTailMinutes`, `foldTailEma`, `ChargeSample`, `SEED_TAIL_MIN`, `TARGET_SOC` (Tasks 1–2); `SettingsStore.setChargeTailMin`, `load().chargeTailMinByAddress`, `recentSamples` (Task 3); `TelemetryReporter.report(..., etaFullMin)` (Task 5).
- Produces: `MonitorState.tailMinByAddress: Map<String, Float>`; the engine passes `etaFullMin` into `reporter.report(...)`.

**Note:** Task 5 adds the `etaFullMin` parameter to `reporter.report(...)`. Implement Task 5 before Step 3's `reporter?.report(...)` edit compiles, or do Tasks 4 and 5 together and compile once at the end.

- [ ] **Step 1: Add `settings` to the constructor and the learned map to state**

Add a `SettingsStore` constructor parameter (import `dev.joely.bmsmon.data.SettingsStore`). The constructor currently (line 70–74) takes `appContext`, `db`, `reporter` and others — add:

```kotlin
    private val settings: SettingsStore,
```

At the `MonitorEngine(...)` construction site (grep `MonitorEngine(`), pass the existing `SettingsStore` instance (the same one handed to `TelemetryReporter`).

Extend `MonitorState` (line 49–57) with:

```kotlin
    val tailMinByAddress: Map<String, Float> = emptyMap(),
```

Add a per-address learn debounce field near the other private engine fields (~line 80):

```kotlin
    private val lastTailLearnAt = HashMap<String, Long>()
```

- [ ] **Step 2: Load the persisted learned map when monitoring starts**

Inside `start()` (after `_state.update { MonitorState( ... ) }`, near line 116–137), add:

```kotlin
        scope.launch {
            val saved = settings.load().chargeTailMinByAddress
            if (saved.isNotEmpty()) _state.update { it.copy(tailMinByAddress = saved) }
        }
```

- [ ] **Step 3: Compute the estimate in `onPoll` and trigger learning at 100%**

In `onPoll` (line 239–277), immediately after the `_state.update { ... }` block that sets `fleet`/`regenAddrs` (ends line 265) and before the `reporter?.report(` call, add:

```kotlin
        val tailMin = st0.tailMinByAddress[addr] ?: SEED_TAIL_MIN
        val etaFullMin = estimateChargeMinutes(
            t.state, t.soc, t.current, t.fullChargeAh, t.capacityAh, regen, tailMin,
        )
```

Change the `reporter?.report(...)` call (line 267–270) to pass the estimate:

```kotlin
        reporter?.report(
            addr, roster.batteryAt(addr)?.advertisedName, roster.batteryAt(addr)?.alias,
            group?.id, t, now, regen, fix?.lat, fix?.lon, fix?.accuracyM, etaFullMin,
        )
```

At the end of `onPoll` (after the `if (logging) { ... }` block, before the `evaluateAlerts()` line ~276), add the learn trigger:

```kotlin
        if (t.state == BatteryState.Charging && t.soc >= TARGET_SOC &&
            now - (lastTailLearnAt[addr] ?: 0L) > 30 * 60_000L
        ) {
            lastTailLearnAt[addr] = now
            scope.launch { learnTail(addr, now) }
        }
```

Add the `learnTail` function to the engine (near the other private helpers):

```kotlin
    /** Fold the just-completed charge's observed 98->100 tail into the per-pack EMA and persist it. */
    private suspend fun learnTail(addr: String, now: Long) {
        val since = now - 6 * 60 * 60_000L   // look back 6h for the completed run
        val samples = repository.recentSamples(addr, since).map {
            ChargeSample(it.tsMs, it.soc ?: -1f, it.state == "Charging")
        }
        val observed = observedChargeTailMinutes(samples) ?: return
        val prev = _state.value.tailMinByAddress[addr] ?: SEED_TAIL_MIN
        val next = foldTailEma(prev, observed)
        _state.update { it.copy(tailMinByAddress = it.tailMinByAddress + (addr to next)) }
        settings.setChargeTailMin(addr, next)
    }
```

Add imports at the top of the file:

```kotlin
import dev.joely.bmsmon.model.ChargeSample
import dev.joely.bmsmon.model.SEED_TAIL_MIN
import dev.joely.bmsmon.model.TARGET_SOC
import dev.joely.bmsmon.model.estimateChargeMinutes
import dev.joely.bmsmon.model.foldTailEma
import dev.joely.bmsmon.model.observedChargeTailMinutes
```

- [ ] **Step 4: Verify it compiles (with Task 5 applied)**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/dev/joely/bmsmon/monitor/MonitorEngine.kt \
        android/app/src/main/java/dev/joely/bmsmon/BmsApp.kt
git commit -m "feat(android): compute charge ETA each poll, learn tail on full charge"
```

---

### Task 5: Upload plumbing (`eta_full_min` through report → outbox)

**Files:**
- Modify: `android/app/src/main/java/dev/joely/bmsmon/cloud/CloudJson.kt` (`SampleJson` line 7–32; `sampleJson(...)` line 37–49)
- Modify: `android/app/src/main/java/dev/joely/bmsmon/cloud/TelemetryReporter.kt` (`report(...)` line 62–83; the import-batch `sampleJson(...)` call line 132–139)

**Interfaces:**
- Produces: `SampleJson.eta_full_min: Float?`; `CloudJson.sampleJson(..., etaFullMin: Float? = null)`; `TelemetryReporter.report(..., etaFullMin: Float? = null)`.
- Consumed by: Task 4 (engine call), and the server (Task 8) reads `eta_full_min` from the JSON.

- [ ] **Step 1: Add the field to `SampleJson` and `sampleJson`**

In `cloud/CloudJson.kt`, add to `SampleJson` after `gps_accuracy_m` (line 31):

```kotlin
    val eta_full_min: Float? = null,
```

Add the parameter to `sampleJson(...)` (after `gpsAccuracyM`, line 43) and pass it through the constructor call (line 46–48):

```kotlin
        lat: Double? = null, lon: Double? = null, gpsAccuracyM: Float? = null,
        etaFullMin: Float? = null,
    ): String = json.encodeToString(
        SampleJson.serializer(),
        SampleJson(tsMs, address, advertisedName, alias, groupId, state, soc, currentA, powerW,
            voltageV, tempC, mosfetTempC, soh, fullChargeAh, remainingAh, cycles, cellMinV, cellMaxV,
            regen, linkEvent, lat, lon, gpsAccuracyM, etaFullMin),
    )
```

- [ ] **Step 2: Thread it through `report(...)`**

In `cloud/TelemetryReporter.kt`, add the parameter to `report(...)` (after `gpsAccuracyM: Float? = null,`, line 72):

```kotlin
        etaFullMin: Float? = null,
```

Pass it in the `CloudJson.sampleJson(...)` call inside `report` (line 75–81) as the final argument:

```kotlin
        val payload = CloudJson.sampleJson(
            tsMs, addr, advertisedName, alias, groupId,
            t.state.name, t.soc, t.current, t.powerW, t.voltage, t.temp, t.mosfetTemp,
            t.soh, t.fullChargeAh, t.capacityAh, t.cycles,
            t.cells.minOrNull(), t.cells.maxOrNull(), regen, null,
            lat, lon, gpsAccuracyM, etaFullMin,
        )
```

(The historical import call at line 132–139 needs no change — `etaFullMin` defaults to null for backfilled rows.)

- [ ] **Step 3: Verify it compiles**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/dev/joely/bmsmon/cloud/CloudJson.kt \
        android/app/src/main/java/dev/joely/bmsmon/cloud/TelemetryReporter.kt
git commit -m "feat(android): upload eta_full_min with each telemetry sample"
```

---

### Task 6: Surface the estimate on `StageItem` for display

**Files:**
- Modify: `android/app/src/main/java/dev/joely/bmsmon/model/Fleet.kt` (`StageItem` line 124)
- Modify: `android/app/src/main/java/dev/joely/bmsmon/BatteryViewModel.kt` (`UiState` ~line 126, `stageItems()` line 194–208, engine-state mirror line 389–395)

**Interfaces:**
- Consumes: `estimateChargeMinutes`, `SEED_TAIL_MIN` (Task 1); `MonitorState.tailMinByAddress` (Task 4).
- Produces: `StageItem.etaFullMin: Float?`; `UiState.tailMinByAddress: Map<String, Float>`.

- [ ] **Step 1: Add the field to `StageItem`**

In `model/Fleet.kt`, line 124:

```kotlin
data class StageItem(
    val telemetry: Telemetry,
    val regen: Boolean,
    val connected: Boolean = true,
    val etaFullMin: Float? = null,
)
```

- [ ] **Step 2: Mirror the learned map into `UiState` and compute the ETA in `stageItems()`**

In `BatteryViewModel.kt`, add to `UiState` (near `regenAddrs`, line 126):

```kotlin
    val tailMinByAddress: Map<String, Float> = emptyMap(),
```

In the `engine.state.collect { es -> ... it.copy(...) }` mirror (line 392–395), add:

```kotlin
                            tailMinByAddress = es.tailMinByAddress,
```

Rewrite `stageItems()` (line 194–208) so each item carries its ETA:

```kotlin
    fun stageItems(): List<StageItem> {
        val targets = when (val t = stageTarget) {
            is StageTarget.Base -> roster.groupById(t.groupId)?.targets ?: emptyList()
            is StageTarget.Single -> roster.targetFor(t.address)?.let { listOf(it) } ?: emptyList()
        }
        return targets.map { tg ->
            val status = fleet[tg.address]
            val connected = status?.reachable == true && status.telemetry != null
            val tel = status?.telemetry?.copy(name = tg.name)
                ?: Telemetry(tg.name, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
            val regenFlag = connected && tg.address in regenAddrs
            val eta = if (connected) estimateChargeMinutes(
                tel.state, tel.soc, tel.current, tel.fullChargeAh, tel.capacityAh,
                regenFlag, tailMinByAddress[tg.address] ?: SEED_TAIL_MIN,
            ) else null
            StageItem(tel, regen = regenFlag, connected = connected, etaFullMin = eta)
        }
    }
```

Add imports (top of `BatteryViewModel.kt`):

```kotlin
import dev.joely.bmsmon.model.SEED_TAIL_MIN
import dev.joely.bmsmon.model.estimateChargeMinutes
```

- [ ] **Step 3: Verify it compiles**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/dev/joely/bmsmon/model/Fleet.kt \
        android/app/src/main/java/dev/joely/bmsmon/BatteryViewModel.kt
git commit -m "feat(android): carry charge ETA on StageItem for the stage"
```

---

### Task 7: Render "~2h 14m to full" under the stage ring

**Files:**
- Modify: `android/app/src/main/java/dev/joely/bmsmon/ui/home/StageScreen.kt` (`StageRingBox`, the readout `Column` at line 165–185)

**Interfaces:**
- Consumes: `StageItem.etaFullMin` (Task 6); `formatEtaMinutes` (Task 1).

- [ ] **Step 1: Add the ETA line to the readout column**

In `StageScreen.kt`, inside `StageRingBox`'s `if (item.connected) { Column(...) { ... } }`, after the power `Text(...)` (ends ~line 180), add:

```kotlin
                item.etaFullMin?.let { eta ->
                    Text(
                        "~${formatEtaMinutes(eta)} to full",
                        color = c.text2,
                        fontFamily = MonoFont,
                        fontSize = 12.sp,
                    )
                }
```

Add the import (top of `StageScreen.kt`):

```kotlin
import dev.joely.bmsmon.model.formatEtaMinutes
```

- [ ] **Step 2: Verify it compiles**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Build the debug APK to confirm the UI module links**

Run: `cd android && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. (Manual on-device check: while a pack charges, the stage shows `~Xh Ym to full` under the wattage; it disappears when idle/discharging/full.)

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/dev/joely/bmsmon/ui/home/StageScreen.kt
git commit -m "feat(android): show time-to-full under the stage ring while charging"
```

---

### Task 8: Server — store and return `eta_full_min`

**Files:**
- Modify: `server/app/db/schema.sql` (additive `ALTER TABLE`, after line 49)
- Modify: `server/app/models.py` (`SampleIn`, after line 39)
- Modify: `server/app/db/queries.py` (`_COLS` line 8–10; `_INSERT` line 25–33; values tuple line 41–48; `fleet_snapshot` SELECT line 97–108)
- Test: `server/tests/test_eta_full_min.py` (create)

**Interfaces:**
- Consumes: uploaded sample JSON with optional `eta_full_min` (Task 5).
- Produces: `samples.eta_full_min` column; `eta_full_min` in `/web/fleet` rows and `/ws` sample events (the latter flows automatically via `s.model_dump()` in `api_device.py:89`).

- [ ] **Step 1: Write the failing test**

Create `server/tests/test_eta_full_min.py` (mirror `test_ws_live.py` conventions):

```python
import json
import time
import uuid

import jwt
from fastapi.testclient import TestClient

from app.main import create_app
from tests.conftest import ADMIN, _bh, _kp  # reuse existing helpers

A = "C8:47:80:15:67:44"


def test_ingest_and_fleet_roundtrip_eta():
    with TestClient(create_app()) as tc:
        code = tc.post("/web/enroll-codes", headers={
            "X-authentik-username": "t", "X-authentik-groups": ADMIN}).json()["code"]
        priv, pub_b64 = _kp()
        device_id = tc.post("/api/v1/enroll", json={
            "code": code, "install_uuid": f"eta-{uuid.uuid4().hex}",
            "public_key_spki_b64": pub_b64}).json()["device_id"]
        body = json.dumps({"batch_seq": 1, "samples": [
            {"ts_ms": int(time.time() * 1000), "address": A, "soc": 81.0,
             "eta_full_min": 171.7, "alias": "2012 · A"}]}).encode()
        tok = jwt.encode({"sub": device_id, "iat": int(time.time()), "exp": int(time.time()) + 60,
                          "jti": uuid.uuid4().hex, "bh": _bh(body)}, priv, algorithm="ES256")
        r = tc.post("/api/v1/ingest", content=body, headers={"Authorization": f"Bearer {tok}"})
        assert r.status_code == 200
        fleet = tc.get("/web/fleet", headers={"X-authentik-username": "t"}).json()
        row = next(x for x in fleet if x["address"] == A)
        assert abs(row["eta_full_min"] - 171.7) < 0.5
```

> Before running: open `server/tests/conftest.py` and confirm the helper names (`ADMIN`, `_bh`, `_kp`, and the `/web/fleet` auth header). If they differ, match them — `test_ws_live.py` inlines `_kp`/`_bh`; reuse whatever those tests import. Adjust the import line accordingly.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && .venv/bin/python -m pytest tests/test_eta_full_min.py -v`
Expected: FAIL — `eta_full_min` KeyError / column does not exist.

- [ ] **Step 3: Add the column, model field, and query columns**

`server/app/db/schema.sql`, after line 49 (the GPS `ALTER`s):

```sql
ALTER TABLE samples ADD COLUMN IF NOT EXISTS eta_full_min real;
```

`server/app/models.py`, in `SampleIn` after `gps_accuracy_m` (line 39):

```python
    eta_full_min: float | None = None
```

`server/app/db/queries.py` — add to `_COLS` (line 8–10):

```python
_COLS = ["state", "soc", "current_a", "power_w", "voltage_v", "temp_c", "mosfet_temp_c",
         "soh", "full_charge_ah", "remaining_ah", "cycles", "cell_min_v", "cell_max_v",
         "link_event", "lat", "lon", "gps_accuracy_m", "eta_full_min"]
```

Update `_INSERT` — add the column and a `$24` placeholder (line 25–33):

```python
_INSERT = """
INSERT INTO samples
  (device_id,address,ts_ms,ts,state,soc,current_a,power_w,voltage_v,temp_c,
   mosfet_temp_c,soh,full_charge_ah,remaining_ah,cycles,cell_min_v,cell_max_v,cells,regen,link_event,
   lat,lon,gps_accuracy_m,eta_full_min)
VALUES
  ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18,$19,$20,$21,$22,$23,$24)
ON CONFLICT DO NOTHING
"""
```

Update the values tuple in `insert_samples` (line 41–48) — add `r["eta_full_min"]` as the final value:

```python
    await conn.executemany(_INSERT, [
        (r["device_id"], r["address"], r["ts_ms"], r["ts"], r["state"], r["soc"],
         r["current_a"], r["power_w"], r["voltage_v"], r["temp_c"], r["mosfet_temp_c"],
         r["soh"], r["full_charge_ah"], r["remaining_ah"], r["cycles"], r["cell_min_v"],
         r["cell_max_v"], r["cells"], r["regen"], r["link_event"],
         r["lat"], r["lon"], r["gps_accuracy_m"], r["eta_full_min"])
        for r in rows
    ])
```

Add `s.eta_full_min` to the `fleet_snapshot` SELECT (line 97–108), in the GPS-columns line:

```python
              s.lat, s.lon, s.gps_accuracy_m, s.eta_full_min, s.received_at,
```

(`sample_row` copies `_COLS` from the JSON automatically, so no change needed there.)

- [ ] **Step 4: Run test to verify it passes**

Run: `cd server && .venv/bin/python -m pytest tests/test_eta_full_min.py -v`
Expected: PASS. Then full suite: `cd server && .venv/bin/python -m pytest` — no regressions.

> The dev Postgres must be up: `docker compose -f server/docker-compose.dev.yml up -d`. The schema re-runs on pool creation, applying the new column.

- [ ] **Step 5: Commit**

```bash
git add server/app/db/schema.sql server/app/models.py server/app/db/queries.py \
        server/tests/test_eta_full_min.py
git commit -m "feat(server): store and return eta_full_min telemetry field"
```

---

### Task 9: WebUI — render the estimate on the main stage

**Files:**
- Modify: `web/src/types.ts` (`Sample` interface, line 1–7)
- Modify: `web/src/util.ts` (add `fmtEta`)
- Modify: `web/src/components/MainStage.tsx` (readout block, ~line 90–96)
- Test: `web/src/util.test.ts` (create)

**Interfaces:**
- Consumes: `FleetItem.eta_full_min` from `/web/fleet` + `/ws` (Task 8).
- Produces: `fmtEta(minutes: number): string`; a "~2h 14m to full" line under the stage readout when charging.

- [ ] **Step 1: Write the failing test**

Create `web/src/util.test.ts`:

```typescript
import { describe, expect, it } from "vitest";
import { fmtEta } from "./util";

describe("fmtEta", () => {
  it("formats hours and minutes", () => {
    expect(fmtEta(134)).toBe("2h 14m");
    expect(fmtEta(45)).toBe("45m");
    expect(fmtEta(-3)).toBe("0m");
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd web && npm test -- util.test.ts`
Expected: FAIL — `fmtEta` is not exported.

- [ ] **Step 3: Implement `fmtEta` and the type + render**

Add to `web/src/util.ts`:

```typescript
/** Compact "2h 14m" / "45m" for a minutes estimate (clamped at 0). */
export function fmtEta(minutes: number): string {
  const t = Math.max(0, Math.round(minutes));
  const h = Math.floor(t / 60);
  const m = t % 60;
  return h > 0 ? `${h}h ${m}m` : `${m}m`;
}
```

Add the field to the `Sample` interface in `web/src/types.ts` (inside the interface body, line 1–7):

```typescript
  eta_full_min?: number | null;
```

In `web/src/components/MainStage.tsx`, import the helper (with the other imports at the top):

```typescript
import { fmtEta, relAgo } from "../util";
```

(If `relAgo` is already imported from `../util`, just add `fmtEta` to that existing import.)

In the connected branch of the readout block (the `connected ? (...) : (...)` at ~line 90–96), replace the single connected `<span>` with a fragment that also shows the ETA when present:

```tsx
        {connected ? (
          <>
            <span className="mono" style={{ color: flowColor, fontSize: 18 }}>
              {`${(it.power_w ?? 0).toFixed(0)} W · ${cur.toFixed(1)} A`}
            </span>
            {it.eta_full_min != null && (
              <div className="mono" style={{ color: "var(--text2)", fontSize: 12, marginTop: 2 }}>
                ~{fmtEta(it.eta_full_min)} to full
              </div>
            )}
          </>
        ) : (
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd web && npm test`
Expected: PASS (existing + `fmtEta` tests).

- [ ] **Step 5: Verify the build**

Run: `cd web && npm run build`
Expected: build succeeds (tsc + vite), no type errors.

- [ ] **Step 6: Commit**

```bash
git add web/src/types.ts web/src/util.ts web/src/util.test.ts \
        web/src/components/MainStage.tsx
git commit -m "feat(web): show time-to-full on the main stage while charging"
```

---

### Task 10: Full-suite verification

**Files:** none (verification only).

- [ ] **Step 1: Android unit tests**

Run: `cd android && ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL — `ChargeEstimateTest` + `ChargeTailLearnTest` + existing tests pass.

- [ ] **Step 2: Server tests**

Run: `cd server && .venv/bin/python -m pytest`
Expected: all pass (dev Postgres up).

- [ ] **Step 3: Web tests + build**

Run: `cd web && npm test && npm run build`
Expected: all pass; production bundle builds.

- [ ] **Step 4 (deploy — only when the user asks):** After the server image build finishes on `main`, pull + recreate the API container per `bmsmon/CLAUDE.md` (the QNAP deploy block), then `curl -fsS https://bmsmon.covert.life/api/v1/health`. The additive `eta_full_min` column applies on container start.

---

## Self-Review

**Spec coverage:**
- Two-part estimate (physics bulk + learned tail, target 100%) → Task 1. ✓
- On-device learning loop from completed full charges → Tasks 2–4. ✓
- Persistence of the learned per-pack constant → Task 3. ✓
- Seed prior 45 min until learned → Task 1 (`SEED_TAIL_MIN`), applied in Tasks 4 & 6. ✓
- Gating (charging only, not regen/idle/discharge, hidden otherwise) → `estimateChargeMinutes` returns null; UI renders only when non-null (Tasks 7, 9). ✓
- ETA computed on phone, uploaded, WebUI mirrors read-only → Tasks 5 (upload), 8 (server store/return), 9 (web render). ✓
- Android + WebUI display → Tasks 7, 9. ✓
- No new BMS commands / additive-only schema → Global Constraints, honored throughout. ✓

**Type consistency:** `estimateChargeMinutes` signature identical in Tasks 1, 4, 6. `tailMinByAddress: Map<String, Float>` consistent across `MonitorState` (Task 4), `UiState` (Task 6). `eta_full_min` (snake) server/JSON/TS; `etaFullMin` (camel) Kotlin — consistent within each language. `report(..., etaFullMin)` defined in Task 5, called in Task 4 (dependency note included).

**Placeholder scan:** none — every step carries real code/commands.

**Scope:** single cohesive feature threaded through one field; ordered so each task compiles and is independently reviewable (Task 4/5 co-compile note included).
