# Motion Reading Timestamp (`motion_at_ms`) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upload the motion reading's own timestamp (`MotionReading.atMs`) as `motion_at_ms` on every telemetry sample, so "gate failed open on a stale reading" is distinguishable from "debounce not yet met" in prod SQL, and Play Services' delivery-gap structure becomes measurable without ADB.

**Architecture:** Pure instrumentation threading: `MonitorEngine.onPoll` already holds the reading; pass `motion?.atMs` through `TelemetryReporter.report()` → `CloudJson.sampleJson()` → `SampleJson.motion_at_ms` → server `SampleIn.motion_at_ms` (clamp-to-null validator) → `samples.motion_at_ms bigint` via the generic `_COLS`/`_INSERT` mechanism. No gate-logic change anywhere.

**Tech Stack:** Kotlin (kotlinx.serialization), FastAPI + Pydantic + asyncpg, Postgres 16, JUnit, pytest.

**Spec:** `docs/superpowers/specs/2026-08-08-motion-staleness-telemetry-design.md`

## Global Constraints

- Commit messages: NEVER mention AI/Claude/generated (repo rule, both CLAUDE.md files).
- Deploy order is load-bearing: **server first, then the APK** — old server silently ignores the new key and reads as an Android failure.
- **NEVER `adb uninstall dev.joely.bmsmon`**; `install -r` only, then `am start -n dev.joely.bmsmon/.MainActivity`, then `ps -A | grep bmsmon`.
- Everything nullable end to end: an older client omitting `motion_at_ms` must ingest unchanged.
- Null semantics: `motion_at_ms` is null exactly when `motion_activity`/`motion_confidence` are (no reading at all); `motion_still` stays always-populated.
- No rounding/truncation of the timestamp — it is the reading's identity (the gate's dedup key).
- No WebUI change, no local Room change, no change to `foldMotion`/`MotionGate`/`MotionSource`.
- Server tests need the dev Postgres: `docker compose -f server/docker-compose.dev.yml up -d` (from repo root). Run pytest with the venv: `cd server && .venv/bin/python -m pytest` (bare `python` lacks deps).

---

### Task 1: Server — column, model field, insert, tests

**Files:**
- Modify: `server/app/db/schema.sql` (after the `motion_still` ALTER at line ~114)
- Modify: `server/app/models.py` (`SampleIn` — field after `motion_still` at line ~43, validator after `_clip_conf` at line ~61)
- Modify: `server/app/db/queries.py` (`_COLS` line ~11, `_INSERT` lines ~36–55, `_INSERT_FIELDS` line ~57)
- Test: `server/tests/test_ingest_jwt.py`

**Interfaces:**
- Consumes: existing ingest pipeline (`sample_row()` copies every `_COLS` key from the parsed `SampleIn` dict; `_INSERT` is positional — currently 30 params `$1..$30`).
- Produces: `samples.motion_at_ms bigint` column; `SampleIn.motion_at_ms: int | None` accepting epoch-ms, clamping garbage to `None`. Task 5's verification queries rely on the column name `motion_at_ms`.

- [ ] **Step 1: Write the failing tests**

In `server/tests/test_ingest_jwt.py`, after `test_ingest_without_motion_fields_still_works` (~line 417), add two tests modeled on `test_ingest_accepts_motion_fields` (line 358):

```python
async def test_ingest_persists_motion_at_ms(app, client):
    # The reading's own timestamp rides beside the motion fields; age (ts_ms - motion_at_ms)
    # is what distinguishes a stale fail-open from a debounce still counting.
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    payload = {"batch_seq": 18, "samples": [
        {"ts_ms": 1719686400000, "address": A, "soc": 87.0,
         "motion_activity": "STILL", "motion_confidence": 100, "motion_still": False,
         "motion_at_ms": 1719686245000}]}
    body = json.dumps(payload).encode()
    r = await client.post("/api/v1/ingest", content=body,
                          headers={"Authorization": f"Bearer {_token(priv, device_id, body)}"})
    assert r.status_code == 200
    assert r.json() == {"accepted": 1, "last_seq": 18}
    async with app.state.pool.acquire() as conn:
        row = await conn.fetchrow("SELECT motion_at_ms FROM samples")
    assert row["motion_at_ms"] == 1719686245000


async def test_ingest_clamps_out_of_range_motion_at_ms(app, client):
    # Python ints are unbounded; a huge value reaching $N::bigint[] would 500 the WHOLE
    # set-based batch insert, which the phone treats as Poison and drops — losing real
    # telemetry to one bogus field. Same rationale as the motion_confidence clamp.
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    payload = {"batch_seq": 19, "samples": [
        {"ts_ms": 1719686400000, "address": A, "soc": 87.0,
         "motion_activity": "STILL", "motion_confidence": 100, "motion_still": True,
         "motion_at_ms": 10**19}]}
    body = json.dumps(payload).encode()
    r = await client.post("/api/v1/ingest", content=body,
                          headers={"Authorization": f"Bearer {_token(priv, device_id, body)}"})
    assert r.status_code == 200
    assert r.json() == {"accepted": 1, "last_seq": 19}
    async with app.state.pool.acquire() as conn:
        row = await conn.fetchrow("SELECT motion_activity, motion_at_ms FROM samples")
    assert row["motion_activity"] == "STILL"
    assert row["motion_at_ms"] is None
```

