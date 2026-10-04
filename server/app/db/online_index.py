"""Online (non-blocking) builds of partitioned indexes on `samples` (SRV-32).

schema.sql only DECLARES an index on samples: `CREATE INDEX <name> ON ONLY samples ...`
inside a to_regclass guard. That is catalog metadata on the parent (no data is read), and
every partition created afterwards gets a matching index automatically. This module
completes the partitions that already existed, from the hourly maintenance pass:
per partition, CREATE INDEX CONCURRENTLY (no write lock: ingest continues), then ATTACH it
to the parent, which turns valid once every partition has one. The catalog is the only
state: an interrupted pass (deploy, restart, timeout) leaves at worst an INVALID child,
which the next pass drops and rebuilds.

Partitioned index names must start with "samples_". A child this module builds is named
<partition> + <parent name minus "samples">: samples_charging_idx ->
samples_2026_10_charging_idx. (Postgres names the children of partitions created after the
declaration itself; nothing here depends on a child's name once it is attached.)
"""
import re

import asyncpg

from app.db.partitions import PARTITION_LOCK_TIMEOUT
from app.db.pool import MAINTENANCE_TIMEOUT_S

TABLE = "samples"
_ON_ONLY = re.compile(r"^CREATE INDEX \S+ ON ONLY \S+ (USING .+)$")

# CREATE/DROP INDEX CONCURRENTLY never block ingest, but each waits for every transaction
# older than its own snapshot: a request query (bounded by the pool's 30 s command
# timeout), or the nightly pg_dump (minutes). Past this wait the build gives up: the step
# logs a warning, any INVALID child it left is replaced by the next hourly pass.
BUILD_LOCK_TIMEOUT = "60s"
# ATTACH locks the child index ACCESS EXCLUSIVE, and every insert into that partition locks
# the same index, so an ATTACH waiting behind a reader of the partition would queue ingest
# behind it. It gives up as fast as partition DDL does; the next pass attaches the child
# that is already built.
ATTACH_LOCK_TIMEOUT = PARTITION_LOCK_TIMEOUT

_INVALID_PARENTS = """
SELECT c.relname
  FROM pg_index x JOIN pg_class c ON c.oid = x.indexrelid
 WHERE x.indrelid = $1::regclass AND NOT x.indisvalid
 ORDER BY c.relname
"""

# Partitions of the table that have no child of the parent index attached yet.
_PENDING = """
SELECT p.relname AS partition
  FROM pg_inherits i JOIN pg_class p ON p.oid = i.inhrelid
 WHERE i.inhparent = $2::regclass
   AND NOT EXISTS (
     SELECT 1 FROM pg_inherits ii JOIN pg_index x ON x.indexrelid = ii.inhrelid
      WHERE ii.inhparent = $1::regclass AND x.indrelid = p.oid)
 ORDER BY p.relname
"""


def child_index_name(parent_index: str, partition: str) -> str:
    if not parent_index.startswith(TABLE + "_"):
        raise ValueError(f"partitioned index {parent_index!r} must be named {TABLE}_...")
    return partition + parent_index[len(TABLE):]


async def _concurrently(conn: asyncpg.Connection, sql: str) -> None:
    """Run a CREATE/DROP INDEX CONCURRENTLY under BUILD_LOCK_TIMEOUT. Neither may run in a
    transaction block, so the timeout is a session setting, put back afterwards."""
    previous = await conn.fetchval("SHOW lock_timeout")
    await conn.execute(f"SET lock_timeout = '{BUILD_LOCK_TIMEOUT}'")
    try:
        await conn.execute(sql, timeout=MAINTENANCE_TIMEOUT_S)
    finally:
        await conn.execute("SELECT set_config('lock_timeout', $1, false)", previous)


async def _attach(conn: asyncpg.Connection, parent_index: str, child: str) -> None:
    async with conn.transaction():
        await conn.execute(f"SET LOCAL lock_timeout = '{ATTACH_LOCK_TIMEOUT}'")
        await conn.execute(f'ALTER INDEX "{parent_index}" ATTACH PARTITION "{child}"')


async def complete_partitioned_index(conn: asyncpg.Connection, parent_index: str) -> list[str]:
    """Build and attach the missing per-partition children of one ON ONLY parent index.
    Must run outside a transaction (CREATE INDEX CONCURRENTLY). Returns the children this
    call attached. Raises LockNotAvailableError when a wait outlasts its lock timeout; the
    children attached so far stay attached, and the next call carries on."""
    indexdef = await conn.fetchval("SELECT pg_get_indexdef($1::regclass)", parent_index)
    m = _ON_ONLY.match(indexdef or "")
    if m is None:
        raise ValueError(f"{parent_index} is not an ON ONLY partitioned index: {indexdef}")
    using = m.group(1)
    attached: list[str] = []
    for row in await conn.fetch(_PENDING, parent_index, TABLE):
        partition = row["partition"]
        child = child_index_name(parent_index, partition)
        valid = await conn.fetchval(
            "SELECT x.indisvalid FROM pg_index x WHERE x.indexrelid = to_regclass($1)", child)
        if valid is True:
            try:
                await _attach(conn, parent_index, child)
                attached.append(child)
                continue
            except asyncpg.exceptions.InvalidObjectDefinitionError:
                valid = False  # same name, different definition: rebuild it
        if valid is False:
            # An interrupted CONCURRENTLY build leaves an INVALID index under this name.
            await _concurrently(conn, f'DROP INDEX CONCURRENTLY IF EXISTS "{child}"')
        await _concurrently(conn, f'CREATE INDEX CONCURRENTLY "{child}" ON "{partition}" {using}')
        await _attach(conn, parent_index, child)
        attached.append(child)
    return attached


async def complete_partitioned_indexes(conn: asyncpg.Connection) -> list[str]:
    """Complete every not-yet-valid partitioned index on samples (normally right after the
    deploy that declared one). Once all are valid this is one catalog read."""
    attached: list[str] = []
    for r in await conn.fetch(_INVALID_PARENTS, TABLE):
        attached += await complete_partitioned_index(conn, r["relname"])
    return attached


async def online_index_status(conn: asyncpg.Connection) -> dict[str, bool]:
    """index name -> valid, for every non-primary index declared on samples."""
    rows = await conn.fetch(
        """SELECT c.relname, x.indisvalid FROM pg_index x JOIN pg_class c ON c.oid = x.indexrelid
            WHERE x.indrelid = $1::regclass AND NOT x.indisprimary ORDER BY c.relname""", TABLE)
    return {r["relname"]: r["indisvalid"] for r in rows}
