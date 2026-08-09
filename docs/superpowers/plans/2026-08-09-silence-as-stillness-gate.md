# Silence-as-Stillness Motion Gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the parked-GPS pause actually fire by closing the gate on one confident-STILL reading left uncontradicted for 10 minutes (silence extends the run), with a 6 h AR re-subscribe backstop — replacing the debounce/staleness close semantics that never closed in production.

**Architecture:** Pure-fold rework in `model/BatterySaver.kt` (`MotionGate` gains `stillSinceMs`; verdict re-derived from the clock on every fold, so silence closes it), an idempotent `maybeResubscribe()` on `MotionSource` driven from the existing `applyGpsGate`, and no wire/server/WebUI change. Reopen semantics, `gpsShouldRun`, and all lock discipline are untouched.

**Tech Stack:** Kotlin, JUnit4 (JVM, pure model tests), Play Services Activity Recognition.

**Spec:** `docs/superpowers/specs/2026-08-09-silence-as-stillness-motion-gate-design.md`

## Global Constraints

- Commit messages: NEVER mention AI/Claude/generated (repo rule).
- `model/BatterySaver.kt` stays pure: no Android imports, no clock access — `nowMs` threaded in.
- Every unusable-signal path (null reading — no permission, AR unavailable, no reading yet, source stopped) must still fail open to GPS-on.
- Deleted outright: `MOTION_STALE_MS`, `STILL_DEBOUNCE_N`, and the staleness branch. Kept: `STILL_CONFIDENCE_MIN = 75`, dedup by `atMs`, `gpsShouldRun` verbatim, `shutdownGps()`'s gate reset.
- New constants: `STILL_CLOSE_HOLD_MS = 10 * 60_000L` (BatterySaver.kt), `RESUBSCRIBE_MS = 6 * 3_600_000L` (MotionSource companion, `internal`).
- Close boundary is inclusive (`>=`), the alert-ladder convention.
- No server, WebUI, or wire-format change of any kind.
- Test commands: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.BatterySaverTest"` (focused) / `./gradlew :app:testDebugUnitTest` (full) / `./gradlew :app:lintDebug`.
- Deploy (Task 4) is **APK-only** — nothing under `server/` or `web/` changes, so no image build/NAS step. Device protocol: `adb -s adb-1C091FDF6003V0-RQzUxy._adb-tls-connect._tcp` every time; `install -r` then `am start -n dev.joely.bmsmon/.MainActivity` then `ps -A | grep bmsmon`; NEVER uninstall `dev.joely.bmsmon`.

---

### Task 1: Rework `MotionGate`/`foldMotion` + rewrite the fold tests

**Files:**
- Modify: `android/app/src/main/java/dev/joely/bmsmon/model/BatterySaver.kt` (lines ~107–209: constants, `MotionGate`, `foldMotion`)
- Test: `android/app/src/test/java/dev/joely/bmsmon/BatterySaverTest.kt` (the `// ── foldMotion ──` section, ~lines 207–387, plus the `closedGate` helper users below it)

**Interfaces:**
- Consumes: `MotionReading` (unchanged), `STILL_CONFIDENCE_MIN` (unchanged).
- Produces: `MotionGate(stillSinceMs: Long? = null, still: Boolean = false, lastConfidentAtMs: Long = 0L)`; `foldMotion(prev: MotionGate, reading: MotionReading?, nowMs: Long): MotionGate` (same signature); `const val STILL_CLOSE_HOLD_MS = 10 * 60_000L`. Task 2's engine comment and Task 3's docs describe exactly these.

- [ ] **Step 1: Rewrite the fold test section (failing first)**

In `BatterySaverTest.kt`, replace the entire `// ── foldMotion ──` section — from the comment banner at ~line 207 through `motionThresholdsAreSeventyFiveAndTwoAndAHalfMinutesAndThreeInARow` (~line 387) — with the code below. KEEP the tests after it (`closedGateCannotMakeGpsRunWhenNotWanted`, `motionReadingCarriesTheActivityName`, and the activity-string-invariance test at the file's end): they survive, except that any `MotionGate(stillRun = …)` construction inside them must be updated to the new shape (the invariance test's property — the `activity` string never affects folding — is unchanged; adapt its constructions only). Remove the now-unused imports of `MOTION_STALE_MS` and `STILL_DEBOUNCE_N`; import `STILL_CLOSE_HOLD_MS`.

