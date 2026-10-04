"""EXPLAIN-based guards (SRV-16/23/28): a windowed query's cost must track its window, not
the size of the month it falls in. Each test seeds one synthetic month into
samples_2026_03: 8 packs, 2 at a 15 s stage cadence and 6 spares at 5 min; GPS 13-18 h UTC;
charging 01-07 h UTC. That is ~410 k rows / ~7 k heap pages. The tests read the plan's
buffer counts."""
import ast
import json
import re
from datetime import datetime, timezone
from pathlib import Path

import app as app_pkg
from app.db import queries as q
from app.db import rollup as ru
from app.db.online_index import complete_partitioned_indexes
from app.db.partitions import ensure_partitions_for_range

DEV = "00000000-0000-0000-0000-000000000001"
MARCH = int(datetime(2026, 3, 1, tzinfo=timezone.utc).timestamp() * 1000)
DAY, HOUR = 86_400_000, 3_600_000
END = MARCH + 31 * DAY
PART = "samples_2026_03"
STAGE = ["C8:47:80:15:67:44", "C8:47:80:15:62:1B"]
SPARES = ["C8:47:80:15:DB:13", "C8:47:80:15:25:9A", "C8:47:80:46:0A:D6",
          "C8:47:80:45:90:FB", "C8:47:80:15:07:DE", "C8:47:80:15:25:01"]

_SEED = """
INSERT INTO samples (device_id, address, ts_ms, ts, soc, current_a, power_w, temp_c, soh,
                     cell_min_v, cell_max_v, lat, lon, gps_accuracy_m)
SELECT '00000000-0000-0000-0000-000000000001'::uuid, a, t, to_timestamp(t / 1000.0), 70,
       CASE WHEN h BETWEEN 1 AND 7 THEN 8.0 WHEN h BETWEEN 13 AND 17 THEN -10.0 ELSE 0 END,
       0, 22, 100, 3.30, 3.31,
       CASE WHEN h BETWEEN 13 AND 17 THEN 43.0 END,
       CASE WHEN h BETWEEN 13 AND 17 THEN -87.9 END,
       CASE WHEN h BETWEEN 13 AND 17 THEN 8.0 END
  FROM unnest($1::text[]) AS a,
       generate_series($2::bigint, $3::bigint, $4::bigint) AS t,
       LATERAL (SELECT ((t / 3600000) % 24)::int AS h) AS hh
"""


async def seed_month(conn) -> int:
    """Seeds March 2026; returns the partition's heap pages."""
    await ensure_partitions_for_range(conn, MARCH, END - 1)
    for a in STAGE + SPARES:
        await q.upsert_battery(conn, a, None, None, None, MARCH)
    await conn.execute(_SEED, STAGE, MARCH, END - 1, 15_000)
    await conn.execute(_SEED, SPARES, MARCH, END - 1, 300_000)
    # VACUUM sets the visibility map (index-only scans) and ANALYZE the stats the planner
    # needs. PARALLEL 0: no dynamic shared memory (docker's /dev/shm is 64 MB).
    await conn.execute(f"VACUUM (ANALYZE, PARALLEL 0) {PART}")
    return await conn.fetchval("SELECT relpages FROM pg_class WHERE relname = $1", PART)