Also extend the existing old-client test `test_ingest_without_motion_fields_still_works` (~line 401): change its SELECT to

```python
        row = await conn.fetchrow(
            "SELECT motion_activity, motion_confidence, motion_still, motion_at_ms FROM samples")
```

and add a final assertion:

```python
    assert row["motion_at_ms"] is None
```

- [ ] **Step 2: Run tests to verify they fail**

Run (dev Postgres must be up: `docker compose -f server/docker-compose.dev.yml up -d` from repo root):

```bash
cd server && .venv/bin/python -m pytest tests/test_ingest_jwt.py -k motion -v
```

Expected: the two new tests FAIL (`column "motion_at_ms" does not exist` — schema.sql reapplies on pool creation, so the failure may instead be the value coming back as absent/KeyError before the column lands; either way FAIL). `test_ingest_without_motion_fields_still_works` also fails on the widened SELECT.

- [ ] **Step 3: Implement**

`server/app/db/schema.sql` — after the `motion_still` line (~114):

```sql
ALTER TABLE samples ADD COLUMN IF NOT EXISTS motion_at_ms      bigint;
```

`server/app/models.py` — in `SampleIn`, after `motion_still: bool | None = None`:

```python
    motion_at_ms: int | None = None
```

and after the `_clip_conf` validator:

```python
    @field_validator("motion_at_ms")
    @classmethod
    def _clip_motion_at(cls, v: int | None) -> int | None:
        # Clamp rather than reject, same rationale as _clip_conf: an unbounded int reaching
        # `$N::bigint[]` would 500 the whole batch, which the phone drops as Poison. Bound is
        # "plausible epoch ms": positive and before 2100-01-01 UTC.
        return v if v is not None and 0 < v < 4_102_444_800_000 else None
```

`server/app/db/queries.py` — three positional edits that must stay in sync:

1. `_COLS`: append `"motion_at_ms"` after `"motion_still"`.
2. `_INSERT`: column list gains `motion_at_ms` after `motion_still` (line ~42), and the unnest gains `$31::bigint[]` after `$30::boolean[]` (line ~50).
3. `_INSERT_FIELDS`: append `"motion_at_ms"` after `"motion_still"`.

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd server && .venv/bin/python -m pytest tests/test_ingest_jwt.py -k motion -v
```

Expected: all motion tests PASS. Then the full suite:

```bash
cd server && .venv/bin/python -m pytest
```

Expected: all pass (was 194 at handover; +2 new).

- [ ] **Step 5: Commit**

```bash
git add server/app/db/schema.sql server/app/models.py server/app/db/queries.py server/tests/test_ingest_jwt.py
git commit -m "feat(server): store the motion reading's timestamp on samples"
```

---

### Task 2: Android — wire encoding (`CloudJson`)

**Files:**
- Modify: `android/app/src/main/java/dev/joely/bmsmon/cloud/CloudJson.kt` (`SampleJson` ~line 37, `sampleJson()` ~lines 59–81)
- Test: `android/app/src/test/java/dev/joely/bmsmon/cloud/CloudJsonTest.kt` (~lines 189–214)

**Interfaces:**
- Consumes: nothing new.
- Produces: `CloudJson.sampleJson(...)` gains parameter `motionAtMs: Long? = null` placed **immediately after `motionStill`** (before `cells`); wire key `motion_at_ms`. Task 3 calls it with that name.

- [ ] **Step 1: Extend the two existing tests to fail**

In `CloudJsonTest.kt`, `motionFieldsAreEncodedWhenPresent` (~line 189): add `motionAtMs = 1_700_000_000_123L` after `motionStill = false`, and a fourth assertion:

```kotlin
        assertTrue(s.contains("\"motion_at_ms\":1700000000123"))
