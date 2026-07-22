# DB Retention Strategy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bound the production Postgres DB at ~10 GB steady state (hard budget 25 GB) by enriching the permanent 30-min rollup with capacity/cycle signals, then dropping raw monthly partitions older than 12 months — without ever losing the long-term battery-health record.

**Architecture:** `samples_rollup` (the forever-tier, per-`(address,30-min-bucket)` sums+counts) gains `cap_sum`/`cap_n` (absolute Ah capacity) and `cycles_max` (cycle count). A schema-version bump forces a one-time full re-roll that backfills those columns from still-present raw. A new daily background task drops `samples_YYYY_MM` partitions older than `BMSMON_RAW_RETENTION_MONTHS` months, gated so a partition is only dropped once fully folded into the rollup (`partition_end_ms <= high_water_ms`).

**Tech Stack:** Python 3, FastAPI, asyncpg, Postgres 16 (declarative RANGE partitioning), pytest/pytest-asyncio.

## Global Constraints

- All work is in `server/`. Run tests with the venv: `cd server && .venv/bin/python -m pytest`.
- Local dev DB is up via `docker compose -f server/docker-compose.dev.yml up -d` (Postgres on `localhost:5432`, user/pw/db all `bmsmon`).
- Schema is idempotent SQL in `server/app/db/schema.sql` applied on pool creation (`app/db/pool.py`) — use `ADD COLUMN IF NOT EXISTS`; there is no separate migration step.
- The rollup stores per-metric **SUMS + COUNTS**, never averages (sums re-aggregate exactly to coarser buckets); cycle count is monotonic → use `max`.
- Rollup rows include only real-telemetry rows: `link_event IS NULL`. Match this filter verbatim.
- Background tasks must never crash the app: catch-and-log, then continue on the next tick (mirror `_gps_scrub_loop`).
- `<= 0` config values disable a retention feature entirely (mirror `gps_retention_days`).
- Do NOT add voltage/current/power to the rollup, do NOT change the phone/ingest/learners, do NOT rework any chart (all out of scope per the spec).
- Spec: `docs/superpowers/specs/2026-07-22-db-retention-strategy-design.md`.

---

### Task 1: Enrich the rollup schema (columns + version marker)

**Files:**
- Modify: `server/app/db/schema.sql` (after the `samples_rollup` / `samples_rollup_state` blocks, ~line 96–106)
- Test: `server/tests/test_retention_schema.py` (create)

**Interfaces:**
- Produces: three new nullable-friendly columns on `samples_rollup` — `cap_sum double precision`, `cap_n int NOT NULL DEFAULT 0`, `cycles_max int`; and `samples_rollup_state.rollup_schema_ver smallint NOT NULL DEFAULT 0`.

- [ ] **Step 1: Write the failing test**

Create `server/tests/test_retention_schema.py`:

```python
"""Schema presence for the rollup health-archive enrichment (DB retention strategy)."""


async def _cols(conn, table):
    rows = await conn.fetch(
        "SELECT column_name FROM information_schema.columns WHERE table_name = $1", table)
    return {r["column_name"] for r in rows}


async def test_rollup_has_capacity_and_cycle_columns(app):
    async with app.state.pool.acquire() as conn:
        cols = await _cols(conn, "samples_rollup")
    assert {"cap_sum", "cap_n", "cycles_max"} <= cols


async def test_rollup_state_has_schema_version(app):
    async with app.state.pool.acquire() as conn:
        cols = await _cols(conn, "samples_rollup_state")
        # default row may not exist yet; the column must, defaulting to 0 when a row is written
        assert "rollup_schema_ver" in cols
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && .venv/bin/python -m pytest tests/test_retention_schema.py -v`
Expected: FAIL — `assert {'cap_sum','cap_n','cycles_max'} <= cols` (columns absent).

- [ ] **Step 3: Add the columns to schema.sql**

In `server/app/db/schema.sql`, immediately after the `CREATE TABLE IF NOT EXISTS samples_rollup_state (...)` block (~line 106), add:

```sql
-- DB retention strategy (2026-07-22): the rollup is the permanent (5-10 year) battery-health
-- archive. Enrich it with the two long-term signals raw carries but the rollup dropped, so raw
-- monthly partitions can be pruned at 12 months without losing capacity history. cap_sum/cap_n =
-- avg absolute full-charge capacity (Ah) per bucket (sum/count re-aggregate exactly); cycles_max =
-- cycle count (monotonic -> max, max-of-maxes re-aggregates exactly).
ALTER TABLE samples_rollup ADD COLUMN IF NOT EXISTS cap_sum double precision;
ALTER TABLE samples_rollup ADD COLUMN IF NOT EXISTS cap_n int NOT NULL DEFAULT 0;
ALTER TABLE samples_rollup ADD COLUMN IF NOT EXISTS cycles_max int;

-- Rollup schema version: when the code's ROLLUP_SCHEMA_VER exceeds this, the rollup pass resets
-- the high-water mark to 0 once, forcing a full re-roll that backfills new columns from raw.
ALTER TABLE samples_rollup_state ADD COLUMN IF NOT EXISTS rollup_schema_ver smallint NOT NULL DEFAULT 0;
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd server && .venv/bin/python -m pytest tests/test_retention_schema.py -v`
Expected: PASS (both tests).

- [ ] **Step 5: Commit**

```bash
cd server && git add app/db/schema.sql tests/test_retention_schema.py
git commit -m "feat(server): enrich samples_rollup with capacity/cycle columns + schema version"
```

---

### Task 2: Compute capacity/cycles in the rollup + version-gated re-roll

**Files:**
- Modify: `server/app/db/rollup.py` (the `_UPSERT` string ~line 47–72; add `ROLLUP_SCHEMA_VER` constant; add `ensure_rollup_schema_current()`; call it at the top of `run_rollup_pass`)
- Test: `server/tests/test_rollup.py` (append two tests; extend `_mk_rows` helper usage)

**Interfaces:**
- Consumes: `samples_rollup.cap_sum/cap_n/cycles_max`, `samples_rollup_state.rollup_schema_ver` (Task 1).
- Produces: `ru.ROLLUP_SCHEMA_VER: int` (= 1); `async ensure_rollup_schema_current(conn) -> bool` (True when it reset the mark); `run_rollup_pass` now populates the three columns and self-triggers the one-time backfill.

- [ ] **Step 1: Write the failing tests**

Append to `server/tests/test_rollup.py`:

```python
async def test_rollup_captures_capacity_and_cycles_exactly(app):
    """cap_sum/cap_n/cycles_max match a raw GROUP BY, and re-aggregate exactly to a
    coarser span (sum-of-sums / sum-of-counts for cap; max-of-maxes for cycles)."""
    pool = app.state.pool
    async with pool.acquire() as conn:
        await _seed_registry(conn)
        now = int(time.time() * 1000)
        base = ((now - 8 * 3_600_000) // B) * B
        rows = []
        for i in range(120):  # ~1 h across several 30-min buckets
            rows.append(q.sample_row(DEV, A, {
                "ts_ms": base + i * 60_000,
                "full_charge_ah": 100.0 - i * 0.01,   # slow fade, per-bucket avg differs
                "cycles": 10 + i // 40,               # monotonic step
            }))
        rows.append(q.sample_row(DEV, A, {"ts_ms": base + 5_000, "link_event": "Connected"}))  # excluded
        await q.insert_samples(conn, rows)
        await ru.run_rollup_pass(conn, now_ms=now)

        # Per-bucket equivalence vs a direct raw aggregate.
        rolled = await conn.fetch(
            "SELECT bucket_ms, cap_sum, cap_n, cycles_max FROM samples_rollup "
            "WHERE address = $1 ORDER BY bucket_ms", A)
        assert rolled
        for r in rolled:
            raw = await conn.fetchrow(
                f"""SELECT sum(full_charge_ah::float8) s, count(full_charge_ah) n, max(cycles) mx
                    FROM samples WHERE address=$1 AND link_event IS NULL
                      AND (ts_ms / {B}) * {B} = $2""", A, r["bucket_ms"])
            _eq(r["cap_sum"], raw["s"])
            assert r["cap_n"] == raw["n"]
            assert r["cycles_max"] == raw["mx"]

        # Re-aggregation across all buckets == overall raw.
        agg = await conn.fetchrow(
            "SELECT sum(cap_sum) s, sum(cap_n) n, max(cycles_max) mx FROM samples_rollup WHERE address=$1", A)
        overall = await conn.fetchrow(
            "SELECT sum(full_charge_ah::float8) s, count(full_charge_ah) n, max(cycles) mx "
            "FROM samples WHERE address=$1 AND link_event IS NULL", A)
        _eq(agg["s"] / agg["n"], overall["s"] / overall["n"])   # avg capacity, exact
        assert agg["mx"] == overall["mx"]


async def test_schema_version_bump_forces_one_time_reroll(app):
    """A stored schema version below the code's triggers exactly one high-water reset."""
    pool = app.state.pool
    async with pool.acquire() as conn:
        await _seed_registry(conn)
        now = int(time.time() * 1000)
        await q.insert_samples(conn, _mk_rows(A, now - 6 * 3_600_000, 40, 400_000))
        await ru.run_rollup_pass(conn, now_ms=now)
        assert await ru.get_high_water_ms(conn) > 0

        # Simulate an older on-disk schema version.
        await conn.execute("UPDATE samples_rollup_state SET rollup_schema_ver = 0")
        did_reset = await ru.ensure_rollup_schema_current(conn)
        assert did_reset is True
        assert await ru.get_high_water_ms(conn) == 0
        ver = await conn.fetchval("SELECT rollup_schema_ver FROM samples_rollup_state WHERE id = 1")
        assert ver == ru.ROLLUP_SCHEMA_VER

        # Idempotent: a second call is a no-op now that versions match.
        await ru.run_rollup_pass(conn, now_ms=now)  # re-backfills
        assert await ru.ensure_rollup_schema_current(conn) is False
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd server && .venv/bin/python -m pytest tests/test_rollup.py::test_rollup_captures_capacity_and_cycles_exactly tests/test_rollup.py::test_schema_version_bump_forces_one_time_reroll -v`
Expected: FAIL — `cap_sum` is NULL (not computed) / `ensure_rollup_schema_current` does not exist (AttributeError).

- [ ] **Step 3: Implement the compute + version gate in rollup.py**

In `server/app/db/rollup.py`:

(a) Add the version constant after the existing constants (~line 37):

```python
ROLLUP_SCHEMA_VER = 1                   # bump when rollup columns/semantics change -> forces one re-roll
```

(b) Replace the `_UPSERT` string (lines ~47–72) with the enriched version — three new columns in the insert list, the SELECT, and the ON CONFLICT SET:

```python
_UPSERT = f"""
INSERT INTO samples_rollup AS r
  (address, bucket_ms, n, soc_sum, soc_n, soh_sum, soh_n,
   spread_sum, spread_n, temp_sum, temp_n, temp_min, temp_max,
   cap_sum, cap_n, cycles_max)
SELECT address,
       (ts_ms / {ROLLUP_BUCKET_MS}) * {ROLLUP_BUCKET_MS} AS bucket_ms,
       count(*)::int,
       sum(soc::float8), count(soc)::int,
       sum(soh), count(soh)::int,
       sum(((cell_max_v - cell_min_v) * 1000)::float8), count(cell_max_v - cell_min_v)::int,
       sum(temp_c::float8), count(temp_c)::int,
       min(temp_c), max(temp_c),
       sum(full_charge_ah::float8), count(full_charge_ah)::int, max(cycles)
  FROM samples
 WHERE ts_ms >= $1 AND ts_ms < $2
   AND ts >= to_timestamp($1::double precision / 1000.0)
   AND ts < to_timestamp($2::double precision / 1000.0)
   AND link_event IS NULL
 GROUP BY address, bucket_ms
ON CONFLICT (address, bucket_ms) DO UPDATE SET
  n = EXCLUDED.n,
  soc_sum = EXCLUDED.soc_sum, soc_n = EXCLUDED.soc_n,
  soh_sum = EXCLUDED.soh_sum, soh_n = EXCLUDED.soh_n,
  spread_sum = EXCLUDED.spread_sum, spread_n = EXCLUDED.spread_n,
  temp_sum = EXCLUDED.temp_sum, temp_n = EXCLUDED.temp_n,
  temp_min = EXCLUDED.temp_min, temp_max = EXCLUDED.temp_max,
  cap_sum = EXCLUDED.cap_sum, cap_n = EXCLUDED.cap_n, cycles_max = EXCLUDED.cycles_max
"""
```