```kotlin
    // ── foldMotion ───────────────────────────────────────────────────────────
    // Silence-as-stillness semantics (2026-08-09 rework): AR delivery is motion-triggered at the
    // sensor level — measured 4 readings in ~18 h while genuinely parked — so the old rule
    // (N fresh confident readings inside a staleness window) demanded evidence that never
    // arrives, and the gate never closed in the recorded telemetry era. Closing now needs ONE
    // confident STILL reading left uncontradicted for STILL_CLOSE_HOLD_MS; silence extends the
    // run instead of failing it open. Reopening is unchanged: a single confident non-STILL.
    // Null readings (no signal at all) still fail open, because MotionGate() means GPS STAYS ON.

    private fun reading(
        still: Boolean,
        conf: Int,
        age: Long,
        now: Long = 10_000_000L,
        activity: String = if (still) "STILL" else "UNKNOWN",
    ) = MotionReading(still = still, confidence = conf, atMs = now - age, activity = activity)

    /** A closed gate as production reaches it: run started HOLD ago, last confident at [atMs]. */
    private fun closedGate(atMs: Long) = MotionGate(
        stillSinceMs = atMs - STILL_CLOSE_HOLD_MS, still = true, lastConfidentAtMs = atMs,
    )

    @Test fun noReadingFailsOpen() {
        val prev = MotionGate(stillSinceMs = 9_000_000L, still = true, lastConfidentAtMs = 9_999_000L)
        assertEquals(MotionGate(), foldMotion(prev, null, 10_000_000L))
    }

    @Test fun confidentStillStartsARunButDoesNotCloseYet() {
        val now = 10_000_000L
        val r = reading(still = true, conf = 99, age = 0, now = now)
        val gate = foldMotion(MotionGate(), r, now)
        assertEquals(r.atMs, gate.stillSinceMs)
        assertFalse(gate.still)
        assertEquals(r.atMs, gate.lastConfidentAtMs)
    }

    // The core inversion: no new readings arrive (AR is silent because nothing moves) and the
    // SAME cached reading refolds while the clock advances. At the hold boundary — inclusive,
    // the alert-ladder convention — the gate closes on silence alone.
    @Test fun silenceClosesTheGateAtTheHoldBoundary() {
        val t0 = 10_000_000L
        val r = reading(still = true, conf = 100, age = 0, now = t0)
        var gate = foldMotion(MotionGate(), r, t0)
        gate = foldMotion(gate, r, t0 + STILL_CLOSE_HOLD_MS - 1)
        assertFalse(gate.still)
        gate = foldMotion(gate, r, t0 + STILL_CLOSE_HOLD_MS)
        assertTrue(gate.still)
        assertEquals(r.atMs, gate.stillSinceMs)
    }

    // Restart self-heal: a fresh gate (process restart) + the subscription's one burst reading
    // + time = closed. Under the old rules a parked restart never re-closed.
    @Test fun restartSelfHealsFromOneBurstReading() {
        val t0 = 10_000_000L
        val burst = reading(still = true, conf = 100, age = 0, now = t0)
        var gate = foldMotion(MotionGate(), burst, t0)
        gate = foldMotion(gate, burst, t0 + STILL_CLOSE_HOLD_MS)
        assertTrue(gate.still)
    }

    // A later confident STILL keeps the ORIGINAL run start — the run is one continuous stretch
    // of stillness, not restarted per reading (else the close would chase the newest reading).
    @Test fun subsequentStillKeepsTheOriginalRunStart() {
        val t0 = 10_000_000L
        var gate = foldMotion(MotionGate(), reading(still = true, conf = 99, age = 0, now = t0), t0)
        val t1 = t0 + 60_000L
        val second = MotionReading(still = true, confidence = 100, atMs = t1, activity = "STILL")
        gate = foldMotion(gate, second, t1)
        assertEquals(t0, gate.stillSinceMs)
        assertEquals(t1, gate.lastConfidentAtMs)
        // Closes HOLD after the FIRST reading, not the second.
        gate = foldMotion(gate, second, t0 + STILL_CLOSE_HOLD_MS)
        assertTrue(gate.still)
    }

    // UNKNOWN@41 mid-run: absence of evidence, not evidence of motion — holds the run, and the
    // clock keeps counting through it, so an uncertain fold can itself close the gate.
    @Test fun uncertaintyHoldsTheRunAndTheClockStillCloses() {
        val t0 = 10_000_000L
        var gate = foldMotion(MotionGate(), reading(still = true, conf = 99, age = 0, now = t0), t0)
        val u1 = t0 + 1_000L
        gate = foldMotion(gate, MotionReading(still = false, confidence = 41, atMs = u1, activity = "UNKNOWN"), u1)
        assertEquals(t0, gate.stillSinceMs)
        assertFalse(gate.still)
        val u2 = t0 + STILL_CLOSE_HOLD_MS
        gate = foldMotion(gate, MotionReading(still = false, confidence = 41, atMs = u2, activity = "UNKNOWN"), u2)
        assertTrue(gate.still)
    }

    // Deliberate inversion of the old "uncertainty cannot postpone fail-open": there is no
    // staleness deadline anymore. A closed gate holds through unbounded silence/uncertainty —
    // that is what a parked night actually looks like (4 readings in ~18 h, all STILL).
    @Test fun closedGateHoldsThroughHoursOfUncertainty() {
        val t0 = 10_000_000L
        val later = t0 + 11 * 3_600_000L
        val gate = foldMotion(
            closedGate(t0),
            MotionReading(still = false, confidence = 41, atMs = later, activity = "UNKNOWN"),
            later,
        )
        assertTrue(gate.still)
    }

    @Test fun confidentNonStillReopensImmediatelyFromClosed() {
        val now = 10_000_000L
        val gate = foldMotion(closedGate(now - 6_000L), reading(still = false, conf = 99, age = 0, now = now), now)
        assertFalse(gate.still)
        assertEquals(null, gate.stillSinceMs)
    }

    // The stoplight case, handled by the hold instead of the deleted N=3 debounce: a spurious
    // STILL mid-drive starts a run, but in-vehicle delivery is rich (~5.7 s cadence measured),
    // so a single confident IN_VEHICLE clears it long before the 10-minute hold.
    @Test fun stoplightStillIsCancelledByInVehicleBeforeTheHold() {
        val t0 = 10_000_000L
        var gate = foldMotion(MotionGate(), reading(still = true, conf = 96, age = 0, now = t0), t0)
        assertEquals(t0, gate.stillSinceMs)
        val t1 = t0 + 120_000L
        gate = foldMotion(gate, MotionReading(still = false, confidence = 90, atMs = t1, activity = "IN_VEHICLE"), t1)
        assertEquals(null, gate.stillSinceMs)
        assertFalse(gate.still)
    }

    // Dedup branch: refolding the same reading must not corrupt the run — and must re-derive
    // the verdict from the clock (this is the branch silence actually closes through).
    @Test fun refoldingOneReadingHoldsTheRunAndAdvancesOnlyTheClock() {
        val t0 = 10_000_000L
        val r = reading(still = true, conf = 99, age = 0, now = t0)
        var gate = foldMotion(MotionGate(), r, t0)
        repeat(12) { i ->
            gate = foldMotion(gate, r, t0 + (i + 1) * 100L)
            assertEquals(t0, gate.stillSinceMs)
            assertFalse(gate.still)
        }
    }

    // STILL below the confidence floor is uncertainty, not evidence: it neither starts a run…
    @Test fun lowConfidenceStillDoesNotStartARun() {
        val now = 10_000_000L
        val gate = foldMotion(MotionGate(), reading(still = true, conf = 38, age = 0, now = now), now)
        assertEquals(MotionGate(), gate)
    }

    // …nor advances one (it holds, exactly like UNKNOWN).
    @Test fun lowConfidenceStillHoldsAnOpenRun() {
        val t0 = 10_000_000L
        val gate = foldMotion(MotionGate(), reading(still = true, conf = 99, age = 0, now = t0), t0)
        val weak = MotionReading(still = true, confidence = 38, atMs = t0 + 1_000L, activity = "STILL")
        val held = foldMotion(gate, weak, t0 + 1_000L)
        assertEquals(gate.stillSinceMs, held.stillSinceMs)
        assertEquals(gate.lastConfidentAtMs, held.lastConfidentAtMs)
    }

    @Test fun motionThresholdsAreSeventyFiveAndTenMinutes() {
        assertEquals(75, STILL_CONFIDENCE_MIN)
        assertEquals(600_000L, STILL_CLOSE_HOLD_MS)
    }
```