```

`motionFieldsAreOmittedEntirelyWhenAbsent` (~line 204): add a fourth assertion:

```kotlin
        assertFalse(s.contains("motion_at_ms"))
```

- [ ] **Step 2: Run to verify failure**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.cloud.CloudJsonTest"
```

Expected: FAIL — compile error (`no value passed for parameter` / unknown parameter `motionAtMs`).

- [ ] **Step 3: Implement**

`SampleJson` — after `val motion_still: Boolean? = null,` (keeping `cells` last):

```kotlin
    val motion_at_ms: Long? = null,
```

`sampleJson()` signature — after `motionActivity: String? = null, motionConfidence: Int? = null, motionStill: Boolean? = null,`:

```kotlin
        motionAtMs: Long? = null,
```

`sampleJson()` body — the positional `SampleJson(...)` construction gains `motionAtMs,` after `motionActivity, motionConfidence, motionStill,` (before the `cells` argument). No finite-guard: it is a `Long`, not a float.

- [ ] **Step 4: Run to verify pass**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.cloud.CloudJsonTest"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/dev/joely/bmsmon/cloud/CloudJson.kt android/app/src/test/java/dev/joely/bmsmon/cloud/CloudJsonTest.kt
git commit -m "feat(android): encode motion_at_ms on uploaded samples"
```

---

### Task 3: Android — thread the timestamp through reporter and engine

**Files:**
- Modify: `android/app/src/main/java/dev/joely/bmsmon/cloud/TelemetryReporter.kt` (`report()` ~lines 126–153)
- Modify: `android/app/src/main/java/dev/joely/bmsmon/monitor/MonitorEngine.kt` (the `reporter?.report(...)` call at ~line 684–688)

**Interfaces:**
- Consumes: `CloudJson.sampleJson(..., motionAtMs = ...)` from Task 2; `MotionReading.atMs` (exists — `model/BatterySaver.kt:96`).
- Produces: `TelemetryReporter.report(...)` gains a required parameter `motionAtMs: Long?` after `motionStill: Boolean?`. `reportLink()` is untouched (link events carry no motion).

No unit test covers `report()` (verified: nothing under `android/app/src/test/` references `TelemetryReporter`); the compile plus the full suite is the check, and on-device verification happens in Task 5.

- [ ] **Step 1: Implement**

`TelemetryReporter.report()` — after `motionStill: Boolean?,` in the signature:

```kotlin
        motionAtMs: Long?,
```

and in its `CloudJson.sampleJson(...)` call, after `motionActivity, motionConfidence, motionStill,`:

```kotlin
            motionAtMs,
```

`MonitorEngine.kt` ~line 687 — the reading and gate already come from the single locked `applyGpsGate(now)` call; extend the report call:

```kotlin
            motion?.activity, motion?.confidence, gate.still, motion?.atMs,
```

(replacing the current `motion?.activity, motion?.confidence, gate.still,` line — the null-together semantics come free: `motion?.atMs` is null exactly when `motion?.activity` is.)

- [ ] **Step 2: Full Android suite + lint**

```bash
cd android && ./gradlew :app:testDebugUnitTest
```

Expected: all pass (382 at handover; Task 2 extended existing tests, count unchanged).

```bash
cd android && ./gradlew :app:lintDebug
```

Expected: 0 errors. (If the task name is unknown in this project use `./gradlew lint`.)

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/dev/joely/bmsmon/cloud/TelemetryReporter.kt android/app/src/main/java/dev/joely/bmsmon/monitor/MonitorEngine.kt
git commit -m "feat(android): thread the motion reading timestamp to the reporter"
```

---

### Task 4: Docs — record the gap as closed in CLAUDE.md

**Files:**
- Modify: `/home/joely/bmsmon/CLAUDE.md` (the motion-state `samples` paragraph, and the closing "Known gap" paragraph)

**Interfaces:** none — prose only.

- [ ] **Step 1: Update the `samples` motion-state paragraph**

In the paragraph beginning `` `samples` also carries **motion state** ``, make two edits:

1. Replace the stale deploy parenthetical `` (`feat/motion-telemetry`, 2026-08-08 — **not yet deployed**: the columns and ingest mapping exist only on that branch until it merges and the server image is rebuilt/redeployed) `` with `` (deployed 2026-08-08 19:55) ``.
2. After the sentence ending `all nullable.` (the one listing the three columns), insert:

