"""The startup schema run must not wait unboundedly for AccessExclusiveLock on samples
(e.g. behind the nightly pg_dump's AccessShareLock) — it retries under a short
lock_timeout instead, so other queries never queue behind it for more than ~0.5 s."""

import logging
import re

import asyncpg
import pytest

from app.config import settings
from app.db import pool as pool_mod
from app.db.pool import SCHEMA_LOCK_TIMEOUT, apply_schema, create_pool


def test_lock_timeout_is_shorter_than_deadlock_timeout():
    # Under Postgres' default deadlock_timeout (1 s) our lock wait gives up first, so a
    # boot that overlaps the dump's lock-acquisition phase backs off instead of winning
    # deadlock resolution against the backup.
    assert SCHEMA_LOCK_TIMEOUT == "500ms"


async def test_schema_applies_normally_without_contention(app):
    async with app.state.pool.acquire() as conn:
        await apply_schema(conn)     # idempotent re-run on an already-migrated DB
        assert await conn.fetchval("SELECT to_regclass('samples') IS NOT NULL")


class _FailingConn:
    """Stands in for a connection whose first `fail_times` schema runs raise `exc`."""

    def __init__(self, real, exc: Exception, fail_times: int):
        self.real, self.exc, self.fail_times = real, exc, fail_times
        self.schema_runs = 0

    def transaction(self):
        return self.real.transaction()

    async def execute(self, sql, *args, **kwargs):
        if sql == pool_mod._SCHEMA:
            self.schema_runs += 1
            if self.schema_runs <= self.fail_times:
                raise self.exc
        return await self.real.execute(sql, *args, **kwargs)


async def test_schema_retries_after_a_lock_timeout(app, caplog):
    caplog.set_level(logging.WARNING, logger="app.db.pool")
    slept: list[float] = []

    async def fake_sleep(d: float) -> None:
        slept.append(d)

    async with app.state.pool.acquire() as real:
        conn = _FailingConn(real, asyncpg.exceptions.LockNotAvailableError("55P03"), 1)
        default = await real.fetchval("SHOW lock_timeout")
        await apply_schema(conn, sleep=fake_sleep, delays=(0.01, 0.01, 0.01))
        assert await real.fetchval("SHOW lock_timeout") == default  # SET LOCAL only
    assert conn.schema_runs == 2 and slept == [0.01]
    assert [r.getMessage() for r in caplog.records if r.name == "app.db.pool"] == [
        "schema: lock wait timed out (attempt 1); retrying in 0.01s"]


async def test_schema_gives_up_after_the_last_retry(app):
    slept: list[float] = []

    async def fake_sleep(d: float) -> None:
        slept.append(d)

    async with app.state.pool.acquire() as real:
        conn = _FailingConn(real, asyncpg.exceptions.LockNotAvailableError("55P03"), 99)
        with pytest.raises(asyncpg.exceptions.LockNotAvailableError):
            await apply_schema(conn, sleep=fake_sleep, delays=(0.0, 0.0))
    assert slept == [0.0, 0.0] and conn.schema_runs == 3  # two retries, then re-raise


async def test_schema_retries_after_a_deadlock(app, caplog):
    caplog.set_level(logging.WARNING, logger="app.db.pool")
    slept: list[float] = []

    async def fake_sleep(d: float) -> None:
        slept.append(d)

    async with app.state.pool.acquire() as real:
        conn = _FailingConn(real, asyncpg.exceptions.DeadlockDetectedError("deadlock detected"), 1)
        await apply_schema(conn, sleep=fake_sleep, delays=(0.0, 0.0))
    assert conn.schema_runs == 2 and slept == [0.0]
    assert [r.getMessage() for r in caplog.records if r.name == "app.db.pool"] == [
        "schema: deadlock detected (attempt 1); retrying in 0.0s"]


async def test_boot_on_an_up_to_date_database_takes_no_table_lock(app):
    """A second session holds ROW EXCLUSIVE on every table: stronger than the nightly
    pg_dump's ACCESS SHARE, and what an in-flight ingest holds. With ZERO retries allowed,
    one lock wait would raise. Bare ALTER TABLE ... IF NOT EXISTS (ACCESS EXCLUSIVE even as
    a no-op) and CREATE INDEX IF NOT EXISTS (SHARE even when present) both fail this."""
    holder = await asyncpg.connect(settings.database_url)
    tx = holder.transaction()
    await tx.start()
    names = [r["t"] for r in await holder.fetch(
        "SELECT format('%I.%I', schemaname, tablename) AS t FROM pg_tables "
        "WHERE schemaname = 'public'")]
    await holder.execute(f"LOCK TABLE {', '.join(names)} IN ROW EXCLUSIVE MODE")
    try:
        async with app.state.pool.acquire() as conn:
            await apply_schema(conn, delays=())
    finally:
        await tx.rollback()
        await holder.close()


def test_schema_sql_has_no_bare_column_alters_or_blocking_index_builds():
    sql = pool_mod._SCHEMA
    assert not re.search(r"ADD\s+COLUMN\s+IF\s+NOT\s+EXISTS", sql, re.I)
    assert not re.search(r"DROP\s+COLUMN\s+IF\s+EXISTS", sql, re.I)
    # An index may only be DECLARED here: ON ONLY the parent (metadata, no data read) and
    # inside an IF (a to_regclass guard), so a boot never builds or even re-checks one.
    for m in re.finditer(r"(\S+)\s+CREATE\s+(?:UNIQUE\s+)?INDEX\b([^;]*)", sql, re.I):
        assert m.group(1).upper() == "THEN", m.group(0)
        assert re.search(r"\bON\s+ONLY\b", m.group(2), re.I), m.group(0)


async def test_column_helpers_add_and_drop_only_when_needed(app):
    async with app.state.pool.acquire() as conn:
        await apply_schema(conn)  # defines the pg_temp helpers on THIS session
        await conn.execute("DROP TABLE IF EXISTS zz_schema_guard")
        await conn.execute("CREATE TABLE zz_schema_guard (id int)")

        async def cols() -> set[str]:
            return {r["attname"] for r in await conn.fetch(
                "SELECT attname FROM pg_attribute WHERE attrelid = 'zz_schema_guard'::regclass "
                "AND attnum > 0 AND NOT attisdropped")}
        try:
            for _ in range(2):  # the second call is a no-op
                await conn.execute("SELECT pg_temp.add_column_if_missing('zz_schema_guard', 'x', 'real')")
            assert await cols() == {"id", "x"}
            for _ in range(2):
                await conn.execute("SELECT pg_temp.drop_column_if_present('zz_schema_guard', 'x')")
            assert await cols() == {"id"}
        finally:
            await conn.execute("DROP TABLE IF EXISTS zz_schema_guard")


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