async def explain(conn, sql: str, *args) -> dict:
    raw = await conn.fetchval("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + sql, *args)
    return json.loads(raw)[0]["Plan"]


def buffers(plan: dict) -> int:
    return plan.get("Shared Hit Blocks", 0) + plan.get("Shared Read Blocks", 0)


def _walk(plan: dict):
    yield plan
    for child in plan.get("Plans", []):
        yield from _walk(child)


def seq_scanned(plan: dict) -> set[str]:
    return {n["Relation Name"] for n in _walk(plan) if n["Node Type"] == "Seq Scan"}


def index_names(plan: dict) -> set[str]:
    return {n["Index Name"] for n in _walk(plan) if "Index Name" in n}


_CHILD_OF = """
SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
  JOIN pg_index x ON x.indexrelid = c.oid
 WHERE i.inhparent = $1::regclass AND x.indrelid = $2::regclass
"""


async def test_charge_sessions_read_only_the_charging_index(app):
    async with app.state.pool.acquire() as conn:
        pages = await seed_month(conn)
        await complete_partitioned_indexes(conn)
        child = await conn.fetchval(_CHILD_OF, "samples_charging_idx", PART)
        plan = await explain(conn, q._CHARGE_SESSION_BUCKETS, STAGE[0], MARCH + DAY)
    assert child in index_names(plan)
    assert PART not in seq_scanned(plan)
    assert buffers(plan) * 4 < pages


async def test_share_trail_reads_only_its_window(app):
    async with app.state.pool.acquire() as conn:
        pages = await seed_month(conn)
        lo = MARCH + 20 * DAY + 12 * HOUR
        plan = await explain(conn, q._GPS_TRACK_ALL, lo, lo + 6 * HOUR, q.GPS_ACCURACY_MAX_M)
    assert PART not in seq_scanned(plan)
    assert buffers(plan) * 10 < pages


async def test_history_raw_parts_read_only_their_windows(app):
    async with app.state.pool.acquire() as conn:
        pages = await seed_month(conn)
        b = q.HISTORY_BUCKET_MS
        hw = END - HOUR                      # rolled up to an hour before the month's end
        since = END - 25 * HOUR - 600_000
        ru_lo = -(-since // b) * b
        routed = await explain(conn, q._HISTORY_ROUTED, since, ru_lo, hw, b)
        raw = await explain(conn, q._HISTORY_RAW, END - HOUR, b)
    for plan in (routed, raw):
        assert PART not in seq_scanned(plan)
        assert buffers(plan) * 10 < pages


async def test_rollup_reroll_reads_only_its_window(app):
    async with app.state.pool.acquire() as conn:
        pages = await seed_month(conn)
        # EXPLAIN ANALYZE executes the upsert; the next test's TRUNCATE cleans it up.
        plan = await explain(conn, ru._UPSERT, END - 48 * HOUR, END)
    assert PART not in seq_scanned(plan)
    # The Insert node's own total includes the rollup rows' index/heap writes; the read
    # side is its single child.
    (read_side,) = plan["Plans"]
    assert buffers(read_side) * 4 < pages


# A range predicate on ts_ms in any form: either operand order, BETWEEN, and any bound
# ($N, %s, :name, or an f-string's interpolation, whose literal part still holds the op).
_WINDOW_ON_TS_MS = re.compile(
    r"\bts_ms\s*(?:>=|<=|>|<)|\bts_ms\s+BETWEEN\b|(?:>=|<=|>|<)\s*(?:\w+\.)?ts_ms\b",
    re.IGNORECASE)


def _code_strings(path: Path):
    """Every string literal in a module except docstrings (prose, not SQL)."""
    tree = ast.parse(path.read_text())
    docs = {id(n.body[0].value) for n in ast.walk(tree)
            if isinstance(n, (ast.Module, ast.ClassDef, ast.FunctionDef, ast.AsyncFunctionDef))
            and n.body and isinstance(n.body[0], ast.Expr)
            and isinstance(n.body[0].value, ast.Constant)}
    for n in ast.walk(tree):
        if isinstance(n, ast.Constant) and isinstance(n.value, str) and id(n) not in docs:
            yield n.value


def test_the_ts_ms_window_guard_catches_every_form():
    for bad in ("ts_ms >= $1", "s.ts_ms<$2", "ts_ms BETWEEN $1 AND $2", "$1 <= ts_ms",
                "%s > s.ts_ms", "ts_ms < :hi", "WHERE ts_ms >= "):
        assert _WINDOW_ON_TS_MS.search(bad), bad
    for ok in ("(ts_ms / 15000) * 15000", "ORDER BY ts_ms", "SELECT s.ts_ms FROM samples s",
               "ts >= to_timestamp($1::double precision / 1000.0)"):
        assert not _WINDOW_ON_TS_MS.search(ok), ok


def test_windowed_queries_filter_on_ts_only():
    """SRV-28: a window is expressed on ts alone. ts is ts_ms at ms precision, so a second
    ts_ms predicate adds nothing but an estimate the planner multiplies in as if it were
    independent (measured 47x too low), and a window on ts_ms ALONE would scan every
    partition. Checks the SQL in every module under app/."""
    modules = sorted(Path(app_pkg.__file__).parent.rglob("*.py"))
    assert len(modules) > 10
    for path in modules:
        hits = [m.group(0) for s in _code_strings(path) for m in _WINDOW_ON_TS_MS.finditer(s)]
        assert not hits, (str(path), hits)


async def test_track_window_is_half_open_to_the_millisecond(app):
    """Dropping ts_ms must not move a boundary: [from_ms, to_ms) still includes from_ms and
    excludes to_ms exactly."""
    a = STAGE[0]
    lo = MARCH + 10 * DAY + 123         # deliberately not second-aligned
    hi = lo + 30_000
    async with app.state.pool.acquire() as conn:
        await ensure_partitions_for_range(conn, lo - 1, hi)
        await q.insert_samples(conn, [
            q.sample_row(DEV, a, {"ts_ms": t, "lat": 40.0 + 10 * i, "lon": -87.0})
            for i, t in enumerate((lo - 1, lo, hi - 1, hi))])
        pts = await q.track_series(conn, a, lo, hi)
    # lo-1 shares lo's 15 s bucket and hi shares hi-1's: wrong boundaries would average them in
    assert [round(p["lat"]) for p in pts] == [50, 60]


async def test_ts_is_ts_ms_to_the_millisecond_for_every_writer(app):
    """The premise of ts-only windows: sample_row derives ts from ts_ms, and
    to_timestamp(ms / 1000.0) (what the windows compare against) names the same instant for
    every millisecond, including skewed far-future and pre-epoch-ish clocks."""
    import random
    rnd = random.Random(14)
    values = [0, 1, 999, 1_000, MARCH, MARCH + 1, 4_102_444_799_999]
    values += [rnd.randrange(1_500_000_000_000, 4_102_444_800_000) for _ in range(2000)]
    async with app.state.pool.acquire() as conn:
        rows = [q.sample_row(DEV, STAGE[0], {"ts_ms": v})["ts"] for v in values]
        bad = await conn.fetchval(
            "SELECT count(*) FROM unnest($1::bigint[], $2::timestamptz[]) AS u(ms, ts)"
            " WHERE ts <> to_timestamp(ms::double precision / 1000.0)", values, rows)
    assert bad == 0


async def test_trend_and_track_prune_to_their_window(app):
    async with app.state.pool.acquire() as conn:
        pages = await seed_month(conn)
        lo = MARCH + 20 * DAY + 12 * HOUR
        plans = [
            await explain(conn, q._TREND_RAW, STAGE[0], lo, lo + 6 * HOUR, 1_800_000),
            await explain(conn, q._TREND_ROUTED, STAGE[0], lo, lo + HOUR, lo + 5 * HOUR,
                          lo + 6 * HOUR, 6 * HOUR),
            await explain(conn, q._TREND_RAW, STAGE[0], END + DAY, END + 2 * DAY, 1_800_000),
        ]
    for plan in plans:
        assert PART not in seq_scanned(plan)
    # the empty window past the month touches no partition's heap at all
    assert buffers(plans[2]) * 100 < pages
