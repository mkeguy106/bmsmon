# Telemetry Database & Battery Graphs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the CSV telemetry log with a Room database (full-resolution samples + permanent per-session rollups + capped raw-frame debug log), import the existing CSV once, and add in-app battery graphs (peak power, internal-resistance/sag, capacity & SOH) on a per-battery History section plus a fleet History screen.

**Architecture:** A Room database holds three tables. All decision logic (session segmentation, rollup math, internal-resistance estimation, CSV parsing, retention cutoffs) lives in pure Kotlin functions that operate on the plain Room entity data classes and are unit-tested on the JVM. A `TelemetryRepository` orchestrates Room DAOs + the pure logic and replaces `TelemetryLogger`; it serializes writes through an internal channel so the BLE poll loop never blocks. Charts are hand-rolled Compose `Canvas`, themed with the existing `Bm` color tokens, with a session-index x-axis so data gaps collapse.

**Tech Stack:** Kotlin 1.9.22, AGP 8.2.2, Jetpack Compose (BOM 2024.02.02), Room 2.6.1, KSP 1.9.22-1.0.17, Coroutines/Flow, JUnit4 (JVM unit tests under `app/src/test`).

## Global Constraints

- **No BLE protocol changes.** Read-only commands only (0x13 etc.); never send write/shutdown opcodes. The destructive-command safety rules in `CLAUDE.md` are absolute.
- **minSdk 26, targetSdk 34, compileSdk 34.** Room 2.6.1 and KSP 1.9.22-1.0.17 are compatible with Kotlin 1.9.22.
- **Straight cutover:** the CSV writer (`TelemetryLogger`) is removed; no parallel CSV logging.
- **Pure logic operates on Room entity data classes directly** (Room annotations are inert in JVM unit tests). Keep DB orchestration (DAO calls, channel) as thin glue; put all branching/math in pure, unit-tested functions.
- **Writes must never block the BLE poll loop** — repository write methods are non-suspend, fire-and-forget onto an internal IO scope.
- **Current sign convention:** `current < 0` = discharge, `> 0` = charge. `powerW` is an unsigned magnitude (`voltage * |current|`).
- **Commit after every task** (each task ends green: `./gradlew :app:testDebugUnitTest` passes and `./gradlew :app:assembleDebug` builds).
- **Run a single test class** with: `./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.ClassName"`.
- **Commit messages:** no "Generated with Claude Code", no "Co-Authored-By: Claude", no AI references (per `CLAUDE.md`).

---

## Phase 1 — Database backbone & cutover

### Task 1: Add Room + KSP to the build

**Files:**
- Modify: `android/build.gradle.kts` (root — add KSP plugin to the `plugins` block)
- Modify: `android/app/build.gradle.kts` (apply KSP, add Room deps)

**Interfaces:**
- Produces: Room (`androidx.room:room-runtime`, `room-ktx`) and the `ksp` compiler available to all later tasks.

- [ ] **Step 1: Add the KSP plugin to the root build file**

In `android/build.gradle.kts`, add the KSP line to the existing `plugins` block:

```kotlin
plugins {
    id("com.android.application") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("com.google.devtools.ksp") version "1.9.22-1.0.17" apply false
}
```

- [ ] **Step 2: Apply KSP and add Room deps in the app build file**

In `android/app/build.gradle.kts`, add to the `plugins` block:

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}
```

Add to the `dependencies` block (near the other `implementation` lines):

```kotlin
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
```

- [ ] **Step 3: Verify the build resolves the new dependencies**

Run: `cd android && ./gradlew :app:assembleDebug -q`
Expected: BUILD SUCCESSFUL (a benign SDK XML version warning is fine).

- [ ] **Step 4: Commit**

```bash
git add android/build.gradle.kts android/app/build.gradle.kts
git commit -m "Add Room and KSP to the Android build"
```

---

### Task 2: Room entities, DAOs, and database

**Files:**
- Create: `android/app/src/main/java/dev/joely/bmsmon/data/db/SampleEntity.kt`
- Create: `android/app/src/main/java/dev/joely/bmsmon/data/db/SessionEntity.kt`
- Create: `android/app/src/main/java/dev/joely/bmsmon/data/db/RawFrameEntity.kt`
- Create: `android/app/src/main/java/dev/joely/bmsmon/data/db/Daos.kt`
- Create: `android/app/src/main/java/dev/joely/bmsmon/data/db/BmsDatabase.kt`

**Interfaces:**
- Produces (entities consumed by all later tasks — exact fields):
  - `SampleEntity(id: Long = 0, address: String, tsMs: Long, sessionId: Long, state: String?, soc: Float?, currentA: Float?, powerW: Float?, voltageV: Float?, tempC: Float?, mosfetTempC: Int?, soh: Int?, fullChargeAh: Float?, remainingAh: Float?, cycles: Int?, cellMinV: Float?, cellMaxV: Float?, regen: Boolean, linkEvent: String?)`
  - `SessionEntity(id: Long = 0, address: String, startMs: Long, endMs: Long, sampleCount: Int, peakPowerW: Float, p95PowerW: Float, meanPowerW: Float, peakCurrentA: Float, peakRegenW: Float, energyWh: Float, socStart: Float, socEnd: Float, minSoc: Float, maxSoc: Float, minVoltageUnderLoad: Float, estInternalResistanceMohm: Float?, irConfidence: Float, sohEnd: Int, fullChargeAhEnd: Float, cyclesEnd: Int, maxTempC: Float)`
  - `RawFrameEntity(id: Long = 0, address: String, tsMs: Long, hex: String, reason: String)`
- Produces (DAOs): `SampleDao`, `SessionDao`, `RawFrameDao`; `BmsDatabase` with `samples()/sessions()/rawFrames()` accessors and a `BmsDatabase.create(context)` builder.

- [ ] **Step 1: Create `SampleEntity.kt`**

```kotlin
package dev.joely.bmsmon.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One poll's worth of telemetry for one pack (full resolution). Telemetry columns are null for
 * link-event rows ([linkEvent] = "Connected"/"Disconnected"). Pruned after the retention window.
 */
@Entity(
    tableName = "samples",
    indices = [Index(value = ["address", "tsMs"]), Index(value = ["sessionId"])],
)
data class SampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val address: String,
    val tsMs: Long,
    val sessionId: Long,
    val state: String?,
    val soc: Float?,
    val currentA: Float?,
    val powerW: Float?,
    val voltageV: Float?,
    val tempC: Float?,
    val mosfetTempC: Int?,
    val soh: Int?,
    val fullChargeAh: Float?,
    val remainingAh: Float?,
    val cycles: Int?,
    val cellMinV: Float?,
    val cellMaxV: Float?,
    val regen: Boolean,
    val linkEvent: String?,
)
```

- [ ] **Step 2: Create `SessionEntity.kt`**

```kotlin
package dev.joely.bmsmon.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One monitoring session for one pack (a continuous run; a gap > SESSION_GAP_MS or a disconnect
 * starts a new one). Holds the rollups that power the aging graphs. Kept forever.
 */