- [ ] **Step 2: Run the focused suite to verify failure**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.BatterySaverTest"`
Expected: FAIL — compile errors (`stillSinceMs`/`STILL_CLOSE_HOLD_MS` unresolved; `MOTION_STALE_MS`/`STILL_DEBOUNCE_N` imports dangling). A compile-failure RED is the expected shape here since the data class itself changes.

- [ ] **Step 3: Implement the new model**

In `BatterySaver.kt`: delete the `MOTION_STALE_MS` and `STILL_DEBOUNCE_N` constants (with their KDoc), keep `STILL_CONFIDENCE_MIN`, and replace `MotionGate` + `foldMotion` with:

```kotlin
/**
 * How long one confident-STILL reading must stand uncontradicted — by confident motion, never by
 * mere silence — before GNSS pauses. 10 min (user-chosen over 5) so a long vehicle standstill
 * (train at a station) rarely closes the gate mid-trip; when one does, departure vibration wakes
 * AR and the first confident non-STILL reopens it. Deliberately separate from [PARKED_HOLD_MS]:
 * that defines "chair parked", this defines "phone still long enough that a mid-trip standstill
 * is implausible".
 */
const val STILL_CLOSE_HOLD_MS = 10 * 60_000L

/**
 * Silence-as-stillness gate state, folded one reading at a time by [foldMotion]
 * (2026-08-09 rework — docs/superpowers/specs/2026-08-09-silence-as-stillness-motion-gate-design.md).
 *
 * [stillSinceMs] is the start of the current uncontradicted confident-STILL run (the starting
 * reading's own [MotionReading.atMs]), or null when no run is live. [still] is the verdict:
 * closed once the run is [STILL_CLOSE_HOLD_MS] old. [lastConfidentAtMs] keeps its dedup role
 * unchanged: the atMs of the last confident reading folded, so re-evaluations never re-fold one
 * reading. The all-default `MotionGate()` is the fail-open state.
 */
data class MotionGate(
    val stillSinceMs: Long? = null,
    val still: Boolean = false,
    val lastConfidentAtMs: Long = 0L,
)

/** Re-derive the verdict from the clock: closed iff the run is HOLD old (inclusive). */
private fun MotionGate.withClock(nowMs: Long): MotionGate {
    val closed = stillSinceMs != null && nowMs - stillSinceMs >= STILL_CLOSE_HOLD_MS
    return if (closed == still) this else copy(still = closed)
}

/**
 * Fold one motion [reading] into [prev] — the second condition for pausing GNSS.
 *
 * WHY the 2026-08-09 inversion: AR delivery is motion-triggered at the sensor level — rich while
 * the device moves (~5.7 s cadence in vehicles), essentially silent while it is genuinely still
 * (measured: 4 readings in ~18 h parked). The old rule (STILL_DEBOUNCE_N fresh readings inside a
 * MOTION_STALE_MS window) therefore demanded evidence that never arrives, and the shipped gate
 * never closed in the recorded telemetry era: silence after a confident STILL is stillness
 * evidence, not signal loss.
 *
 * Rules, branch order load-bearing:
 * - **null reading** → fail open (no permission, AR unavailable, no reading yet, source stopped).
 * - **already folded** (atMs == lastConfidentAtMs) → hold the run, re-derive the verdict from
 *   the clock. This is the branch silence closes through: evaluations keep arriving (per BLE
 *   frame + the 5-min range tick) while readings do not.
 * - **uncertain** (confidence < [STILL_CONFIDENCE_MIN], which is how UNKNOWN always arrives) →
 *   same: neither starts, breaks, nor ends a run, and there is no staleness deadline to postpone.
 * - **confident STILL** → start the run if none (at the reading's own time), else keep its
 *   start; verdict from the clock.
 * - **confident non-STILL** → reopen on the single reading, run cleared — getting into a vehicle
 *   resumes GNSS at the first solid reading.
 *
 * The hold replaces the debounce's anti-flap job: a spurious stoplight STILL must survive
 * [STILL_CLOSE_HOLD_MS] uncontradicted, and in a real drive AR's rich in-vehicle delivery
 * contradicts it long before that. A silently-dead subscription can now hold the gate closed
 * while parked (accepted by explicit user decision) — bounded by the discharge clause in
 * [gpsShouldRun] and MotionSource's periodic re-subscribe.
 */
fun foldMotion(prev: MotionGate, reading: MotionReading?, nowMs: Long): MotionGate = when {
    reading == null -> MotionGate()
    reading.atMs == prev.lastConfidentAtMs -> prev.withClock(nowMs)
    reading.confidence < STILL_CONFIDENCE_MIN -> prev.withClock(nowMs)
    reading.still -> MotionGate(
        stillSinceMs = prev.stillSinceMs ?: reading.atMs,
        lastConfidentAtMs = reading.atMs,
    ).withClock(nowMs)
    else -> MotionGate(lastConfidentAtMs = reading.atMs)
}
```

