"""SRV-32: an index on samples is DECLARED ON ONLY in schema.sql and completed online by the
maintenance pass: CREATE INDEX CONCURRENTLY per partition (ingest keeps writing), then
ATTACH. The catalog is the only state, so an interrupted pass is simply redone."""
import asyncio
import logging
import re
import time
import uuid
from datetime import datetime, timezone

import asyncpg
import pytest

from app.config import settings
from app.db import online_index
from app.db import queries as q
from app.db.online_index import (child_index_name, complete_partitioned_indexes,
                                 online_index_status)
from app.db.partitions import _month_bounds, precreate_partitions, reset_ensured_months
from app.db.pool import apply_schema
from app.maintenance import run_maintenance

PARENT = "samples_zztest_idx"
DEV = "00000000-0000-0000-0000-000000000001"
A = "C8:47:80:15:67:44"


def _current_partition() -> str:
    now = datetime.now(timezone.utc)
    return _month_bounds(now.year, now.month)[0]


async def _declare(conn) -> None:
    await conn.execute(f"DROP INDEX IF EXISTS {PARENT}")
    await conn.execute(f"CREATE INDEX {PARENT} ON ONLY samples (address)")


async def _attached(conn) -> dict[str, str]:
    """partition -> the child of PARENT attached to it"""
    rows = await conn.fetch(
        """SELECT p.relname AS partition, c.relname AS child
             FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
             JOIN pg_index x ON x.indexrelid = c.oid JOIN pg_class p ON p.oid = x.indrelid
            WHERE i.inhparent = $1::regclass""", PARENT)
    return {r["partition"]: r["child"] for r in rows}


async def _partitions(conn) -> set[str]:
    return {r["relname"] for r in await conn.fetch(
        "SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid "
        "WHERE i.inhparent = 'samples'::regclass")}


async def _long_reader():
    """A second session like the nightly pg_dump: ACCESS SHARE on samples, and a snapshot
    older than any build started after it."""
    conn = await asyncpg.connect(settings.database_url)
    tx = conn.transaction(isolation="repeatable_read")
    await tx.start()
    await conn.execute("LOCK TABLE samples IN ACCESS SHARE MODE")
    await conn.fetchval("SELECT count(*) FROM samples")
    return conn, tx


async def _drop_test_indexes(conn) -> None:
    """PARENT with its attached children, and any child a failed build left unattached."""
    await conn.execute(f"DROP INDEX IF EXISTS {PARENT}")
    for r in await conn.fetch(
            "SELECT relname FROM pg_class WHERE relname LIKE 'samples\\_%\\_zztest\\_idx'"):
        await conn.execute(f'DROP INDEX IF EXISTS "{r["relname"]}"')


def test_child_names_follow_the_parent():
    assert child_index_name("samples_charging_idx", "samples_2026_10") == "samples_2026_10_charging_idx"
    with pytest.raises(ValueError):
        child_index_name("charging_idx", "samples_2026_10")


async def test_declared_index_is_completed_on_every_partition(app):
    async with app.state.pool.acquire() as conn:
        await _declare(conn)
        try:
            assert (await online_index_status(conn))[PARENT] is False
            default_lock_timeout = await conn.fetchval("SHOW lock_timeout")
            await complete_partitioned_indexes(conn)
            assert set(await _attached(conn)) == await _partitions(conn)
            assert (await online_index_status(conn))[PARENT] is True
            assert await complete_partitioned_indexes(conn) == []  # nothing left to do
            assert await conn.fetchval("SHOW lock_timeout") == default_lock_timeout
        finally:
            await conn.execute(f"DROP INDEX IF EXISTS {PARENT}")


async def test_a_failed_concurrent_build_is_dropped_and_rebuilt(app):
    """A CONCURRENTLY build that dies (a deploy, a restart, here a unique violation) leaves an
    INVALID index under the child's name; the next pass must replace it, never attach it."""
    part = _current_partition()
    child = child_index_name(PARENT, part)
    now = int(time.time() * 1000)
    async with app.state.pool.acquire() as conn:
        await conn.execute(f"DROP INDEX IF EXISTS {PARENT}")
        await conn.execute(f'DROP INDEX IF EXISTS "{child}"')
        await q.insert_samples(conn, [q.sample_row(DEV, A, {"ts_ms": now, "soc": 50.0}),
                                      q.sample_row(DEV, A, {"ts_ms": now + 1000, "soc": 50.0})])
        with pytest.raises(asyncpg.exceptions.UniqueViolationError):
            await conn.execute(f'CREATE UNIQUE INDEX CONCURRENTLY "{child}" ON "{part}" (soc)')
        assert await conn.fetchval(
            "SELECT indisvalid FROM pg_index WHERE indexrelid = to_regclass($1)", child) is False
        await _declare(conn)
        try:
            await complete_partitioned_indexes(conn)
            assert (await _attached(conn))[part] == child
            assert "(address)" in await conn.fetchval("SELECT pg_get_indexdef(to_regclass($1))", child)
        finally:
            await conn.execute(f"DROP INDEX IF EXISTS {PARENT}")
            await conn.execute(f'DROP INDEX IF EXISTS "{child}"')


