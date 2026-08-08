# Motion State in Uploaded Telemetry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Record what Activity Recognition reported and what the motion gate concluded on every uploaded telemetry sample, so gate behaviour is reconstructible from the server instead of only from `logcat` on a device reachable over ADB at home.

**Architecture:** Three nullable fields ride the existing sample row end to end — Kotlin `SampleJson` → gzipped batch POST → server Pydantic `SampleIn` → three nullable Postgres columns. `MonitorEngine` already holds both the reading and the gate verdict where it calls `reporter.report()`, so no new state or lifecycle is introduced. Everything is nullable, so older clients and pre-existing rows keep working untouched.

**Tech Stack:** Kotlin, kotlinx.serialization, FastAPI, Pydantic, asyncpg, Postgres 16, JUnit 4, pytest.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-08-08-motion-telemetry-design.md`. Read it before starting.
- Repo root `/home/joely/bmsmon`. Android gradle dir `/home/joely/bmsmon/android`. Server dir `/home/joely/bmsmon/server`.
- Android: `./gradlew :app:testDebugUnitTest` (**378 currently pass**) · `./gradlew :app:assembleDebug` · `./gradlew :app:lintDebug` (**0 errors — keep it so**).
- Server: `cd /home/joely/bmsmon/server && .venv/bin/python -m pytest -q` (**188 currently pass**). Bare `python` lacks the deps — use the venv.
- Commit messages must contain **no** reference to AI, Claude, or automated generation. Hard repo rule.
- **Two similarly-named classes exist — do not conflate them.** `SampleJson` (Kotlin, `android/app/src/main/java/dev/joely/bmsmon/cloud/CloudJson.kt:10`) is the wire row the phone encodes. `SampleIn` (Pydantic, `server/app/models.py:15`) is the same row as the server parses it. **Both** need the three fields.
- All three fields are **nullable everywhere**. A client that never sends them must keep ingesting successfully — that is what stops this breaking the running deployment.
- `model/BatterySaver.kt` is **pure**: zero imports, no Android types, no clock access. It must stay that way.
- This app is read-only over BLE. Do not modify anything under `ble/`. Never send a BMS write command.
- **Device protocol** (the phone is the user's live wheelchair battery monitor): `adb install -r` only, **NEVER `adb uninstall dev.joely.bmsmon`** (~400 MB irreplaceable telemetry); `install -r` leaves the app stopped, so always `adb -s <serial> shell am start -n dev.joely.bmsmon/.MainActivity` and confirm with `ps -A | grep bmsmon`; **never `am force-stop`**; `adb shell input swipe X Y X Y <duration>` is **not** a safe long-press on this device (it reads as a notification-shade drag — it toggled airplane mode and left the phone at its PIN lock screen). Serial: `adb-1C091FDF6003V0-RQzUxy._adb-tls-connect._tcp`.

## File Structure

| File | Responsibility |
|---|---|
| `android/.../model/BatterySaver.kt` (modify) | `MotionReading` gains `activity: String` — the reading must carry the name, not discard it |
| `android/.../motion/MotionSource.kt` (modify) | Populate `activity`; widen `activityName()` from private to internal |
| `android/.../cloud/CloudJson.kt` (modify) | Three fields on `SampleJson`; three params on `sampleJson()` |
| `android/.../cloud/TelemetryReporter.kt` (modify) | Three params on `report()`, passed through |
| `android/.../monitor/MonitorEngine.kt` (modify) | Supply reading + gate verdict at the `report()` call site |
| `server/app/models.py` (modify) | Three fields on `SampleIn` |
| `server/app/db/schema.sql` (modify) | Three `ADD COLUMN IF NOT EXISTS` |
| `server/app/db/queries.py` (modify) | Three columns in `_INSERT`, `_INSERT_FIELDS`, and the unnest cast list |
| `server/app/routers/api_device.py` (modify) | Map the three fields into the row dict |

---

### Task 1: Carry the activity name on `MotionReading`

**Files:**
- Modify: `android/app/src/main/java/dev/joely/bmsmon/model/BatterySaver.kt:93`
- Modify: `android/app/src/main/java/dev/joely/bmsmon/motion/MotionSource.kt` (the receiver's `cache.set(...)`, and `activityName` at ~line 153)
- Test: `android/app/src/test/java/dev/joely/bmsmon/BatterySaverTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `data class MotionReading(val still: Boolean, val confidence: Int, val atMs: Long, val activity: String)`; `internal fun activityName(type: Int): String` in `motion/MotionSource.kt`.

