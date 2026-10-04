"""The startup schema run must not wait unboundedly for AccessExclusiveLock on samples
(e.g. behind the nightly pg_dump's AccessShareLock) — it retries under a short
lock_timeout instead, so other queries never queue behind it for more than ~0.5 s."""

import asyncio
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


async def test_boot_on_an_up_to_date_database_waits_for_neither_ingest_nor_the_dump(app):
    """A second session holds ROW EXCLUSIVE on every table: what an in-flight ingest holds,
    and it also blocks every mode that would conflict with the nightly pg_dump's ACCESS
    SHARE. With ZERO retries allowed, one lock wait would raise. So the boot takes no lock
    that conflicts with ROW EXCLUSIVE (a weaker one, e.g. SHARE UPDATE EXCLUSIVE, would
    pass). Bare ALTER TABLE ... IF NOT EXISTS (ACCESS EXCLUSIVE even as a no-op) and
    CREATE INDEX IF NOT EXISTS (SHARE even when present) both fail this."""
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
    decls = list(re.finditer(r"\bCREATE\s+(?:UNIQUE\s+)?INDEX\b([^;]*)", sql, re.I))
    assert decls, "no index declaration found: this check would be vacuous"
    for m in decls:
        before = sql[:m.start()].split()
        assert before and before[-1].upper() == "THEN", m.group(0)
        assert re.search(r"\bON\s+ONLY\b", m.group(1), re.I), m.group(0)


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


async def _until_waiting_on_a_lock(observer, pid: int) -> None:
    for _ in range(250):
        if await observer.fetchval(
                "SELECT wait_event_type = 'Lock' FROM pg_stat_activity WHERE pid = $1", pid):
            return
        await asyncio.sleep(0.02)
    raise AssertionError("the second schema run never waited on the lock")


async def test_a_change_a_concurrent_run_made_first_counts_as_done(app):
    """Two schema runs both see the change as still needed; the one that gets the lock
    second must carry on, not fail the boot."""
    async with app.state.pool.acquire() as conn:
        await apply_schema(conn)  # defines the pg_temp helpers on THIS session
        await conn.execute(
            "DROP TABLE IF EXISTS zz_schema_race; CREATE TABLE zz_schema_race (id int)")
        first = await asyncpg.connect(settings.database_url)
        try:
            for change, helper in (
                    ("ADD COLUMN x real", "add_column_if_missing('zz_schema_race', 'x', 'real')"),
                    ("DROP COLUMN x", "drop_column_if_present('zz_schema_race', 'x')")):
                tx = first.transaction()
                await tx.start()
                await first.execute(f"ALTER TABLE zz_schema_race {change}")  # not committed yet
                second = asyncio.ensure_future(conn.execute(f"SELECT pg_temp.{helper}"))
                await _until_waiting_on_a_lock(first, conn.get_server_pid())
                await tx.commit()
                await asyncio.wait_for(second, 5)
        finally:
            await first.close()  # rolls back anything left open, releasing its lock
        await conn.execute("DROP TABLE zz_schema_race")


async def test_a_real_lock_wait_is_retried_until_the_holder_lets_go(app, monkeypatch, caplog):
    """The helpers' ALTER behind a real ACCESS SHARE holder (what pg_dump takes) gives up
    after SCHEMA_LOCK_TIMEOUT, and the next attempt, once the holder is gone, finishes."""
    caplog.set_level(logging.WARNING, logger="app.db.pool")
    helpers = pool_mod._SCHEMA.split("CREATE TABLE IF NOT EXISTS devices")[0]
    monkeypatch.setattr(pool_mod, "_SCHEMA", helpers +
                        "SELECT pg_temp.add_column_if_missing('zz_schema_lock', 'x', 'real');")
    slept: list[float] = []
    holder = await asyncpg.connect(settings.database_url)
    try:
        await holder.execute(
            "DROP TABLE IF EXISTS zz_schema_lock; CREATE TABLE zz_schema_lock (id int)")
        await holder.execute("BEGIN; LOCK TABLE zz_schema_lock IN ACCESS SHARE MODE")

        async def release(d: float) -> None:
            slept.append(d)
            await holder.execute("ROLLBACK")

        async with app.state.pool.acquire() as conn:
            await apply_schema(conn, sleep=release, delays=(0.0, 0.0))
        assert await holder.fetchval("SELECT count(*) FROM pg_attribute WHERE "
                                     "attrelid = 'zz_schema_lock'::regclass AND attname = 'x'") == 1
    finally:
        await holder.execute("ROLLBACK; DROP TABLE IF EXISTS zz_schema_lock")
        await holder.close()
    assert slept == [0.0]
    assert [r.getMessage() for r in caplog.records if r.name == "app.db.pool"] == [
        "schema: lock wait timed out (attempt 1); retrying in 0.0s"]


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
