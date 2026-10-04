"""EXPLAIN-based guards (SRV-16/23/28): a windowed query's cost must track its window, not
the size of the month it falls in. Each test seeds one synthetic month into
samples_2026_03: 8 packs, 2 at a 15 s stage cadence and 6 spares at 5 min; GPS 13-18 h UTC;
charging 01-07 h UTC. That is ~410 k rows / ~7 k heap pages. The tests read the plan's
buffer counts."""
import json
from datetime import datetime, timezone

from app.db import queries as q
from app.db import rollup as ru
from app.db.online_index import complete_partitioned_indexes
from app.db.partitions import ensure_partitions_for_range

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