(c) Add the version-gate function after `_set_high_water_ms` (~line 88):

```python
async def ensure_rollup_schema_current(conn: asyncpg.Connection) -> bool:
    """One-time backfill trigger: if the persisted rollup_schema_ver is below the code's
    ROLLUP_SCHEMA_VER, reset the high-water mark to 0 so the next pass re-rolls every bucket
    with the current columns, and stamp the new version. Idempotent — returns True only on the
    pass that actually reset. The state row is created (id=1, hw=0) if absent."""
    row = await conn.fetchrow(
        "SELECT high_water_ms, rollup_schema_ver FROM samples_rollup_state WHERE id = 1")
    stored_ver = int(row["rollup_schema_ver"]) if row is not None else 0
    if stored_ver >= ROLLUP_SCHEMA_VER:
        return False
    await conn.execute(
        """INSERT INTO samples_rollup_state (id, high_water_ms, rollup_schema_ver)
           VALUES (1, 0, $1)
           ON CONFLICT (id) DO UPDATE SET high_water_ms = 0, rollup_schema_ver = $1""",
        ROLLUP_SCHEMA_VER)
    return True
```

(d) Call it at the very top of `run_rollup_pass`, before reading the high-water mark (right after the `now_ms` default, ~line 114):

```python
    if now_ms is None:
        now_ms = int(time.time() * 1000)
    await ensure_rollup_schema_current(conn)   # one-time backfill re-roll on version bump
    b = ROLLUP_BUCKET_MS
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd server && .venv/bin/python -m pytest tests/test_rollup.py -v`
Expected: PASS — the two new tests plus all pre-existing rollup tests (the enriched upsert must not disturb existing equivalence; `test_rollup_pass_idempotent` still holds because the new columns are deterministic).

- [ ] **Step 5: Commit**

```bash
cd server && git add app/db/rollup.py tests/test_rollup.py
git commit -m "feat(server): roll capacity/cycles into samples_rollup + version-gated backfill"
```

---

### Task 3: Retention module — drop expired raw partitions

**Files:**
- Create: `server/app/db/retention.py`
- Modify: `server/app/db/partitions.py` (add `discard_ensured_month`)
- Test: `server/tests/test_retention.py` (create)

**Interfaces:**
- Consumes: `get_high_water_ms` (rollup), `samples_YYYY_MM` catalog partitions.
- Produces:
  - `partitions.discard_ensured_month(year: int, month: int) -> None` — drop a month from the process-local `_ensured` cache.
  - `retention.cutoff_month(now_ms: int, keep_months: int) -> tuple[int, int]` — the (year, month) whose start is the exclusive lower bound of kept data.
  - `retention.drop_expired_partitions(conn, keep_months: int, now_ms: int | None = None) -> list[str]` — DROPs and returns names of partitions strictly older than the cutoff AND fully rolled up.

- [ ] **Step 1: Write the failing test**

Create `server/tests/test_retention.py`:

```python
"""Raw monthly-partition retention (DB retention strategy 2026-07-22)."""
import time
from datetime import datetime, timezone

from app.db import queries as q
from app.db import retention as ret
from app.db import rollup as ru

A = "C8:47:80:15:67:44"
DEV = "00000000-0000-0000-0000-000000000001"


async def _seed_dev(conn):
    await conn.execute(
        "INSERT INTO devices (id, install_uuid, public_key_spki) VALUES ($1,$2,$3)",
        DEV, "uuid-ret-1", b"\x00")


def _ms(y, m, d=15):
    return int(datetime(y, m, d, tzinfo=timezone.utc).timestamp() * 1000)


async def _partition_names(conn):
    rows = await conn.fetch(
        """SELECT c.relname FROM pg_inherits i
             JOIN pg_class c ON c.oid = i.inhrelid
             JOIN pg_class p ON p.oid = i.inhparent
            WHERE p.relname = 'samples'""")
    return {r["relname"] for r in rows}


def test_cutoff_month_is_keep_months_back():
    # now = mid-July 2026, keep 12 -> cutoff month is July 2025 (its start is the lower bound)
    y, m = ret.cutoff_month(_ms(2026, 7), 12)
    assert (y, m) == (2025, 7)


async def test_drops_only_fully_rolled_old_partitions(app):
    pool = app.state.pool
    async with pool.acquire() as conn:
        await _seed_dev(conn)
        # Old data (Jan 2025) + recent data (this month).
        old = _ms(2025, 1)
        now = int(time.time() * 1000)
        await q.insert_samples(conn, [q.sample_row(DEV, A, {"ts_ms": old, "soc": 50.0})])
        await q.insert_samples(conn, [q.sample_row(DEV, A, {"ts_ms": now - 60_000, "soc": 60.0})])
        # Roll everything up so the old partition is archived (high-water passes it).
        await ru.run_rollup_pass(conn, now_ms=now)

        before = await _partition_names(conn)
        assert "samples_2025_01" in before
        dropped = await ret.drop_expired_partitions(conn, keep_months=12, now_ms=now)
        after = await _partition_names(conn)

        assert "samples_2025_01" in dropped
        assert "samples_2025_01" not in after       # gone
        assert before - {"samples_2025_01"} == after  # nothing else touched


async def test_high_water_gate_blocks_unrolled_partition(app):
    """An old partition that is NOT yet rolled up must NOT be dropped."""
    pool = app.state.pool
    async with pool.acquire() as conn:
        await _seed_dev(conn)
        old = _ms(2025, 1)
        now = int(time.time() * 1000)
        await q.insert_samples(conn, [q.sample_row(DEV, A, {"ts_ms": old, "soc": 50.0})])
        # Do NOT roll up: high-water stays 0.
        assert await ru.get_high_water_ms(conn) == 0
        dropped = await ret.drop_expired_partitions(conn, keep_months=12, now_ms=now)
        assert dropped == []
        assert "samples_2025_01" in await _partition_names(conn)


async def test_disabled_keep_months_drops_nothing(app):
    pool = app.state.pool
    async with pool.acquire() as conn:
        await _seed_dev(conn)
        old = _ms(2025, 1)
        now = int(time.time() * 1000)
        await q.insert_samples(conn, [q.sample_row(DEV, A, {"ts_ms": old, "soc": 50.0})])
        await ru.run_rollup_pass(conn, now_ms=now)
        assert await ret.drop_expired_partitions(conn, keep_months=0, now_ms=now) == []
        assert await ret.drop_expired_partitions(conn, keep_months=-1, now_ms=now) == []
        assert "samples_2025_01" in await _partition_names(conn)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && .venv/bin/python -m pytest tests/test_retention.py -v`
Expected: FAIL — `ModuleNotFoundError: app.db.retention` / `cutoff_month` undefined.

- [ ] **Step 3: Add `discard_ensured_month` to partitions.py**

In `server/app/db/partitions.py`, after `reset_ensured_months` (~line 43):

```python
def discard_ensured_month(year: int, month: int) -> None:
    """Forget a single month's partition from the process-local cache — called after a
    partition is DROPped so a (vanishingly rare) very-late sample for that month would
    re-CREATE it rather than fail on a poisoned 'known to exist' cache entry."""
    _ensured.discard((year, month))
```

- [ ] **Step 4: Implement retention.py**

Create `server/app/db/retention.py`:

```python
"""Raw monthly-partition retention (DB retention strategy 2026-07-22).

samples grows ~0.8 GB/month and nothing used to drop a partition. The rollup is now the
permanent battery-health archive (SRV-14 + capacity/cycles), so raw only needs a bounded
recent window (default 12 months) for fine-grained Journey/charge/efficiency detail. This
DROPs whole monthly partitions older than that window — instant space reclaim, no delete
bloat. A partition is dropped ONLY once fully folded into the rollup (its end <= the
high-water mark), so health data is always archived before raw is discarded.
"""
import re
from datetime import datetime, timezone

import asyncpg

from app.db.partitions import discard_ensured_month
from app.db.rollup import get_high_water_ms

_PART_RE = re.compile(r"^samples_(\d{4})_(\d{2})$")


def cutoff_month(now_ms: int, keep_months: int) -> tuple[int, int]:
    """The (year, month) whose START is the exclusive lower bound of kept data: the current
    month minus keep_months. A partition is droppable iff its whole range is < this start."""
    d = datetime.fromtimestamp(now_ms / 1000, tz=timezone.utc)
    # zero-based month index arithmetic
    idx = (d.year * 12 + (d.month - 1)) - keep_months
    return idx // 12, idx % 12 + 1


def _month_end_ms(year: int, month: int) -> int:
    ny, nm = (year + 1, 1) if month == 12 else (year, month + 1)
    return int(datetime(ny, nm, 1, tzinfo=timezone.utc).timestamp() * 1000)


async def drop_expired_partitions(
    conn: asyncpg.Connection, keep_months: int, now_ms: int | None = None
) -> list[str]:
    """DROP every samples_YYYY_MM partition whose entire range is older than the
    keep_months cutoff AND fully rolled up (end_ms <= high_water_ms). Returns dropped names.
    keep_months <= 0 disables retention (no-op)."""
    if keep_months <= 0:
        return []
    if now_ms is None:
        import time
        now_ms = int(time.time() * 1000)
    cy, cm = cutoff_month(now_ms, keep_months)
    cutoff_start_ms = int(datetime(cy, cm, 1, tzinfo=timezone.utc).timestamp() * 1000)
    hw = await get_high_water_ms(conn)

    rows = await conn.fetch(
        """SELECT c.relname FROM pg_inherits i
             JOIN pg_class c ON c.oid = i.inhrelid
             JOIN pg_class p ON p.oid = i.inhparent
            WHERE p.relname = 'samples'""")
    dropped: list[str] = []
    for r in rows:
        mobj = _PART_RE.match(r["relname"])
        if not mobj:
            continue
        y, mo = int(mobj.group(1)), int(mobj.group(2))
        end_ms = _month_end_ms(y, mo)
        # Old enough AND fully archived into the rollup.
        if end_ms <= cutoff_start_ms and end_ms <= hw:
            await conn.execute(f'DROP TABLE IF EXISTS "{r["relname"]}"')
            discard_ensured_month(y, mo)
            dropped.append(r["relname"])
    return dropped
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd server && .venv/bin/python -m pytest tests/test_retention.py -v`
Expected: PASS (all four tests).

- [ ] **Step 6: Commit**

```bash
cd server && git add app/db/retention.py app/db/partitions.py tests/test_retention.py
git commit -m "feat(server): drop raw monthly partitions past the retention window"
```

---

### Task 4: Config + background retention loop

**Files:**
- Modify: `server/app/config.py` (add `raw_retention_months`)
- Modify: `server/app/main.py` (constants, `run_retention` wrapper, `_retention_loop`, lifespan wiring)
- Test: `server/tests/test_retention.py` (append wrapper + config tests)

**Interfaces:**
- Consumes: `retention.drop_expired_partitions`, `settings.raw_retention_months`.
- Produces: `settings.raw_retention_months: int` (default 12); `main.run_retention(pool) -> list[str]`.

- [ ] **Step 1: Write the failing tests**

Append to `server/tests/test_retention.py`:

```python
async def test_main_run_retention_wrapper_noop_on_empty_db(app):
    from app.main import run_retention
    assert await run_retention(app.state.pool) == []


def test_raw_retention_months_default():
    from app.config import settings
    assert settings.raw_retention_months == 12
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd server && .venv/bin/python -m pytest tests/test_retention.py::test_main_run_retention_wrapper_noop_on_empty_db tests/test_retention.py::test_raw_retention_months_default -v`
Expected: FAIL — `ImportError: cannot import name 'run_retention'` / `settings` has no `raw_retention_months`.

