"""Background maintenance that must never run on a request path (SRV-24): an hourly pass
that pre-creates partitions a month ahead (later tasks add the pack-registry backfill and
online index builds). Every step is isolated: one that fails is logged and the others
still run; the next pass retries."""
import asyncio
import logging
import time

import asyncpg

from app.db.partitions import PRECREATE_AHEAD_MS, precreate_partitions

logger = logging.getLogger(__name__)

MAINTENANCE_INITIAL_DELAY_S = 60
MAINTENANCE_INTERVAL_S = 3600


async def _step(name: str, coro):
    try:
        return await coro
    except asyncpg.exceptions.LockNotAvailableError:
        logger.warning("maintenance: %s gave up on a lock wait; the next pass retries", name)
    except Exception:
        logger.exception("maintenance: %s failed; the next pass retries", name)
    return None


async def run_maintenance(pool, now_ms: int | None = None) -> dict[str, object]:
    """One pass. Returns what each step did (None = it failed). Directly callable for tests."""
    now_ms = int(time.time() * 1000) if now_ms is None else now_ms
    report: dict[str, object] = {}
    async with pool.acquire() as conn:
        report["partitions"] = await _step(
            "partition pre-create",
            precreate_partitions(conn, now_ms, now_ms + PRECREATE_AHEAD_MS))
    if report["partitions"]:
        logger.info("maintenance: created partition(s) %s", ", ".join(report["partitions"]))
    return report


async def maintenance_loop(pool) -> None:
    await asyncio.sleep(MAINTENANCE_INITIAL_DELAY_S)
    while True:
        try:
            await run_maintenance(pool)
        except Exception:
            # pool.acquire itself failed (DB down): the next pass retries.
            logger.exception("maintenance pass failed; retrying in %d s", MAINTENANCE_INTERVAL_S)
        await asyncio.sleep(MAINTENANCE_INTERVAL_S)
