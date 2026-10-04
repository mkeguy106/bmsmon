"""SRV-16: the per-pack rewrites of the fleet-wide window queries must return exactly
what the old month-wide ts-filtered queries returned. The old SQL is kept here verbatim
as the baseline; data spans a month (partition) boundary, several packs, link events,
NULL soc, coarse and missing GPS accuracy, and windows whose edges land on samples."""
from datetime import datetime, timezone

import pytest

from app.db import queries as q
from app.db import rollup as ru
from app.db.partitions import ensure_partitions_for_range

DAY = 86_400_000
HOUR = 3_600_000
FEB28 = int(datetime(2026, 2, 28, tzinfo=timezone.utc).timestamp() * 1000)
MAR1 = FEB28 + DAY
END = FEB28 + 3 * DAY
PACKS = ["C8:47:80:15:67:44", "C8:47:80:15:62:1B", "C8:47:80:15:DB:13"]
B = q.HISTORY_BUCKET_MS

_OLD_RAW = """
SELECT address, (ts_ms / $2) * $2 AS bucket_ms, avg(soc)::real AS soc
  FROM samples
 WHERE ts_ms >= $1 AND ts >= to_timestamp($1::double precision / 1000.0)
   AND link_event IS NULL AND soc IS NOT NULL
 GROUP BY address, bucket_ms ORDER BY address, bucket_ms
"""

_OLD_ROUTED = """
WITH parts AS (
  SELECT address, (ts_ms / $4) * $4 AS bucket_ms,
         sum(soc::float8) AS soc_sum, count(soc)::bigint AS soc_n
    FROM samples
   WHERE ts_ms >= $1 AND ts_ms < $2
     AND ts >= to_timestamp($1::double precision / 1000.0)
     AND ts < to_timestamp($2::double precision / 1000.0)
     AND link_event IS NULL AND soc IS NOT NULL
   GROUP BY address, bucket_ms
  UNION ALL
  SELECT address, bucket_ms, soc_sum, soc_n::bigint
    FROM samples_rollup
   WHERE bucket_ms >= $2 AND bucket_ms < $3 AND soc_n > 0
  UNION ALL
  SELECT address, (ts_ms / $4) * $4 AS bucket_ms,
         sum(soc::float8), count(soc)::bigint
    FROM samples
   WHERE ts_ms >= $3 AND ts >= to_timestamp($3::double precision / 1000.0)
     AND link_event IS NULL AND soc IS NOT NULL
   GROUP BY address, bucket_ms
)
SELECT address, bucket_ms, (sum(soc_sum) / nullif(sum(soc_n), 0))::real AS soc
  FROM parts GROUP BY address, bucket_ms ORDER BY address, bucket_ms
"""

_OLD_TRACK = """
SELECT (ts_ms / 15000) * 15000 AS bucket_ms,
       avg(lat)::double precision AS lat, avg(lon)::double precision AS lon,
       avg(power_w)::real AS power_w, avg(current_a)::real AS current_a
  FROM samples
 WHERE ts_ms >= $1 AND ts_ms < $2
   AND ts >= to_timestamp($1::double precision / 1000.0)
   AND ts < to_timestamp($2::double precision / 1000.0)
   AND link_event IS NULL AND lat IS NOT NULL AND lon IS NOT NULL
   AND (gps_accuracy_m IS NULL OR gps_accuracy_m <= $3)
 GROUP BY bucket_ms ORDER BY bucket_ms
"""

_OLD_UPSERT_SELECT = f"""
SELECT address, (ts_ms / {ru.ROLLUP_BUCKET_MS}) * {ru.ROLLUP_BUCKET_MS} AS bucket_ms,
       count(*)::int, sum(soc::float8), count(soc)::int, sum(soh), count(soh)::int,
       sum(((cell_max_v - cell_min_v) * 1000)::float8), count(cell_max_v - cell_min_v)::int,
       sum(temp_c::float8), count(temp_c)::int, min(temp_c), max(temp_c)
  FROM samples
 WHERE ts_ms >= $1 AND ts_ms < $2
   AND ts >= to_timestamp($1::double precision / 1000.0)
   AND ts < to_timestamp($2::double precision / 1000.0)
   AND link_event IS NULL
 GROUP BY address, bucket_ms ORDER BY address, bucket_ms
"""

_SEED = """
INSERT INTO samples (device_id, address, ts_ms, ts, soc, soh, temp_c, cell_min_v, cell_max_v,
                     current_a, power_w, lat, lon, gps_accuracy_m, link_event)
SELECT '00000000-0000-0000-0000-000000000001'::uuid, a, t, to_timestamp(t / 1000.0),
       CASE WHEN n % 7 = 0 THEN NULL ELSE 30 + (n * 13) % 60 + 0.25 END,
       CASE WHEN n % 3 = 0 THEN NULL ELSE 95 + n % 5 END,
       CASE WHEN n % 4 = 2 THEN NULL ELSE 18 + (n % 9) * 1.5 END,
       3.301 + (n % 3) * 0.004, 3.334 + (n % 4) * 0.006,
       -5 + (n % 11), (n % 11) * 7.5,
       CASE WHEN n % 5 = 1 THEN NULL ELSE 43.0 + (n % 100) * 0.0001 END,
       CASE WHEN n % 5 = 1 THEN NULL ELSE -87.9 - (n % 100) * 0.0001 END,
       CASE WHEN n % 9 = 0 THEN 400.0 WHEN n % 9 = 1 THEN NULL ELSE 8.0 END,
       CASE WHEN n % 13 = 5 THEN 'Disconnected' END
  FROM unnest($1::text[]) WITH ORDINALITY AS u(a, k),
       generate_series($2::bigint, $3::bigint, $4::bigint) WITH ORDINALITY AS g(t, n)
"""