Today `MotionReading` holds only `still`, `confidence`, `atMs`. The activity type is mapped to a name for the log line and then **discarded**, so the upload path has nothing to send. This task fixes that first, because every later task depends on it.

- [ ] **Step 1: Write the failing test**

Append to `BatterySaverTest.kt`:

```kotlin
    // The reading must carry the activity NAME, not just the still/not-still collapse — the whole
    // point of uploading it is telling "UNKNOWN@41" apart from "IN_VEHICLE@90", which both map to
    // still=false and are indistinguishable without it.
    @Test fun motionReadingCarriesTheActivityName() {
        val r = MotionReading(still = false, confidence = 90, atMs = 1_000L, activity = "IN_VEHICLE")
        assertEquals("IN_VEHICLE", r.activity)
        assertFalse(r.still)
    }

    // Adding the field must not disturb the gate: foldMotion ignores it entirely.
    @Test fun activityNameDoesNotAffectTheGateVerdict() {
        val now = 10_000_000L
        var g = MotionGate()
        repeat(STILL_DEBOUNCE_N) { i ->
            g = foldMotion(g, MotionReading(true, 99, now - (STILL_DEBOUNCE_N - i) * 1_000L, "STILL"), now)
        }
        assertTrue(g.still)
    }
```

Add `import dev.joely.bmsmon.model.MotionGate`, `import dev.joely.bmsmon.model.STILL_DEBOUNCE_N`, and `import dev.joely.bmsmon.model.foldMotion` if not already present in that file's import block.

- [ ] **Step 2: Run to verify it fails**

```bash
cd /home/joely/bmsmon/android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.BatterySaverTest"
```

Expected: FAIL — `MotionReading` has no parameter `activity`.

- [ ] **Step 3: Add the field**

In `model/BatterySaver.kt`, change the declaration at line 93 to:

```kotlin
data class MotionReading(
    val still: Boolean,
    val confidence: Int,
    val atMs: Long,
    /**
     * Most probable detected activity, as a readable name (`STILL`, `IN_VEHICLE`, `UNKNOWN`, …).
     *
     * Carried purely so it can be uploaded — `foldMotion` never reads it. It exists because
     * `still = false` collapses `UNKNOWN@41` and `IN_VEHICLE@90` into the same value, and telling
     * those apart from the server is the entire reason this field was added.
     */
    val activity: String,
)
```

Keep it a plain `String`: this file is pure, with zero imports, and must not gain an Android type.

- [ ] **Step 4: Populate it and widen the mapper**

In `motion/MotionSource.kt`, change `private fun activityName(` to `internal fun activityName(`, and pass the name when constructing the reading in the receiver:

```kotlin
            cache.set(
                MotionReading(
                    still = top.type == DetectedActivity.STILL,
                    confidence = top.confidence,
                    atMs = System.currentTimeMillis(),
                    activity = activityName(top.type),
                ),
            )
```

Do **not** copy the `when` block into a second place — two copies would drift.

- [ ] **Step 5: Run tests and lint**

```bash
cd /home/joely/bmsmon/android && ./gradlew :app:testDebugUnitTest :app:lintDebug
```

Expected: PASS (380 tests), lint 0 errors.

- [ ] **Step 6: Commit**

```bash
cd /home/joely/bmsmon && git add android/app/src/main/java/dev/joely/bmsmon/model/BatterySaver.kt android/app/src/main/java/dev/joely/bmsmon/motion/MotionSource.kt android/app/src/test/java/dev/joely/bmsmon/BatterySaverTest.kt && git commit -m "feat(android): carry the activity name on MotionReading

still=false collapses UNKNOWN and IN_VEHICLE into one value; telling
them apart from the server is why the name is needed."
```

---

### Task 2: Put the three fields on the wire

**Files:**
- Modify: `android/app/src/main/java/dev/joely/bmsmon/cloud/CloudJson.kt` (`SampleJson` at :10, `sampleJson()` at :57)
- Modify: `android/app/src/main/java/dev/joely/bmsmon/cloud/TelemetryReporter.kt` (`report()` at :126)
- Modify: `android/app/src/main/java/dev/joely/bmsmon/monitor/MonitorEngine.kt` (the `reporter?.report(...)` call site)
- Test: `android/app/src/test/java/dev/joely/bmsmon/cloud/CloudJsonTest.kt`