@Entity(tableName = "sessions", indices = [Index(value = ["address", "startMs"])])
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val address: String,
    val startMs: Long,
    val endMs: Long,
    val sampleCount: Int,
    val peakPowerW: Float,
    val p95PowerW: Float,
    val meanPowerW: Float,
    val peakCurrentA: Float,
    val peakRegenW: Float,
    val energyWh: Float,
    val socStart: Float,
    val socEnd: Float,
    val minSoc: Float,
    val maxSoc: Float,
    val minVoltageUnderLoad: Float,
    val estInternalResistanceMohm: Float?,
    val irConfidence: Float,
    val sohEnd: Int,
    val fullChargeAhEnd: Float,
    val cyclesEnd: Int,
    val maxTempC: Float,
)
```

- [ ] **Step 3: Create `RawFrameEntity.kt`**

```kotlin
package dev.joely.bmsmon.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A raw BLE response frame (hex) kept for debugging. [reason]: periodic / realign / decode_fail. */
@Entity(tableName = "raw_frames", indices = [Index(value = ["tsMs"])])
data class RawFrameEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val address: String,
    val tsMs: Long,
    val hex: String,
    val reason: String,
)
```

- [ ] **Step 4: Create `Daos.kt`**

```kotlin
package dev.joely.bmsmon.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SampleDao {
    @Insert suspend fun insert(sample: SampleEntity): Long
    @Insert suspend fun insertAll(samples: List<SampleEntity>)

    @Query("SELECT * FROM samples WHERE sessionId = :sessionId ORDER BY tsMs ASC")
    suspend fun forSession(sessionId: Long): List<SampleEntity>

    @Query("SELECT * FROM samples WHERE address = :address AND linkEvent IS NULL ORDER BY tsMs ASC")
    suspend fun telemetryFor(address: String): List<SampleEntity>

    @Query("DELETE FROM samples WHERE tsMs < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long): Int

    @Query("SELECT COUNT(*) FROM samples") fun count(): Long

    @Query("DELETE FROM samples") suspend fun clear()
}

@Dao
interface SessionDao {
    @Insert suspend fun insert(session: SessionEntity): Long
    @Update suspend fun update(session: SessionEntity)

    @Query("SELECT * FROM sessions WHERE address = :address ORDER BY startMs ASC")
    fun forAddress(address: String): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions ORDER BY startMs ASC")
    fun all(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id") suspend fun byId(id: Long): SessionEntity?

    @Query("DELETE FROM sessions") suspend fun clear()
}

@Dao
interface RawFrameDao {
    @Insert suspend fun insert(frame: RawFrameEntity)

    @Query("DELETE FROM raw_frames WHERE tsMs < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long): Int

    @Query("SELECT COALESCE(SUM(LENGTH(hex)), 0) FROM raw_frames")
    suspend fun totalHexBytes(): Long

    @Query("DELETE FROM raw_frames WHERE id IN (SELECT id FROM raw_frames ORDER BY tsMs ASC LIMIT :n)")
    suspend fun deleteOldest(n: Int): Int

    @Query("DELETE FROM raw_frames") suspend fun clear()
}
```

- [ ] **Step 5: Create `BmsDatabase.kt`**

```kotlin
package dev.joely.bmsmon.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [SampleEntity::class, SessionEntity::class, RawFrameEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class BmsDatabase : RoomDatabase() {
    abstract fun samples(): SampleDao
    abstract fun sessions(): SessionDao
    abstract fun rawFrames(): RawFrameDao

    companion object {
        fun create(context: Context): BmsDatabase =
            Room.databaseBuilder(context, BmsDatabase::class.java, "bms.db").build()
    }
}
```

- [ ] **Step 6: Verify it compiles (Room codegen runs)**

Run: `cd android && ./gradlew :app:assembleDebug -q`
Expected: BUILD SUCCESSFUL (Room generates the `_Impl` classes).

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/java/dev/joely/bmsmon/data/db/
git commit -m "Add Room entities, DAOs, and database for telemetry storage"
```

---

### Task 3: Session segmentation (pure logic)

**Files:**
- Create: `android/app/src/main/java/dev/joely/bmsmon/data/Sessions.kt`
- Test: `android/app/src/test/java/dev/joely/bmsmon/SessionSegmenterTest.kt`

**Interfaces:**
- Produces: `const val SESSION_GAP_MS = 10 * 60 * 1000L`; `fun isNewSession(prevSampleTsMs: Long?, prevWasDisconnect: Boolean, nowMs: Long, gapMs: Long = SESSION_GAP_MS): Boolean`

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.joely.bmsmon

import dev.joely.bmsmon.data.SESSION_GAP_MS
import dev.joely.bmsmon.data.isNewSession
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSegmenterTest {
    @Test fun firstSampleEverStartsSession() {
        assertTrue(isNewSession(prevSampleTsMs = null, prevWasDisconnect = false, nowMs = 1_000))
    }

    @Test fun backToBackSamplesStayInSession() {
        assertFalse(isNewSession(prevSampleTsMs = 1_000, prevWasDisconnect = false, nowMs = 3_000))
    }

    @Test fun gapBeyondThresholdStartsNewSession() {
        val now = 1_000 + SESSION_GAP_MS + 1
        assertTrue(isNewSession(prevSampleTsMs = 1_000, prevWasDisconnect = false, nowMs = now))
    }

    @Test fun gapExactlyAtThresholdStaysInSession() {
        val now = 1_000 + SESSION_GAP_MS
        assertFalse(isNewSession(prevSampleTsMs = 1_000, prevWasDisconnect = false, nowMs = now))
    }

    @Test fun disconnectSinceLastSampleStartsNewSession() {
        assertTrue(isNewSession(prevSampleTsMs = 1_000, prevWasDisconnect = true, nowMs = 2_000))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.SessionSegmenterTest"`
Expected: FAIL — unresolved reference `isNewSession` / `SESSION_GAP_MS`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package dev.joely.bmsmon.data

/** A gap larger than this (or a disconnect) between samples for one pack starts a new session. */
const val SESSION_GAP_MS = 10 * 60 * 1000L

/**
 * True when the incoming sample at [nowMs] should open a NEW session for a pack, given the pack's
 * previous sample time ([prevSampleTsMs], null if none) and whether the pack disconnected since
 * that sample ([prevWasDisconnect]). A gap strictly greater than [gapMs], a disconnect, or no
 * prior sample all start a new session.
 */
fun isNewSession(
    prevSampleTsMs: Long?,
    prevWasDisconnect: Boolean,
    nowMs: Long,
    gapMs: Long = SESSION_GAP_MS,
): Boolean {
    if (prevSampleTsMs == null) return true
    if (prevWasDisconnect) return true
    return (nowMs - prevSampleTsMs) > gapMs
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.SessionSegmenterTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/dev/joely/bmsmon/data/Sessions.kt android/app/src/test/java/dev/joely/bmsmon/SessionSegmenterTest.kt
git commit -m "Add session segmentation logic"
```

---

### Task 4: Session rollup computation (pure logic)

**Files:**
- Modify: `android/app/src/main/java/dev/joely/bmsmon/data/Sessions.kt`
- Test: `android/app/src/test/java/dev/joely/bmsmon/RollupTest.kt`

**Interfaces:**
- Consumes: `SampleEntity` (Task 2).
- Produces: `fun computeRollup(address: String, sessionId: Long, samples: List<SampleEntity>): SessionEntity`. Uses only telemetry rows (`linkEvent == null`). `current < -DISCHARGE_EPS` (`DISCHARGE_EPS = 0.05f`) defines discharge. Returns a `SessionEntity` with `id = sessionId`.

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.joely.bmsmon

import dev.joely.bmsmon.data.computeRollup
import dev.joely.bmsmon.data.db.SampleEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class RollupTest {
    private fun sample(ts: Long, soc: Float, cur: Float, pw: Float, v: Float, regen: Boolean = false) =
        SampleEntity(
            address = "A", tsMs = ts, sessionId = 7, state = if (cur < 0) "Discharging" else "Charging",
            soc = soc, currentA = cur, powerW = pw, voltageV = v, tempC = 25f, mosfetTempC = 26,
            soh = 99, fullChargeAh = 98.5f, remainingAh = 50f, cycles = 12,
            cellMinV = v / 4f, cellMaxV = v / 4f, regen = regen, linkEvent = null,
        )

    @Test fun rollupAggregatesDischargeSamples() {
        // Three discharge samples 1s apart: powers 100, 300, 200 W; currents -10,-30,-20 A.
        val samples = listOf(
            sample(0, 90f, -10f, 100f, 13.2f),
            sample(1000, 89f, -30f, 300f, 13.0f),
            sample(2000, 88f, -20f, 200f, 13.1f),
        )
        val r = computeRollup("A", 7, samples)
        assertEquals(7, r.id)
        assertEquals("A", r.address)
        assertEquals(3, r.sampleCount)
        assertEquals(300f, r.peakPowerW, 0.01f)
        assertEquals(30f, r.peakCurrentA, 0.01f)
        assertEquals(200f, r.meanPowerW, 0.01f)
        assertEquals(90f, r.socStart, 0.01f)
        assertEquals(88f, r.socEnd, 0.01f)
        assertEquals(13.0f, r.minVoltageUnderLoad, 0.01f)
        assertEquals(99, r.sohEnd)
        assertEquals(12, r.cyclesEnd)
        // energy: sample[0] 100W for 1s + sample[1] 300W for 1s = (100+300)/3600 Wh
        assertEquals((100f + 300f) / 3600f, r.energyWh, 0.001f)
    }

    @Test fun regenPeakFromRegenSamplesOnly() {
        val samples = listOf(
            sample(0, 88f, -10f, 100f, 13.1f),
            sample(1000, 88f, 20f, 250f, 13.3f, regen = true),
        )
        val r = computeRollup("A", 7, samples)
        assertEquals(250f, r.peakRegenW, 0.01f)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.RollupTest"`
Expected: FAIL — unresolved reference `computeRollup`.

- [ ] **Step 3: Write minimal implementation**

Append to `data/Sessions.kt`:

```kotlin
import dev.joely.bmsmon.data.db.SampleEntity
import dev.joely.bmsmon.data.db.SessionEntity

/** A current more negative than this (A) counts as discharge. */
const val DISCHARGE_EPS = 0.05f

/**
 * Compute a [SessionEntity] (rollups) from a session's [samples]. Link-event rows are ignored.
 * Discharge stats use samples with `currentA < -DISCHARGE_EPS`. Energy integrates each interval's
 * leading-sample power over its duration (gaps are already bounded by session segmentation).
 */
fun computeRollup(address: String, sessionId: Long, samples: List<SampleEntity>): SessionEntity {
    val tel = samples.filter { it.linkEvent == null }
    val startMs = tel.firstOrNull()?.tsMs ?: 0L
    val endMs = tel.lastOrNull()?.tsMs ?: startMs
    val socs = tel.mapNotNull { it.soc }
    val discharge = tel.filter { (it.currentA ?: 0f) < -DISCHARGE_EPS }
    val dischargePowers = discharge.mapNotNull { it.powerW }.sorted()

    val peakPowerW = dischargePowers.lastOrNull() ?: 0f
    val p95PowerW = if (dischargePowers.isEmpty()) 0f
        else dischargePowers[((dischargePowers.size - 1) * 0.95f).toInt()]
    val meanPowerW = if (dischargePowers.isEmpty()) 0f else dischargePowers.average().toFloat()
    val peakCurrentA = discharge.mapNotNull { it.currentA }.minOrNull()?.let { -it } ?: 0f
    val peakRegenW = tel.filter { it.regen }.mapNotNull { it.powerW }.maxOrNull() ?: 0f
    val minVUnderLoad = discharge.mapNotNull { it.voltageV }.minOrNull() ?: 0f

    var energyWh = 0f
    for (i in 0 until tel.size - 1) {
        val a = tel[i]
        val dtH = (tel[i + 1].tsMs - a.tsMs).coerceAtLeast(0) / 3_600_000f
        if ((a.currentA ?: 0f) < -DISCHARGE_EPS) energyWh += (a.powerW ?: 0f) * dtH
    }

    val ir = estimateInternalResistanceMohm(discharge)

    return SessionEntity(
        id = sessionId,
        address = address,
        startMs = startMs,
        endMs = endMs,
        sampleCount = tel.size,
        peakPowerW = peakPowerW,
        p95PowerW = p95PowerW,
        meanPowerW = meanPowerW,
        peakCurrentA = peakCurrentA,
        peakRegenW = peakRegenW,
        energyWh = energyWh,
        socStart = socs.firstOrNull() ?: 0f,
        socEnd = socs.lastOrNull() ?: 0f,
        minSoc = socs.minOrNull() ?: 0f,
        maxSoc = socs.maxOrNull() ?: 0f,
        minVoltageUnderLoad = minVUnderLoad,
        estInternalResistanceMohm = ir?.mohm,
        irConfidence = ir?.confidence ?: 0f,
        sohEnd = tel.mapNotNull { it.soh }.lastOrNull() ?: 0,
        fullChargeAhEnd = tel.mapNotNull { it.fullChargeAh }.lastOrNull() ?: 0f,
        cyclesEnd = tel.mapNotNull { it.cycles }.lastOrNull() ?: 0,
        maxTempC = tel.mapNotNull { it.tempC }.maxOrNull() ?: 0f,
    )
}
```

> Note: `estimateInternalResistanceMohm` is implemented in Task 5. To keep this task green on its own, add the temporary stub below to `data/Sessions.kt` now; Task 5 replaces it with the real version + tests.

```kotlin
data class IrEstimate(val mohm: Float, val confidence: Float)
fun estimateInternalResistanceMohm(dischargeSamples: List<SampleEntity>): IrEstimate? = null
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.RollupTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/dev/joely/bmsmon/data/Sessions.kt android/app/src/test/java/dev/joely/bmsmon/RollupTest.kt
git commit -m "Add session rollup computation"
```

---

### Task 5: Internal-resistance estimation (pure logic)

**Files:**
- Modify: `android/app/src/main/java/dev/joely/bmsmon/data/Sessions.kt` (replace the Task 4 stub)
- Test: `android/app/src/test/java/dev/joely/bmsmon/InternalResistanceTest.kt`

**Interfaces:**
- Consumes: `SampleEntity`.
- Produces: `const val IR_MIN_CURRENT_SPREAD_A = 8f`; `data class IrEstimate(val mohm: Float, val confidence: Float)`; `fun estimateInternalResistanceMohm(dischargeSamples: List<SampleEntity>): IrEstimate?` — linear regression of `voltageV` against `currentA`; resistance (Ω) = slope, reported in mΩ. Returns `null` when the current spread is below `IR_MIN_CURRENT_SPREAD_A` (low confidence).

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.joely.bmsmon

import dev.joely.bmsmon.data.estimateInternalResistanceMohm
import dev.joely.bmsmon.data.db.SampleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InternalResistanceTest {
    private fun s(cur: Float, v: Float) = SampleEntity(
        address = "A", tsMs = 0, sessionId = 1, state = "Discharging", soc = 80f,
        currentA = cur, powerW = v * -cur, voltageV = v, tempC = 25f, mosfetTempC = 26,
        soh = 100, fullChargeAh = 100f, remainingAh = 80f, cycles = 1,
        cellMinV = v / 4f, cellMaxV = v / 4f, regen = false, linkEvent = null,
    )

    @Test fun recoversKnownResistance() {
        // V = 13.2 + I * 0.02  (R = 20 mOhm); I negative under load.
        val samples = listOf(s(-5f, 13.1f), s(-20f, 12.8f), s(-50f, 12.2f))
        val est = estimateInternalResistanceMohm(samples)!!
        assertEquals(20f, est.mohm, 0.5f)
        assertTrue(est.confidence > 0f)
    }

    @Test fun lowCurrentSpreadReturnsNull() {
        // All currents within < 8 A of each other → not enough spread to trust.
        val samples = listOf(s(-10f, 13.0f), s(-12f, 12.99f), s(-14f, 12.98f))
        assertNull(estimateInternalResistanceMohm(samples))
    }

    @Test fun emptyReturnsNull() {
        assertNull(estimateInternalResistanceMohm(emptyList()))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.InternalResistanceTest"`
Expected: FAIL — the stub returns null, so `recoversKnownResistance` fails on `est!!`.

- [ ] **Step 3: Replace the stub with the real implementation**

In `data/Sessions.kt`, delete the stub `estimateInternalResistanceMohm` and the stub `IrEstimate`, and add:

```kotlin
/** Minimum discharge-current spread (A) within a session to trust the resistance estimate. */
const val IR_MIN_CURRENT_SPREAD_A = 8f

data class IrEstimate(val mohm: Float, val confidence: Float)

/**
 * Estimate effective internal resistance from discharge samples by regressing voltage on current.
 * With the discharge-negative sign convention, V ≈ Voc + I·R, so the slope dV/dI is the resistance
 * in ohms (reported here in mΩ). Returns null when the current spread is too small to be reliable.
 * [confidence] scales with spread (1.0 at >= 4× the minimum spread).
 */
fun estimateInternalResistanceMohm(dischargeSamples: List<SampleEntity>): IrEstimate? {
    val pts = dischargeSamples.mapNotNull { sample ->
        val i = sample.currentA ?: return@mapNotNull null
        val v = sample.voltageV ?: return@mapNotNull null
        i to v
    }
    if (pts.size < 2) return null
    val currents = pts.map { it.first }
    val spread = (currents.maxOrNull()!! - currents.minOrNull()!!)
    if (spread < IR_MIN_CURRENT_SPREAD_A) return null

    val n = pts.size
    val meanI = currents.average()
    val meanV = pts.map { it.second }.average()
    var cov = 0.0
    var varI = 0.0
    for ((i, v) in pts) {
        cov += (i - meanI) * (v - meanV)
        varI += (i - meanI) * (i - meanI)
    }
    if (varI == 0.0) return null
    val slopeOhm = cov / varI            // dV/dI = R (ohms)
    val mohm = (slopeOhm * 1000.0).toFloat()
    val confidence = (spread / (IR_MIN_CURRENT_SPREAD_A * 4f)).coerceIn(0f, 1f)
    return IrEstimate(mohm = mohm, confidence = confidence)
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.InternalResistanceTest" --tests "dev.joely.bmsmon.RollupTest"`
Expected: PASS (both classes — Rollup still green now that IR is real).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/dev/joely/bmsmon/data/Sessions.kt android/app/src/test/java/dev/joely/bmsmon/InternalResistanceTest.kt
git commit -m "Add internal-resistance estimation from voltage/current regression"
```

---

### Task 6: CSV line parser (pure logic)

**Files:**
- Create: `android/app/src/main/java/dev/joely/bmsmon/data/CsvImport.kt`
- Test: `android/app/src/test/java/dev/joely/bmsmon/CsvImportTest.kt`

**Interfaces:**
- Consumes: `SampleEntity`.
- Produces: `fun parseCsvLine(line: String): SampleEntity?` — maps the legacy CSV columns `timestamp_ms,name,address,state,soc,current_a,power_w,voltage_v,regen` to a `SampleEntity` (`sessionId = 0`, absent fields null). Returns null for the header row, blank lines, and malformed rows. Rows whose `state` is `Connected`/`Disconnected` become link-event rows (telemetry null, `linkEvent` set).

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.joely.bmsmon

import dev.joely.bmsmon.data.parseCsvLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CsvImportTest {
    @Test fun parsesTelemetryRow() {
        val row = "1782605607426,2016 · B,C8:47:80:15:25:9A,Charging,64,7.948,107.29,13.499,0"
        val s = parseCsvLine(row)!!
        assertEquals("C8:47:80:15:25:9A", s.address)
        assertEquals(1782605607426L, s.tsMs)
        assertEquals("Charging", s.state)
        assertEquals(64f, s.soc!!, 0.01f)
        assertEquals(7.948f, s.currentA!!, 0.001f)
        assertEquals(107.29f, s.powerW!!, 0.01f)
        assertEquals(13.499f, s.voltageV!!, 0.001f)
        assertEquals(false, s.regen)
        assertNull(s.linkEvent)
        assertNull(s.soh)        // not present in the CSV
    }

    @Test fun parsesRegenFlag() {
        val row = "1782663972080,2012 · A,C8:47:80:15:67:44,Discharging,87,-22.324,297.29,13.317,1"
        assertEquals(true, parseCsvLine(row)!!.regen)
    }

    @Test fun parsesLinkEventRow() {
        val row = "1782687206267,2023 · B,C8:47:80:45:90:FB,Disconnected,,,,,0"
        val s = parseCsvLine(row)!!
        assertEquals("Disconnected", s.linkEvent)
        assertNull(s.soc)
        assertNull(s.currentA)
    }

    @Test fun skipsHeaderAndJunk() {
        assertNull(parseCsvLine("timestamp_ms,name,address,state,soc,current_a,power_w,voltage_v,regen"))
        assertNull(parseCsvLine(""))
        assertNull(parseCsvLine("garbage,row"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.CsvImportTest"`
Expected: FAIL — unresolved reference `parseCsvLine`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package dev.joely.bmsmon.data

import dev.joely.bmsmon.data.db.SampleEntity

private val LINK_STATES = setOf("Connected", "Disconnected")

/**
 * Parse one line of the legacy usage_log.csv into a [SampleEntity] (sessionId = 0, filled in by the
 * importer). Columns: timestamp_ms,name,address,state,soc,current_a,power_w,voltage_v,regen.
 * Returns null for the header, blank lines, and malformed rows. Connected/Disconnected rows become
 * link-event rows with telemetry columns null.
 */
fun parseCsvLine(line: String): SampleEntity? {
    if (line.isBlank()) return null
    val f = line.split(",")
    if (f.size < 9) return null
    val ts = f[0].toLongOrNull() ?: return null      // header's "timestamp_ms" → null → skip
    val address = f[2].trim().uppercase()
    if (address.isEmpty()) return null
    val state = f[3].trim()
    val isLink = state in LINK_STATES
    val regen = f[8].trim() == "1"
    return SampleEntity(
        address = address,
        tsMs = ts,
        sessionId = 0,
        state = if (isLink) null else state.ifEmpty { null },
        soc = if (isLink) null else f[4].toFloatOrNull(),
        currentA = if (isLink) null else f[5].toFloatOrNull(),
        powerW = if (isLink) null else f[6].toFloatOrNull(),
        voltageV = if (isLink) null else f[7].toFloatOrNull(),
        tempC = null, mosfetTempC = null, soh = null, fullChargeAh = null,
        remainingAh = null, cycles = null, cellMinV = null, cellMaxV = null,
        regen = regen,
        linkEvent = if (isLink) state else null,
    )
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.CsvImportTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/dev/joely/bmsmon/data/CsvImport.kt android/app/src/test/java/dev/joely/bmsmon/CsvImportTest.kt
git commit -m "Add legacy CSV line parser for one-time import"
```

---

### Task 7: Retention cutoffs (pure logic)

**Files:**
- Create: `android/app/src/main/java/dev/joely/bmsmon/data/Retention.kt`
- Test: `android/app/src/test/java/dev/joely/bmsmon/RetentionTest.kt`

**Interfaces:**
- Produces: `const val SAMPLE_RETENTION_DAYS = 14`; `const val RAW_FRAME_RETENTION_DAYS = 7`; `const val RAW_FRAME_MAX_BYTES = 20L * 1024 * 1024`; `fun cutoffMs(nowMs: Long, days: Int): Long`.

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.joely.bmsmon

import dev.joely.bmsmon.data.cutoffMs
import org.junit.Assert.assertEquals
import org.junit.Test

class RetentionTest {
    @Test fun cutoffSubtractsDays() {
        val now = 1_000_000_000_000L
        assertEquals(now - 14L * 86_400_000L, cutoffMs(now, 14))
        assertEquals(now - 7L * 86_400_000L, cutoffMs(now, 7))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.RetentionTest"`
Expected: FAIL — unresolved reference `cutoffMs`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package dev.joely.bmsmon.data

/** Full-resolution samples older than this many days are pruned. */
const val SAMPLE_RETENTION_DAYS = 14

/** Raw debug frames older than this many days are pruned. */
const val RAW_FRAME_RETENTION_DAYS = 7

/** Raw-frame table is also capped by total hex size (whichever limit hits first). */
const val RAW_FRAME_MAX_BYTES = 20L * 1024 * 1024

/** Timestamp [days] before [nowMs]. */
fun cutoffMs(nowMs: Long, days: Int): Long = nowMs - days * 86_400_000L
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.RetentionTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/dev/joely/bmsmon/data/Retention.kt android/app/src/test/java/dev/joely/bmsmon/RetentionTest.kt
git commit -m "Add retention cutoff constants and helper"
```

---

### Task 8: Frame-reason classification (expose protocol offset)

**Files:**
- Modify: `android/app/src/main/java/dev/joely/bmsmon/ble/BmsProtocol.kt` (expose offset)
- Create: `android/app/src/main/java/dev/joely/bmsmon/data/FrameReason.kt`
- Test: `android/app/src/test/java/dev/joely/bmsmon/FrameReasonTest.kt`

**Interfaces:**
- Consumes: `BmsProtocol.parseTelemetry` (existing), `Telemetry`.
- Produces: `fun BmsProtocol.statusFrameOffset(raw: ByteArray): Int?` (public); `object FrameReason { const val PERIODIC = "periodic"; const val REALIGN = "realign"; const val DECODE_FAIL = "decode_fail" }`; `fun classifyFrame(raw: ByteArray, parsedOk: Boolean): String`.

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.joely.bmsmon

import dev.joely.bmsmon.data.FrameReason
import dev.joely.bmsmon.data.classifyFrame
import org.junit.Assert.assertEquals
import org.junit.Test

class FrameReasonTest {
    // A frame whose status header (01 93 55 AA) sits at offset 3 → aligned (00 00 LEN at 0..2).
    private fun aligned() = ByteArray(110).also {
        it[3] = 0x01; it[4] = 0x93.toByte(); it[5] = 0x55; it[6] = 0xAA.toByte()
    }
    // Two stale bytes prepended → header now at offset 5 → realigned.
    private fun misaligned() = ByteArray(110).also {
        it[5] = 0x01; it[6] = 0x93.toByte(); it[7] = 0x55; it[8] = 0xAA.toByte()
    }

    @Test fun alignedSuccessIsPeriodic() {
        assertEquals(FrameReason.PERIODIC, classifyFrame(aligned(), parsedOk = true))
    }

    @Test fun misalignedSuccessIsRealign() {
        assertEquals(FrameReason.REALIGN, classifyFrame(misaligned(), parsedOk = true))
    }

    @Test fun parseFailureIsDecodeFail() {
        assertEquals(FrameReason.DECODE_FAIL, classifyFrame(ByteArray(110), parsedOk = false))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.FrameReasonTest"`
Expected: FAIL — unresolved references.

- [ ] **Step 3: Expose the offset in `BmsProtocol.kt`**

In `ble/BmsProtocol.kt`, add a public accessor (place it just above the private `statusFrameStart`):

```kotlin
    /** Frame offset of the status header (00 00 LEN 01 93 55 AA), or null if none. 0 = aligned. */
    fun statusFrameOffset(raw: ByteArray): Int? = statusFrameStart(raw)
```

- [ ] **Step 4: Create `FrameReason.kt`**

```kotlin
package dev.joely.bmsmon.data

import dev.joely.bmsmon.ble.BmsProtocol

/** Why a raw frame was stored, for debugging bad decodes. */
object FrameReason {
    const val PERIODIC = "periodic"
    const val REALIGN = "realign"
    const val DECODE_FAIL = "decode_fail"
}

/**
 * Classify a received frame: [DECODE_FAIL] if it didn't parse, [REALIGN] if it parsed but the
 * status header wasn't at offset 0 (stale bytes were prepended), else [PERIODIC].
 */
fun classifyFrame(raw: ByteArray, parsedOk: Boolean): String = when {
    !parsedOk -> FrameReason.DECODE_FAIL
    (BmsProtocol.statusFrameOffset(raw) ?: 0) != 0 -> FrameReason.REALIGN
    else -> FrameReason.PERIODIC
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.FrameReasonTest"`
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/dev/joely/bmsmon/ble/BmsProtocol.kt android/app/src/main/java/dev/joely/bmsmon/data/FrameReason.kt android/app/src/test/java/dev/joely/bmsmon/FrameReasonTest.kt
git commit -m "Classify raw frames as periodic/realign/decode_fail"
```

---

### Task 9: TelemetryRepository (write path + queries + import + prune)

**Files:**
- Create: `android/app/src/main/java/dev/joely/bmsmon/data/TelemetryRepository.kt`

**Interfaces:**
- Consumes: `BmsDatabase` + DAOs (Task 2); `isNewSession`, `computeRollup`, `SESSION_GAP_MS` (Tasks 3-4); `cutoffMs`, retention constants (Task 7); `parseCsvLine` (Task 6); `Telemetry` model.
- Produces (consumed by Tasks 10-11, 14-16):
  - `class TelemetryRepository(private val db: BmsDatabase)`
  - `fun ingest(address: String, t: Telemetry, raw: ByteArray, reason: String, regen: Boolean, tsMs: Long)` — non-suspend, fire-and-forget.
  - `fun ingestRawOnly(address: String, raw: ByteArray, reason: String, tsMs: Long)` — non-suspend.
  - `fun logLink(address: String, connected: Boolean, tsMs: Long)` — non-suspend.
  - `fun sessions(address: String): Flow<List<SessionEntity>>`
  - `fun allSessions(): Flow<List<SessionEntity>>`
  - `suspend fun importCsvOnce(files: List<File>)` — segments + inserts; idempotency handled by the caller's flag (Task 12).
  - `suspend fun clearAll()`; `suspend fun approxSizeBytes(): Long`

**This task is orchestration glue** (no new branching logic — all decisions are in the pure functions already tested). It is validated by `assembleDebug` compiling and by the manual run in Task 13. No unit test is added here.

- [ ] **Step 1: Write the repository**

```kotlin
package dev.joely.bmsmon.data

import dev.joely.bmsmon.data.db.BmsDatabase
import dev.joely.bmsmon.data.db.RawFrameEntity
import dev.joely.bmsmon.data.db.SampleEntity
import dev.joely.bmsmon.data.db.SessionEntity
import dev.joely.bmsmon.model.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Single facade for telemetry persistence (replaces TelemetryLogger). Writes are serialized through
 * an unlimited channel consumed by one coroutine, so callers (the BLE poll loop) never block and DB
 * access is single-threaded. Per-pack session continuity is tracked in memory: a gap or disconnect
 * finalizes the open session (computing its rollups from its samples) and opens a new one.
 */
class TelemetryRepository(private val db: BmsDatabase) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ops = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    // Per-address session bookkeeping (touched only on the single writer coroutine).
    private val openSessionId = HashMap<String, Long>()
    private val lastSampleTs = HashMap<String, Long>()
    private val pendingDisconnect = HashMap<String, Boolean>()
    private var sinceLastPrune = 0

    init {
        scope.launch { for (op in ops) runCatching { op() } }
    }

    private fun hex(raw: ByteArray): String = raw.joinToString("") { "%02x".format(it) }

    fun ingest(address: String, t: Telemetry, raw: ByteArray, reason: String, regen: Boolean, tsMs: Long) {
        ops.trySend {
            val sessionId = advanceSession(address, tsMs)
            db.samples().insert(sampleFrom(address, t, sessionId, regen, tsMs))
            db.rawFrames().insert(RawFrameEntity(address = address, tsMs = tsMs, hex = hex(raw), reason = reason))
            lastSampleTs[address] = tsMs
            pendingDisconnect[address] = false
            maybePrune(tsMs)
        }
    }

    fun ingestRawOnly(address: String, raw: ByteArray, reason: String, tsMs: Long) {
        ops.trySend {
            db.rawFrames().insert(RawFrameEntity(address = address, tsMs = tsMs, hex = hex(raw), reason = reason))
        }
    }

    fun logLink(address: String, connected: Boolean, tsMs: Long) {
        ops.trySend {
            val sessionId = openSessionId[address] ?: 0L
            db.samples().insert(
                SampleEntity(
                    address = address, tsMs = tsMs, sessionId = sessionId, state = null, soc = null,
                    currentA = null, powerW = null, voltageV = null, tempC = null, mosfetTempC = null,
                    soh = null, fullChargeAh = null, remainingAh = null, cycles = null,
                    cellMinV = null, cellMaxV = null, regen = false,
                    linkEvent = if (connected) "Connected" else "Disconnected",
                ),
            )
            if (!connected) {
                pendingDisconnect[address] = true
                finalizeSession(address)   // close the run; next sample opens a fresh session
            }
        }
    }

    /** Decide the session for this sample, finalizing+opening as needed. Returns the open id. */
    private suspend fun advanceSession(address: String, tsMs: Long): Long {
        val isNew = isNewSession(lastSampleTs[address], pendingDisconnect[address] == true, tsMs)
        if (isNew) {
            finalizeSession(address)
            val id = db.sessions().insert(emptySession(address, tsMs))
            openSessionId[address] = id
        }
        return openSessionId[address] ?: db.sessions().insert(emptySession(address, tsMs))
            .also { openSessionId[address] = it }
    }

    /** Recompute and persist rollups for the pack's currently-open session, then forget it. */
    private suspend fun finalizeSession(address: String) {
        val id = openSessionId.remove(address) ?: return
        val samples = db.samples().forSession(id)
        if (samples.none { it.linkEvent == null }) return
        db.sessions().update(computeRollup(address, id, samples))
    }

    private fun emptySession(address: String, tsMs: Long) = SessionEntity(
        id = 0, address = address, startMs = tsMs, endMs = tsMs, sampleCount = 0,
        peakPowerW = 0f, p95PowerW = 0f, meanPowerW = 0f, peakCurrentA = 0f, peakRegenW = 0f,
        energyWh = 0f, socStart = 0f, socEnd = 0f, minSoc = 0f, maxSoc = 0f,
        minVoltageUnderLoad = 0f, estInternalResistanceMohm = null, irConfidence = 0f,
        sohEnd = 0, fullChargeAhEnd = 0f, cyclesEnd = 0, maxTempC = 0f,
    )

    private fun sampleFrom(address: String, t: Telemetry, sessionId: Long, regen: Boolean, tsMs: Long) =
        SampleEntity(
            address = address, tsMs = tsMs, sessionId = sessionId, state = t.state.name,
            soc = t.soc, currentA = t.current, powerW = t.powerW, voltageV = t.voltage,
            tempC = t.temp, mosfetTempC = t.mosfetTemp, soh = t.soh, fullChargeAh = t.fullChargeAh,
            remainingAh = t.capacityAh, cycles = t.cycles,
            cellMinV = t.cells.minOrNull(), cellMaxV = t.cells.maxOrNull() ?: t.cellV,
            regen = regen, linkEvent = null,
        )

    private suspend fun maybePrune(nowMs: Long) {
        if (++sinceLastPrune < 200) return
        sinceLastPrune = 0
        db.samples().deleteOlderThan(cutoffMs(nowMs, SAMPLE_RETENTION_DAYS))
        db.rawFrames().deleteOlderThan(cutoffMs(nowMs, RAW_FRAME_RETENTION_DAYS))
        while (db.rawFrames().totalHexBytes() > RAW_FRAME_MAX_BYTES) {
            if (db.rawFrames().deleteOldest(1000) == 0) break
        }
    }

    fun sessions(address: String): Flow<List<SessionEntity>> = db.sessions().forAddress(address)
    fun allSessions(): Flow<List<SessionEntity>> = db.sessions().all()

    /** One-time backfill of legacy CSV files (oldest first). Segments via the same gap rule. */
    suspend fun importCsvOnce(files: List<File>) {
        for (file in files) {
            if (!file.exists()) continue
            file.useLines { lines ->
                for (line in lines) {
                    val parsed = parseCsvLine(line) ?: continue
                    val sessionId = advanceSession(parsed.address, parsed.tsMs)
                    db.samples().insert(parsed.copy(sessionId = sessionId))
                    lastSampleTs[parsed.address] = parsed.tsMs
                    pendingDisconnect[parsed.address] = parsed.linkEvent == "Disconnected"
                }
            }
        }
        openSessionId.keys.toList().forEach { finalizeSession(it) }
    }

    suspend fun clearAll() {
        db.samples().clear(); db.sessions().clear(); db.rawFrames().clear()
        openSessionId.clear(); lastSampleTs.clear(); pendingDisconnect.clear()
    }

    suspend fun approxSizeBytes(): Long =
        db.samples().count() * 80L + db.rawFrames().totalHexBytes()
}
```

- [ ] **Step 2: Verify it compiles**

Run: `cd android && ./gradlew :app:assembleDebug -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/dev/joely/bmsmon/data/TelemetryRepository.kt
git commit -m "Add TelemetryRepository: DB write path, session finalize, import, prune"
```

---

### Task 10: Thread raw frames through BmsRepository

**Files:**
- Modify: `android/app/src/main/java/dev/joely/bmsmon/ble/BmsRepository.kt`

**Interfaces:**
- Changes the `start(...)` callback `onTelemetry: (String, Telemetry) -> Unit` to `onPoll: (String, ByteArray, Telemetry?) -> Unit`. `onReachable` is unchanged.
- Produces (consumed by Task 11): `BmsRepository.start(scope, targets, onPoll, onReachable)` where `onPoll(address, rawFrame, telemetryOrNull)` is called for **every** received frame (success, realign, or decode failure).

**Glue task** — validated by compile + Task 11's wiring + Task 13 run.

- [ ] **Step 1: Change the callback field and `start` signature**

In `ble/BmsRepository.kt`, replace the `onTelemetry` field declaration:

```kotlin
    private var onPoll: (String, ByteArray, Telemetry?) -> Unit = { _, _, _ -> }
    private var onReachable: (String, Boolean) -> Unit = { _, _ -> }
```

And update `start(...)`:

```kotlin
    fun start(
        scope: CoroutineScope,
        targets: List<BmsTarget>,
        onPoll: (String, ByteArray, Telemetry?) -> Unit,
        onReachable: (String, Boolean) -> Unit,
    ) {
        stop()
        this.scope = scope
        this.allTargets = targets.map { it.copy(address = it.address.trim().uppercase()) }
        this.onPoll = onPoll
        this.onReachable = onReachable
        running = true
        samplerJob = scope.launch(Dispatchers.IO) { samplerLoop() }
    }
```

- [ ] **Step 2: Emit every frame in the stage worker**

In `stageWorker`, replace the poll/parse block:

```kotlin
                while (running && target.address in stageAddrs) {
                    val data = session.poll(4000) ?: break  // a miss → reconnect
                    val tel = BmsProtocol.parseTelemetry(data, target.name)
                    if (tel != null) lastState[target.address] = tel.state
                    onPoll(target.address, data, tel)
                    delay(STAGE_POLL_MS)
                }
```

- [ ] **Step 3: Emit every frame in the sampler**

In `sampleOne`, replace the parse/dispatch block:

```kotlin
        try {
            val ok = gate.withPermit { session.connect(8000) }
            val data = if (ok) session.poll(4000) else null
            val tel = data?.let { BmsProtocol.parseTelemetry(it, t.name) }
            if (tel != null) {
                sampleFailures[t.address] = 0
                lastState[t.address] = tel.state
                onReachable(t.address, true)
            } else {
                markSampleFail(t.address)
            }
            if (data != null) onPoll(t.address, data, tel)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            markSampleFail(t.address)
        } finally {
            session.close()
        }
```

- [ ] **Step 4: Verify it compiles (will fail at the engine call site — that's Task 11)**

Run: `cd android && ./gradlew :app:compileDebugKotlin -q`
Expected: FAIL only in `MonitorEngine.kt` (it still passes `onTelemetry = ::onTelemetry`). `BmsRepository.kt` itself compiles. Proceed to Task 11 to fix the call site; commit them together.

- [ ] **Step 5: (Deferred commit)** — commit at the end of Task 11.

---

### Task 11: Wire MonitorEngine to TelemetryRepository

**Files:**
- Modify: `android/app/src/main/java/dev/joely/bmsmon/monitor/MonitorEngine.kt`

**Interfaces:**
- Consumes: `TelemetryRepository` (Task 9), `classifyFrame`/`FrameReason` (Task 8), `BmsRepository.start(... onPoll ...)` (Task 10).
- Produces: `MonitorEngine.repositoryFor()` is not exposed; the engine owns a `TelemetryRepository` and exposes `val history: TelemetryRepository` (consumed by the ViewModel in Task 15).

- [ ] **Step 1: Replace the logger field with the repository**

In `MonitorEngine.kt`, change imports and fields:

```kotlin
import dev.joely.bmsmon.data.TelemetryRepository
import dev.joely.bmsmon.data.classifyFrame
import dev.joely.bmsmon.data.db.BmsDatabase
```

Replace `private val logger = TelemetryLogger(appContext)` with:

```kotlin
    private val repository = TelemetryRepository(BmsDatabase.create(appContext))

    /** Exposed so the ViewModel can read session history for the graphs. */
    val history: TelemetryRepository get() = repository
```

Replace the `val logPath: String get() = logger.path` line with a temporary stub so `BatteryViewModel` (which still reads `engine.logPath` until Task 13) keeps compiling:

```kotlin
    val logPath: String get() = ""  // CSV path gone; removed together with the UiState field in Task 13
```

- [ ] **Step 2: Point `start` at the new `onPoll` callback**

In `start(...)`, change the `repository.start(...)` (the BLE engine) call's `onTelemetry = ::onTelemetry` to `onPoll = ::onPoll`. Note: the BLE engine field is also named `repository` in the current code — rename the BLE one to `ble` to avoid the clash with the new telemetry repository. Update the field declaration `private val repository = BmsRepository(appContext)` to:

```kotlin
    private val ble = BmsRepository(appContext)
```

and update every `repository.<x>()` call that referred to the BLE engine (`start`, `setTargets`, `stop`, `setStage`, `setDisabled`, `kickAll`) to `ble.<x>()`. The `start` call becomes:

```kotlin
        ble.start(
            scope = scope,
            targets = roster.allTargets(),
            onPoll = ::onPoll,
            onReachable = ::onReachable,
        )
```

- [ ] **Step 3: Replace `onTelemetry` with `onPoll`**

Replace the whole `onTelemetry` function with:

```kotlin
    private fun onPoll(addr: String, raw: ByteArray, t: Telemetry?) {
        val now = now()
        if (t == null) {
            if (logging) repository.ingestRawOnly(addr, raw, "decode_fail", now)
            return
        }
        val st0 = _state.value
        val group = roster.groupOf(addr)
        // Regen is judged against the group's last-discharge time BEFORE this sample updates it.
        val regen = isRegen(t, group?.let { st0.lastDischargeAt[it.id] }, now)
        _state.update { st ->
            val fleet = st.fleet + (addr to (st.fleet[addr] ?: BatteryStatus())
                .copy(telemetry = t, reachable = true))
            var peakP = st.peakPowerW
            var peakC = st.peakCurrentA
            if (logging && t.current < -0.05f) {  // discharging — track peak draw
                peakP = maxOf(peakP, t.powerW)
                peakC = maxOf(peakC, -t.current)
            }
            st.copy(
                fleet = fleet,
                regenAddrs = if (regen) st.regenAddrs + addr else st.regenAddrs - addr,
                lastDischargeAt = recomputeLastDischarge(fleet, st.lastDischargeAt, now),
                peakPowerW = peakP,
                peakCurrentA = peakC,
            )
        }
        if (logging) repository.ingest(addr, t, raw, classifyFrame(raw, parsedOk = true), regen, now)
    }
```

- [ ] **Step 4: Update the link-event log call in `onReachable`**

Replace the logging block at the end of `onReachable`:

```kotlin
        if (reachable != was && logging) {
            repository.logLink(addr, reachable, now())
        }
```

(The `roster.batteryAt(addr)?.alias` name lookup is no longer needed — the DB keys on address; delete that line.)

- [ ] **Step 5: Repoint `clearLog`**

Replace `clearLog()`'s body:

```kotlin
    fun clearLog() {
        scope.launch { repository.clearAll() }
        _state.update { it.copy(peakPowerW = 0f, peakCurrentA = 0f) }
    }
```

(Add `import kotlinx.coroutines.launch` if not already present.)

- [ ] **Step 6: Verify it compiles**

Run: `cd android && ./gradlew :app:compileDebugKotlin -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit (Tasks 10 + 11 together)**

```bash
git add android/app/src/main/java/dev/joely/bmsmon/ble/BmsRepository.kt android/app/src/main/java/dev/joely/bmsmon/monitor/MonitorEngine.kt
git commit -m "Route telemetry and raw frames through TelemetryRepository"
```

---

### Task 12: One-time CSV import on first launch

**Files:**
- Modify: `android/app/src/main/java/dev/joely/bmsmon/data/SettingsStore.kt` (add a `csvImported` flag)
- Modify: `android/app/src/main/java/dev/joely/bmsmon/monitor/MonitorEngine.kt` (run import once on first `start`)

**Interfaces:**
- Consumes: `TelemetryRepository.importCsvOnce` (Task 9), `SettingsStore`.
- Produces: `Persisted.csvImported: Boolean` (read by `SettingsStore.load()`) + `suspend fun SettingsStore.setCsvImported(on: Boolean)`; import runs at most once.

- [ ] **Step 1: Confirm the existing SettingsStore pattern**

Run: `cd android && grep -n "data class Persisted\|val logging\|LOGGING =\|fun load\|setLogging" app/src/main/java/dev/joely/bmsmon/data/SettingsStore.kt`
Expected: `Persisted` data class with `val logging: Boolean`, a `K.LOGGING = booleanPreferencesKey("logging")` key, a `load(): Persisted` reader, and `suspend fun setLogging(on)`. The new flag mirrors all four.

- [ ] **Step 2: Add the `csvImported` preference (mirror `logging`)**

In `SettingsStore.kt`, make these four edits, each mirroring the existing `logging` lines:
1. In the keys object `K`, add: `val CSV_IMPORTED = booleanPreferencesKey("csv_imported")`
2. In the `Persisted` data class, add: `val csvImported: Boolean,`
3. In `load()`, where it builds `Persisted(...)`, add: `csvImported = prefs[K.CSV_IMPORTED] ?: false,` (use the same `prefs`/getter local name `load()` already uses).
4. Add a setter next to `setLogging`: `suspend fun setCsvImported(on: Boolean) = context.dataStore.edit { it[K.CSV_IMPORTED] = on }.let {}`

- [ ] **Step 3: Run the import once from the engine**

In `MonitorEngine.kt`, add a one-shot import kicked off the first time monitoring starts. Add a field and a method, and call it at the top of `start(...)`:

```kotlin
    @Volatile private var importChecked = false

    /** Backfill the legacy CSVs into the DB exactly once (guarded by a persisted flag). */
    fun importLegacyCsvIfNeeded(alreadyImported: Boolean, markImported: suspend () -> Unit, filesDir: File?) {
        if (importChecked || alreadyImported) { importChecked = true; return }
        importChecked = true
        scope.launch {
            val dir = filesDir ?: return@launch
            repository.importCsvOnce(listOf(File(dir, "usage_log.csv"), File(dir, "usage_log.1.csv")))
            markImported()
        }
    }
```

Add imports `java.io.File` and `kotlinx.coroutines.launch` if missing.

- [ ] **Step 4: Trigger it from the ViewModel's start path**

In `BatteryViewModel.kt`, where monitoring starts (the `startMonitoring`/`engine.start` path around line 509), call the import once, passing the persisted flag and a marker. Add inside `startMonitoring()` before/after `engine.start(...)`:

```kotlin
        engine.importLegacyCsvIfNeeded(
            alreadyImported = _state.value.csvImported,
            markImported = { store.setCsvImported(true) },
            filesDir = getApplication<Application>().getExternalFilesDir(null),
        )
```

`BatteryViewModel` is `AndroidViewModel(app)`, so `getApplication<Application>()` is the Context — add `import android.app.Application` if it isn't already imported. Add `csvImported: Boolean = false` to `UiState`, and populate it in the `init` settings block (the `s.copy(...)` near `logging = p.logging`) with `csvImported = p.csvImported`.

- [ ] **Step 5: Verify it compiles**

Run: `cd android && ./gradlew :app:assembleDebug -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/dev/joely/bmsmon/data/SettingsStore.kt android/app/src/main/java/dev/joely/bmsmon/monitor/MonitorEngine.kt android/app/src/main/java/dev/joely/bmsmon/BatteryViewModel.kt
git commit -m "Import legacy usage_log CSV into the database once"
```

---

### Task 13: Remove TelemetryLogger and repoint Settings UI

**Files:**
- Delete: `android/app/src/main/java/dev/joely/bmsmon/data/TelemetryLogger.kt`
- Modify: `android/app/src/main/java/dev/joely/bmsmon/monitor/MonitorEngine.kt` (remove the `logPath` stub)
- Modify: `android/app/src/main/java/dev/joely/bmsmon/BatteryViewModel.kt` (drop `logPath`, add DB size)
- Modify: `android/app/src/main/java/dev/joely/bmsmon/ui/settings/SettingsScreen.kt` (replace the path readout)

**Interfaces:**
- Consumes: `TelemetryRepository.approxSizeBytes` (Task 9), `MonitorEngine.history` (Task 11).
- Produces: the Settings "logging" section shows a DB size string instead of a CSV path.

- [ ] **Step 1: Delete the CSV logger**

```bash
git rm android/app/src/main/java/dev/joely/bmsmon/data/TelemetryLogger.kt
```

- [ ] **Step 2: Replace `logPath` in the ViewModel with a DB-size string**

In `BatteryViewModel.kt`: remove `val logPath: String` from `UiState`, change the state initializer `MutableStateFlow(UiState(logPath = engine.logPath))` to `MutableStateFlow(UiState())`, and delete the `val logPath` stub from `MonitorEngine.kt` (added in Task 11). Add `val dbSize: String = ""` to `UiState`. Add a function that refreshes it and call it when opening Settings:

```kotlin
    fun refreshDbSize() = viewModelScope.launch {
        val bytes = engine.history.approxSizeBytes()
        val mb = bytes / (1024f * 1024f)
        _state.update { it.copy(dbSize = "%.1f MB".format(mb)) }
    }
```

Call `refreshDbSize()` inside `goSettings()`.

- [ ] **Step 3: Repoint the Settings readout**

In `SettingsScreen.kt` around line 526, replace the `Text(state.logPath, ...)` line with the DB-size readout:

```kotlin
        Text("Database: ${state.dbSize}", color = c.text3, fontSize = 10.sp, fontFamily = MonoFont,
            modifier = Modifier.padding(top = 4.dp))
```

(Keep the surrounding modifiers/style identical to what was there.)

- [ ] **Step 4: Verify the whole suite + build**

Run: `cd android && ./gradlew :app:testDebugUnitTest :app:assembleDebug -q`
Expected: BUILD SUCCESSFUL; all unit tests pass.

- [ ] **Step 5: Install and smoke-test the cutover on the device**

Run:
```bash
adb connect 192.168.0.16:5555
adb -s 192.168.0.16:5555 install -r android/app/build/outputs/apk/debug/app-debug.apk
adb -s 192.168.0.16:5555 shell monkey -p dev.joely.bmsmon -c android.intent.category.LAUNCHER 1
```
Then enable monitoring in the app, wait ~30 s, and verify the DB exists and is filling:
```bash
adb -s 192.168.0.16:5555 shell run-as dev.joely.bmsmon ls -la databases/
```
Expected: `bms.db` present and growing. (If `run-as` is blocked on the device, instead confirm via the Settings "Database: X.X MB" readout increasing.)

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "Remove CSV logger; show database size in Settings"
```

---

## Phase 2 — Graphs & UI

### Task 14: Reusable line-chart Canvas composable

**Files:**
- Create: `android/app/src/main/java/dev/joely/bmsmon/ui/charts/LineChart.kt`
- Test: `android/app/src/test/java/dev/joely/bmsmon/ChartScaleTest.kt`

**Interfaces:**
- Produces:
  - `data class ChartSeries(val label: String, val color: Color, val points: List<Float>)` (x is the index → gaps collapse).
  - `fun normalize(values: List<Float>, min: Float, max: Float): List<Float>` — pure, maps to 0..1 (consumed by the chart + tested).
  - `@Composable fun LineChart(series: List<ChartSeries>, modifier: Modifier, yLabel: String)`.

- [ ] **Step 1: Write the failing test for the pure scaling helper**

```kotlin
package dev.joely.bmsmon

import dev.joely.bmsmon.ui.charts.normalize
import org.junit.Assert.assertEquals
import org.junit.Test

class ChartScaleTest {
    @Test fun mapsRangeToZeroOne() {
        val out = normalize(listOf(0f, 5f, 10f), min = 0f, max = 10f)
        assertEquals(0f, out[0], 0.001f)
        assertEquals(0.5f, out[1], 0.001f)
        assertEquals(1f, out[2], 0.001f)
    }

    @Test fun flatRangeMapsToMidline() {
        val out = normalize(listOf(7f, 7f), min = 7f, max = 7f)
        assertEquals(0.5f, out[0], 0.001f)
        assertEquals(0.5f, out[1], 0.001f)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.ChartScaleTest"`
Expected: FAIL — unresolved reference `normalize`.

- [ ] **Step 3: Implement the chart + helper**

```kotlin
package dev.joely.bmsmon.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke

/** Map [values] into 0..1 across [min,max]; a zero-width range maps everything to the midline. */
fun normalize(values: List<Float>, min: Float, max: Float): List<Float> {
    val span = max - min
    if (span <= 0f) return values.map { 0.5f }
    return values.map { ((it - min) / span).coerceIn(0f, 1f) }
}

/**
 * Minimal multi-series line chart over a shared index x-axis (so time gaps collapse: point N is
 * just the Nth session, evenly spaced). Each series is scaled against the combined min/max of all
 * series so they share a y-axis. No external chart deps — matches the hand-rolled gauge style.
 */
@Composable
fun LineChart(series: List<ChartSeries>, modifier: Modifier = Modifier, yLabel: String = "") {
    val all = series.flatMap { it.points }
    val lo = all.minOrNull() ?: 0f
    val hi = all.maxOrNull() ?: 1f
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        series.forEach { s ->
            if (s.points.size < 2) return@forEach
            val ys = normalize(s.points, lo, hi)
            val dx = w / (s.points.size - 1)
            val path = Path()
            ys.forEachIndexed { i, y ->
                val px = i * dx
                val py = h - y * h
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            drawPath(path, color = s.color, style = Stroke(width = 3f))
        }
    }
}

data class ChartSeries(val label: String, val color: Color, val points: List<Float>)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.ChartScaleTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/dev/joely/bmsmon/ui/charts/LineChart.kt android/app/src/test/java/dev/joely/bmsmon/ChartScaleTest.kt
git commit -m "Add reusable Compose Canvas line chart"
```

---

### Task 15: Expose history Flows + History screen nav in the ViewModel

**Files:**
- Modify: `android/app/src/main/java/dev/joely/bmsmon/BatteryViewModel.kt`

**Interfaces:**
- Consumes: `MonitorEngine.history` (Task 11), `SessionEntity`.
- Produces (consumed by Tasks 16-17):
  - `Screen.History` added to the `enum class Screen`.
  - `fun goHistory()` / the existing `goHome()` to return.
  - `fun sessionsFor(address: String): Flow<List<SessionEntity>>` (delegates to `engine.history.sessions`).
  - `fun allSessions(): Flow<List<SessionEntity>>` (delegates to `engine.history.allSessions`).

- [ ] **Step 1: Add the screen + accessors**

In `BatteryViewModel.kt`:

```kotlin
enum class Screen { Home, Settings, Detail, History }
```

Add the navigation + query delegates (near `goSettings`/`openDetail`):

```kotlin
    fun goHistory() = _state.update { it.copy(screen = Screen.History) }

    fun sessionsFor(address: String) = engine.history.sessions(address)
    fun allSessions() = engine.history.allSessions()
```

Add the import `import dev.joely.bmsmon.data.db.SessionEntity` if a type reference is needed.

- [ ] **Step 2: Verify it compiles**

Run: `cd android && ./gradlew :app:compileDebugKotlin -q`
Expected: BUILD SUCCESSFUL (the new `Screen.History` branch is handled in Task 16/17's `when`).

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/dev/joely/bmsmon/BatteryViewModel.kt
git commit -m "Expose session history flows and History screen route"
```

---

### Task 16: Per-battery History section on the Detail screen

**Files:**
- Create: `android/app/src/main/java/dev/joely/bmsmon/ui/history/BatteryGraphs.kt`
- Modify: `android/app/src/main/java/dev/joely/bmsmon/ui/detail/BatteryDetailScreen.kt`
- Modify: `android/app/src/main/java/dev/joely/bmsmon/ui/App.kt` (pass the sessions flow into Detail)

**Interfaces:**
- Consumes: `LineChart`/`ChartSeries` (Task 14), `SessionEntity`, `vm.sessionsFor` (Task 15), `Bm` colors/`RegenGreen`/`AlertWarn`.
- Produces: `@Composable fun BatteryGraphs(sessions: List<SessionEntity>, accent: Color, power: Color)` rendering the three core charts (peak power, internal resistance, capacity & SOH).

- [ ] **Step 1: Build the three-graph composable**

```kotlin
package dev.joely.bmsmon.ui.history

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import dev.joely.bmsmon.data.db.SessionEntity
import dev.joely.bmsmon.ui.charts.ChartSeries
import dev.joely.bmsmon.ui.charts.LineChart
import dev.joely.bmsmon.ui.theme.Bm

/**
 * The three core aging graphs for a set of [sessions] (one point per session, x = session index so
 * gaps collapse): peak discharge power, estimated internal resistance, and capacity & SOH.
 */
@Composable
fun BatteryGraphs(sessions: List<SessionEntity>, accent: Color, power: Color) {
    val c = Bm.colors
    if (sessions.size < 2) {
        Text("Not enough history yet — keep monitoring to build trends.",
            color = c.text3, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
        return
    }

    Graph("Peak power per session (W)", c.text2) {
        LineChart(
            series = listOf(
                ChartSeries("peak", power, sessions.map { it.peakPowerW }),
                ChartSeries("p95", accent, sessions.map { it.p95PowerW }),
            ),
            modifier = Modifier.fillMaxWidth().height(140.dp),
        )
    }

    val irSeries = sessions.map { it.estInternalResistanceMohm ?: Float.NaN }
        .map { if (it.isNaN()) 0f else it }
    Graph("Internal resistance (mΩ) — rising = aging", c.text2) {
        LineChart(
            series = listOf(ChartSeries("mΩ", accent, irSeries)),
            modifier = Modifier.fillMaxWidth().height(140.dp),
        )
    }

    Graph("Capacity (Ah) & SOH (%) vs cycles", c.text2) {
        LineChart(
            series = listOf(
                ChartSeries("Ah", accent, sessions.map { it.fullChargeAhEnd }),
                ChartSeries("SOH", power, sessions.map { it.sohEnd.toFloat() }),
            ),
            modifier = Modifier.fillMaxWidth().height(140.dp),
        )
    }
}

@Composable
private fun Graph(title: String, titleColor: Color, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(title, color = titleColor, fontSize = 12.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 4.dp))
        content()
    }
}
```

- [ ] **Step 2: Render it in the Detail screen**

In `BatteryDetailScreen.kt`, accept the sessions list and add a History `Section`. Change the signature to:

```kotlin
fun BatteryDetailScreen(state: UiState, sessions: List<SessionEntity>, onBack: () -> Unit) {
```

Add imports for `SessionEntity`, `BatteryGraphs`, `Bm`/accent/power, then inside the scrolling `Column` (after the existing `Section("Identity")` block) add:

```kotlin
            Section("History") {
                BatteryGraphs(sessions = sessions, accent = state.accent, power = state.power)
            }
```

- [ ] **Step 3: Provide the sessions flow from `App.kt`**

In `ui/App.kt`, in the `Screen.Detail ->` branch, collect the per-battery sessions and pass them in:

```kotlin
                    Screen.Detail -> {
                        val addr = state.detailAddress
                        val sessions by (if (addr != null) vm.sessionsFor(addr) else kotlinx.coroutines.flow.flowOf(emptyList()))
                            .collectAsState(initial = emptyList())
                        BatteryDetailScreen(state = state, sessions = sessions, onBack = vm::closeDetail)
                    }
```

(Imports: `androidx.compose.runtime.collectAsState`, `androidx.compose.runtime.getValue` are already present; add `dev.joely.bmsmon.data.db.SessionEntity` only if referenced explicitly.)

- [ ] **Step 4: Verify it builds**

Run: `cd android && ./gradlew :app:assembleDebug -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/dev/joely/bmsmon/ui/history/BatteryGraphs.kt android/app/src/main/java/dev/joely/bmsmon/ui/detail/BatteryDetailScreen.kt android/app/src/main/java/dev/joely/bmsmon/ui/App.kt
git commit -m "Add per-battery History graphs to the Detail screen"
```

---

### Task 17: Fleet History screen + entry point

**Files:**
- Create: `android/app/src/main/java/dev/joely/bmsmon/ui/history/HistoryScreen.kt`
- Modify: `android/app/src/main/java/dev/joely/bmsmon/ui/App.kt` (route `Screen.History`)
- Modify: `android/app/src/main/java/dev/joely/bmsmon/ui/home/HomeScreen.kt` (top-bar entry)

**Interfaces:**
- Consumes: `vm.allSessions()` (Task 15), `LineChart`/`ChartSeries` (Task 14), `SessionEntity`, `vm.goHistory`/`vm::goHome`.
- Produces: `@Composable fun HistoryScreen(sessions: List<SessionEntity>, accent: Color, onBack: () -> Unit)` overlaying every pack's internal-resistance aging trend on one axis.

- [ ] **Step 1: Build the fleet screen**

```kotlin
package dev.joely.bmsmon.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.joely.bmsmon.data.db.SessionEntity
import dev.joely.bmsmon.ui.charts.ChartSeries
import dev.joely.bmsmon.ui.charts.LineChart
import dev.joely.bmsmon.ui.theme.Bm
import dev.joely.bmsmon.ui.theme.ThemeSwatches

/**
 * Fleet-wide history: every pack's internal-resistance aging trend overlaid on one axis so an
 * outlier (a weakening pack) stands out. One line per address, colored from the theme swatches.
 */
@Composable
fun HistoryScreen(sessions: List<SessionEntity>, accent: Color, onBack: () -> Unit) {
    val c = Bm.colors
    val byAddress = sessions.groupBy { it.address }.toList()
    Column(Modifier.fillMaxSize().background(c.bg).verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Fleet history", color = c.text, fontSize = 18.sp, modifier = Modifier.padding(bottom = 8.dp))
        if (byAddress.isEmpty()) {
            Text("No sessions recorded yet.", color = c.text3, fontSize = 12.sp)
        } else {
            Text("Internal resistance (mΩ) — higher / rising lines are weaker packs",
                color = c.text2, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
            LineChart(
                series = byAddress.mapIndexed { i, (_, list) ->
                    ChartSeries(
                        label = list.first().address,
                        color = ThemeSwatches[i % ThemeSwatches.size],
                        points = list.map { it.estInternalResistanceMohm ?: 0f },
                    )
                },
                modifier = Modifier.fillMaxWidth().height(200.dp),
            )
            byAddress.forEachIndexed { i, (addr, _) ->
                Text("● $addr", color = ThemeSwatches[i % ThemeSwatches.size], fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp))
            }
        }
        Text("Back", color = accent, fontSize = 14.sp,
            modifier = Modifier.padding(top = 16.dp).clickableBack(onBack))
    }
}

// Small helper to keep the import list minimal.
private fun Modifier.clickableBack(onBack: () -> Unit): Modifier = this.then(
    androidx.compose.foundation.clickable(onClick = onBack),
)
```

- [ ] **Step 2: Route `Screen.History` in `App.kt`**

In `ui/App.kt`'s `when (state.screen)`, add:

```kotlin
                    Screen.History -> {
                        val sessions by vm.allSessions().collectAsState(initial = emptyList())
                        HistoryScreen(sessions = sessions, accent = state.accent, onBack = vm::goHome)
                    }
```

Add `import dev.joely.bmsmon.ui.history.HistoryScreen`.

- [ ] **Step 3: Add a top-bar entry on Home**

In `HomeScreen.kt`, add an `onHistory: () -> Unit` parameter to the composable signature, and place a small history icon/button in the top bar next to Settings (mirror the existing Settings button's style — reuse `Icons` already imported there; e.g. `Icons.Filled.ShowChart`). Wire it to `onHistory`.

In `ui/App.kt`'s `HomeScreen(...)` call, pass `onHistory = vm::goHistory`.

- [ ] **Step 4: Handle back navigation**

In `ui/App.kt`, the existing `BackHandler` already returns non-Home screens; add `Screen.History -> vm.goHome()` to its `when` so system back leaves the History screen.

- [ ] **Step 5: Verify build + full suite**

Run: `cd android && ./gradlew :app:testDebugUnitTest :app:assembleDebug -q`
Expected: BUILD SUCCESSFUL; all unit tests pass.

- [ ] **Step 6: Install and visually verify on device**

Run:
```bash
adb -s 192.168.0.16:5555 install -r android/app/build/outputs/apk/debug/app-debug.apk
adb -s 192.168.0.16:5555 shell monkey -p dev.joely.bmsmon -c android.intent.category.LAUNCHER 1
```
Open a battery's Detail → History (after some sessions exist or post-import) and the fleet History screen; confirm charts render and lines appear.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/java/dev/joely/bmsmon/ui/history/HistoryScreen.kt android/app/src/main/java/dev/joely/bmsmon/ui/App.kt android/app/src/main/java/dev/joely/bmsmon/ui/home/HomeScreen.kt
git commit -m "Add fleet History screen with internal-resistance overlay"
```

---

## Self-Review notes (addressed)

- **Spec coverage:** Room storage (Tasks 1-2), 3-table schema (Task 2), session rollups/segmentation (Tasks 3-4), internal resistance (Task 5), CSV import (Tasks 6, 9, 12), retention/caps (Tasks 7, 9), raw-frame capture + reason (Tasks 8-11), straight cutover/remove CSV (Tasks 11, 13), graphs ×3 (Tasks 14, 16), per-battery + fleet placement (Tasks 16-17), gap-collapsing via session-index x-axis (Task 14). The optional per-session timeline graph is intentionally **not** included in this plan (spec marked it optional); add as a follow-up if wanted.
- **`IR_MIN_CURRENT_SPREAD_A`** (the spec's one TBD) is resolved to **8 A** in Task 5.
- **Type consistency:** `SampleEntity`/`SessionEntity`/`RawFrameEntity` field names are used identically across Tasks 2, 4, 5, 9, 16, 17. The BLE engine field is renamed `ble` (Task 11) to free the name `repository` for the telemetry repo. `onPoll(address, raw, telemetry?)` signature matches between Tasks 10 and 11. `classifyFrame`/`FrameReason` strings match Tasks 8 and 11.
- **Known device:** the wireless-ADB device address `192.168.0.16:5555` is from this session; re-run `adb connect` / `adb devices` if it has changed.