- [ ] **Step 3: Add the config field**

In `server/app/config.py`, after the `gps_retention_days` field (~line 45):

```python
    # Raw retention (DB retention strategy): samples monthly partitions older than this many
    # months are DROPped by a daily background task, once fully folded into samples_rollup (the
    # permanent health archive). Telemetry stays queryable at 30-min resolution forever via the
    # rollup; only fine-grained raw (GPS/charge/efficiency detail) expires. Default 12 months.
    # Set <= 0 to disable pruning entirely (keep raw forever).
    raw_retention_months: int = int(os.environ.get("BMSMON_RAW_RETENTION_MONTHS", "12"))
```

- [ ] **Step 4: Add the wrapper, loop, and wiring in main.py**

In `server/app/main.py`:

(a) Import the retention drop (extend the existing `app.db` imports, ~line 12):

```python
from app.db.retention import drop_expired_partitions
```

(b) Add cadence constants after the rollup constants (~line 26):

```python
# Raw-partition retention cadence: first pass shortly after startup (after the rollup has had a
# chance to advance the high-water mark), then daily. The high-water gate makes an early pass
# safe regardless — it simply drops nothing until buckets are archived.
RETENTION_INITIAL_DELAY_S = 90
RETENTION_INTERVAL_S = 24 * 3600
```

(c) Add the wrapper + loop after `_rollup_loop` (~line 69):

```python
async def run_retention(pool) -> list[str]:
    """One raw-partition retention pass: DROP samples partitions older than
    settings.raw_retention_months that are fully rolled up. Directly callable for tests."""
    async with pool.acquire() as conn:
        return await drop_expired_partitions(conn, settings.raw_retention_months)


async def _retention_loop(pool) -> None:
    await asyncio.sleep(RETENTION_INITIAL_DELAY_S)
    while True:
        try:
            dropped = await run_retention(pool)
            if dropped:
                logger.info("raw retention: dropped %d expired partitions: %s",
                            len(dropped), ", ".join(dropped))
        except Exception:
            # A DB hiccup must never crash the app or stop future runs.
            logger.exception("raw retention pass failed; retrying in %d s", RETENTION_INTERVAL_S)
        await asyncio.sleep(RETENTION_INTERVAL_S)
```

(d) Wire it into `lifespan`, mirroring the GPS-scrub skip-when-disabled pattern (~line 101):

```python
    tasks = [asyncio.create_task(_rollup_loop(app.state.pool))]
    if settings.gps_retention_days > 0:
        tasks.append(asyncio.create_task(_gps_scrub_loop(app.state.pool)))
    if settings.raw_retention_months > 0:
        tasks.append(asyncio.create_task(_retention_loop(app.state.pool)))
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd server && .venv/bin/python -m pytest tests/test_retention.py tests/test_config.py -v`
Expected: PASS (retention wrapper + config default; `test_config.py` unaffected).

- [ ] **Step 6: Run the full server suite (no regressions)**

Run: `cd server && .venv/bin/python -m pytest -q`
Expected: PASS — all tests, including the full `test_rollup.py` and existing `test_gps_retention.py`.

- [ ] **Step 7: Commit**

```bash
cd server && git add app/config.py app/main.py tests/test_retention.py
git commit -m "feat(server): daily raw-partition retention loop + BMSMON_RAW_RETENTION_MONTHS"
```

---

### Task 5: Documentation

**Files:**
- Modify: `CLAUDE.md` (bmsmon project root)
- Modify: `server/app/db/partitions.py` (correct the "nothing ever drops a partition" comment)
- Modify: `~/GoogleDrive/obsidian/notes/Bmsmon.md`

**Interfaces:** none (docs only).

- [ ] **Step 1: Update the partitions.py comment**

In `server/app/db/partitions.py`, the cache comment (~line 30) says "nothing ever drops a partition (test TRUNCATEs keep them)." Replace with:

```python
# and dropped partitions are evicted from this cache via discard_ensured_month (app/db/retention.py
# DROPs partitions past the retention window; test TRUNCATEs keep them).
```

- [ ] **Step 2: Update CLAUDE.md**

In `CLAUDE.md`, in the "Cloud Server & Deployment" area (near the `samples` table / schema description), add a paragraph:

```markdown
**DB retention (keeps the cloud DB ~10 GB, budget 25 GB).** `samples_rollup` is the permanent
(5-10 year) battery-health archive: besides SOC/SOH%/cell-spread/temperature it now also keeps
`cap_sum`/`cap_n` (avg absolute full-charge capacity, Ah) and `cycles_max` (cycle count) per
30-min bucket — sums/counts (and max for the monotonic cycle count) re-aggregate exactly. Raw
`samples_YYYY_MM` partitions are DROPped once older than `BMSMON_RAW_RETENTION_MONTHS` (default
12) by a daily background task (`app/db/retention.py`, wired in `main.py` like the GPS-scrub
loop), gated so a partition is dropped only after it is fully folded into the rollup
(`partition_end_ms <= high_water_ms`) — health data is always archived before raw is discarded.
GPS/trip detail is treated as disposable; History/Fleet-Health trends are rollup-served and
unaffected. A `rollup_schema_ver` on `samples_rollup_state` forces a one-time full re-roll
(high-water reset) when the rollup columns change, backfilling the new columns from raw. This is
**preventive** — data starts June 2026, so the first real drop is ~mid-2027. Deploy note: on the
enrichment deploy, watch the one-time re-roll advance the high-water mark back to present (logs:
"samples_rollup: upserted N bucket rows") before it matters; manual fallback is
`UPDATE samples_rollup_state SET high_water_ms = 0;`.
```

- [ ] **Step 3: Update the Obsidian note**

In `~/GoogleDrive/obsidian/notes/Bmsmon.md`, add a short bullet under the cloud/server status reflecting: rollup is now the permanent health archive (adds capacity-Ah + cycles), raw pruned at 12 months by a daily task, DB bounded ~10 GB. (Match the note's existing brevity/format.)

- [ ] **Step 4: Commit**

```bash
cd /home/joely/bmsmon && git add CLAUDE.md server/app/db/partitions.py
git commit -m "docs: record DB retention strategy (rollup health archive + raw pruning)"
```

(The Obsidian note lives outside the repo — save it, no commit needed.)

---

## Self-Review

**Spec coverage:**
- Component 1 (enrich rollup: cap_sum/cap_n/cycles_max) → Tasks 1 (schema) + 2 (compute). ✓
- Component 2 (version-gated one-time backfill) → Task 2 (`ROLLUP_SCHEMA_VER`, `ensure_rollup_schema_current`, called in `run_rollup_pass`). ✓
- Component 3 (retention task: catalog enumeration, cutoff, high-water gate, DROP, config, daily loop) → Tasks 3 + 4. ✓
- Component 4 (WebUI impact: trends preserved, Journey/charge/efficiency degrade) → no code change required; covered by the full-suite run in Task 4 Step 6 (existing `test_history`/`test_trends`/`test_track`/`test_charge_sessions` must still pass). ✓
- Component 5 (testing: exact re-aggregation, retention gate cases) → Tasks 2 + 3 tests. ✓
- Rollout / deploy note → Task 5 CLAUDE.md. ✓
- Docs update (explicit user ask) → Task 5. ✓

**Placeholder scan:** No TBD/TODO/"handle edge cases"; every code step has full code. ✓

**Type consistency:** `cutoff_month(now_ms, keep_months) -> (year, month)` used identically in test and impl; `drop_expired_partitions(conn, keep_months, now_ms=None) -> list[str]` consistent across Task 3/4; `ensure_rollup_schema_current(conn) -> bool` and `ROLLUP_SCHEMA_VER` consistent Task 2; `discard_ensured_month(year, month)` defined in partitions.py (Task 3 Step 3) and imported in retention.py. Column names `cap_sum`/`cap_n`/`cycles_max`/`rollup_schema_ver` identical across schema, upsert, and tests. ✓