```
A fourth column, `motion_at_ms` (bigint — `MotionReading.atMs`, the reading's own wall-clock
timestamp, same device clock as `ts_ms`), was added 2026-08-08 (spec:
`docs/superpowers/specs/2026-08-08-motion-staleness-telemetry-design.md`): `ts_ms - motion_at_ms`
is the reading's age, which separates "gate failed open on staleness" from "debounce not yet met",
and distinct `motion_at_ms` values identify individual readings, making the Play Services
delivery-gap distribution measurable from prod SQL. It is null exactly when
`motion_activity`/`motion_confidence` are; garbage values clamp to null server-side
(`_clip_motion_at`, mirroring `_clip_conf`).
```

- [ ] **Step 2: Close the "Known gap" paragraph at the end of the file**

The final paragraph ("**Known gap found immediately by using it:**") ends with `Worth doing before
the next diagnostic cycle rather than during one, which is the same lesson that produced this
feature.` Append to that paragraph:

```
**CLOSED 2026-08-08: `motion_at_ms` ships exactly this** — see the motion-state paragraph above.
```

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: motion_at_ms closes the staleness observability gap"
```

---

### Task 5: Deploy and verify (post-merge, main session only — needs SSH + ADB)

Runs after the branch merges to `main` (via superpowers:finishing-a-development-branch). **Server first, then APK — order is load-bearing.**

**Files:** none (operational).

- [ ] **Step 1: Merge triggers the image build; watch it**

```bash
gh run watch
```

Expected: `build-server` workflow green (push touching `server/**` triggers it).

- [ ] **Step 2: Deploy the server, verify column + old client unaffected**

```bash
ssh joely@ddnas02 'bash -lc "cd /share/bsv/docker-compose && \
  docker compose --env-file .env -f bmsmon/docker-compose.yml pull bmsmon-api && \
  docker compose --env-file .env -f bmsmon/docker-compose.yml up -d bmsmon-api"'
curl -fsS https://bmsmon.covert.life/api/v1/health
```

Expected: `{"status":"ok"}`. Then:

```bash
ssh joely@ddnas02 'bash -lc "docker exec bmsmon-db psql -U bmsmon -d bmsmon -At -c \"SELECT count(*) FROM information_schema.columns WHERE table_name = \\\$\\\$samples\\\$\\\$ AND column_name = \\\$\\\$motion_at_ms\\\$\\\$\""'
```

(Dollar-quoting instead of SQL single quotes — the fish → ssh → bash → psql quoting stack makes
literal single quotes fragile; if the escaping still fights, just run `ssh joely@ddnas02` first and
issue the `docker exec` interactively.)

Expected: `1`. Then confirm the **old** phone client is still ingesting (row count advancing, `motion_at_ms` all null):

```bash
ssh joely@ddnas02 'bash -lc "docker exec bmsmon-db psql -U bmsmon -d bmsmon -At -c \"SELECT max(to_timestamp(ts_ms/1000)), count(*) FILTER (WHERE motion_at_ms IS NOT NULL) FROM samples WHERE ts_ms > (extract(epoch from now())*1000 - 600000)\""'
```

Expected: a max timestamp within the last minute or two; the filtered count `0`.

- [ ] **Step 3: Build and install the APK**

```bash
cd android && ./gradlew :app:assembleDebug
adb -s adb-1C091FDF6003V0-RQzUxy._adb-tls-connect._tcp install -r app/build/outputs/apk/debug/app-debug.apk
adb -s adb-1C091FDF6003V0-RQzUxy._adb-tls-connect._tcp shell am start -n dev.joely.bmsmon/.MainActivity
adb -s adb-1C091FDF6003V0-RQzUxy._adb-tls-connect._tcp shell 'ps -A | grep bmsmon'
```

Expected: install `Success`, activity starts, process listed. If install fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` (signature mismatch — wrong variant): **STOP. Never uninstall `dev.joely.bmsmon`.** Ask the user which variant the device runs.

- [ ] **Step 4: Verify new rows carry the field, and it behaves**

Wait for one upload cycle (~15–30 s), then:

```bash
ssh joely@ddnas02 'bash -lc "docker exec bmsmon-db psql -U bmsmon -d bmsmon -At -c \"SELECT motion_activity, motion_confidence, motion_still, ts_ms - motion_at_ms AS age_ms FROM samples WHERE motion_at_ms IS NOT NULL ORDER BY ts_ms DESC LIMIT 5\""'
```

Expected: rows with plausible `age_ms` (0 to a few minutes; may legitimately exceed 150 000 during delivery gaps — that is the phenomenon this field exists to expose). Confirm packs are reporting (BLE healthy) by the rows being fresh — never by the adapter flag.