async def test_a_same_named_index_with_another_definition_is_rebuilt(app):
    part = _current_partition()
    child = child_index_name(PARENT, part)
    async with app.state.pool.acquire() as conn:
        await conn.execute(f"DROP INDEX IF EXISTS {PARENT}")
        await conn.execute(f'DROP INDEX IF EXISTS "{child}"')
        await conn.execute(f'CREATE INDEX "{child}" ON "{part}" (ts)')
        await _declare(conn)
        try:
            await complete_partitioned_indexes(conn)
            assert (await _attached(conn))[part] == child
            assert "(address)" in await conn.fetchval("SELECT pg_get_indexdef(to_regclass($1))", child)
        finally:
            await conn.execute(f"DROP INDEX IF EXISTS {PARENT}")
            await conn.execute(f'DROP INDEX IF EXISTS "{child}"')


async def test_the_maintenance_pass_completes_declared_indexes(app):
    async with app.state.pool.acquire() as conn:
        await _declare(conn)
    try:
        report = await run_maintenance(app.state.pool)
        assert report["indexes"]
        async with app.state.pool.acquire() as conn:
            assert (await online_index_status(conn))[PARENT] is True
    finally:
        async with app.state.pool.acquire() as conn:
            await conn.execute(f"DROP INDEX IF EXISTS {PARENT}")


async def test_ingest_proceeds_while_a_build_runs(app):
    """A CONCURRENTLY build waits for every transaction older than it (here a pg_dump-like
    reader). Ingest into the very partition being indexed must not wait with it."""
    async with app.state.pool.acquire() as conn:
        await _declare(conn)
    dump, dump_tx = await _long_reader()
    build_conn = await asyncpg.connect(settings.database_url)
    ingest = await asyncpg.connect(settings.database_url)
    builder = asyncio.create_task(complete_partitioned_indexes(build_conn))
    try:
        building = None
        for _ in range(200):  # until the build reaches its wait for the reader's snapshot
            building = await ingest.fetchval(
                "SELECT query FROM pg_stat_activity WHERE pid = $1 AND wait_event_type = 'Lock' "
                "AND query LIKE 'CREATE INDEX CONCURRENTLY%'", build_conn.get_server_pid())
            if building:
                break
            await asyncio.sleep(0.05)
        assert building, "the build never waited"
        part = re.search(r'ON "(samples_\d{4}_\d{2})"', building).group(1)
        ts_ms = int(datetime(int(part[8:12]), int(part[13:15]), 15,
                             tzinfo=timezone.utc).timestamp() * 1000)

        await ingest.execute("SET lock_timeout = '2s'")
        t0 = time.monotonic()
        async with ingest.transaction():  # as /api/v1/ingest stores a batch
            rows = [q.sample_row(DEV, A, {"ts_ms": ts_ms, "soc": 50.0, "current_a": 4.0})]
            assert await q.insert_samples(ingest, rows) == 1
        assert time.monotonic() - t0 < 2
        assert await ingest.fetchval(
            "SELECT tableoid::regclass::text FROM samples WHERE address = $1 AND ts_ms = $2",
            A, ts_ms) == part
        assert not builder.done()  # the insert really ran while the build was in flight

        await dump_tx.rollback()  # the "dump" ends: the build finishes
        await asyncio.wait_for(builder, 30)
        async with app.state.pool.acquire() as conn:
            assert set(await _attached(conn)) == await _partitions(conn)
    finally:
        if not builder.done():
            builder.cancel()
            await asyncio.gather(builder, return_exceptions=True)
        for c in (dump, build_conn, ingest):
            await c.close()
        async with app.state.pool.acquire() as conn:
            await _drop_test_indexes(conn)


async def test_a_build_behind_a_long_reader_gives_up_and_the_next_pass_repairs_it(
        app, monkeypatch, caplog):
    """Behind the nightly pg_dump a build gives up after BUILD_LOCK_TIMEOUT (shortened here)
    instead of holding the pass for the dump's duration. The pass itself does not fail: the
    step is logged, an INVALID child is left behind, and the next pass replaces it."""
    monkeypatch.setattr(online_index, "BUILD_LOCK_TIMEOUT", "200ms")
    caplog.set_level(logging.WARNING, logger="app.maintenance")
    async with app.state.pool.acquire() as conn:
        await _declare(conn)
    try:
        dump, dump_tx = await _long_reader()
        try:
            report = await run_maintenance(app.state.pool)
        finally:
            await dump_tx.rollback()
            await dump.close()
        assert report["indexes"] is None
        assert "online index build gave up on a lock wait" in caplog.text
        async with app.state.pool.acquire() as conn:
            left = await conn.fetch(
                "SELECT c.relname FROM pg_index x JOIN pg_class c ON c.oid = x.indexrelid "
                "WHERE c.relname LIKE 'samples\\_%\\_zztest\\_idx' AND NOT x.indisvalid")
            assert len(left) == 1  # the build that gave up, INVALID and unattached
            assert (await online_index_status(conn))[PARENT] is False

        report = await run_maintenance(app.state.pool)
        assert left[0]["relname"] in report["indexes"]
        async with app.state.pool.acquire() as conn:
            assert (await online_index_status(conn))[PARENT] is True
            assert set(await _attached(conn)) == await _partitions(conn)
    finally:
        async with app.state.pool.acquire() as conn:
            await _drop_test_indexes(conn)


