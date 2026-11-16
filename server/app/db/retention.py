"""Raw samples retention (2026-07-22 design, docs/superpowers/specs/2026-07-22-db-retention-
strategy-design.md).

samples grows about 1 GB a month and is partitioned by UTC month. samples_rollup is the
permanent battery-health archive (SOC, SOH, cell spread, temperature, capacity, cycles at
30-min resolution), so raw only needs a bounded recent window for the detail the rollup
cannot serve (GPS trails, charge sessions, efficiency). The hourly maintenance pass DROPs
each partition whose whole month is older than settings.raw_retention_months:

- Only once archived: a partition goes only when its end is at or below the rollup's
  high-water mark, so its health data is in the rollup first, and only while the rollup's
  schema version is current, so a column added later is backfilled before raw goes.
- Lock-bounded like partition creation (SRV-24): a DROP needs ACCESS EXCLUSIVE on samples,
  so it gives up after PARTITION_LOCK_TIMEOUT behind a long reader (the nightly pg_dump)
  and the next pass retries.
- Counted: the rows each dropped partition held go into samples_retention_log in the same
  transaction as the DROP. The NAS backup's content floor compares count(samples) +
  sum(row_count), which only grows, so a deliberate drop never reads as a lost database.
"""
import logging
import re
import time
from datetime import datetime, timezone

import asyncpg

from app.db.partitions import PARTITION_LOCK_TIMEOUT, discard_ensured_month
from app.db.pool import MAINTENANCE_TIMEOUT_S
from app.db.rollup import get_high_water_ms, rollup_schema_current

logger = logging.getLogger(__name__)

_PART_RE = re.compile(r"^samples_(\d{4})_(\d{2})$")


def _month_start_ms(year: int, month: int) -> int:
    return int(datetime(year, month, 1, tzinfo=timezone.utc).timestamp() * 1000)


def cutoff_ms(now_ms: int, keep_months: int) -> int:
    """Start of the month keep_months before now_ms's month (UTC). A partition whose month
    ends at or before this is past the window."""
    d = datetime.fromtimestamp(now_ms / 1000, tz=timezone.utc)
    idx = d.year * 12 + (d.month - 1) - keep_months
    return _month_start_ms(idx // 12, idx % 12 + 1)


def _month_end_ms(year: int, month: int) -> int:
    return _month_start_ms(year + 1, 1) if month == 12 else _month_start_ms(year, month + 1)


async def expired_partitions(conn: asyncpg.Connection, keep_months: int,
                             now_ms: int) -> list[tuple[str, int, int]]:
    """(name, year, month) of every samples partition past the window AND archived, oldest
    first. Empty when keep_months <= 0."""
    if keep_months <= 0 or not await rollup_schema_current(conn):
        return []
    cut = cutoff_ms(now_ms, keep_months)
    hw = await get_high_water_ms(conn)
    rows = await conn.fetch(
        """SELECT c.relname FROM pg_inherits i
             JOIN pg_class c ON c.oid = i.inhrelid
            WHERE i.inhparent = 'samples'::regclass""")
    out: list[tuple[str, int, int]] = []
    for r in rows:
        m = _PART_RE.match(r["relname"])
        if not m:
            continue
        y, mo = int(m.group(1)), int(m.group(2))
        end = _month_end_ms(y, mo)
        if end <= cut and end <= hw:
            out.append((r["relname"], y, mo))
    return sorted(out, key=lambda t: (t[1], t[2]))


async def drop_expired_partitions(conn: asyncpg.Connection, keep_months: int,
                                  now_ms: int | None = None) -> list[str]:
    """DROP each expired, archived partition in its own short transaction, logging its row
    count. A partition that gives up on a lock wait is skipped (the next pass retries).
    Returns the names dropped."""
    if conn.is_in_transaction():
        raise RuntimeError("drop_expired_partitions must run outside a transaction")
    now_ms = int(time.time() * 1000) if now_ms is None else now_ms
    dropped: list[str] = []
    for name, y, mo in await expired_partitions(conn, keep_months, now_ms):
        try:
            async with conn.transaction():
                await conn.execute(f"SET LOCAL lock_timeout = '{PARTITION_LOCK_TIMEOUT}'")
                # SHARE on the partition only: readers carry on, but no late insert can land
                # between the count and the DROP, so the logged count is exact.
                await conn.execute(f'LOCK TABLE "{name}" IN SHARE MODE')
                n = await conn.fetchval(f'SELECT count(*) FROM "{name}"',
                                        timeout=MAINTENANCE_TIMEOUT_S)
                await conn.execute(f'DROP TABLE "{name}"')
                await conn.execute(
                    "INSERT INTO samples_retention_log (partition_name, row_count) VALUES ($1, $2)",
                    name, n)
        except asyncpg.exceptions.LockNotAvailableError:
            logger.warning("raw retention: %s gave up on a lock wait; the next pass retries", name)
            continue
        discard_ensured_month(y, mo)
        logger.info("raw retention: dropped %s (%d rows)", name, n)
        dropped.append(name)
    return dropped
