"""SRV-24: partition DDL runs off the request path (lifespan + an hourly maintenance pass)
under a 0.5 s lock_timeout, never unbounded inside an ingest transaction; request queries
are bounded by the pool's command_timeout."""
import asyncio
import logging
import time
from datetime import datetime, timezone

import asyncpg
import pytest

from app import maintenance
from app.config import settings
from app.db import partitions as parts
from app.db import pool as pool_mod
from app.db import queries as q
from app.db.partitions import (PRECREATE_AHEAD_MS, ensure_partition, next_month_partition_name,
                               precreate_partitions, reset_ensured_months)
from app.main import create_app
from app.maintenance import run_maintenance

DEV = "00000000-0000-0000-0000-000000000009"
A = "C8:47:80:15:25:01"


def _ms(y: int, m: int, d: int = 15) -> int:
    return int(datetime(y, m, d, tzinfo=timezone.utc).timestamp() * 1000)


async def _drop(conn, *names: str) -> None:
    for n in names:
        await conn.execute(f"DROP TABLE IF EXISTS {n}")
    reset_ensured_months()  # the process cache must never claim a dropped partition


async def _long_reader():
    """A second session holding ACCESS SHARE on samples, as a long read or pg_dump does."""
    conn = await asyncpg.connect(settings.database_url)
    tx = conn.transaction()
    await tx.start()
    await conn.execute("LOCK TABLE samples IN ACCESS SHARE MODE")
    return conn, tx


async def _ingest_one_at(conn, ts_ms: int) -> str:
    """Insert one sample the way ingest does (insert_samples inside a transaction) and
    return the partition it landed in."""
    row = q.sample_row(DEV, A, {"ts_ms": ts_ms, "state": "Idle", "soc": 80.0})
    async with conn.transaction():
        assert await q.insert_samples(conn, [row]) == 1
    return await conn.fetchval(
        "SELECT tableoid::regclass::text FROM samples WHERE address = $1 AND ts_ms = $2",
        A, ts_ms)


def test_next_month_partition_name_rolls_over_the_year():
    assert next_month_partition_name(_ms(2026, 12)) == "samples_2027_01"
    assert next_month_partition_name(_ms(2026, 10, 3)) == "samples_2026_11"


def test_the_precreate_window_always_reaches_into_the_next_month():
    # A month is at most 31 days long, so [now, now + PRECREATE_AHEAD_MS] covers the start
    # of the next month from ANY instant, including the first millisecond of a month.
    for y, m in [(2026, 1), (2026, 2), (2028, 2), (2026, 4), (2026, 12)]:
        start = _ms(y, m, 1)
        nxt = next_month_partition_name(start)
        ny, nm = (y + 1, 1) if m == 12 else (y, m + 1)
        assert nxt == f"samples_{ny:04d}_{nm:02d}"
        assert _ms(ny, nm, 1) <= start + PRECREATE_AHEAD_MS


async def test_precreate_creates_this_and_next_month(app):
    async with app.state.pool.acquire() as conn:
        await _drop(conn, "samples_2031_07", "samples_2031_08")
        try:
            span = (_ms(2031, 7), _ms(2031, 7) + PRECREATE_AHEAD_MS)
            assert await precreate_partitions(conn, *span) == ["samples_2031_07", "samples_2031_08"]
            assert await precreate_partitions(conn, *span) == []
        finally:
            await _drop(conn, "samples_2031_07", "samples_2031_08")


async def test_precreate_refuses_to_run_inside_a_transaction(app):
    async with app.state.pool.acquire() as conn:
        async with conn.transaction():
            with pytest.raises(RuntimeError):
                await precreate_partitions(conn, _ms(2031, 7), _ms(2031, 7))


async def test_precreate_gives_up_fast_behind_a_long_reader(app):
    async with app.state.pool.acquire() as conn:
        await _drop(conn, "samples_2031_09")
    holder, tx = await _long_reader()
    try:
        async with app.state.pool.acquire() as conn:
            t0 = time.monotonic()
            with pytest.raises(asyncpg.exceptions.LockNotAvailableError):
                await precreate_partitions(conn, _ms(2031, 9), _ms(2031, 9))
            assert time.monotonic() - t0 < 5
            assert (2031, 9) not in parts._ensured
    finally:
        await tx.rollback()
        await holder.close()
    async with app.state.pool.acquire() as conn:
        try:
            assert await precreate_partitions(conn, _ms(2031, 9), _ms(2031, 9)) == ["samples_2031_09"]
        finally:
            await _drop(conn, "samples_2031_09")


