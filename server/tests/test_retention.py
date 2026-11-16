"""Raw samples retention (2026-07-22 design): the maintenance pass drops monthly partitions
past BMSMON_RAW_RETENTION_MONTHS, only once the rollup has archived them, under a short
lock_timeout, logging each partition's row count for the NAS backup's content floor.

The test DB is shared and partitions survive TRUNCATE, so assertions name only the months
these tests create (2019) rather than the full set of partitions."""
import time
from datetime import datetime, timezone

import asyncpg

from app.config import settings
from app.db import partitions as parts
from app.db import queries as q
from app.db import retention as ret
from app.db import rollup as ru
from app.maintenance import run_maintenance

A = "C8:47:80:15:67:44"
DEV = "00000000-0000-0000-0000-000000000001"


def _ms(y, m, d=15):
    return int(datetime(y, m, d, tzinfo=timezone.utc).timestamp() * 1000)


async def _seed(conn, *months):
    """One device, then rows in each (year, month); returns nothing."""
    await conn.execute(
        "INSERT INTO devices (id, install_uuid, public_key_spki) VALUES ($1,$2,$3)",
        DEV, "uuid-ret-1", b"\x00")
    for y, m, n in months:
        await q.insert_samples(conn, [q.sample_row(DEV, A, {"ts_ms": _ms(y, m) + i * 1000, "soc": 50.0})
                                      for i in range(n)])


async def _archived(conn, hw_ms):
    """The rollup has archived everything below hw_ms with the current columns."""
    await conn.execute(
        """INSERT INTO samples_rollup_state (id, high_water_ms, rollup_schema_ver) VALUES (1, $1, $2)
           ON CONFLICT (id) DO UPDATE SET high_water_ms = $1, rollup_schema_ver = $2""",
        hw_ms, ru.ROLLUP_SCHEMA_VER)


async def _exists(conn, name):
    return await conn.fetchval("SELECT to_regclass($1)", name) is not None


async def _log(conn):
    return [(r["partition_name"], r["row_count"]) for r in await conn.fetch(
        "SELECT partition_name, row_count FROM samples_retention_log "
        "WHERE partition_name LIKE 'samples_2019_%' ORDER BY id")]


def test_cutoff_is_the_start_of_the_month_keep_months_back():
    assert ret.cutoff_ms(_ms(2026, 7), 12) == _ms(2025, 7, 1)
    assert ret.cutoff_ms(_ms(2026, 1), 1) == _ms(2025, 12, 1)
    assert ret.cutoff_ms(_ms(2026, 1), 13) == _ms(2024, 12, 1)


async def test_drops_archived_old_partitions_logs_rows_and_keeps_recent(app):
    now = int(time.time() * 1000)
    async with app.state.pool.acquire() as conn:
        await _seed(conn, (2019, 1, 3), (2019, 2, 2))
        await q.insert_samples(conn, [q.sample_row(DEV, A, {"ts_ms": now - 60_000, "soc": 60.0})])
        await _archived(conn, now)
        dropped = await ret.drop_expired_partitions(conn, 12, now)
        assert {"samples_2019_01", "samples_2019_02"} <= set(dropped)
        assert not await _exists(conn, "samples_2019_01")
        assert await _exists(conn, f"samples_{datetime.now(timezone.utc):%Y_%m}")
        assert {("samples_2019_01", 3), ("samples_2019_02", 2)} <= set(await _log(conn))
        # The process cache forgot the month: a very late sample re-creates the partition.
        assert await q.insert_samples(conn, [q.sample_row(DEV, A, {"ts_ms": _ms(2019, 1), "soc": 1.0})]) == 1
        assert await _exists(conn, "samples_2019_01")


async def test_unarchived_partition_is_kept(app):
    """The high-water gate: past the window but not yet rolled up, so not dropped."""
    now = int(time.time() * 1000)
    async with app.state.pool.acquire() as conn:
        await _seed(conn, (2019, 1, 1), (2019, 2, 1))
        await _archived(conn, _ms(2019, 2, 1))   # January archived, February not
        dropped = await ret.drop_expired_partitions(conn, 12, now)
        assert "samples_2019_01" in dropped
        assert "samples_2019_02" not in dropped
        assert await _exists(conn, "samples_2019_02")


async def test_stale_rollup_schema_holds_every_drop(app):
    """Until the rollup has backfilled a newly added column, raw stays."""
    now = int(time.time() * 1000)
    async with app.state.pool.acquire() as conn:
        await _seed(conn, (2019, 1, 1))
        await _archived(conn, now)
        await conn.execute("UPDATE samples_rollup_state SET rollup_schema_ver = 0")
        assert await ret.drop_expired_partitions(conn, 12, now) == []
        assert await _exists(conn, "samples_2019_01")


async def test_disabled_drops_nothing(app):
    now = int(time.time() * 1000)
    async with app.state.pool.acquire() as conn:
        await _seed(conn, (2019, 1, 1))
        await _archived(conn, now)
        assert await ret.drop_expired_partitions(conn, 0, now) == []
        assert await ret.drop_expired_partitions(conn, -1, now) == []
        assert await _exists(conn, "samples_2019_01")


async def test_gives_up_behind_a_long_reader_then_drops_on_the_next_pass(app):
    now = int(time.time() * 1000)
    async with app.state.pool.acquire() as conn:
        await _seed(conn, (2019, 1, 2))
        await _archived(conn, now)
        reader = await asyncpg.connect(settings.database_url)
        tx = reader.transaction()
        await tx.start()
        await reader.execute("LOCK TABLE samples IN ACCESS SHARE MODE")   # as pg_dump does
        try:
            t0 = time.monotonic()
            dropped = await ret.drop_expired_partitions(conn, 12, now)
            assert time.monotonic() - t0 < 30
            assert "samples_2019_01" not in dropped
            assert await _exists(conn, "samples_2019_01")
            assert await _log(conn) == []          # nothing logged for a drop that did not happen
        finally:
            await tx.rollback()
            await reader.close()
        assert "samples_2019_01" in await ret.drop_expired_partitions(conn, 12, now)
        assert ("samples_2019_01", 2) in await _log(conn)


async def test_maintenance_pass_runs_retention(app, set_setting):
    now = int(time.time() * 1000)
    async with app.state.pool.acquire() as conn:
        await _seed(conn, (2019, 1, 1))
        await _archived(conn, now)
    set_setting("raw_retention_months", 0)
    report = await run_maintenance(app.state.pool, now_ms=now)
    assert report["retention"] == []
    set_setting("raw_retention_months", 12)
    report = await run_maintenance(app.state.pool, now_ms=now)
    assert "samples_2019_01" in report["retention"]


def test_raw_retention_months_default():
    assert settings.raw_retention_months == 12


async def test_schema_has_rollup_archive_columns_and_retention_log(app):
    async with app.state.pool.acquire() as conn:
        cols = {(r["table_name"], r["column_name"]) for r in await conn.fetch(
            """SELECT table_name, column_name FROM information_schema.columns
                WHERE table_name IN ('samples_rollup', 'samples_rollup_state', 'samples_retention_log')""")}
    assert {("samples_rollup", "cap_sum"), ("samples_rollup", "cap_n"),
            ("samples_rollup", "cycles_max"), ("samples_rollup_state", "rollup_schema_ver"),
            ("samples_retention_log", "row_count")} <= cols