async def test_attach_gives_up_fast_behind_a_reader_of_the_partition(app):
    """ATTACH locks the child index ACCESS EXCLUSIVE, and every insert into the partition
    locks that index too: an ATTACH left waiting behind a reader would queue ingest behind
    it. It gives up after ATTACH_LOCK_TIMEOUT, and the next pass attaches the child it
    already built (no rebuild)."""
    part = _current_partition()
    child = child_index_name(PARENT, part)
    async with app.state.pool.acquire() as conn:
        await _drop_test_indexes(conn)
        await conn.execute(f'CREATE INDEX "{child}" ON "{part}" (address)')  # built, unattached
        built = await conn.fetchval("SELECT to_regclass($1)::oid", child)
        await _declare(conn)
        try:
            # Every other partition is already done, so the pass goes straight to the ATTACH
            # (a build would first wait out the reader's snapshot: BUILD_LOCK_TIMEOUT).
            for other in await _partitions(conn) - {part}:
                await conn.execute(f'CREATE INDEX "{child_index_name(PARENT, other)}" '
                                   f'ON "{other}" (address)')
                await conn.execute(f'ALTER INDEX {PARENT} ATTACH PARTITION '
                                   f'"{child_index_name(PARENT, other)}"')
            reader = await asyncpg.connect(settings.database_url)
            tx = reader.transaction()
            await tx.start()
            # Planning a query on the partition takes ACCESS SHARE on each of its indexes.
            await reader.fetch(f'SELECT 1 FROM "{part}" WHERE address = $1', A)
            try:
                t0 = time.monotonic()
                with pytest.raises(asyncpg.exceptions.LockNotAvailableError):
                    await complete_partitioned_indexes(conn)
                assert time.monotonic() - t0 < 5
            finally:
                await tx.rollback()
                await reader.close()
            assert await complete_partitioned_indexes(conn) == [child]
            assert (await _attached(conn))[part] == child
            assert await conn.fetchval("SELECT to_regclass($1)::oid", child) == built
        finally:
            await _drop_test_indexes(conn)


async def test_schema_declares_the_charging_index(app):
    async with app.state.pool.acquire() as conn:
        assert "samples_charging_idx" in await online_index_status(conn)
        d = await conn.fetchval("SELECT pg_get_indexdef('samples_charging_idx'::regclass)")
    assert "ON ONLY" in d and "INCLUDE (ts_ms, soc, temp_c)" in d and "current_a > " in d


async def test_a_fresh_database_ends_with_every_index():
    """The boot of an EMPTY database (CI's image smoke, a new install): the ON ONLY
    declaration has no partition to wait for, so it is born valid, and every partition the
    boot then creates gets its child automatically. No maintenance pass is needed."""
    name = f"bmsmon_zz_fresh_{uuid.uuid4().hex[:12]}"
    admin = await asyncpg.connect(settings.database_url)
    await admin.execute(f'CREATE DATABASE "{name}" TEMPLATE template0')
    reset_ensured_months()  # the cache describes the test database, not this one
    try:
        conn = await asyncpg.connect(settings.database_url, database=name)
        try:
            await apply_schema(conn, delays=())
            now = int(time.time() * 1000)
            created = await precreate_partitions(conn, now - 31 * 86_400_000, now + 31 * 86_400_000)
            assert len(created) >= 2
            status = await online_index_status(conn)
            assert "samples_charging_idx" in status and all(status.values()), status
            missing = await conn.fetch(
                """SELECT x.indexrelid::regclass::text AS idx, p.relname AS partition
                     FROM pg_index x JOIN pg_inherits i ON i.inhparent = x.indrelid
                     JOIN pg_class p ON p.oid = i.inhrelid
                    WHERE x.indrelid = 'samples'::regclass
                      AND NOT EXISTS (SELECT 1 FROM pg_inherits ii JOIN pg_index xx
                                        ON xx.indexrelid = ii.inhrelid
                                       WHERE ii.inhparent = x.indexrelid AND xx.indrelid = p.oid)""")
            assert missing == []
        finally:
            await conn.close()
    finally:
        reset_ensured_months()
        await admin.execute(f'DROP DATABASE IF EXISTS "{name}" WITH (FORCE)')
        await admin.close()
