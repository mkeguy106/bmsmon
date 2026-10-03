"""The startup schema run must not wait unboundedly for AccessExclusiveLock on samples
(e.g. behind the nightly pg_dump's AccessShareLock) — it retries under a short
lock_timeout instead, so other queries never queue behind it for more than ~0.5 s."""

import logging

import asyncpg
import pytest

from app.config import settings
from app.db import pool as pool_mod
from app.db.pool import SCHEMA_LOCK_TIMEOUT, apply_schema, create_pool


async def _holder():
    """A second session holding ACCESS SHARE on samples, exactly like pg_dump does."""
    conn = await asyncpg.connect(settings.database_url)
    tx = conn.transaction()
    await tx.start()
    await conn.execute("LOCK TABLE samples IN ACCESS SHARE MODE")
    return conn, tx


def test_lock_timeout_is_shorter_than_deadlock_timeout():
    # Under Postgres' default deadlock_timeout (1 s) our lock wait gives up first, so a
    # boot that overlaps the dump's lock-acquisition phase backs off instead of winning
    # deadlock resolution against the backup.
    assert SCHEMA_LOCK_TIMEOUT == "500ms"


async def test_schema_retries_until_the_dump_releases_its_lock(app, caplog):
    caplog.set_level(logging.WARNING, logger="app.db.pool")
    holder, tx = await _holder()
    calls: list[float] = []

    async def fake_sleep(d: float) -> None:
        calls.append(d)
        if len(calls) == 1:          # the "dump" finishes during the first backoff
            await tx.commit()

    try:
        async with app.state.pool.acquire() as conn:
            default = await conn.fetchval("SHOW lock_timeout")
            await apply_schema(conn, sleep=fake_sleep, delays=(0.01, 0.01, 0.01))
            assert await conn.fetchval("SHOW lock_timeout") == default  # SET LOCAL only
    finally:
        await holder.close()
    assert calls == [0.01]           # one lock timeout, one retry, then success
    assert [r.getMessage() for r in caplog.records if r.name == "app.db.pool"] == [
        "schema: lock wait timed out (attempt 1); retrying in 0.01s"]


async def test_schema_gives_up_after_the_last_retry(app):
    holder, tx = await _holder()
    slept: list[float] = []

    async def fake_sleep(d: float) -> None:
        slept.append(d)

    try:
        async with app.state.pool.acquire() as conn:
            default = await conn.fetchval("SHOW lock_timeout")
            with pytest.raises(asyncpg.exceptions.LockNotAvailableError):
                await apply_schema(conn, sleep=fake_sleep, delays=(0.0, 0.0))
            assert await conn.fetchval("SHOW lock_timeout") == default
    finally:
        await tx.rollback()
        await holder.close()
    assert slept == [0.0, 0.0]       # three attempts: two retries, then re-raise


async def test_schema_applies_normally_without_contention(app):
    async with app.state.pool.acquire() as conn:
        await apply_schema(conn)     # idempotent re-run on an already-migrated DB
        assert await conn.fetchval("SELECT to_regclass('samples') IS NOT NULL")


class _DeadlockOnceConn:
    """Stands in for a connection whose first schema run loses a deadlock."""

    def __init__(self, real):
        self.real = real
        self.schema_runs = 0

    def transaction(self):
        return self.real.transaction()

    async def execute(self, sql):
        if sql == pool_mod._SCHEMA:
            self.schema_runs += 1
            if self.schema_runs == 1:
                raise asyncpg.exceptions.DeadlockDetectedError("deadlock detected")
        return await self.real.execute(sql)


async def test_schema_retries_after_a_deadlock(app, caplog):
    caplog.set_level(logging.WARNING, logger="app.db.pool")
    slept: list[float] = []

    async def fake_sleep(d: float) -> None:
        slept.append(d)

    async with app.state.pool.acquire() as real:
        conn = _DeadlockOnceConn(real)
        await apply_schema(conn, sleep=fake_sleep, delays=(0.0, 0.0))
    assert conn.schema_runs == 2 and slept == [0.0]
    assert [r.getMessage() for r in caplog.records if r.name == "app.db.pool"] == [
        "schema: deadlock detected (attempt 1); retrying in 0.0s"]


async def test_create_pool_closes_the_pool_when_the_schema_finally_fails(monkeypatch):
    created: list[asyncpg.Pool] = []
    real_create_pool = asyncpg.create_pool

    async def capture(*args, **kwargs):
        p = await real_create_pool(*args, **kwargs)
        created.append(p)
        return p

    async def give_up(conn, **_):
        raise asyncpg.exceptions.LockNotAvailableError("canceling statement")

    monkeypatch.setattr(pool_mod.asyncpg, "create_pool", capture)
    monkeypatch.setattr(pool_mod, "apply_schema", give_up)
    with pytest.raises(asyncpg.exceptions.LockNotAvailableError):
        await create_pool()
    assert len(created) == 1 and created[0].is_closing()