**Interfaces:**
- Consumes: `MotionReading.activity` (Task 1).
- Produces: `SampleJson` fields `motion_activity: String?`, `motion_confidence: Int?`, `motion_still: Boolean?`; `sampleJson(..., motionActivity: String? = null, motionConfidence: Int? = null, motionStill: Boolean? = null)`; `report(..., motionActivity: String? = null, motionConfidence: Int? = null, motionStill: Boolean? = null)`.

- [ ] **Step 1: Write the failing tests**

Append to `CloudJsonTest.kt`:

```kotlin
    @Test fun motionFieldsAreEncodedWhenPresent() {
        val s = CloudJson.sampleJson(
            tsMs = 1_700_000_000_000L, address = "AA:BB", advertisedName = null, alias = null,
            groupId = null, state = "Idle", soc = 50f, currentA = 0f, powerW = 0f, voltageV = 13.2f,
            tempC = 25f, mosfetTempC = null, soh = null, fullChargeAh = null, remainingAh = null,
            cycles = null, cellMinV = null, cellMaxV = null, regen = false, linkEvent = null,
            motionActivity = "IN_VEHICLE", motionConfidence = 90, motionStill = false,
        )
        assertTrue(s.contains("\"motion_activity\":\"IN_VEHICLE\""))
        assertTrue(s.contains("\"motion_confidence\":90"))
        assertTrue(s.contains("\"motion_still\":false"))
    }

    // The omission is what makes this free when motion sensing is unavailable — assert it, do not
    // assume it. `explicitNulls = false` should drop the keys entirely rather than emit nulls.
    @Test fun motionFieldsAreOmittedEntirelyWhenAbsent() {
        val s = CloudJson.sampleJson(
            tsMs = 1_700_000_000_000L, address = "AA:BB", advertisedName = null, alias = null,
            groupId = null, state = "Idle", soc = 50f, currentA = 0f, powerW = 0f, voltageV = 13.2f,
            tempC = 25f, mosfetTempC = null, soh = null, fullChargeAh = null, remainingAh = null,
            cycles = null, cellMinV = null, cellMaxV = null, regen = false, linkEvent = null,
        )
        assertFalse(s.contains("motion_activity"))
        assertFalse(s.contains("motion_confidence"))
        assertFalse(s.contains("motion_still"))
    }
```

Match the argument style already used by the neighbouring tests in that file — if they pass positionally rather than by name, follow suit; the point is the three motion assertions, not the call style.

- [ ] **Step 2: Run to verify it fails**

```bash
cd /home/joely/bmsmon/android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.cloud.CloudJsonTest"
```

Expected: FAIL — `sampleJson` has no parameter `motionActivity`.

- [ ] **Step 3: Add the wire fields**

In `CloudJson.kt`, add to `SampleJson` after `eta_full_min` (keep `cells` last, since it is the only list):

```kotlin
    val motion_activity: String? = null,
    val motion_confidence: Int? = null,
    val motion_still: Boolean? = null,
```

Add matching parameters to `sampleJson()` after `etaFullMin` and before `cells`, and pass them straight through to the constructor. **No `finiteOrNull()` treatment** — that guard exists for floats, and these are a `String`, an `Int` and a `Boolean`.

- [ ] **Step 4: Thread them through the reporter**

In `TelemetryReporter.report()`, add after `etaFullMin: Float? = null,`:

```kotlin
        motionActivity: String? = null,
        motionConfidence: Int? = null,
        motionStill: Boolean? = null,
```

and pass them to the `CloudJson.sampleJson(...)` call in the same order.

- [ ] **Step 5: Supply them at the call site**

In `MonitorEngine`, at the `reporter?.report(...)` call, read both halves from state that is already there — `motionSource.current()` for the reading and `motionGate.still` for the verdict:

```kotlin
        val motion = motionSource.current()
        reporter?.report(
            addr, roster.batteryAt(addr)?.advertisedName, roster.batteryAt(addr)?.alias,
            group?.id, t, now, regen, uploadFix?.lat, uploadFix?.lon, uploadFix?.accuracyM, etaFullMin,
            motion?.activity, motion?.confidence, motionGate.still,
        )
```

Note the asymmetry and keep it: the reading is nullable (there may be none yet), but `motionGate.still` is always a real verdict — a fresh `MotionGate()` means "not still", which is true and is the fail-open default.

- [ ] **Step 6: Run tests and lint**

```bash
cd /home/joely/bmsmon/android && ./gradlew :app:testDebugUnitTest :app:lintDebug
```

Expected: PASS (382 tests), lint 0 errors.

- [ ] **Step 7: Commit**

