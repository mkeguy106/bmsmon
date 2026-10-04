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
# and the app never drops a partition (test TRUNCATEs keep them; a test that drops one
# calls reset_ensured_months()).
#
# ROLLBACK SAFETY: a month is NEVER added right after a CREATE that ran inside an outer
# (ingest) transaction — that CREATE is a savepoint and rolls back with the batch, and a
# poisoned cache would make every later insert fail with "no partition ... found for row".
# Inside a transaction we only trust to_regclass() (sees committed DDL); outside one
# (precreate_partitions from the lifespan and the maintenance pass, autocommit) the CREATE
# commits on the context exit, so caching is safe.
_ensured: set[tuple[int, int]] = set()

# SRV-24: partition DDL needs ACCESS EXCLUSIVE on samples. Waiting for it unboundedly
# behind a long reader would also queue every later query on samples behind the CREATE,
# so every CREATE here gives up after this long and is retried later.
PARTITION_LOCK_TIMEOUT = "500ms"
# How far ahead the maintenance pass keeps partitions: always this month and the next.
PRECREATE_AHEAD_MS = 31 * 86_400_000


def next_month_partition_name(now_ms: int) -> str:
    """Name of the partition for the UTC month after now_ms's month."""
    d = datetime.fromtimestamp(now_ms / 1000, tz=timezone.utc)
    y, m = (d.year + 1, 1) if d.month == 12 else (d.year, d.month + 1)
    return _month_bounds(y, m)[0]


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
            if in_tx:
                # SRV-24: inside a request, give up quickly (LockNotAvailableError -> a
                # marked 503 -> the phone retries) rather than queue ingest behind a long
                # reader. The setting also bounds the rest of this one batch's
                # transaction, i.e. its INSERT. Steady state never gets here: the
                # maintenance pass pre-creates every partition a month ahead.
                await conn.execute(f"SET LOCAL lock_timeout = '{PARTITION_LOCK_TIMEOUT}'")
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


async def precreate_partitions(conn: asyncpg.Connection, min_ms: int, max_ms: int) -> list[str]:
    """SRV-24: create the monthly partitions covering [min_ms, max_ms] OFF the request path
    (lifespan + the hourly maintenance pass), each in its own short transaction under
    PARTITION_LOCK_TIMEOUT. Raises LockNotAvailableError when a long reader holds samples;
    the caller logs it and the next pass retries. Returns the partitions it created."""
    if conn.is_in_transaction():
        raise RuntimeError("precreate_partitions must run outside a transaction")
    created: list[str] = []
    for y, m in sorted(_months_in_range(min_ms, max_ms)):
        if (y, m) in _ensured:
            continue
        name, start, end = _month_bounds(y, m)
        if await conn.fetchval("SELECT to_regclass($1)", name) is None:
            try:
                async with conn.transaction():
                    await conn.execute(f"SET LOCAL lock_timeout = '{PARTITION_LOCK_TIMEOUT}'")
                    await conn.execute(
                        f"CREATE TABLE IF NOT EXISTS {name} PARTITION OF samples "
                        f"FOR VALUES FROM ('{start}') TO ('{end}')")
                created.append(name)
            except (asyncpg.exceptions.UniqueViolationError,
                    asyncpg.exceptions.DuplicateTableError,
                    asyncpg.exceptions.DuplicateObjectError):
                pass  # created concurrently (see ensure_partition): it exists
        _ensured.add((y, m))  # committed, or already there: safe to cache
    return created