async def test_in_request_partition_ddl_has_a_short_lock_timeout(app):
    async with app.state.pool.acquire() as conn:
        await _drop(conn, "samples_2031_10")
    holder, tx = await _long_reader()
    try:
        async with app.state.pool.acquire() as conn:
            t0 = time.monotonic()
            with pytest.raises(asyncpg.exceptions.LockNotAvailableError):
                async with conn.transaction():  # stands in for the ingest transaction
                    await ensure_partition(conn, 2031, 10)
            assert time.monotonic() - t0 < 5
    finally:
        await tx.rollback()
        await holder.close()
        reset_ensured_months()


async def test_maintenance_pass_precreates_the_coming_month(app):
    async with app.state.pool.acquire() as conn:
        await _drop(conn, "samples_2031_11", "samples_2031_12")
    try:
        report = await run_maintenance(app.state.pool, now_ms=_ms(2031, 11))
        assert report["partitions"] == ["samples_2031_11", "samples_2031_12"]
    finally:
        async with app.state.pool.acquire() as conn:
            await _drop(conn, "samples_2031_11", "samples_2031_12")


async def test_after_a_pass_ingest_at_midnight_on_the_1st_needs_no_ddl(app):
    # The last minute of a month: the pass must already have made the NEXT month, so a
    # sample stamped at exactly 00:00:00.000 UTC on the 1st inserts even while a long
    # reader (the nightly pg_dump) holds samples and the request path could not CREATE.
    now = int(datetime(2031, 11, 30, 23, 59, tzinfo=timezone.utc).timestamp() * 1000)
    midnight = _ms(2031, 12, 1)
    async with app.state.pool.acquire() as conn:
        await _drop(conn, "samples_2031_11", "samples_2031_12")
    try:
        report = await run_maintenance(app.state.pool, now_ms=now)
        assert report["partitions"] == ["samples_2031_11", next_month_partition_name(now)]
        reset_ensured_months()  # as after a restart: the ingest path must not need the cache
        holder, tx = await _long_reader()
        try:
            async with app.state.pool.acquire() as conn:
                assert await _ingest_one_at(conn, midnight) == "samples_2031_12"
        finally:
            await tx.rollback()
            await holder.close()
    finally:
        async with app.state.pool.acquire() as conn:
            await _drop(conn, "samples_2031_11", "samples_2031_12")


async def test_a_pass_on_the_real_clock_creates_next_month_and_it_takes_an_insert(app):
    # The scheduled pass (no now_ms) against the wall clock: next month's partition is
    # made, and a sample dated inside it is accepted.
    nxt = next_month_partition_name(int(time.time() * 1000))
    async with app.state.pool.acquire() as conn:
        await _drop(conn, nxt)  # the lifespan already made it; prove the PASS makes it
    report = await run_maintenance(app.state.pool)
    assert report["partitions"] == [nxt]
    y, m = int(nxt[8:12]), int(nxt[13:15])
    async with app.state.pool.acquire() as conn:
        assert await _ingest_one_at(conn, _ms(y, m, 1)) == nxt


async def test_a_failing_step_is_logged_and_does_not_stop_the_pass(app, monkeypatch, caplog):
    async def boom(*args, **kwargs):
        raise RuntimeError("boom")

    monkeypatch.setattr(maintenance, "precreate_partitions", boom)
    caplog.set_level(logging.WARNING, logger="app.maintenance")
    report = await run_maintenance(app.state.pool)
    assert report["partitions"] is None
    assert "partition pre-create failed" in caplog.text


async def test_a_lock_timeout_is_a_warning_not_a_traceback(app, monkeypatch, caplog):
    async def busy(*args, **kwargs):
        raise asyncpg.exceptions.LockNotAvailableError("canceling statement due to lock timeout")

    monkeypatch.setattr(maintenance, "precreate_partitions", busy)
    caplog.set_level(logging.WARNING, logger="app.maintenance")
    report = await run_maintenance(app.state.pool)
    assert report["partitions"] is None
    [rec] = [r for r in caplog.records if r.name == "app.maintenance"]
    assert rec.levelno == logging.WARNING and rec.exc_info is None
    assert "gave up on a lock wait" in rec.getMessage()


async def test_request_queries_are_bounded_by_command_timeout(monkeypatch):
    monkeypatch.setattr(pool_mod, "COMMAND_TIMEOUT_S", 0.3)
    application = create_app()
    async with application.router.lifespan_context(application):
        async with application.state.pool.acquire() as conn:
            with pytest.raises(asyncio.TimeoutError):
                await conn.fetchval("SELECT pg_sleep(2)")
            # background statements opt out explicitly
            await conn.execute("SELECT pg_sleep(0.5)", timeout=pool_mod.MAINTENANCE_TIMEOUT_S)
