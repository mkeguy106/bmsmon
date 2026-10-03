"""The startup schema run must not wait unboundedly for AccessExclusiveLock on samples
(e.g. behind the nightly pg_dump's AccessShareLock) — it retries under a short
lock_timeout instead, so other queries never queue behind it for more than ~1 s."""

import asyncpg
import pytest

from app.config import settings
from app.db.pool import apply_schema


async def _holder():
    """A second session holding ACCESS SHARE on samples, exactly like pg_dump does."""
    conn = await asyncpg.connect(settings.database_url)
    tx = conn.transaction()
    await tx.start()
    await conn.execute("LOCK TABLE samples IN ACCESS SHARE MODE")
    return conn, tx


async def test_schema_retries_until_the_dump_releases_its_lock(app):
    holder, tx = await _holder()
    calls: list[float] = []

    async def fake_sleep(d: float) -> None:
        calls.append(d)
        if len(calls) == 1:          # the "dump" finishes during the first backoff
            await tx.commit()

    try:
        async with app.state.pool.acquire() as conn:
            await apply_schema(conn, sleep=fake_sleep, delays=(0.01, 0.01, 0.01))
    finally:
        await holder.close()
    assert calls == [0.01]           # one lock timeout, one retry, then success


async def test_schema_gives_up_after_the_last_retry(app):
    holder, tx = await _holder()
    slept: list[float] = []

    async def fake_sleep(d: float) -> None:
        slept.append(d)

    try:
        async with app.state.pool.acquire() as conn:
            with pytest.raises(asyncpg.exceptions.LockNotAvailableError):
                await apply_schema(conn, sleep=fake_sleep, delays=(0.0, 0.0))
    finally:
        await tx.rollback()
        await holder.close()
    assert slept == [0.0, 0.0]       # three attempts: two retries, then re-raise


async def test_schema_applies_normally_without_contention(app):
    async with app.state.pool.acquire() as conn:
        await apply_schema(conn)     # idempotent re-run on an already-migrated DB
        assert await conn.fetchval("SELECT to_regclass('samples') IS NOT NULL")
