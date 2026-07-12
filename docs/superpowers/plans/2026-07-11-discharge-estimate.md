# Discharge Estimate (miles + time remaining) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show a learned high/low discharge-remaining estimate — miles, active-use hours, wall-clock time to empty — for the staged base on the Android stage and (mirrored read-only) on the WebUI.

**Architecture:** The phone learns three per-pack parameter bands (Wh/day, active W, Wh/mile) from its 14-day Room history via a periodic engine job, stores them in SettingsStore, and computes a per-pack estimate once per poll (carried on `BatteryStatus`, like `etaFullMin`). Learned params ride the existing one-way `POST /api/v1/config` push into a new `device_range_config` table; the WebUI re-evaluates a line-for-line TypeScript twin of the pure formula (no live tilt on web). Spec: `docs/superpowers/specs/2026-07-11-discharge-estimate-design.md`.

**Tech Stack:** Kotlin/Compose + Room + DataStore (android/), FastAPI + asyncpg (server/), React + Vite + vitest (web/), JUnit4 unit tests.

## Global Constraints

- Commit messages: NEVER include "Generated with Claude Code", "Co-Authored-By: Claude", or any reference to AI/Claude/automated generation (CLAUDE.md rule).
- Read-only BLE only — this feature adds no BLE writes of any kind.
- All new estimate math is pure functions with unit tests on both platforms; Kotlin and TS twins must share the same test vectors.
- Nominal pack voltage constant: **12.8 V**. Learning window: **14 days** (matches `SAMPLE_RETENTION_DAYS`).
- Seeds (until ≥3 qualifying days): whPerDay `Band(78f, 182f)` (130 ±40%), activeW `Band(52.5f, 97.5f)` (75 ±30%), whPerMile `Band(15f, 25f)` (20 ±25%).
- Qualified drive segment: consecutive telemetry rows, both with GPS fix and `gpsAccuracyM < 20`, `0.5 ≤ dt ≤ 15 s`, row `state == "Discharging"`, `powerW > 40`, segment speed `0.4–4.0 m/s`. Distance = equirectangular approximation.
- Burn stats exclude regen rows (`regen == true`) and use only `0.5 ≤ dt ≤ 60 s` gaps.
- Android build/test from `/home/joely/bmsmon/android`: `./gradlew :app:testDebugUnitTest`. Server tests from `/home/joely/bmsmon/server`: `.venv/bin/python -m pytest` (needs `docker compose -f docker-compose.dev.yml up -d` Postgres). Web tests from `/home/joely/bmsmon/web`: `npx vitest run`.

---

### Task 1: Pure estimate formula + formatting (`model/RangeEstimate.kt`)

**Files:**
- Create: `android/app/src/main/java/dev/joely/bmsmon/model/RangeEstimate.kt`
- Test: `android/app/src/test/java/dev/joely/bmsmon/RangeEstimateTest.kt`

**Interfaces:**
- Consumes: `BatteryState` (existing enum: Idle/Charging/Discharging/Disabled).
- Produces (used by Tasks 2, 4, 5, 6, 8):
  - `data class Band(val lo: Float, val hi: Float)` with `val mid: Float`
  - `data class RangeParams(val whPerDay: Band, val activeW: Band, val whPerMile: Band, val learnedDays: Int, val updatedMs: Long)`
  - `val SEED_RANGE_PARAMS: RangeParams`
  - `data class TodayUsage(val disWh: Float, val disHours: Float, val hoursSinceMidnight: Float)`
  - `data class PackRange(val milesLo: Float, val milesHi: Float, val activeHLo: Float, val activeHHi: Float, val wallHLo: Float, val wallHHi: Float)`
  - `fun tiltedBand(band: Band, todayRate: Float?, w: Float): Band`
  - `fun estimatePackRange(state: BatteryState, remainingAh: Float, params: RangeParams, today: TodayUsage?): PackRange?`
  - `fun minRange(ranges: List<PackRange>): PackRange`
  - `fun formatRangeLine(r: PackRange): String`

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.joely.bmsmon

import dev.joely.bmsmon.model.Band
import dev.joely.bmsmon.model.BatteryState
import dev.joely.bmsmon.model.PackRange
import dev.joely.bmsmon.model.RangeParams
import dev.joely.bmsmon.model.SEED_RANGE_PARAMS
import dev.joely.bmsmon.model.TodayUsage
import dev.joely.bmsmon.model.estimatePackRange
import dev.joely.bmsmon.model.formatRangeLine
import dev.joely.bmsmon.model.minRange
import dev.joely.bmsmon.model.tiltedBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** SHARED VECTORS: web/src/range.test.ts asserts the same numbers — keep in sync. */
class RangeEstimateTest {
    // 70 Ah remaining × 12.8 V = 896 Wh; bands: whPerDay 100–180, activeW 70–100, whPerMile 18–24.
    private val params = RangeParams(
        whPerDay = Band(100f, 180f), activeW = Band(70f, 100f), whPerMile = Band(18f, 24f),
        learnedDays = 10, updatedMs = 0L,
    )

    @Test fun computesAllThreeBands() {
        val r = estimatePackRange(BatteryState.Discharging, 70f, params, today = null)!!
        assertEquals(896f / 24f, r.milesLo, 0.01f)     // 37.33
        assertEquals(896f / 18f, r.milesHi, 0.01f)     // 49.78
        assertEquals(896f / 100f, r.activeHLo, 0.01f)  // 8.96
        assertEquals(896f / 70f, r.activeHHi, 0.01f)   // 12.8
        assertEquals(896f / 180f * 24f, r.wallHLo, 0.01f)  // 119.47
        assertEquals(896f / 100f * 24f, r.wallHHi, 0.01f)  // 215.04
    }

    @Test fun nullWhenCharging() {
        assertNull(estimatePackRange(BatteryState.Charging, 70f, params, null))
    }

    @Test fun nullWhenRemainingUnknown() {
        assertNull(estimatePackRange(BatteryState.Idle, 0f, params, null))
    }

    @Test fun idleStillEstimates() {
        val r = estimatePackRange(BatteryState.Idle, 70f, params, null)
        assertEquals(896f / 24f, r!!.milesLo, 0.01f)
    }

    @Test fun tiltShiftsCenterKeepsWidth() {
        // band 100–180 (mid 140, half-width 40); today: 90 Wh over 3 dis-hours, 12 h into the day.
        // w = min(0.5, 3/4 × 0.5) = 0.375; todayWhPerDay = 90 × 24/12 = 180.
        // mid' = 140×0.625 + 180×0.375 = 155 → band 115–195.
        val t = tiltedBand(Band(100f, 180f), todayRate = 180f, w = 0.375f)
        assertEquals(115f, t.lo, 0.01f)
        assertEquals(195f, t.hi, 0.01f)
    }

    @Test fun tiltAppliedThroughEstimate() {
        // Same tilt as above lowers wall-clock time: hi = 896/115×24 = 186.99, lo = 896/195×24 = 110.28.
        val today = TodayUsage(disWh = 90f, disHours = 3f, hoursSinceMidnight = 12f)
        val r = estimatePackRange(BatteryState.Discharging, 70f, params, today)!!
        assertEquals(896f / 195f * 24f, r.wallHLo, 0.05f)
        assertEquals(896f / 115f * 24f, r.wallHHi, 0.05f)
        // whPerMile is never tilted.
        assertEquals(896f / 24f, r.milesLo, 0.01f)
    }

    @Test fun noTiltUnderQuarterHourDischarge() {
        val today = TodayUsage(disWh = 5f, disHours = 0.1f, hoursSinceMidnight = 2f)
        val r = estimatePackRange(BatteryState.Discharging, 70f, params, today)!!
        assertEquals(896f / 180f * 24f, r.wallHLo, 0.01f)  // untouched history band
    }

    @Test fun minAcrossPacksTakesWorstPerFigure() {
        val a = PackRange(37f, 50f, 9f, 13f, 119f, 215f)
        val b = PackRange(40f, 45f, 8f, 14f, 125f, 200f)
        val m = minRange(listOf(a, b))
        assertEquals(37f, m.milesLo, 0f); assertEquals(45f, m.milesHi, 0f)
        assertEquals(8f, m.activeHLo, 0f); assertEquals(13f, m.activeHHi, 0f)
        assertEquals(119f, m.wallHLo, 0f); assertEquals(200f, m.wallHHi, 0f)
    }

    @Test fun formatsWholeMilesHoursAndDays() {
        assertEquals("~37–50 mi · ~9–13h use · ~5–9 days",
            formatRangeLine(PackRange(37.33f, 49.78f, 8.96f, 12.8f, 119.47f, 215.04f)))
    }

    @Test fun formatsDecimalMilesWhenLow() {
        assertEquals("~1.5–2.4 mi · ~0–1h use · ~34–42h",
            formatRangeLine(PackRange(1.5f, 2.4f, 0.4f, 0.6f, 34.2f, 42.1f)))
    }