Also check `BatterySaver.kt`'s other KDoc for now-false claims (e.g. `MotionReading.atMs` doc referencing "the same clock foldMotion compares against" stays true; anything referencing staleness/debounce must be updated or dropped).

- [ ] **Step 4: Run focused, then full suite**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.BatterySaverTest"`
Expected: PASS. Then `./gradlew :app:testDebugUnitTest` — expected: all pass (no other code references the deleted constants; verify with `grep -rn "MOTION_STALE_MS\|STILL_DEBOUNCE_N\|stillRun" app/src/main app/src/test` → only hits should be the files you just edited; fix any stragglers).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/dev/joely/bmsmon/model/BatterySaver.kt android/app/src/test/java/dev/joely/bmsmon/BatterySaverTest.kt
git commit -m "feat(android): close the parked-GPS gate on silence after confident STILL"
```

---

### Task 2: `MotionSource.maybeResubscribe` + engine wiring

**Files:**
- Modify: `android/app/src/main/java/dev/joely/bmsmon/motion/MotionSource.kt`
- Modify: `android/app/src/main/java/dev/joely/bmsmon/monitor/MonitorEngine.kt` (`applyGpsGate` ~line 524; `startRangeLoop` comment ~lines 767–771)

**Interfaces:**
- Consumes: Task 1's clock-close semantics (the range-tick comment describes them).
- Produces: `MotionSource.maybeResubscribe(nowMs: Long)` (`@Synchronized`, no-op unless subscribed and `RESUBSCRIBE_MS` elapsed); `internal const val RESUBSCRIBE_MS = 6 * 3_600_000L` in the `MotionSource` companion.

- [ ] **Step 1: Implement `maybeResubscribe`**

In `MotionSource.kt`: add a private `var lastRequestAtMs = 0L`; in `start()`, set `lastRequestAtMs = System.currentTimeMillis()` beside the `requestActivityUpdates` call; add to the companion:

```kotlin
        /** Refresh the AR subscription this often — see [maybeResubscribe]. */
        internal const val RESUBSCRIBE_MS = 6 * 3_600_000L
