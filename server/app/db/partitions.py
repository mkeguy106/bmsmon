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


async def ensure_partition(conn: asyncpg.Connection, year: int, month: int) -> None:
    name, start, end = _month_bounds(year, month)
    await conn.execute(
        f"CREATE TABLE IF NOT EXISTS {name} PARTITION OF samples "
        f"FOR VALUES FROM ('{start}') TO ('{end}')"
    )


async def ensure_partitions_for_range(conn: asyncpg.Connection, min_ms: int, max_ms: int) -> None:
    for y, m in sorted(_months_in_range(min_ms, max_ms)):
        await ensure_partition(conn, y, m)