```bash
cd /home/joely/bmsmon && git add android/app/src/main/java/dev/joely/bmsmon/cloud/ android/app/src/main/java/dev/joely/bmsmon/monitor/MonitorEngine.kt android/app/src/test/java/dev/joely/bmsmon/cloud/CloudJsonTest.kt && git commit -m "feat(android): upload motion activity, confidence and gate verdict

Nullable throughout, so an install without motion sensing sends nothing
and costs no bytes."
```

---

### Task 3: Accept and store them server-side

**Files:**
- Modify: `server/app/models.py:15` (`SampleIn`)
- Modify: `server/app/db/schema.sql` (after the `ADD COLUMN IF NOT EXISTS` lines at ~110-111)
- Modify: `server/app/db/queries.py` (`_INSERT` at :35, `_INSERT_FIELDS` at :54)
- Modify: `server/app/routers/api_device.py` (the row dict built per sample)
- Test: the server test suite under `server/tests/`

**Interfaces:**
- Consumes: the wire fields from Task 2 — `motion_activity`, `motion_confidence`, `motion_still`.
- Produces: three nullable `samples` columns of the same names.

- [ ] **Step 1: Write the failing tests**

Add to the existing ingest test module (find it with `grep -rln "ingest" server/tests/` and follow that file's fixtures and style rather than inventing new ones). Two cases:

```python
def test_ingest_accepts_motion_fields(...):
    """A sample carrying motion state round-trips into the new columns."""
    # POST a batch whose sample includes:
    #   "motion_activity": "IN_VEHICLE", "motion_confidence": 90, "motion_still": False
    # then assert the stored row has those three values.

def test_ingest_without_motion_fields_still_works(...):
    """Older clients send no motion keys at all; ingest must not break and the columns are NULL."""
    # POST a batch whose sample omits all three keys entirely.
    # Assert the response is success and the stored row has NULL in all three.
```

The second test is the important one — it is what guarantees this change cannot break the phone currently uploading in production.

- [ ] **Step 2: Run to verify they fail**

```bash
cd /home/joely/bmsmon/server && .venv/bin/python -m pytest -q -k motion
```

Expected: FAIL — `SampleIn` rejects or ignores the unknown fields, and the columns do not exist.

- [ ] **Step 3: Add the Pydantic fields**

In `server/app/models.py`, in `SampleIn` alongside `gps_accuracy_m` / `eta_full_min` (lines 39-40):

```python
    motion_activity: str | None = None
    motion_confidence: int | None = None
    motion_still: bool | None = None
```

- [ ] **Step 4: Add the columns**

In `server/app/db/schema.sql`, after the existing `ALTER TABLE samples ADD COLUMN IF NOT EXISTS eta_full_min real;`:

```sql
ALTER TABLE samples ADD COLUMN IF NOT EXISTS motion_activity   text;
ALTER TABLE samples ADD COLUMN IF NOT EXISTS motion_confidence smallint;
ALTER TABLE samples ADD COLUMN IF NOT EXISTS motion_still      boolean;
```

Idempotent and run on pool creation, so these land automatically on container restart — there is no separate migration step.

- [ ] **Step 5: Extend the insert**

`queries.py` builds its arrays positionally from `_INSERT_FIELDS`, so **three places must stay in lockstep or the columns silently receive the wrong data**:

1. the column list inside `_INSERT` — add `,motion_activity,motion_confidence,motion_still` after `cell4_v`
2. the `unnest(...)` cast list — add `$28::text[], $29::smallint[], $30::boolean[]`
3. `_INSERT_FIELDS` — append `"motion_activity", "motion_confidence", "motion_still"` in the same order

Verify the three orderings match before running anything. A mismatch here type-errors at best and writes confidence into the activity column at worst.

- [ ] **Step 6: Map them in the router**

In `server/app/routers/api_device.py`, wherever the per-sample row dict is built for `insert_samples`, add the three keys reading from the `SampleIn` model, following exactly how `gps_accuracy_m` and `eta_full_min` are handled there.

- [ ] **Step 7: Run the server suite**

```bash
cd /home/joely/bmsmon/server && .venv/bin/python -m pytest -q
```

Expected: PASS (190 tests: 188 + 2 new).

- [ ] **Step 8: Commit**

```bash
cd /home/joely/bmsmon && git add server/ && git commit -m "feat(server): accept and store motion state on samples

Three nullable columns via idempotent ALTER TABLE, so they land on
container restart. A body omitting them still ingests, which is what
keeps the running client working."
```

---

### Task 4: Verify on-device and measure the wire cost

**Files:** none — this task is measurement. It changes no code.

**Interfaces:**
- Consumes: everything above.
- Produces: a recorded wire-cost figure, and the decision the spec defers to it.

The spec accepts that motion is a device-level fact written onto every pack's row (8× duplication) on the assumption gzip collapses it — and explicitly says **that is an assumption, not a measurement**. This task settles it.

- [ ] **Step 1: Build, install, relaunch**

```bash
cd /home/joely/bmsmon/android && ./gradlew :app:assembleDebug && \
adb -s adb-1C091FDF6003V0-RQzUxy._adb-tls-connect._tcp install -r app/build/outputs/apk/debug/app-debug.apk && \
adb -s adb-1C091FDF6003V0-RQzUxy._adb-tls-connect._tcp shell am start -n dev.joely.bmsmon/.MainActivity && \
adb -s adb-1C091FDF6003V0-RQzUxy._adb-tls-connect._tcp shell 'ps -A | grep bmsmon'
```

`install -r` only. **Never `adb uninstall`.** Confirm the process came back before continuing.

- [ ] **Step 2: Confirm the data actually arrives**

Wait ~2 minutes for a batch to flush (`FLUSH_AGE_MS` is 15 s, `MIN_BATCH` 20), then query production read-only:

```bash
ssh joely@ddnas02 'bash -lc "docker exec bmsmon-db psql -U bmsmon -d bmsmon -At -F, -c \
  \"SELECT motion_activity, motion_confidence, motion_still, count(*) \
    FROM samples WHERE ts_ms > (extract(epoch from now())-300)*1000 \
    GROUP BY 1,2,3 ORDER BY 4 DESC;\""'
```

Expected: non-null rows, e.g. `STILL,100,t,…`. If every row is null the phone is not sending them — check that Task 2's call site is reached, before assuming the server is at fault.

- [ ] **Step 3: Cross-check against the log**

```bash
adb -s adb-1C091FDF6003V0-RQzUxy._adb-tls-connect._tcp logcat -d | grep "MotionSource: reading" | tail -5
```

The activity and confidence values must agree with what landed in the database at the same timestamps. Agreement is the real proof; a non-null column proves only that *something* was written.

- [ ] **Step 4: Measure the wire cost and record it**

Compare uploaded batch sizes against the pre-change baseline (the reporter's `onStatus` surfaces gzipped wire bytes; `cloud/UploadRate.kt` tracks them). Record the actual before/after figure in the report — **a number, not an impression**.

If the increase is material, the spec's stated fallback is to populate the three fields **only on the staged base's rows** rather than all packs, since the staged base is the one whose behaviour anyone is diagnosing. Do not make that change unilaterally: report the number and let the coordinator decide.

- [ ] **Step 5: Leave the device healthy**

Confirm before finishing: app running, monitoring active (foreground service up, notification present), telemetry writing. The phone is the user's live wheelchair battery monitor.

---

### Task 5: Documentation

**Files:**
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: everything above, including Task 4's measured figure.
- Produces: no code.

- [ ] **Step 1: Document the columns**

In the section describing the `samples` table and its GPS columns, record that `samples` now also carries `motion_activity` (text), `motion_confidence` (smallint) and `motion_still` (boolean), all nullable; that they are the phone's Activity Recognition reading plus the motion gate's own verdict; and **why the verdict is stored separately** — it is not derivable from the reading, because the debounce carries state across readings and uncertainty holds the previous verdict.

Note that motion is device-level but written per-pack, with Task 4's measured wire cost as the justification.

- [ ] **Step 2: Record what it unlocks**

Add the diagnostic queries from the spec's "What this unlocks" section, so the next person does not rebuild them from scratch. Include the observability motivation in one line: the previous diagnosis needed ADB plus a hand-enlarged logcat buffer, and the evidence rotated away twice.

- [ ] **Step 3: Commit**

```bash
cd /home/joely/bmsmon && git add CLAUDE.md && git commit -m "docs: record motion state on the samples table"
```

---

## Verification before calling this done

- [ ] `./gradlew :app:testDebugUnitTest` passes in full
- [ ] `./gradlew :app:lintDebug` reports 0 errors
- [ ] `cd server && .venv/bin/python -m pytest -q` passes in full
- [ ] Production rows show non-null motion values that **agree with the device log** at matching timestamps
- [ ] A payload omitting all three fields still ingests (the running-client guarantee)
- [ ] The wire-cost delta is measured and recorded as a number
- [ ] Phone left running, monitoring active; `adb uninstall` never used