```

and add the method:

```kotlin
    /**
     * Re-issue the periodic-updates request on the same PendingIntent (FLAG_UPDATE_CURRENT makes
     * it a refresh, not a re-registration — the receiver is untouched). Under silence-as-stillness
     * a silently-dead Play Services subscription can hold the gate CLOSED while parked, so the
     * request is refreshed every [RESUBSCRIBE_MS]; a failure routes through the same
     * [onSubscribeFailed] rollback as [start], after which the next gate evaluation's [start]
     * rebuilds the subscription from scratch. Observed on both post-restart subscribes: a fresh
     * request delivers one immediate reading, so each refresh doubles as a stillness probe — it
     * either confirms the run or reveals motion and reopens the gate (expected for the refresh
     * path too, but unverified — checked on-device after deploy). No-op unless currently
     * subscribed and due.
     */
    @Synchronized
    fun maybeResubscribe(nowMs: Long) {
        if (!requesting || nowMs - lastRequestAtMs < RESUBSCRIBE_MS) return
        lastRequestAtMs = nowMs
        runCatching { client.requestActivityUpdates(INTERVAL_MS, pendingIntent()) }
            .onSuccess { task -> task.addOnFailureListener { e -> onSubscribeFailed(e) } }
            .onFailure { e -> onSubscribeFailed(e) }
    }
```

- [ ] **Step 2: Wire it in the engine and fix the stale comment**

In `MonitorEngine.applyGpsGate` (~line 524), replace:

```kotlin
        if (gpsWanted && gpsPauseParked) motionSource.start() else motionSource.stop()
