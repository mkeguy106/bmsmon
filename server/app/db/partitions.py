from datetime import datetime, timezone

import asyncpg


def _month_bounds(year: int, month: int) -> tuple[str, str, str]:
    start = datetime(year, month, 1, tzinfo=timezone.utc)
    ny, nm = (year + 1, 1) if month == 12 else (year, month + 1)
    end = datetime(ny, nm, 1, tzinfo=timezone.utc)
    name = f"samples_{year:04d}_{month:02d}"
    return name, start.isoformat(), end.isoformat()


def _months_in_range(min_ms: int, max_ms: int) -> set[tuple[int, int]]:
    lo = datetime.fromtimestamp(min_ms / 1000, tz=timezone.utc)
    hi = datetime.fromtimestamp(max_ms / 1000, tz=timezone.utc)
    out: set[tuple[int, int]] = set()
    y, m = lo.year, lo.month
    while (y, m) <= (hi.year, hi.month):
        out.add((y, m))
        y, m = (y + 1, 1) if m == 12 else (y, m + 1)
    return out


# Process-local cache of months whose partition is KNOWN to exist as COMMITTED catalog
# state, so the 99.99%-case ingest batch skips the CREATE TABLE IF NOT EXISTS + savepoint
# round trip entirely. Grows by one entry per month for the process lifetime — bounded in
# practice (a year of uptime = 12 tuples). PROCESS-LOCAL is fine (single worker, SRV-8),
# and safe across test apps: entries are only added for verified-committed partitions,
# and nothing ever drops a partition (test TRUNCATEs keep them).
#
# ROLLBACK SAFETY: a month is NEVER added right after a CREATE that ran inside an outer
# (ingest) transaction — that CREATE is a savepoint and rolls back with the batch, and a
# poisoned cache would make every later insert fail with "no partition ... found for row".
# Inside a transaction we only trust to_regclass() (sees committed DDL); outside one
# (startup/lifespan, autocommit) the CREATE commits on the context exit, so caching is safe.
_ensured: set[tuple[int, int]] = set()


def reset_ensured_months() -> None:
    """Test hook: forget which partitions this process has verified."""
    _ensured.clear()


async def ensure_partition(conn: asyncpg.Connection, year: int, month: int) -> None:
    if (year, month) in _ensured:
        return
    name, start, end = _month_bounds(year, month)
    in_tx = conn.is_in_transaction()
    if in_tx:
        if await conn.fetchval("SELECT to_regclass($1)", name) is not None:
            _ensured.add((year, month))
            return
    try:
        # Nested conn.transaction() = a SAVEPOINT when we're already inside the ingest
        # transaction, so a failed CREATE doesn't abort the whole batch insert.
        async with conn.transaction():
            await conn.execute(
                f"CREATE TABLE IF NOT EXISTS {name} PARTITION OF samples "
                f"FOR VALUES FROM ('{start}') TO ('{end}')"
            )
    except (asyncpg.exceptions.UniqueViolationError, asyncpg.exceptions.DuplicateTableError,
            asyncpg.exceptions.DuplicateObjectError):
        # Known Postgres catalog race: two connections running CREATE TABLE IF NOT EXISTS
        # for the same partition concurrently can still raise unique_violation /
        # duplicate_table. The loser can safely proceed — the partition exists.
        pass
    if not in_tx:
        # Autocommit: the nested conn.transaction() above was a real transaction and has
        # committed (or the partition already existed) — safe to cache. The in-tx path
        # instead re-verifies via to_regclass on the NEXT batch (one cheap SELECT), which
        # only ever happens for the first couple of batches of a brand-new month.
        _ensured.add((year, month))


async def ensure_partitions_for_range(conn: asyncpg.Connection, min_ms: int, max_ms: int) -> None:
    for y, m in sorted(_months_in_range(min_ms, max_ms)):
        await ensure_partition(conn, y, m)
