import asyncio
import logging
from pathlib import Path

import asyncpg
from fastapi import Request

from app.config import settings

logger = logging.getLogger(__name__)

_SCHEMA = (Path(__file__).parent / "schema.sql").read_text()

# Backoff between attempts: ~5 min in total (plus a 0.5 s lock wait per attempt), inside
# the prod compose healthcheck's start_period (infra repo), so autoheal never kills a
# startup that is still waiting. After the last attempt the startup fails and the
# container restarts.
SCHEMA_RETRY_DELAYS_S: tuple[float, ...] = (1, 2, 4, 8, 15, 30, 30, 30, 30, 30, 30, 30, 30, 30)
# Shorter than Postgres' default deadlock_timeout (1 s): when a boot overlaps the nightly
# dump's lock-acquisition phase, our lock wait gives up first, so the boot backs off and
# retries instead of winning deadlock resolution against the backup.
SCHEMA_LOCK_TIMEOUT = "500ms"

# SRV-24: no request query may hold a pool connection indefinitely. A statement still
# running after COMMAND_TIMEOUT_S is cancelled and surfaces as asyncio.TimeoutError, which
# the API answers as a marked 503 (transient: the phone retries). Background maintenance
# (schema, rollup, GPS scrub, index builds) passes timeout=MAINTENANCE_TIMEOUT_S
# explicitly: an online index build can legitimately wait out a nightly pg_dump.
# Partition pre-creation needs no opt-out: its 0.5 s lock_timeout bounds its only wait,
# and creating an empty partition is otherwise instant.
COMMAND_TIMEOUT_S = 30.0
MAINTENANCE_TIMEOUT_S = 3600.0

# Lock contention a later attempt can get past, with the WARNING each one logs.
_RETRYABLE: dict[type[Exception], str] = {
    asyncpg.exceptions.LockNotAvailableError: "lock wait timed out",
    asyncpg.exceptions.DeadlockDetectedError: "deadlock detected",
}


async def apply_schema(conn, *, sleep=asyncio.sleep,
                       delays: tuple[float, ...] = SCHEMA_RETRY_DELAYS_S) -> None:
    """Run schema.sql under a short lock_timeout, retrying on lock contention.

    On an up-to-date database schema.sql takes no table lock: its column changes and
    samples index declarations read the catalog first (schema.sql's header). A boot that
    does change something still locks that table: ACCESS EXCLUSIVE for a new column or the
    legacy primary-key migration (it waits behind the nightly pg_dump's AccessShareLock),
    SHARE for a new index declaration (it waits behind ingest's RowExclusiveLock). Waiting
    unboundedly would queue every other query on the table behind the pending DDL.
    SCHEMA_LOCK_TIMEOUT gives the queue slot back after 0.5 s; we retry (also after a
    deadlock) until the holder lets go, and re-raise after the last attempt so a genuinely
    stuck database still fails the startup loudly. SET LOCAL ends with the transaction, so
    the connection keeps its own timeout. The script runs under MAINTENANCE_TIMEOUT_S, not
    the pool's request bound: a real migration can legitimately take minutes once it holds
    its locks."""
    for attempt, delay in enumerate((*delays, None), start=1):
        try:
            async with conn.transaction():
                await conn.execute(f"SET LOCAL lock_timeout = '{SCHEMA_LOCK_TIMEOUT}'")
                await conn.execute(_SCHEMA, timeout=MAINTENANCE_TIMEOUT_S)
            return
        except tuple(_RETRYABLE) as e:
            if delay is None:
                raise
            reason = next(r for t, r in _RETRYABLE.items() if isinstance(e, t))
            logger.warning("schema: %s (attempt %d); retrying in %ss", reason, attempt, delay)
            await sleep(delay)


async def create_pool() -> asyncpg.Pool:
    # min_size=3: keep a few warm connections so the first burst after an idle period
    # (phone batch + WS snapshot + share poll landing together) doesn't pay TLS/auth
    # connection setup on the hot path. max_size unchanged.
    pool = await asyncpg.create_pool(settings.database_url, min_size=3, max_size=10,
                                     command_timeout=COMMAND_TIMEOUT_S)
    try:
        async with pool.acquire() as conn:
            await apply_schema(conn)
    except Exception:
        await pool.close()  # the startup is failing: don't leave its connections open
        raise
    return pool


def get_pool(request: Request) -> asyncpg.Pool:
    return request.app.state.pool