```

with:

```kotlin
        if (gpsWanted && gpsPauseParked) {
            motionSource.start()
            motionSource.maybeResubscribe(now)
        } else {
            motionSource.stop()
        }
```

(`applyGpsGate` is called from both `onPoll` and the 5-min range tick, so the refresh fires even with `onPoll` stalled; the elapsed check makes the per-frame calls free.)

In `startRangeLoop` (~lines 767–771), replace the now-false note:

```kotlin
                // Note this driver alone cannot close the motion gate quickly: foldMotion folds
                // one reading per call, so three ticks (~15 min) of confident-STILL samples are
                // needed, and any staleness gap in between resets the run. That is fine — it is a
                // backstop against a stalled onPoll pinning GNSS on, and its failure direction is
                // GPS staying on. onPoll remains the driver that actually closes the gate.
```

with:

```kotlin
                // This driver can also CLOSE the motion gate: foldMotion re-derives the verdict
                // from the clock on every fold, so a tick past STILL_CLOSE_HOLD_MS closes it even
                // with onPoll stalled (worst case one tick of lag, erring toward GPS-on). It also
                // drives MotionSource.maybeResubscribe via applyGpsGate.
```

- [ ] **Step 3: Full suite + lint**

Run: `cd android && ./gradlew :app:testDebugUnitTest` — expected: all pass.
Run: `cd android && ./gradlew :app:lintDebug` — expected: 0 errors.
(No JVM test covers `maybeResubscribe` — Play Services has no harness here, same as `start()`/`stop()`; on-device verification is Task 4's.)

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/dev/joely/bmsmon/motion/MotionSource.kt android/app/src/main/java/dev/joely/bmsmon/monitor/MonitorEngine.kt
git commit -m "feat(android): periodic AR re-subscribe backstop for the closed gate"
```

---

### Task 3: CLAUDE.md — record the finding and the new semantics

**Files:**
- Modify: `CLAUDE.md` (repo root — the motion-gate paragraphs)

**Interfaces:** prose only; the claims must match Task 1/2 code exactly (`STILL_CLOSE_HOLD_MS = 10 min`, `RESUBSCRIBE_MS = 6 h`, deleted `MOTION_STALE_MS`/`STILL_DEBOUNCE_N`, branch order, fail-open paths).

- [ ] **Step 1: Replace the fold-rules block**

Find the paragraph beginning `` `foldMotion(prev, reading, nowMs)` (`model/BatterySaver.kt` — pure, no clock, JVM-tested) folds one reading at a time into a `MotionGate`, **asymmetrically**, in this branch order: `` and its five bullet points, and replace paragraph + bullets with:

```
`foldMotion(prev, reading, nowMs)` (`model/BatterySaver.kt` — pure, no clock, JVM-tested) was
**reworked 2026-08-09 to silence-as-stillness semantics** (spec:
`docs/superpowers/specs/2026-08-09-silence-as-stillness-motion-gate-design.md`), because the
first night of motion telemetry proved AR delivery is **motion-triggered at the sensor level** —
4 readings in ~18 h while genuinely parked, rich ~5.7 s cadence in vehicles — so the original
debounce-and-staleness rule demanded evidence that never arrives and the gate **never closed in
the recorded telemetry era** (70,779 rows, zero closes, GNSS on all night). Silence after a
confident STILL is stillness evidence, not signal loss. Branch order, still load-bearing:

- **null reading** → fails open, gate reset — every unusable-signal path (permission denied, AR
  unavailable, no reading yet, source stopped) still lands here.
- **already folded** (`reading.atMs == gate.lastConfidentAtMs`) → holds the run and **re-derives
  the verdict from the clock** — this is the branch silence closes through, since gate
  evaluations keep arriving (per BLE frame + the 5-min range tick) while readings do not.
- **uncertain** (confidence < `STILL_CONFIDENCE_MIN` 75) → same as already-folded: uncertainty
  neither starts, breaks, nor ends a run, and there is no longer a staleness deadline.
- **confident STILL** → starts the run if none (`stillSinceMs` = the reading's own `atMs`), else
  keeps its start; the verdict closes once the run is `STILL_CLOSE_HOLD_MS` (**10 min**,
  inclusive) old.
- **confident non-STILL** → reopens on a **single** reading, run cleared (unchanged).

`MOTION_STALE_MS` and `STILL_DEBOUNCE_N` are **deleted**. The hold replaces the debounce's
anti-flap job (a stoplight STILL must survive 10 uncontradicted minutes; in-vehicle delivery
contradicts it in seconds), and 10 min was chosen over 5 so train-station stops rarely close the
gate mid-trip — one that does self-corrects on departure vibration at the cost of one GNSS
restart. The inverted risk — a silently-dead AR subscription holding the gate **closed** while
parked — was explicitly accepted, bounded by the discharge clause (chair outings discharge at the
start, and `gpsShouldRun` still requires BOTH halves to pause) and by
`MotionSource.maybeResubscribe`: the same PendingIntent's update request is re-issued every
`RESUBSCRIBE_MS` (6 h) from `applyGpsGate`, and since a fresh request delivers one immediate
reading, each refresh doubles as a stillness probe. Restarts self-heal: the subscribe burst's one
reading starts a run and the gate closes 10 min later, where the old rules left a restarted gate
open forever.
```

