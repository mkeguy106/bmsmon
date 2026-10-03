import asyncio
import logging
from pathlib import Path

import asyncpg
from fastapi import Request

from app.config import settings

logger = logging.getLogger(__name__)

_SCHEMA = (Path(__file__).parent / "schema.sql").read_text()

# Backoff between lock-timeout attempts: ~5 min in total, inside the API container's
# healthcheck start_period (infra plan) so autoheal never kills a waiting startup.
SCHEMA_RETRY_DELAYS_S: tuple[float, ...] = (1, 2, 4, 8, 15, 30, 30, 30, 30, 30, 30, 30, 30, 30)


async def apply_schema(conn, *, sleep=asyncio.sleep,
                       delays: tuple[float, ...] = SCHEMA_RETRY_DELAYS_S) -> None:
    """Run schema.sql under a 1 s lock_timeout, retrying on lock contention.

    Every `ALTER TABLE samples ADD COLUMN IF NOT EXISTS` takes AccessExclusiveLock even as a
    no-op. Waiting for it unboundedly (e.g. behind the nightly pg_dump's AccessShareLock) would
    queue every other query on samples behind the pending ALTER. A short lock_timeout gives the
    queue slot back after 1 s; we retry until the holder lets go, and re-raise after the last
    attempt so a genuinely stuck database still fails the startup loudly."""
    for attempt, delay in enumerate((*delays, None), start=1):
        try:
            async with conn.transaction():
                await conn.execute("SET LOCAL lock_timeout = '1s'")
                await conn.execute(_SCHEMA)
            return
        except asyncpg.exceptions.LockNotAvailableError:
            if delay is None:
                raise
            logger.warning("schema: samples is locked (attempt %d); retrying in %ss", attempt, delay)
            await sleep(delay)


async def create_pool() -> asyncpg.Pool:
    # min_size=3: keep a few warm connections so the first burst after an idle period
    # (phone batch + WS snapshot + share poll landing together) doesn't pay TLS/auth
    # connection setup on the hot path. max_size unchanged.
    pool = await asyncpg.create_pool(settings.database_url, min_size=3, max_size=10)
    async with pool.acquire() as conn:
        await apply_schema(conn)
    return pool


def get_pool(request: Request) -> asyncpg.Pool:
    return request.app.state.pool