async def _seed(conn):
    await ensure_partitions_for_range(conn, FEB28, END)
    for a in PACKS:
        await q.upsert_battery(conn, a, None, None, None, FEB28)
    await conn.execute(_SEED, PACKS[:2], FEB28, END - 1, 61_000)
    # an offset, sparser third pack so packs do not share timestamps
    await conn.execute(_SEED, PACKS[2:], FEB28 + 12_345, END - 1, 600_000)
    # rows sitting exactly on the window edges used below
    for a in PACKS:
        for t in (MAR1 - 1, MAR1, MAR1 + HOUR, END - HOUR):
            await conn.execute(
                "INSERT INTO samples (device_id, address, ts_ms, ts, soc, lat, lon) "
                "VALUES ('00000000-0000-0000-0000-000000000001', $1, $2::bigint, "
                "to_timestamp($2::double precision / 1000.0), 55, 43.5, -87.5) "
                "ON CONFLICT DO NOTHING", a, t)


async def test_share_trail_matches_old_query(app):
    async with app.state.pool.acquire() as conn:
        await _seed(conn)
        windows = [
            (FEB28, END),                       # whole span, crosses the month boundary
            (MAR1 - 6 * HOUR, MAR1 + 6 * HOUR),  # straddles the boundary
            (MAR1, MAR1 + HOUR),                # lower edge on a sample
            (MAR1 - 1, MAR1),                   # upper edge exclusive
            (MAR1 + HOUR, MAR1 + HOUR + 1),     # one-ms window on a sample
            (END + DAY, END + 2 * DAY),         # empty
        ]
        saw_rows = False
        for lo, hi in windows:
            old = [dict(r) for r in await conn.fetch(_OLD_TRACK, lo, hi, q.GPS_ACCURACY_MAX_M)]
            new = await q.gps_track_all(conn, lo, hi)
            assert len(new) == len(old), (lo, hi)
            for n, o in zip(new, old):
                # avg(float8) sums in scan order, which differs per pack now: the last
                # ulp of lat/lon may move; everything else is exact.
                assert n["lat"] == pytest.approx(o["lat"], abs=1e-9)
                assert n["lon"] == pytest.approx(o["lon"], abs=1e-9)
                assert {k: v for k, v in n.items() if k not in ("lat", "lon")} == \
                       {k: v for k, v in o.items() if k not in ("lat", "lon")}, (lo, hi)
            saw_rows = saw_rows or bool(old)
        assert saw_rows
        assert await q.gps_track_all(conn, END + DAY, END + 2 * DAY) == []


async def test_history_raw_and_routed_match_old_queries(app):
    async with app.state.pool.acquire() as conn:
        await _seed(conn)
        for since in (FEB28, MAR1 - 90 * 60_000 - 777, MAR1, MAR1 + HOUR + 5):
            old = [dict(r) for r in await conn.fetch(_OLD_RAW, since, B)]
            new = [dict(r) for r in await conn.fetch(q._HISTORY_RAW, since, B)]
            assert new == old and old, since
        assert [dict(r) for r in await conn.fetch(q._HISTORY_RAW, END + DAY, B)] == []
        # routed: roll up everything below hw, then compare head / middle / tail.
        hw = MAR1 + 30 * HOUR
        await ru.run_rollup_pass(conn, END)
        for since in (FEB28 + 5, MAR1 - 3 * HOUR - 1, MAR1 - 3 * HOUR):
            ru_lo = -(-since // B) * B
            for hwm in (hw, hw + B, END - HOUR):
                old = [dict(r) for r in await conn.fetch(_OLD_ROUTED, since, ru_lo, hwm, B)]
                new = [dict(r) for r in await conn.fetch(q._HISTORY_ROUTED, since, ru_lo, hwm, B)]
                assert new == old and old, (since, hwm)


async def test_rollup_upsert_matches_old_select(app):
    async with app.state.pool.acquire() as conn:
        await _seed(conn)
        for lo, hi in ((FEB28, END), (MAR1 - 2 * HOUR, MAR1 + 2 * HOUR),
                       (MAR1, MAR1 + B), (MAR1 - B, MAR1), (END + DAY, END + 2 * DAY)):
            await conn.execute("TRUNCATE samples_rollup")
            expected = [tuple(r) for r in await conn.fetch(_OLD_UPSERT_SELECT, lo, hi)]
            await conn.execute(ru._UPSERT, lo, hi)
            got = [tuple(r) for r in await conn.fetch(
                "SELECT address, bucket_ms, n, soc_sum, soc_n, soh_sum, soh_n, spread_sum, "
                "spread_n, temp_sum, temp_n, temp_min, temp_max FROM samples_rollup "
                "ORDER BY address, bucket_ms")]
            assert got == expected, (lo, hi)