- [ ] **Step 2: Retire the superseded open-item text**

Two stale passages must not survive as open questions:

1. The paragraph beginning `**The saving is PARTIAL, not full, and the tuning fix for it is OPEN.**` — replace the whole paragraph with:

```
**The 2026-08-08 "partial saving / MOTION_STALE_MS tuning OPEN" question is RESOLVED by the
2026-08-09 rework above** — the first night of motion telemetry showed the saving was not partial
but **zero** (the gate never closed), the staleness window was the wrong knob entirely, and
silence-as-stillness replaced it. AR's true power cost remains unmeasured (its revert condition
stands), and the wire-cost measurement is still owed.
```

2. In the `**VERIFIED IN THE FIELD 2026-08-08**` section at the end of the file, the sentence beginning `Still open, unchanged: the saving remains **partial**` — replace that sentence with:

```
Still open after the 2026-08-09 silence-as-stillness rework: AR's own power cost is still
**unmeasured** with its revert condition intact, and the wire-cost measurement is still owed.
```

Keep the file's ~100-char hard-wrap style in all inserted text; re-wrap any line the edits leave over ~100 chars.

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: silence-as-stillness gate semantics and the delivery finding"
```

---

### Task 4: Deploy (APK only) and verify on-device (post-merge, main session — needs ADB + SSH)

Runs after the branch merges to `main`. No server/web files changed, so there is NO image build or NAS step — do not run them.

- [ ] **Step 1: Build, install, relaunch, confirm process**

```bash
cd android && ./gradlew :app:assembleDebug
adb -s adb-1C091FDF6003V0-RQzUxy._adb-tls-connect._tcp install -r app/build/outputs/apk/debug/app-debug.apk
adb -s adb-1C091FDF6003V0-RQzUxy._adb-tls-connect._tcp shell am start -n dev.joely.bmsmon/.MainActivity
adb -s adb-1C091FDF6003V0-RQzUxy._adb-tls-connect._tcp shell 'ps -A | grep bmsmon'
```

Expected: `Success`, activity starts, process listed. On `INSTALL_FAILED_UPDATE_INCOMPATIBLE`: STOP, never uninstall.

- [ ] **Step 2: Verify the gate closes (~10–12 min after launch, phone parked)**

```bash
ssh joely@ddnas02 'bash -lc "docker exec bmsmon-db psql -U bmsmon -d bmsmon -At -c \"SELECT to_timestamp(ts_ms/1000), motion_still, motion_activity, lat IS NOT NULL AS gps FROM samples WHERE motion_at_ms IS NOT NULL ORDER BY ts_ms DESC LIMIT 5\""'
```

Expected: `motion_still = t` on fresh rows once ~10 min have passed since the restart's burst reading (the first `t` in the entire telemetry era), and `gps` flipping to `f` on subsequent rows as the pause takes effect (`FOREGROUND_SERVICE_TYPE_LOCATION` drops 24 → 16 in `dumpsys activity services` if double-checking on-device). If `motion_still` stays `f` past 15 min: read `logcat | grep MotionSource` for whether the burst reading arrived, before touching anything.

- [ ] **Step 3: Record the outcome**

Update the memory note `bmsmon-ar-delivery-motion-gated.md` with a "fix shipped + verified <date>" line, and verify CLAUDE.md's claims against what the device actually did. The 6 h re-subscribe probe is verified opportunistically later (a reading logged ~6 h after launch with no other cause); note it as owed if not observed.