    @Test fun seedParamsMatchSpec() {
        assertEquals(78f, SEED_RANGE_PARAMS.whPerDay.lo, 0f)
        assertEquals(182f, SEED_RANGE_PARAMS.whPerDay.hi, 0f)
        assertEquals(52.5f, SEED_RANGE_PARAMS.activeW.lo, 0f)
        assertEquals(97.5f, SEED_RANGE_PARAMS.activeW.hi, 0f)
        assertEquals(15f, SEED_RANGE_PARAMS.whPerMile.lo, 0f)
        assertEquals(25f, SEED_RANGE_PARAMS.whPerMile.hi, 0f)
        assertEquals(0, SEED_RANGE_PARAMS.learnedDays)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/joely/bmsmon/android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.RangeEstimateTest"`
Expected: FAIL to compile — `Unresolved reference: RangeParams` etc.

- [ ] **Step 3: Write the implementation**

```kotlin
package dev.joely.bmsmon.model

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Discharge-remaining estimate (miles + active-use hours + wall-clock time to empty), high/low
 * bands learned from real usage. Design: docs/superpowers/specs/2026-07-11-discharge-estimate-design.md
 * web/src/range.ts is a line-for-line TypeScript twin — keep the math identical.
 */

const val NOMINAL_PACK_V = 12.8f

/** Max live-tilt weight — history always anchors at least half the band's center. */
const val TILT_MAX_W = 0.5f

/** Today's discharge hours at which the tilt weight saturates. */
const val TILT_FULL_HOURS = 4f

/** Minimum discharge time today before any tilt applies (a 10-min burst must not whipsaw it). */
const val TILT_MIN_HOURS = 0.25f

data class Band(val lo: Float, val hi: Float) {
    val mid: Float get() = (lo + hi) / 2f
}

/** Learned per-pack parameter bands (p20/p80 across qualifying days, else the seeds). */
data class RangeParams(
    val whPerDay: Band,
    val activeW: Band,
    val whPerMile: Band,
    val learnedDays: Int,
    val updatedMs: Long,
)

/** Cold-start bands from the 2026-07 real-data investigation (2 weeks, 2012 daily driver). */
val SEED_RANGE_PARAMS = RangeParams(
    whPerDay = Band(78f, 182f),      // 130 Wh/day ±40%
    activeW = Band(52.5f, 97.5f),    // 75 W ±30%
    whPerMile = Band(15f, 25f),      // 20 Wh/mi ±25%
    learnedDays = 0,
    updatedMs = 0L,
)

/** Today's observed burn since local midnight (computed from Room by the engine). */
data class TodayUsage(val disWh: Float, val disHours: Float, val hoursSinceMidnight: Float)

/** One pack's estimate: miles, active-use hours, wall-clock hours — each as a lo/hi band. */
data class PackRange(
    val milesLo: Float, val milesHi: Float,
    val activeHLo: Float, val activeHHi: Float,
    val wallHLo: Float, val wallHHi: Float,
)

/** Shift the band's center toward [todayRate] by weight [w]; the width stays from history. */
fun tiltedBand(band: Band, todayRate: Float?, w: Float): Band {
    if (todayRate == null || w <= 0f || !todayRate.isFinite()) return band
    val half = (band.hi - band.lo) / 2f
    val mid = (1f - w) * band.mid + w * todayRate
    return Band((mid - half).coerceAtLeast(0.01f), mid + half)
}

/**
 * Per-pack discharge estimate, or null when it doesn't apply: charging (the recharge ETA owns
 * that display slot) or no usable remaining capacity. Idle packs still estimate — "how far can
 * I go" is meaningful while parked.
 */
fun estimatePackRange(
    state: BatteryState,
    remainingAh: Float,
    params: RangeParams,
    today: TodayUsage?,
): PackRange? {
    if (state == BatteryState.Charging) return null
    if (remainingAh <= 0f || !remainingAh.isFinite()) return null
    val remWh = remainingAh * NOMINAL_PACK_V

    // Live tilt (Android only; the web twin passes today = null): weight grows with how much
    // discharge actually happened today, so a heavy morning slides the band without whipsaw.
    var whPerDay = params.whPerDay
    var activeW = params.activeW
    if (today != null && today.disHours >= TILT_MIN_HOURS && today.hoursSinceMidnight >= 1f) {
        val w = minOf(TILT_MAX_W, today.disHours / TILT_FULL_HOURS * TILT_MAX_W)
        whPerDay = tiltedBand(whPerDay, today.disWh * 24f / today.hoursSinceMidnight, w)
        activeW = tiltedBand(activeW, today.disWh / today.disHours, w)
    }

    return PackRange(
        milesLo = remWh / params.whPerMile.hi, milesHi = remWh / params.whPerMile.lo,
        activeHLo = remWh / activeW.hi, activeHHi = remWh / activeW.lo,
        wallHLo = remWh / whPerDay.hi * 24f, wallHHi = remWh / whPerDay.lo * 24f,
    )
}

/** Base-level readout: the weaker pack bounds each figure (series pair — it ends the trip). */
fun minRange(ranges: List<PackRange>): PackRange = ranges.reduce { a, b ->
    PackRange(
        minOf(a.milesLo, b.milesLo), minOf(a.milesHi, b.milesHi),
        minOf(a.activeHLo, b.activeHLo), minOf(a.activeHHi, b.activeHHi),
        minOf(a.wallHLo, b.wallHLo), minOf(a.wallHHi, b.wallHHi),
    )
}

/** "~37–50 mi · ~9–13h use · ~5–9 days" (days when the low bound exceeds 48 h, else hours). */
fun formatRangeLine(r: PackRange): String {
    val miles = if (r.milesHi < 10f) {
        "~${fmt1(r.milesLo)}–${fmt1(r.milesHi)} mi"
    } else {
        "~${r.milesLo.roundToInt()}–${r.milesHi.roundToInt()} mi"
    }
    val use = "~${r.activeHLo.roundToInt()}–${r.activeHHi.roundToInt()}h use"
    val wall = if (r.wallHLo > 48f) {
        "~${(r.wallHLo / 24f).roundToInt()}–${(r.wallHHi / 24f).roundToInt()} days"
    } else {
        "~${r.wallHLo.roundToInt()}–${r.wallHHi.roundToInt()}h"
    }
    return "$miles · $use · $wall"
}

private fun fmt1(v: Float) = String.format(Locale.US, "%.1f", v)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /home/joely/bmsmon/android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.RangeEstimateTest"`
Expected: PASS (11 tests)

- [ ] **Step 5: Commit**

```bash
cd /home/joely/bmsmon
git add android/app/src/main/java/dev/joely/bmsmon/model/RangeEstimate.kt \
        android/app/src/test/java/dev/joely/bmsmon/RangeEstimateTest.kt
git commit -m "feat(android): pure discharge-range estimate formula with live tilt"
```

---

### Task 2: Pure learner (`model/RangeLearn.kt`)

**Files:**
- Create: `android/app/src/main/java/dev/joely/bmsmon/model/RangeLearn.kt`
- Test: `android/app/src/test/java/dev/joely/bmsmon/RangeLearnTest.kt`

**Interfaces:**
- Consumes: `Band`, `RangeParams`, `SEED_RANGE_PARAMS`, `TodayUsage` (Task 1).
- Produces (used by Task 4):
  - `data class RangeRow(val tsMs: Long, val state: String?, val powerW: Float?, val lat: Double?, val lon: Double?, val gpsAccuracyM: Float?, val regen: Boolean)`
  - `fun learnRangeParams(rows: List<RangeRow>, zone: java.time.ZoneId, nowMs: Long): RangeParams`
  - `fun todayUsage(rows: List<RangeRow>, zone: java.time.ZoneId, nowMs: Long): TodayUsage`
  - `fun percentile(sortedValues: List<Float>, p: Float): Float`

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.joely.bmsmon

import dev.joely.bmsmon.model.RangeRow
import dev.joely.bmsmon.model.SEED_RANGE_PARAMS
import dev.joely.bmsmon.model.learnRangeParams
import dev.joely.bmsmon.model.percentile
import dev.joely.bmsmon.model.todayUsage
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class RangeLearnTest {
    private val zone = ZoneId.of("UTC")

    private fun ts(day: Int, hour: Int, min: Int = 0, sec: Int = 0): Long =
        ZonedDateTime.of(2026, 7, day, hour, min, sec, 0, zone).toInstant().toEpochMilli()

    /** [hours] of samples every 30 s starting [startHour] on [day], discharging at [powerW]. */
    private fun dischargeDay(day: Int, startHour: Int, hours: Float, powerW: Float): List<RangeRow> {
        val n = (hours * 3600 / 30).toInt()
        return (0..n).map { i ->
            RangeRow(ts(day, startHour) + i * 30_000L, "Discharging", powerW,
                lat = null, lon = null, gpsAccuracyM = null, regen = false)
        }
    }

    /** Idle filler so the day passes the 12 h coverage bar (samples every 30 s). */
    private fun idleFiller(day: Int, startHour: Int, hours: Int): List<RangeRow> {
        val n = hours * 3600 / 30
        return (0..n).map { i ->
            RangeRow(ts(day, startHour) + i * 30_000L, "Idle", 0f, null, null, null, regen = false)
        }
    }

    @Test fun percentileInterpolatesLinearly() {
        val v = listOf(100f, 120f, 140f, 160f, 180f)
        assertEquals(116f, percentile(v, 0.2f), 0.01f)  // idx 0.8 → 100 + 0.8×20
        assertEquals(164f, percentile(v, 0.8f), 0.01f)  // idx 3.2 → 160 + 0.2×20
        assertEquals(100f, percentile(v, 0f), 0f)
        assertEquals(180f, percentile(v, 1f), 0f)
    }

    @Test fun learnsWhPerDayBandFromQualifyingDays() {
        // 4 qualifying days (13 h coverage each): 2 h discharge at 50/60/80/100 W
        // → 100/120/160/200 Wh/day. p20 = 112, p80 = 176 (linear interp, idx 0.6/2.4).
        val rows = (1..4).flatMap { d ->
            val w = listOf(50f, 60f, 80f, 100f)[d - 1]
            dischargeDay(d, 8, 2f, w) + idleFiller(d, 10, 11)
        }
        val p = learnRangeParams(rows, zone, nowMs = ts(5, 0))
        assertEquals(112f, p.whPerDay.lo, 1f)
        assertEquals(176f, p.whPerDay.hi, 1f)
        assertEquals(4, p.learnedDays)
        // activeW: day means are exactly 50/60/80/100 → p20 = 56, p80 = 88.
        assertEquals(56f, p.activeW.lo, 1f)
        assertEquals(88f, p.activeW.hi, 1f)
    }

    @Test fun fewerThanThreeQualifyingDaysFallsBackToSeeds() {
        val rows = dischargeDay(1, 8, 2f, 80f) + idleFiller(1, 10, 11) +
            dischargeDay(2, 8, 2f, 90f) + idleFiller(2, 10, 11)
        val p = learnRangeParams(rows, zone, nowMs = ts(3, 0))
        assertEquals(SEED_RANGE_PARAMS.whPerDay.lo, p.whPerDay.lo, 0f)
        assertEquals(SEED_RANGE_PARAMS.whPerDay.hi, p.whPerDay.hi, 0f)
        assertEquals(2, p.learnedDays)
    }

    @Test fun lowCoverageDayDoesNotQualify() {
        // Day with only 2 h of samples (< 12 h coverage) must be excluded entirely.
        val good = (1..3).flatMap { d -> dischargeDay(d, 8, 2f, 80f) + idleFiller(d, 10, 11) }
        val sparse = dischargeDay(4, 8, 2f, 500f)  // huge burn but only 2 h coverage
        val p = learnRangeParams(good + sparse, zone, nowMs = ts(5, 0))
        // All qualifying days burned 160 Wh → a degenerate flat band stays near 160, not 1000.
        assertEquals(160f, p.whPerDay.mid, 5f)
    }

    @Test fun whPerMileFromQualifiedDriveSegments() {
        // 3 days, each: 12+ h coverage, plus a 100-sample drive at 2 m/s (10 s apart, 20 m
        // steps) at 72 W discharging with 10 m accuracy. Per segment: dWh = 72×10/3600 = 0.2 Wh,
        // d = 20 m. Day total: 20 Wh, 2000 m = 1.2427 mi → 16.09 Wh/mi (same all days → flat band).
        fun drive(day: Int): List<RangeRow> = (0..100).map { i ->
            RangeRow(ts(day, 9) + i * 10_000L, "Discharging", 72f,
                lat = 40.0 + i * 20 / 111_320.0, lon = -75.0, gpsAccuracyM = 10f, regen = false)
        }
        val rows = (1..3).flatMap { d -> drive(d) + idleFiller(d, 10, 13) }  // ascending by tsMs
        val p = learnRangeParams(rows, zone, nowMs = ts(4, 0))
        assertEquals(16.09f, p.whPerMile.mid, 0.3f)
    }

    @Test fun regenAndBadGpsSegmentsExcluded() {
        // Regen rows must not count toward burn; accuracy ≥ 20 m must not count toward miles.
        val burn = (1..3).flatMap { d -> dischargeDay(d, 8, 2f, 80f) + idleFiller(d, 10, 11) }
        val regenRows = (0..10).map { i ->
            // Hour 22 — after the idle filler ends (21:00), keeping the row list ascending.
            RangeRow(ts(1, 22) + i * 10_000L, "Discharging", 300f, null, null, null, regen = true)
        }
        val p = learnRangeParams(burn + regenRows, zone, nowMs = ts(4, 0))
        assertEquals(160f, p.whPerDay.mid, 5f)  // regen watts never counted
        // No qualified GPS at all → whPerMile stays seeded.
        assertEquals(SEED_RANGE_PARAMS.whPerMile.lo, p.whPerMile.lo, 0f)
    }

    @Test fun todayUsageSumsSinceLocalMidnight() {
        val rows = dischargeDay(1, 23, 1f, 100f) +   // yesterday — excluded
            dischargeDay(2, 8, 2f, 100f)             // today: 200 Wh over 2 h
        val t = todayUsage(rows, zone, nowMs = ts(2, 12))
        assertEquals(200f, t.disWh, 2f)
        assertEquals(2f, t.disHours, 0.05f)
        assertEquals(12f, t.hoursSinceMidnight, 0.01f)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/joely/bmsmon/android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.RangeLearnTest"`
Expected: FAIL to compile — `Unresolved reference: RangeRow`

- [ ] **Step 3: Write the implementation**

```kotlin
package dev.joely.bmsmon.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Learns the per-pack discharge-range parameter bands from the local 14-day sample history.
 * Pure — the engine maps Room rows to [RangeRow] and calls this off the poll path.
 * Design: docs/superpowers/specs/2026-07-11-discharge-estimate-design.md
 */

/** One telemetry row, pre-filtered to linkEvent == null (recentSamples does that). */
data class RangeRow(
    val tsMs: Long,
    val state: String?,
    val powerW: Float?,
    val lat: Double?,
    val lon: Double?,
    val gpsAccuracyM: Float?,
    val regen: Boolean,
)

/** A day must have this much sample coverage to teach daily-burn stats. */
private const val MIN_DAY_COVERAGE_S = 12f * 3600f

/** Minimum discharge time for a day to teach the active-draw mean. */
private const val MIN_DAY_DIS_H = 0.25f

/** Minimum qualified drive distance for a day to teach Wh/mile. */
private const val MIN_DAY_DRIVE_M = 80.4672f  // 0.05 mi

/** Days of history needed before a learned band replaces its seed. */
private const val MIN_LEARN_DAYS = 3

// Burn-stat gap bounds (s): a gap larger than a poll hiccup teaches nothing.
private const val BURN_DT_MIN_S = 0.5f
private const val BURN_DT_MAX_S = 60f

// Qualified drive segment bounds (see the spec's data findings on GPS jitter).
private const val DRIVE_DT_MIN_S = 0.5f
private const val DRIVE_DT_MAX_S = 15f
private const val DRIVE_MIN_POWER_W = 40f
private const val DRIVE_MAX_ACCURACY_M = 20f
private const val DRIVE_MIN_SPEED_MPS = 0.4f
private const val DRIVE_MAX_SPEED_MPS = 4.0f

private const val METERS_PER_MILE = 1609.34f
private const val METERS_PER_DEG = 111_320.0

/** Linear-interpolated percentile of pre-sorted [sortedValues]; [p] in 0..1. */
fun percentile(sortedValues: List<Float>, p: Float): Float {
    if (sortedValues.isEmpty()) return 0f
    val idx = p.coerceIn(0f, 1f) * (sortedValues.size - 1)
    val lo = idx.toInt()
    val hi = minOf(lo + 1, sortedValues.size - 1)
    val frac = idx - lo
    return sortedValues[lo] + frac * (sortedValues[hi] - sortedValues[lo])
}

private data class DayStats(
    var coverageS: Float = 0f,
    var disWh: Float = 0f,
    var disS: Float = 0f,
    var driveWh: Float = 0f,
    var driveM: Float = 0f,
)

/** Equirectangular distance in meters between two fixes — fine at wheelchair scale. */
private fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
    val dy = (lat2 - lat1) * METERS_PER_DEG
    val dx = (lon2 - lon1) * METERS_PER_DEG * cos(Math.toRadians((lat1 + lat2) / 2))
    return sqrt(dx * dx + dy * dy).toFloat()
}

private fun accumulate(rows: List<RangeRow>, zone: ZoneId): Map<LocalDate, DayStats> {
    val days = HashMap<LocalDate, DayStats>()
    for (i in 1 until rows.size) {
        val prev = rows[i - 1]
        val cur = rows[i]
        val dt = (cur.tsMs - prev.tsMs) / 1000f
        if (dt < BURN_DT_MIN_S || dt > BURN_DT_MAX_S) continue
        val day = Instant.ofEpochMilli(cur.tsMs).atZone(zone).toLocalDate()
        val s = days.getOrPut(day) { DayStats() }
        s.coverageS += dt
        val p = cur.powerW
        if (cur.state == "Discharging" && !cur.regen && p != null && p.isFinite() && p > 0f) {
            s.disWh += p * dt / 3600f
            s.disS += dt
            // Qualified drive segment: tight GPS on both fixes, wheelchair-speed movement,
            // real draw. Anything else is indoor jitter — it must not teach Wh/mile.
            if (dt <= DRIVE_DT_MAX_S && p > DRIVE_MIN_POWER_W &&
                cur.lat != null && cur.lon != null && prev.lat != null && prev.lon != null &&
                (cur.gpsAccuracyM ?: Float.MAX_VALUE) < DRIVE_MAX_ACCURACY_M &&
                (prev.gpsAccuracyM ?: Float.MAX_VALUE) < DRIVE_MAX_ACCURACY_M
            ) {
                val d = distanceM(prev.lat, prev.lon, cur.lat, cur.lon)
                val speed = d / dt
                if (speed in DRIVE_MIN_SPEED_MPS..DRIVE_MAX_SPEED_MPS) {
                    s.driveWh += p * dt / 3600f
                    s.driveM += d
                }
            }
        }
    }
    return days
}

/** p20/p80 band across per-day values, or [seed] with fewer than [MIN_LEARN_DAYS] days. */
private fun bandOf(values: List<Float>, seed: Band): Band {
    if (values.size < MIN_LEARN_DAYS) return seed
    val sorted = values.sorted()
    val lo = percentile(sorted, 0.2f).coerceAtLeast(0.01f)
    val hi = percentile(sorted, 0.8f)
    // A degenerate flat band (identical days) still needs width for an honest hi/lo readout.
    return if (hi - lo < lo * 0.1f) Band(lo * 0.95f, hi * 1.05f) else Band(lo, hi)
}

/** Distill [rows] (14-day window, ascending, one pack) into learned parameter bands. */
fun learnRangeParams(rows: List<RangeRow>, zone: ZoneId, nowMs: Long): RangeParams {
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    // Today is still accumulating — a half day would bias every per-day statistic low.
    val days = accumulate(rows, zone).filterKeys { it != today }
    val qualifying = days.values.filter { it.coverageS >= MIN_DAY_COVERAGE_S }
    val whPerDay = qualifying.map { it.disWh }
    val activeW = qualifying.filter { it.disS / 3600f >= MIN_DAY_DIS_H }
        .map { it.disWh / (it.disS / 3600f) }
    val whPerMile = days.values.filter { it.driveM >= MIN_DAY_DRIVE_M }
        .map { it.driveWh / (it.driveM / METERS_PER_MILE) }
    return RangeParams(
        whPerDay = bandOf(whPerDay, SEED_RANGE_PARAMS.whPerDay),
        activeW = bandOf(activeW, SEED_RANGE_PARAMS.activeW),
        whPerMile = bandOf(whPerMile, SEED_RANGE_PARAMS.whPerMile),
        learnedDays = whPerDay.size,
        updatedMs = nowMs,
    )
}

/** Today's burn so far (since local midnight) — the live-tilt input. */
fun todayUsage(rows: List<RangeRow>, zone: ZoneId, nowMs: Long): TodayUsage {
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val stats = accumulate(rows, zone)[today] ?: DayStats()
    val midnight = today.atStartOfDay(zone).toInstant().toEpochMilli()
    return TodayUsage(
        disWh = stats.disWh,
        disHours = stats.disS / 3600f,
        hoursSinceMidnight = (nowMs - midnight) / 3_600_000f,
    )
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /home/joely/bmsmon/android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.RangeLearnTest"`
Expected: PASS (7 tests)

- [ ] **Step 5: Commit**

```bash
cd /home/joely/bmsmon
git add android/app/src/main/java/dev/joely/bmsmon/model/RangeLearn.kt \
        android/app/src/test/java/dev/joely/bmsmon/RangeLearnTest.kt
git commit -m "feat(android): range learner — per-day burn/draw/Wh-per-mile bands from Room history"
```

---

### Task 3: Store GPS in the local Room DB (migration v3→v4)

**Files:**
- Modify: `android/app/src/main/java/dev/joely/bmsmon/data/db/SampleEntity.kt`
- Modify: `android/app/src/main/java/dev/joely/bmsmon/data/db/BmsDatabase.kt`
- Modify: `android/app/src/main/java/dev/joely/bmsmon/data/TelemetryRepository.kt`
- Modify: `android/app/src/main/java/dev/joely/bmsmon/monitor/MonitorEngine.kt` (onPoll only)
- Create (generated): `android/app/schemas/dev.joely.bmsmon.data.db.BmsDatabase/4.json` (KSP emits it on build — commit it)

**Interfaces:**
- Consumes: `GpsFix(lat: Double, lon: Double, accuracyM: Float?)` from `location/LocationSource.kt`.
- Produces: `SampleEntity` gains `lat: Double? = null`, `lon: Double? = null`, `gpsAccuracyM: Float? = null`; `TelemetryRepository.ingest(...)` gains a trailing `fix: GpsFix? = null` parameter. Task 4 reads these columns via the existing `recentSamples()`.

- [ ] **Step 1: Add the three nullable columns to SampleEntity**

In `SampleEntity.kt`, after `val regen: Boolean,` and before `val linkEvent: String?,` add:

```kotlin
    // GPS fix at sample time (nullable; null on link rows, pre-v4 rows, and GPS-off samples).
    // Stored locally so the range learner can compute Wh/mile offline — previously GPS only
    // rode the cloud outbox (see the 2026-07-11 discharge-estimate design).
    val lat: Double? = null,
    val lon: Double? = null,
    val gpsAccuracyM: Float? = null,
```

- [ ] **Step 2: Bump the DB version and add the migration**

In `BmsDatabase.kt`: change `version = 3` to `version = 4`. In the companion, after `MIGRATION_2_3` add:

```kotlin
        // Discharge-estimate feature: persist the GPS fix locally (it previously only rode the
        // cloud outbox) so Wh/mile can be learned on-phone, offline. Nullable — no backfill.
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE samples ADD COLUMN lat REAL")
                db.execSQL("ALTER TABLE samples ADD COLUMN lon REAL")
                db.execSQL("ALTER TABLE samples ADD COLUMN gpsAccuracyM REAL")
            }
        }
```

and change the builder line to `.addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)`.

- [ ] **Step 3: Carry the fix through ingest**

In `TelemetryRepository.kt`:
- Add import: `import dev.joely.bmsmon.location.GpsFix`
- Change the `ingest` signature to:

```kotlin
    fun ingest(address: String, t: Telemetry, raw: ByteArray, reason: String, regen: Boolean, tsMs: Long, fix: GpsFix? = null) {
```

and its `sampleFrom` call to `db.samples().insert(sampleFrom(address, t, sessionId, regen, tsMs, fix))`.
- Change `sampleFrom` to:

```kotlin
    private fun sampleFrom(address: String, t: Telemetry, sessionId: Long, regen: Boolean, tsMs: Long, fix: GpsFix?) =
        SampleEntity(
            address = address, tsMs = tsMs, sessionId = sessionId, state = t.state.name,
            soc = t.soc, currentA = t.current, powerW = t.powerW, voltageV = t.voltage,
            tempC = t.temp, mosfetTempC = t.mosfetTemp, soh = t.soh, fullChargeAh = t.fullChargeAh,
            remainingAh = t.capacityAh, cycles = t.cycles,
            cellMinV = t.cells.minOrNull(), cellMaxV = t.cells.maxOrNull() ?: t.cellV,
            regen = regen, lat = fix?.lat, lon = fix?.lon, gpsAccuracyM = fix?.accuracyM,
            linkEvent = null,
        )
```

- [ ] **Step 4: Pass the same fix the uploader gets**

In `MonitorEngine.kt` `onPoll()`: the `val fix = ...` line already exists just above `reporter?.report(...)`. Change the logging block below it to pass it through:

```kotlin
        if (logging) {
            val header = (ProfileRegistry.profileFor(roster.batteryAt(addr)?.advertisedName)
                ?: RedodoBekenProfile).responseHeader
            repository.ingest(addr, t, raw, classifyFrame(raw, parsedOk = true, header), regen, now, fix)
        }
```

- [ ] **Step 5: Build + run all unit tests**

Run: `cd /home/joely/bmsmon/android && ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all existing tests pass; `app/schemas/dev.joely.bmsmon.data.db.BmsDatabase/4.json` now exists.

- [ ] **Step 6: Commit**

```bash
cd /home/joely/bmsmon
git add android/app/src/main/java/dev/joely/bmsmon/data/db/SampleEntity.kt \
        android/app/src/main/java/dev/joely/bmsmon/data/db/BmsDatabase.kt \
        android/app/src/main/java/dev/joely/bmsmon/data/TelemetryRepository.kt \
        android/app/src/main/java/dev/joely/bmsmon/monitor/MonitorEngine.kt \
        "android/app/schemas/dev.joely.bmsmon.data.db.BmsDatabase/4.json"
git commit -m "feat(android): store GPS fix in local Room samples (db v4) for on-phone Wh/mile learning"
```

---

### Task 4: Engine learning loop + per-poll estimate + persistence

**Files:**
- Modify: `android/app/src/main/java/dev/joely/bmsmon/monitor/MonitorEngine.kt`
- Modify: `android/app/src/main/java/dev/joely/bmsmon/model/Fleet.kt` (`BatteryStatus`)
- Modify: `android/app/src/main/java/dev/joely/bmsmon/data/SettingsStore.kt`

**Interfaces:**
- Consumes: `learnRangeParams`, `todayUsage`, `RangeRow` (Task 2); `estimatePackRange`, `RangeParams`, `SEED_RANGE_PARAMS`, `Band`, `TodayUsage`, `PackRange` (Task 1); `SampleEntity.lat/lon/gpsAccuracyM` (Task 3).
- Produces (used by Tasks 5, 6):
  - `MonitorState.rangeParamsByAddress: Map<String, RangeParams>` and `MonitorState.todayUsageByAddress: Map<String, TodayUsage>`
  - `BatteryStatus.range: PackRange?` (computed once per poll, like `etaFullMin`)
  - `Persisted.rangeParamsByAddress: Map<String, RangeParams>`; `SettingsStore.setRangeParams(map: Map<String, RangeParams>)`

- [ ] **Step 1: Extend BatteryStatus (Fleet.kt)**

```kotlin
data class BatteryStatus(
    val telemetry: Telemetry? = null,
    val reachable: Boolean = false,
    val etaFullMin: Float? = null,
    /** Discharge-remaining estimate for the latest sample — engine-computed once per poll,
     *  same single-writer pattern as [etaFullMin]. Null while charging or with no capacity. */
    val range: PackRange? = null,
)
```

- [ ] **Step 2: Persist learned params (SettingsStore.kt)**

Add to `Persisted`: `val rangeParamsByAddress: Map<String, RangeParams> = emptyMap(),` (import `dev.joely.bmsmon.model.RangeParams` and `dev.joely.bmsmon.model.Band`). Add key `val RANGE_PARAMS = stringPreferencesKey("range_params_by_address")` to `K`, decode line `rangeParamsByAddress = p[K.RANGE_PARAMS]?.let(::decodeRangeParams) ?: emptyMap(),`, setter:

```kotlin
    suspend fun setRangeParams(map: Map<String, RangeParams>) =
        context.dataStore.edit { it[K.RANGE_PARAMS] = encodeRangeParams(map) }.let {}
```

File-level codec functions (bottom of file, next to the chargeTail codec):

```kotlin
/** Per-pack learned range bands (address -> the six band edges + provenance). */
private fun encodeRangeParams(map: Map<String, RangeParams>): String {
    val root = JSONObject()
    map.forEach { (addr, r) ->
        root.put(addr, JSONObject()
            .put("wdLo", r.whPerDay.lo.jsonSafe()).put("wdHi", r.whPerDay.hi.jsonSafe())
            .put("awLo", r.activeW.lo.jsonSafe()).put("awHi", r.activeW.hi.jsonSafe())
            .put("wmLo", r.whPerMile.lo.jsonSafe()).put("wmHi", r.whPerMile.hi.jsonSafe())
            .put("days", r.learnedDays).put("ts", r.updatedMs))
    }
    return root.toString()
}

private fun decodeRangeParams(json: String): Map<String, RangeParams> = runCatching {
    val root = JSONObject(json)
    buildMap {
        root.keys().forEach { addr ->
            val o = root.getJSONObject(addr)
            put(addr, RangeParams(
                whPerDay = Band(o.getDouble("wdLo").toFloat(), o.getDouble("wdHi").toFloat()),
                activeW = Band(o.getDouble("awLo").toFloat(), o.getDouble("awHi").toFloat()),
                whPerMile = Band(o.getDouble("wmLo").toFloat(), o.getDouble("wmHi").toFloat()),
                learnedDays = o.optInt("days", 0),
                updatedMs = o.optLong("ts", 0L),
            ))
        }
    }
}.getOrDefault(emptyMap())
```

- [ ] **Step 3: Engine — state fields, loop, per-poll estimate (MonitorEngine.kt)**

Imports to add:

```kotlin
import dev.joely.bmsmon.model.PackRange
import dev.joely.bmsmon.model.RangeParams
import dev.joely.bmsmon.model.RangeRow
import dev.joely.bmsmon.model.SEED_RANGE_PARAMS
import dev.joely.bmsmon.model.TodayUsage
import dev.joely.bmsmon.model.estimatePackRange
import dev.joely.bmsmon.model.learnRangeParams
import dev.joely.bmsmon.model.todayUsage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
```

`MonitorState` gains two fields (after `tailMinByAddress`):

```kotlin
    val rangeParamsByAddress: Map<String, RangeParams> = emptyMap(),
    val todayUsageByAddress: Map<String, TodayUsage> = emptyMap(),
```

Class fields (near `lastTailLearnAt`):

```kotlin
    private var rangeJob: Job? = null
    @Volatile private var lastRangeLearnAt = 0L
```

In `start()`: inside the existing `scope.launch { runCatching { ... } }` that loads `chargeTailMinByAddress`, extend the block to also load saved range params, then start the loop after `registerBtReceiver()`:

```kotlin
        scope.launch {
            runCatching {
                val saved = settings.load()
                if (saved.chargeTailMinByAddress.isNotEmpty())
                    _state.update { it.copy(tailMinByAddress = saved.chargeTailMinByAddress) }
                if (saved.rangeParamsByAddress.isNotEmpty())
                    _state.update { it.copy(rangeParamsByAddress = saved.rangeParamsByAddress) }
            }
        }
        startRangeLoop()
```

(Replace the whole existing tail-load `scope.launch` block with the above — same behavior plus the range load.)

In `stop()`: add `rangeJob?.cancel(); rangeJob = null` next to `unregisterBtReceiver()`, and preserve the learned params across the state reset by adding to the rebuilt `MonitorState(...)`:

```kotlin
                rangeParamsByAddress = st.rangeParamsByAddress,
```

New members (place after `learnTail`):

```kotlin
    /** Cadence of the range pass: today-usage refresh every pass, full re-learn every 6 h. */
    private fun startRangeLoop() {
        rangeJob?.cancel()
        rangeJob = scope.launch {
            while (isActive) {
                runCatching { rangePass() }
                delay(5 * 60_000L)
            }
        }
    }

    private suspend fun rangePass() {
        val now = now()
        val zone = java.time.ZoneId.systemDefault()
        val learn = now - lastRangeLearnAt > 6 * 60 * 60_000L
        if (learn) lastRangeLearnAt = now
        for (b in roster.batteries) {
            val addr = b.address
            val rows = repository.recentSamples(addr, now - 14L * 86_400_000L).map {
                RangeRow(it.tsMs, it.state, it.powerW, it.lat, it.lon, it.gpsAccuracyM, it.regen)
            }
            if (rows.isEmpty()) continue
            if (learn) {
                val params = learnRangeParams(rows, zone, now)
                _state.update { it.copy(rangeParamsByAddress = it.rangeParamsByAddress + (addr to params)) }
            }
            val today = todayUsage(rows, zone, now)
            _state.update { it.copy(todayUsageByAddress = it.todayUsageByAddress + (addr to today)) }
        }
        if (learn) runCatching { settings.setRangeParams(_state.value.rangeParamsByAddress) }
    }
```

In `onPoll()`, right after the `etaFullMin` computation, add:

```kotlin
        // Discharge-range estimate — same single-writer pattern as the charge ETA: computed once
        // per poll here, carried on BatteryStatus, only displayed by the UI.
        val range = estimatePackRange(
            t.state, t.capacityAh,
            st0.rangeParamsByAddress[addr] ?: SEED_RANGE_PARAMS,
            st0.todayUsageByAddress[addr],
        )
```

and extend the fleet update copy to carry it:

```kotlin
            val fleet = st.fleet + (addr to (st.fleet[addr] ?: BatteryStatus())
                .copy(telemetry = t, reachable = true, etaFullMin = etaFullMin, range = range))
```

Also trigger an immediate first pass on restore: `restoreFromPersisted()` needs no change (it calls `start()`, which starts the loop; the loop's first iteration runs immediately and `lastRangeLearnAt == 0` forces a learn).

- [ ] **Step 4: Build + run all unit tests**

Run: `cd /home/joely/bmsmon/android && ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 5: Commit**

```bash
cd /home/joely/bmsmon
git add android/app/src/main/java/dev/joely/bmsmon/monitor/MonitorEngine.kt \
        android/app/src/main/java/dev/joely/bmsmon/model/Fleet.kt \
        android/app/src/main/java/dev/joely/bmsmon/data/SettingsStore.kt
git commit -m "feat(android): engine range-learning loop + per-poll discharge estimate on BatteryStatus"
```

---

### Task 5: Android stage UI — base-level range line

**Files:**
- Modify: `android/app/src/main/java/dev/joely/bmsmon/model/Fleet.kt` (`StageItem` + pure line selector)
- Modify: `android/app/src/main/java/dev/joely/bmsmon/BatteryViewModel.kt` (`stageItems()`)
- Modify: `android/app/src/main/java/dev/joely/bmsmon/ui/home/StageScreen.kt`
- Test: `android/app/src/test/java/dev/joely/bmsmon/StageRangeLineTest.kt`

**Interfaces:**
- Consumes: `PackRange`, `minRange`, `formatRangeLine` (Task 1); `BatteryStatus.range` (Task 4).
- Produces: `StageItem.range: PackRange?`; `fun stageRangeLine(items: List<StageItem>): String?` in Fleet.kt.

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.joely.bmsmon

import dev.joely.bmsmon.model.PackRange
import dev.joely.bmsmon.model.StageItem
import dev.joely.bmsmon.model.Telemetry
import dev.joely.bmsmon.model.stageRangeLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StageRangeLineTest {
    // Positional through temp: name, soc, powerW, current, voltage, capacityAh(remaining), cellV, temp.
    private val tel = Telemetry("2024-B", 70f, 50f, -4f, 13.1f, 70f, 3.28f, 30f)
    private val range = PackRange(37.33f, 49.78f, 8.96f, 12.8f, 119.47f, 215.04f)

    @Test fun formatsMinAcrossConnectedPacks() {
        val worse = PackRange(30f, 45f, 8f, 12f, 110f, 200f)
        val line = stageRangeLine(listOf(
            StageItem(tel, regen = false, connected = true, range = range),
            StageItem(tel, regen = false, connected = true, range = worse),
        ))
        assertEquals("~30–45 mi · ~8–12h use · ~5–8 days", line)
    }

    @Test fun nullWhenAnyPackDisconnected() {
        assertNull(stageRangeLine(listOf(
            StageItem(tel, regen = false, connected = true, range = range),
            StageItem(tel, regen = false, connected = false, range = null),
        )))
    }

    @Test fun nullWhenAnyPackHasNoRange() {
        // e.g. one pack charging (its estimate is null) — the recharge ETA owns that slot.
        assertNull(stageRangeLine(listOf(
            StageItem(tel, regen = false, connected = true, range = range),
            StageItem(tel, regen = false, connected = true, range = null),
        )))
    }

    @Test fun nullWhenEmpty() {
        assertNull(stageRangeLine(emptyList()))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/joely/bmsmon/android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.StageRangeLineTest"`
Expected: FAIL to compile — `No value passed for parameter 'range'` / `Unresolved reference: stageRangeLine`

- [ ] **Step 3: Extend StageItem + add the selector (Fleet.kt)**

```kotlin
data class StageItem(
    val telemetry: Telemetry,
    val regen: Boolean,
    val connected: Boolean = true,
    val etaFullMin: Float? = null,
    val range: PackRange? = null,
)

/**
 * The stage's base-level discharge line ("~37–50 mi · ~9–13h use · ~5–9 days"), or null when it
 * doesn't apply: empty stage, any staged pack disconnected (no fake numbers — DISCONNECTED
 * semantics), or any pack without an estimate (charging: the recharge ETA owns the slot).
 * Min across packs: the weaker pack of a series pair ends the trip.
 */
fun stageRangeLine(items: List<StageItem>): String? {
    if (items.isEmpty() || items.any { !it.connected }) return null
    val ranges = items.map { it.range ?: return null }
    return formatRangeLine(minRange(ranges))
}
```

In `BatteryViewModel.stageItems()`, extend the `StageItem(...)` construction:

```kotlin
            val eta = if (connected) status?.etaFullMin else null
            val range = if (connected) status?.range else null
            StageItem(tel, regen = regenFlag, connected = connected, etaFullMin = eta, range = range)
```

- [ ] **Step 4: Render the line (StageScreen.kt)**

Add imports: `import dev.joely.bmsmon.model.stageRangeLine`.

Replace the non-empty branch of `StageScreen` (both layouts) with a version that appends one centered line under the packs:

```kotlin
    Column(modifier.fillMaxSize()) {
        if (items.size > 2) {
            Row(Modifier.weight(1f).fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically) {
                items.forEach { item ->
                    BatteryBlock(item, tempInF, showTempGauge, tempGaugeSide, thresholds, envelope,
                        Modifier.width(320.dp))
                }
            }
        } else {
            // Sit the packs up near the top bar rather than vertically centered in the page.
            Column(Modifier.weight(1f).padding(top = 6.dp)) {
                items.forEach { item ->
                    BatteryBlock(item, tempInF, showTempGauge, tempGaugeSide, thresholds, envelope,
                        Modifier.weight(1f))
                }
            }
        }
        stageRangeLine(items)?.let { line ->
            Text(
                line,
                color = Bm.colors.text2,
                fontFamily = MonoFont,
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp),
            )
        }
    }
```

(The `isEmpty` early-return above this stays untouched.)

- [ ] **Step 5: Run tests + build the debug APK**

Run: `cd /home/joely/bmsmon/android && ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: PASS + BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
cd /home/joely/bmsmon
git add android/app/src/main/java/dev/joely/bmsmon/model/Fleet.kt \
        android/app/src/main/java/dev/joely/bmsmon/BatteryViewModel.kt \
        android/app/src/main/java/dev/joely/bmsmon/ui/home/StageScreen.kt \
        android/app/src/test/java/dev/joely/bmsmon/StageRangeLineTest.kt
git commit -m "feat(android): base-level discharge-remaining line on the main stage"
```

---

### Task 6: Cloud push — learned params ride the config channel

**Files:**
- Modify: `android/app/src/main/java/dev/joely/bmsmon/cloud/CloudJson.kt`
- Modify: `android/app/src/main/java/dev/joely/bmsmon/BatteryViewModel.kt`

**Interfaces:**
- Consumes: `MonitorState.rangeParamsByAddress` (Task 4).
- Produces: `TempConfigJson.ranges: List<RangeConfigJson>? = null`; `encodeTempConfig(..., ranges: Map<String, RangeParams>? = null)`. The server (Task 7) receives per-address rows `{address, wh_per_day_lo/hi, active_w_lo/hi, wh_per_mile_lo/hi, learned_days, updated_at_ms}`.

- [ ] **Step 1: Extend the config body (CloudJson.kt)**

Add import `dev.joely.bmsmon.model.RangeParams`. Add below `TempConfigJson`:

```kotlin
/** One pack's learned discharge-range bands, riding the one-way config push (latest-wins). */
@Serializable
data class RangeConfigJson(
    val address: String,
    val wh_per_day_lo: Float, val wh_per_day_hi: Float,
    val active_w_lo: Float, val active_w_hi: Float,
    val wh_per_mile_lo: Float, val wh_per_mile_hi: Float,
    val learned_days: Int,
    val updated_at_ms: Long,
)
```

Add to `TempConfigJson` (after `alerts_on`):

```kotlin
    // Learned discharge-range parameter bands, one row per pack (2026-07-11 design). Optional —
    // an older app pushing a temp-only body must keep validating server-side.
    val ranges: List<RangeConfigJson>? = null,
```

Change `encodeTempConfig` to accept and pass them:

```kotlin
    fun encodeTempConfig(
        profileId: String, t: TempThresholds, env: TempEnvelope, unit: String, updatedAtMs: Long,
        seizeSoc: Int? = null, alertsOn: Boolean? = null,
        ranges: Map<String, RangeParams>? = null,
    ): String =
        json.encodeToString(
            TempConfigJson.serializer(),
            TempConfigJson(
                profileId, t.coldCautionC, t.hotCautionC, t.coldCritC, t.hotCritC,
                unit, updatedAtMs,
                cutoff_cold_c = env.coldCutoffC, cutoff_hot_c = env.hotCutoffC,
                charge_lock_cold_c = env.chargeLockColdC, charge_lock_hot_c = env.chargeLockHotC,
                charge_resume_cold_c = env.chargeResumeColdC,
                seize_soc = seizeSoc, alerts_on = alertsOn,
                ranges = ranges?.takeIf { it.isNotEmpty() }?.map { (addr, r) ->
                    RangeConfigJson(
                        addr,
                        r.whPerDay.lo, r.whPerDay.hi,
                        r.activeW.lo, r.activeW.hi,
                        r.whPerMile.lo, r.whPerMile.hi,
                        r.learnedDays, r.updatedMs,
                    )
                },
            ),
        )
```

- [ ] **Step 2: Enqueue on params change (BatteryViewModel.kt)**

Add import `dev.joely.bmsmon.model.RangeParams`. Add a VM field near the top of the class (next to other private vars):

```kotlin
    // Last range-params map pushed to the cloud — dedups the config enqueue (the engine re-learns
    // every 6 h; identical results must not re-POST).
    private var lastPushedRangeParams: Map<String, RangeParams> = emptyMap()
```

In the `engine.state.collect { es -> ... }` block (after the `if (es.monitoring) { refresh() ... }` section, inside the collect lambda), add:

```kotlin
                // Learned range params changed → ride the one-way config push so the WebUI's
                // formula twin uses the same bands (latest-wins per address server-side).
                if (es.rangeParamsByAddress.isNotEmpty() &&
                    es.rangeParamsByAddress != lastPushedRangeParams
                ) {
                    lastPushedRangeParams = es.rangeParamsByAddress
                    enqueueTempConfig(_state.value.stageProfile().id)
                }
```

Change `enqueueTempConfig` to include the engine's current params:

```kotlin
    private fun enqueueTempConfig(profileId: String) {
        if (!_state.value.cloudSyncAlerts || !_state.value.enrolled) return
        val t = _state.value.tempThresholdsFor(profileId)
        val env = (ProfileRegistry.all.firstOrNull { it.id == profileId } ?: RedodoBekenProfile).tempEnvelope
        val unit = if (_state.value.tempFahrenheit) "F" else "C"
        val seizeSoc = _state.value.cloudSeizeSoc
        val alertsOn = _state.value.alertsOn
        val ranges = engine.state.value.rangeParamsByAddress
        viewModelScope.launch {
            store.setPendingTempConfig(
                CloudJson.encodeTempConfig(profileId, t, env, unit, clockMs(), seizeSoc, alertsOn, ranges),
            )
        }
    }
```

- [ ] **Step 3: Build + run all unit tests**

Run: `cd /home/joely/bmsmon/android && ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 4: Commit**

```bash
cd /home/joely/bmsmon
git add android/app/src/main/java/dev/joely/bmsmon/cloud/CloudJson.kt \
        android/app/src/main/java/dev/joely/bmsmon/BatteryViewModel.kt
git commit -m "feat(android): push learned range bands over the one-way config channel"
```

---

### Task 7: Server — device_range_config table, ingest, mirror endpoint

**Files:**
- Modify: `server/app/db/schema.sql`
- Modify: `server/app/models.py`
- Modify: `server/app/db/queries.py`
- Modify: `server/app/routers/api_device.py`
- Modify: `server/app/routers/web.py`
- Test: `server/tests/test_range_config.py`

**Interfaces:**
- Consumes: config body rows from Task 6 (`ranges: [{address, wh_per_day_lo, wh_per_day_hi, active_w_lo, active_w_hi, wh_per_mile_lo, wh_per_mile_hi, learned_days, updated_at_ms}]`).
- Produces: `GET /web/range-config` → `{"configs": [{device_id, address, wh_per_day_lo, ..., learned_days, updated_at_ms, received_at}]}` (used by Task 9).

- [ ] **Step 1: Write the failing tests**

`server/tests/test_range_config.py` (helpers copied from `test_config.py` — engineers may read tasks out of order):

```python
import base64
import gzip
import hashlib
import json
import time
import uuid

import jwt
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec

from app.db import queries as q


def _keypair():
    priv = ec.generate_private_key(ec.SECP256R1())
    spki = priv.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    return priv, spki


def _bh(body: bytes) -> str:
    return base64.urlsafe_b64encode(hashlib.sha256(body).digest()).rstrip(b"=").decode()


def _token(priv, device_id, body: bytes, exp_in=60, jti=None):
    now = int(time.time())
    return jwt.encode({"sub": device_id, "iat": now, "exp": now + exp_in,
                       "jti": jti or uuid.uuid4().hex, "bh": _bh(body)},
                      priv, algorithm="ES256")


async def _enroll_device(app, spki):
    async with app.state.pool.acquire() as conn:
        return str(await q.create_device(conn, "inst-range", spki, "dev"))


def _range_row(addr="C8:47:80:15:25:01", ts=1000):
    return {"address": addr,
            "wh_per_day_lo": 100.0, "wh_per_day_hi": 180.0,
            "active_w_lo": 70.0, "active_w_hi": 100.0,
            "wh_per_mile_lo": 18.0, "wh_per_mile_hi": 24.0,
            "learned_days": 10, "updated_at_ms": ts}


def _cfg(updated_at_ms=1000, ranges=None):
    body = {"profile_id": "redodo-beken-bk-ble-1.0", "cold_caution_c": 5, "hot_caution_c": 45,
            "cold_crit_c": -12, "hot_crit_c": 53, "unit": "F", "updated_at_ms": updated_at_ms}
    if ranges is not None:
        body["ranges"] = ranges
    return body


async def _post_cfg(client, priv, device_id, cfg):
    body = json.dumps(cfg).encode()
    return await client.post("/api/v1/config", content=body,
                             headers={"Authorization": f"Bearer {_token(priv, device_id, body)}"})


USER = {"X-authentik-username": "joel",
        "X-authentik-groups": "Covert.life - Full App Access - User Group"}


async def test_config_with_ranges_upserts_rows(app, client):
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    r = await _post_cfg(client, priv, device_id,
                        _cfg(ranges=[_range_row(), _range_row(addr="C8:47:80:15:67:44")]))
    assert r.status_code == 200
    async with app.state.pool.acquire() as conn:
        n = await conn.fetchval("SELECT count(*) FROM device_range_config WHERE device_id=$1",
                                uuid.UUID(device_id))
    assert n == 2


async def test_range_config_latest_wins(app, client):
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    await _post_cfg(client, priv, device_id,
                    _cfg(updated_at_ms=2000, ranges=[{**_range_row(ts=2000), "wh_per_day_lo": 111.0}]))
    # A stale (older updated_at_ms) push must not clobber the newer row.
    await _post_cfg(client, priv, device_id,
                    _cfg(updated_at_ms=1000, ranges=[{**_range_row(ts=1000), "wh_per_day_lo": 999.0}]))
    async with app.state.pool.acquire() as conn:
        lo = await conn.fetchval(
            "SELECT wh_per_day_lo FROM device_range_config WHERE device_id=$1",
            uuid.UUID(device_id))
    assert lo == 111.0


async def test_temp_only_body_still_validates(app, client):
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    r = await _post_cfg(client, priv, device_id, _cfg())   # no ranges key at all
    assert r.status_code == 200


async def test_web_range_config_mirror(app, client):
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    await _post_cfg(client, priv, device_id, _cfg(ranges=[_range_row()]))
    r = await client.get("/web/range-config", headers=USER)
    assert r.status_code == 200
    rows = r.json()["configs"]
    assert len(rows) == 1
    assert rows[0]["address"] == "C8:47:80:15:25:01"
    assert rows[0]["wh_per_mile_hi"] == 24.0
    assert rows[0]["learned_days"] == 10
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /home/joely/bmsmon/server && docker compose -f docker-compose.dev.yml up -d && .venv/bin/python -m pytest tests/test_range_config.py -q`
Expected: FAIL — `relation "device_range_config" does not exist` / 422 / 404.

- [ ] **Step 3: Schema (schema.sql, append at end)**

```sql
-- Learned discharge-range parameter bands pushed from the phone (one-way, latest-wins per
-- device+pack). The webui's range.ts formula twin reads these; no write path back from web.
CREATE TABLE IF NOT EXISTS device_range_config (
  device_id uuid NOT NULL,
  address text NOT NULL,
  wh_per_day_lo real NOT NULL,
  wh_per_day_hi real NOT NULL,
  active_w_lo real NOT NULL,
  active_w_hi real NOT NULL,
  wh_per_mile_lo real NOT NULL,
  wh_per_mile_hi real NOT NULL,
  learned_days int NOT NULL DEFAULT 0,
  updated_at_ms bigint NOT NULL,
  received_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (device_id, address)
);
```

- [ ] **Step 4: Model (models.py)**

Add before `TempConfigBody`:

```python
class RangeConfigRow(BaseModel):
    address: str
    wh_per_day_lo: float
    wh_per_day_hi: float
    active_w_lo: float
    active_w_hi: float
    wh_per_mile_lo: float
    wh_per_mile_hi: float
    learned_days: int = 0
    updated_at_ms: int
```

Add to `TempConfigBody` (after `alerts_on`):

```python
    # Learned discharge-range bands, one row per pack (2026-07-11 design). Optional — an
    # older app pushing a temp-only body must keep validating (never a re-POSTed 422).
    ranges: list[RangeConfigRow] | None = None
```

- [ ] **Step 5: Queries (queries.py, after `get_alert_config`)**

```python
async def upsert_range_config(conn, device_id, row: dict) -> None:
    """Store one pack's learned discharge-range bands (one-way phone push, latest-wins
    guarded on updated_at_ms — mirrors upsert_temp_config)."""
    await conn.execute(
        """INSERT INTO device_range_config
             (device_id, address, wh_per_day_lo, wh_per_day_hi, active_w_lo, active_w_hi,
              wh_per_mile_lo, wh_per_mile_hi, learned_days, updated_at_ms, received_at)
           VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10, now())
           ON CONFLICT (device_id, address) DO UPDATE SET
             wh_per_day_lo = EXCLUDED.wh_per_day_lo,
             wh_per_day_hi = EXCLUDED.wh_per_day_hi,
             active_w_lo = EXCLUDED.active_w_lo,
             active_w_hi = EXCLUDED.active_w_hi,
             wh_per_mile_lo = EXCLUDED.wh_per_mile_lo,
             wh_per_mile_hi = EXCLUDED.wh_per_mile_hi,
             learned_days = EXCLUDED.learned_days,
             updated_at_ms = EXCLUDED.updated_at_ms,
             received_at = now()
           WHERE EXCLUDED.updated_at_ms >= device_range_config.updated_at_ms""",
        device_id, row["address"], row["wh_per_day_lo"], row["wh_per_day_hi"],
        row["active_w_lo"], row["active_w_hi"], row["wh_per_mile_lo"], row["wh_per_mile_hi"],
        row["learned_days"], row["updated_at_ms"],
    )


async def get_range_config_all(conn) -> list[dict]:
    """Latest learned range bands per device+pack (for the read-only webui mirror)."""
    rows = await conn.fetch(
        """SELECT device_id, address, wh_per_day_lo, wh_per_day_hi, active_w_lo, active_w_hi,
                  wh_per_mile_lo, wh_per_mile_hi, learned_days, updated_at_ms, received_at
           FROM device_range_config ORDER BY updated_at_ms DESC"""
    )
    return [dict(r) for r in rows]
```

- [ ] **Step 6: Wire the config endpoint (api_device.py)**

In `config()`, after the `upsert_alert_config` block and before the `last_seen_at` update:

```python
        # Learned discharge-range bands (parallel to alert config): only when the phone
        # includes them — a temp-only body leaves ranges None and the stored rows untouched.
        if cfg.ranges:
            for row in cfg.ranges:
                await q.upsert_range_config(conn, device_id, row.model_dump())
```

- [ ] **Step 7: Mirror endpoint (web.py, after `alert_config`)**

```python
@router.get("/range-config")
async def range_config(user: AuthUser = Depends(current_user), pool=Depends(get_pool)):
    """Read-only mirror of the learned discharge-range bands the phone pushed (one-way)."""
    async with pool.acquire() as conn:
        return {"configs": jsonable(await q.get_range_config_all(conn))}
```

- [ ] **Step 8: Run the full server suite**

Run: `cd /home/joely/bmsmon/server && .venv/bin/python -m pytest -q`
Expected: all tests pass, including the 4 new ones.

- [ ] **Step 9: Commit**

```bash
cd /home/joely/bmsmon
git add server/app/db/schema.sql server/app/models.py server/app/db/queries.py \
        server/app/routers/api_device.py server/app/routers/web.py \
        server/tests/test_range_config.py
git commit -m "feat(server): device_range_config table + config ingest + /web/range-config mirror"
```

---

### Task 8: Web — formula twin (`range.ts`) + decoder fields

**Files:**
- Create: `web/src/range.ts`
- Test: `web/src/range.test.ts`
- Modify: `web/src/types.ts`, `web/src/decode.ts` (add `remaining_ah` + `full_charge_ah` — the fleet snapshot and WS already send them; the decoder currently strips them)

**Interfaces:**
- Consumes: `GET /web/range-config` rows (Task 7); `FleetItem.remaining_ah` (added here).
- Produces (used by Task 9):
  - `interface RangeBand { lo: number; hi: number }`
  - `interface RangeParams { whPerDay: RangeBand; activeW: RangeBand; whPerMile: RangeBand; learnedDays: number; updatedMs: number }`
  - `const SEED_RANGE_PARAMS: RangeParams`
  - `interface RangeConfigRow { device_id: string; address: string; wh_per_day_lo: number; wh_per_day_hi: number; active_w_lo: number; active_w_hi: number; wh_per_mile_lo: number; wh_per_mile_hi: number; learned_days: number; updated_at_ms: number }`
  - `function selectRangeParams(rows: RangeConfigRow[]): Map<string, RangeParams>` (newest per address)
  - `interface PackRange { milesLo: number; milesHi: number; activeHLo: number; activeHHi: number; wallHLo: number; wallHHi: number }`
  - `function estimatePackRange(charging: boolean, remainingAh: number | null | undefined, params: RangeParams): PackRange | null`
  - `function minRange(ranges: PackRange[]): PackRange`
  - `function formatRangeLine(r: PackRange): string`
  - `function decodeRangeConfigs(x: unknown): RangeConfigRow[] | null` (in decode.ts)

- [ ] **Step 1: Write the failing test**

`web/src/range.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import {
  SEED_RANGE_PARAMS, estimatePackRange, formatRangeLine, minRange, selectRangeParams,
  type PackRange, type RangeConfigRow, type RangeParams,
} from "./range";

// SHARED VECTORS: android RangeEstimateTest.kt asserts the same numbers — keep in sync.
const params: RangeParams = {
  whPerDay: { lo: 100, hi: 180 }, activeW: { lo: 70, hi: 100 }, whPerMile: { lo: 18, hi: 24 },
  learnedDays: 10, updatedMs: 0,
};

describe("estimatePackRange", () => {
  it("computes all three bands (70 Ah × 12.8 V = 896 Wh)", () => {
    const r = estimatePackRange(false, 70, params)!;
    expect(r.milesLo).toBeCloseTo(896 / 24, 2);
    expect(r.milesHi).toBeCloseTo(896 / 18, 2);
    expect(r.activeHLo).toBeCloseTo(896 / 100, 2);
    expect(r.activeHHi).toBeCloseTo(896 / 70, 2);
    expect(r.wallHLo).toBeCloseTo((896 / 180) * 24, 2);
    expect(r.wallHHi).toBeCloseTo((896 / 100) * 24, 2);
  });
  it("null while charging", () => expect(estimatePackRange(true, 70, params)).toBeNull());
  it("null without remaining capacity", () => {
    expect(estimatePackRange(false, 0, params)).toBeNull();
    expect(estimatePackRange(false, null, params)).toBeNull();
  });
});

describe("minRange", () => {
  it("takes the worst pack per figure", () => {
    const a: PackRange = { milesLo: 37, milesHi: 50, activeHLo: 9, activeHHi: 13, wallHLo: 119, wallHHi: 215 };
    const b: PackRange = { milesLo: 40, milesHi: 45, activeHLo: 8, activeHHi: 14, wallHLo: 125, wallHHi: 200 };
    const m = minRange([a, b]);
    expect([m.milesLo, m.milesHi, m.activeHLo, m.activeHHi, m.wallHLo, m.wallHHi])
      .toEqual([37, 45, 8, 13, 119, 200]);
  });
});

describe("formatRangeLine", () => {
  it("whole miles/hours/days", () => {
    expect(formatRangeLine({ milesLo: 37.33, milesHi: 49.78, activeHLo: 8.96, activeHHi: 12.8, wallHLo: 119.47, wallHHi: 215.04 }))
      .toBe("~37–50 mi · ~9–13h use · ~5–9 days");
  });
  it("decimal miles when low, hours under 48h", () => {
    expect(formatRangeLine({ milesLo: 1.5, milesHi: 2.4, activeHLo: 0.4, activeHHi: 0.6, wallHLo: 34.2, wallHHi: 42.1 }))
      .toBe("~1.5–2.4 mi · ~0–1h use · ~34–42h");
  });
});

describe("selectRangeParams", () => {
  const row = (address: string, ts: number, lo = 100): RangeConfigRow => ({
    device_id: "d", address, wh_per_day_lo: lo, wh_per_day_hi: 180,
    active_w_lo: 70, active_w_hi: 100, wh_per_mile_lo: 18, wh_per_mile_hi: 24,
    learned_days: 10, updated_at_ms: ts,
  });
  it("newest row per address wins", () => {
    const m = selectRangeParams([row("A", 1000, 999), row("A", 2000, 111), row("B", 500)]);
    expect(m.get("A")!.whPerDay.lo).toBe(111);
    expect(m.get("B")!.whPerDay.lo).toBe(100);
  });
});

describe("seeds", () => {
  it("match the spec", () => {
    expect(SEED_RANGE_PARAMS.whPerDay).toEqual({ lo: 78, hi: 182 });
    expect(SEED_RANGE_PARAMS.activeW).toEqual({ lo: 52.5, hi: 97.5 });
    expect(SEED_RANGE_PARAMS.whPerMile).toEqual({ lo: 15, hi: 25 });
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/joely/bmsmon/web && npx vitest run src/range.test.ts`
Expected: FAIL — cannot resolve `./range`.

- [ ] **Step 3: Write `web/src/range.ts`**

```ts
// Discharge-remaining estimate — line-for-line TypeScript twin of the Android pure formula
// (android/.../model/RangeEstimate.kt). Keep the math identical; both test suites share the
// same vectors. Documented divergence: the web shows the history band without the live tilt
// (the tilt inputs live in the phone's Room DB). Design:
// docs/superpowers/specs/2026-07-11-discharge-estimate-design.md

export const NOMINAL_PACK_V = 12.8;

export interface RangeBand { lo: number; hi: number }
export interface RangeParams {
  whPerDay: RangeBand; activeW: RangeBand; whPerMile: RangeBand;
  learnedDays: number; updatedMs: number;
}

/** Cold-start bands — must match SEED_RANGE_PARAMS in RangeEstimate.kt. */
export const SEED_RANGE_PARAMS: RangeParams = {
  whPerDay: { lo: 78, hi: 182 },
  activeW: { lo: 52.5, hi: 97.5 },
  whPerMile: { lo: 15, hi: 25 },
  learnedDays: 0, updatedMs: 0,
};

/** One row of GET /web/range-config. */
export interface RangeConfigRow {
  device_id: string; address: string;
  wh_per_day_lo: number; wh_per_day_hi: number;
  active_w_lo: number; active_w_hi: number;
  wh_per_mile_lo: number; wh_per_mile_hi: number;
  learned_days: number; updated_at_ms: number;
}

/** Newest row per address → params map (rows arrive newest-first, but don't rely on it). */
export function selectRangeParams(rows: RangeConfigRow[]): Map<string, RangeParams> {
  const best = new Map<string, RangeConfigRow>();
  for (const r of rows) {
    const cur = best.get(r.address);
    if (!cur || r.updated_at_ms > cur.updated_at_ms) best.set(r.address, r);
  }
  const out = new Map<string, RangeParams>();
  for (const [addr, r] of best) {
    out.set(addr, {
      whPerDay: { lo: r.wh_per_day_lo, hi: r.wh_per_day_hi },
      activeW: { lo: r.active_w_lo, hi: r.active_w_hi },
      whPerMile: { lo: r.wh_per_mile_lo, hi: r.wh_per_mile_hi },
      learnedDays: r.learned_days, updatedMs: r.updated_at_ms,
    });
  }
  return out;
}

export interface PackRange {
  milesLo: number; milesHi: number;
  activeHLo: number; activeHHi: number;
  wallHLo: number; wallHHi: number;
}

/** Per-pack estimate, or null when charging (the recharge ETA owns the slot) / no capacity. */
export function estimatePackRange(
  charging: boolean,
  remainingAh: number | null | undefined,
  params: RangeParams,
): PackRange | null {
  if (charging) return null;
  if (remainingAh == null || !Number.isFinite(remainingAh) || remainingAh <= 0) return null;
  const remWh = remainingAh * NOMINAL_PACK_V;
  return {
    milesLo: remWh / params.whPerMile.hi, milesHi: remWh / params.whPerMile.lo,
    activeHLo: remWh / params.activeW.hi, activeHHi: remWh / params.activeW.lo,
    wallHLo: (remWh / params.whPerDay.hi) * 24, wallHHi: (remWh / params.whPerDay.lo) * 24,
  };
}

/** Base-level readout: the weaker pack bounds each figure (series pair — it ends the trip). */
export function minRange(ranges: PackRange[]): PackRange {
  return ranges.reduce((a, b) => ({
    milesLo: Math.min(a.milesLo, b.milesLo), milesHi: Math.min(a.milesHi, b.milesHi),
    activeHLo: Math.min(a.activeHLo, b.activeHLo), activeHHi: Math.min(a.activeHHi, b.activeHHi),
    wallHLo: Math.min(a.wallHLo, b.wallHLo), wallHHi: Math.min(a.wallHHi, b.wallHHi),
  }));
}

/** "~37–50 mi · ~9–13h use · ~5–9 days" (days when the low bound exceeds 48 h, else hours). */
export function formatRangeLine(r: PackRange): string {
  const miles = r.milesHi < 10
    ? `~${r.milesLo.toFixed(1)}–${r.milesHi.toFixed(1)} mi`
    : `~${Math.round(r.milesLo)}–${Math.round(r.milesHi)} mi`;
  const use = `~${Math.round(r.activeHLo)}–${Math.round(r.activeHHi)}h use`;
  const wall = r.wallHLo > 48
    ? `~${Math.round(r.wallHLo / 24)}–${Math.round(r.wallHHi / 24)} days`
    : `~${Math.round(r.wallHLo)}–${Math.round(r.wallHHi)}h`;
  return `${miles} · ${use} · ${wall}`;
}
```

- [ ] **Step 4: Add the missing telemetry fields + config decoder (types.ts, decode.ts)**

`types.ts` — add to `Sample` after `cycles`:

```ts
  full_charge_ah?: number | null; remaining_ah?: number | null;
```

`decode.ts` — add `"full_charge_ah", "remaining_ah",` to `SAMPLE_KEYS` (after `"cycles",`). Then add at the bottom:

```ts
const RANGE_CONFIG_KEYS = [
  "device_id", "address", "wh_per_day_lo", "wh_per_day_hi", "active_w_lo", "active_w_hi",
  "wh_per_mile_lo", "wh_per_mile_hi", "learned_days", "updated_at_ms",
] as const;

const validRangeConfig = (x: unknown): x is Record<string, unknown> =>
  isObj(x) && typeof x.device_id === "string" && typeof x.address === "string" &&
  Number.isFinite(x.wh_per_day_lo) && Number.isFinite(x.wh_per_day_hi) &&
  Number.isFinite(x.active_w_lo) && Number.isFinite(x.active_w_hi) &&
  Number.isFinite(x.wh_per_mile_lo) && Number.isFinite(x.wh_per_mile_hi) &&
  Number.isFinite(x.updated_at_ms);

/** Strict like decodeTempConfigs: any malformed row invalidates the response. */
export function decodeRangeConfigs(x: unknown): RangeConfigRow[] | null {
  if (!Array.isArray(x) || !x.every(validRangeConfig)) return warn("range-config", x);
  return x.map((c) => ({ learned_days: 0, ...pick<RangeConfigRow>(c, RANGE_CONFIG_KEYS) }));
}
```

with import `import type { RangeConfigRow } from "./range";` at the top.

- [ ] **Step 5: Run the web test suite**

Run: `cd /home/joely/bmsmon/web && npx vitest run`
Expected: all pass, including the new range tests.

- [ ] **Step 6: Commit**

```bash
cd /home/joely/bmsmon
git add web/src/range.ts web/src/range.test.ts web/src/types.ts web/src/decode.ts
git commit -m "feat(web): discharge-range formula twin + remaining_ah in the fleet decoder"
```

---

### Task 9: Web UI — fetch config + range strip on MainStage

**Files:**
- Modify: `web/src/api.ts`
- Modify: `web/src/App.tsx`
- Modify: `web/src/components/MainStage.tsx`

**Interfaces:**
- Consumes: `decodeRangeConfigs` (Task 8), `GET /web/range-config` (Task 7), `selectRangeParams`, `estimatePackRange`, `minRange`, `formatRangeLine`, `SEED_RANGE_PARAMS` (Task 8).
- Produces: `getRangeConfig(): Promise<{ configs: RangeConfigRow[] }>`; `MainStage` gains prop `rangeParams: Map<string, RangeParams>`.

- [ ] **Step 1: API client (api.ts)**

Add imports `decodeRangeConfigs` (from `./decode`) and `type { RangeConfigRow } from "./range"`, then after `getTempConfig`:

```ts
export const getRangeConfig = async (): Promise<{ configs: RangeConfigRow[] }> => {
  const r = await fetch("/web/range-config").then(j);
  const configs = isObj(r) ? decodeRangeConfigs(r.configs) : null;
  if (!configs) throw new Error("malformed /web/range-config response");
  return { configs };
};
```

- [ ] **Step 2: App wiring (App.tsx)**

Add imports: `getRangeConfig` (from `./api`), `selectRangeParams, type RangeParams` (from `./range`). After the alert-config effect add a poll on the same cadence:

```ts
  // Learned discharge-range bands synced from the phone (read-only mirror).
  const [rangeParams, setRangeParams] = useState<Map<string, RangeParams>>(new Map());
  useEffect(() => {
    let alive = true;
    const load = () => getRangeConfig()
      .then((r) => { if (alive) setRangeParams(selectRangeParams(r.configs)); })
      .catch(() => { /* keep last */ });
    load();
    const t = setInterval(load, 60_000);
    return () => { alive = false; clearInterval(t); };
  }, []);
```

Pass it to the stage: `<MainStage ... lowSeized={lowSeized} rangeParams={rangeParams} />`.

- [ ] **Step 3: The strip (MainStage.tsx)**

Add imports:

```ts
import {
  SEED_RANGE_PARAMS, estimatePackRange, formatRangeLine, minRange, type RangeParams,
} from "../range";
```

Extend the props type with `rangeParams: Map<string, RangeParams>;` (and the destructuring with `rangeParams`). Compute the line just before `return` (after the `bannerColor` line):

```ts
  // Base-level discharge-remaining line — the twin of the Android stage line. Hidden when any
  // staged pack is disconnected (no fake numbers), charging (the recharge ETA owns the slot),
  // or missing remaining_ah. History band only — no live tilt on the web (documented divergence).
  const rangeLine = (() => {
    if (rows.length === 0 || rows.some((r) => !r.connected)) return null;
    const ranges = rows.map(({ it }) => estimatePackRange(
      (it.current_a ?? 0) > 0.1,
      it.remaining_ah,
      rangeParams.get(it.address) ?? SEED_RANGE_PARAMS,
    ));
    if (ranges.some((r) => r == null)) return null;
    return formatRangeLine(minRange(ranges as NonNullable<typeof ranges[number]>[]));
  })();
```

Render it inside the stage card, directly after the closing `</div>` of the pack-row flex container (the one with `minHeight: 280`):

```tsx
        {rangeLine && (
          <div className="mono" style={{ textAlign: "center", color: "var(--text2)",
            fontSize: 12, marginTop: 12 }}>
            {rangeLine}
          </div>
        )}
```

- [ ] **Step 4: Tests + typecheck + build**

Run: `cd /home/joely/bmsmon/web && npx vitest run && npx tsc --noEmit && npm run build`
Expected: tests pass, no type errors, production build succeeds.

- [ ] **Step 5: Commit**

```bash
cd /home/joely/bmsmon
git add web/src/api.ts web/src/App.tsx web/src/components/MainStage.tsx
git commit -m "feat(web): discharge-remaining strip on the main stage from synced range bands"
```

---

### Task 10: Backtest against real data + docs

**Files:**
- Create: `docs/range-backtest-2026-07.md` (findings write-up)
- Modify: `CLAUDE.md` (bmsmon repo root), `~/GoogleDrive/obsidian/notes/Bmsmon.md`

- [ ] **Step 1: Backtest the learner's bands against the real cloud history**

Write the learner's per-day statistic as SQL (mirroring `RangeLearn.kt` exactly: dt 0.5–60 s, Discharging, regen excluded, ≥12 h coverage) and check what fraction of days each pack's p20/p80 band would have bracketed. Run per address (all 8 packs; the daily drivers matter most):

```bash
cat > /tmp/backtest.sql <<'EOF'
WITH pts AS (
  SELECT address, ts, power_w, state, regen,
         EXTRACT(epoch FROM ts - lag(ts) OVER (PARTITION BY address ORDER BY ts)) AS dt
  FROM samples WHERE link_event IS NULL
),
day AS (
  SELECT address, date_trunc('day', ts)::date AS d,
    sum(dt) FILTER (WHERE dt BETWEEN 0.5 AND 60) AS coverage_s,
    sum(power_w*dt/3600.0) FILTER (WHERE state='Discharging' AND NOT regen AND dt BETWEEN 0.5 AND 60) AS dis_wh
  FROM pts GROUP BY 1, 2
),
q AS (SELECT * FROM day WHERE coverage_s >= 43200 AND dis_wh IS NOT NULL),
band AS (
  SELECT address,
    percentile_cont(0.2) WITHIN GROUP (ORDER BY dis_wh) AS lo,
    percentile_cont(0.8) WITHIN GROUP (ORDER BY dis_wh) AS hi,
    count(*) AS days
  FROM q GROUP BY 1
)
SELECT q.address, band.days,
  round(band.lo::numeric,0) AS p20_wh, round(band.hi::numeric,0) AS p80_wh,
  round(avg(CASE WHEN q.dis_wh BETWEEN band.lo AND band.hi THEN 1.0 ELSE 0 END)::numeric, 2) AS frac_in_band
FROM q JOIN band USING (address) GROUP BY 1, 2, 3, 4 ORDER BY 1;
EOF
scp /tmp/backtest.sql joely@ddnas02:/tmp/backtest.sql
ssh joely@ddnas02 'bash -lc "docker exec -i bmsmon-db psql -U bmsmon -d bmsmon < /tmp/backtest.sql"'
```

Expected: `frac_in_band` around 0.55–0.75 per pack (p20/p80 brackets ~60% by construction; small-sample wobble is fine). Record the actual table in `docs/range-backtest-2026-07.md` with a one-paragraph verdict. **If a daily-driver pack lands below 0.4 or above 0.9**, the day-qualification rules are off — investigate before shipping (most likely culprit: the 12 h coverage bar vs. that pack's monitoring pattern).

- [ ] **Step 2: Update CLAUDE.md**

Add to the Android App section of `/home/joely/bmsmon/CLAUDE.md` (after the charge-time ETA bullet in the accuracy check-in block is NOT the place — add a new paragraph after the **Main-stage upload indicator** paragraph):

```markdown
**Discharge estimate (miles + time remaining).** The stage shows a base-level learned
high/low line — `~37–50 mi · ~9–13h use · ~5–9 days` — under the rings whenever the staged
packs are connected and not charging (charging shows the recharge ETA instead). Pure math in
`model/RangeEstimate.kt` (estimate + live tilt + formatting) and `model/RangeLearn.kt`
(per-day p20/p80 bands: Wh/day, active W, Wh/mile from GPS-qualified outdoor drive segments)
with a line-for-line TS twin in `web/src/range.ts` (no tilt on web). The engine learns every
6 h from the local 14-day Room history (GPS now stored locally — samples db v4), refreshes
today's tilt every 5 min, computes the per-pack estimate once per poll onto
`BatteryStatus.range`, persists params in SettingsStore, and pushes them over the one-way
config channel into `device_range_config` (mirrored by `GET /web/range-config`). Seeds until
≥3 qualifying days: 130 Wh/day ±40%, 75 W ±30%, 20 Wh/mi ±25% (from the 2026-07 backtest —
see docs/range-backtest-2026-07.md).
```

Also add one line to the **Accuracy check-in — due 2026-07-15** list:

```markdown
- **Discharge-range bands** — re-run the backtest in docs/range-backtest-2026-07.md; check the
  learned Wh/mile band against accumulated outdoor driving (it learns from sparse data).
```

- [ ] **Step 3: Update the Obsidian note + memory**

Append a short paragraph to `~/GoogleDrive/obsidian/notes/Bmsmon.md` mirroring the CLAUDE.md summary (feature shipped, where it lives, learned-from-usage design). Update the memory directory: add `bmsmon-discharge-estimate.md` (type: project) noting the feature, the seed values' provenance, and that the WebUI twin has no live tilt; add its line to `MEMORY.md`.

- [ ] **Step 4: Full verification sweep**

```bash
cd /home/joely/bmsmon/android && ./gradlew :app:testDebugUnitTest :app:assembleDebug
cd /home/joely/bmsmon/server && .venv/bin/python -m pytest -q
cd /home/joely/bmsmon/web && npx vitest run && npm run build
```

Expected: everything green.

- [ ] **Step 5: Commit**

```bash
cd /home/joely/bmsmon
git add CLAUDE.md docs/range-backtest-2026-07.md
git commit -m "docs: discharge-estimate backtest results + architecture notes"
```

---

## Post-plan notes (not tasks)

- **Deploy** (after user sign-off): push to `main` → GitHub Actions builds `ghcr.io/mkeguy106/bmsmon-server:latest` → pull+recreate `bmsmon-api` on ddnas02 per CLAUDE.md. The new `device_range_config` table lands automatically via schema.sql on container start. Android: `adb install -r` (never uninstall — user data).
- **On-device verification**: after installing, watch the stage — the line appears within one range-pass (~immediately on start); with <3 days of on-phone GPS history the miles band will be the seed (15–25 Wh/mi) until local data accumulates (the cloud history does NOT backfill the phone's Room DB; the pre-existing samples there have no GPS until v4 rows accumulate).
