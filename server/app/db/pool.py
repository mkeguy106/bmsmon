from pathlib import Path

import asyncpg
from fastapi import Request

from app.config import settings

_SCHEMA = (Path(__file__).parent / "schema.sql").read_text()


async def create_pool() -> asyncpg.Pool:
    # min_size=3: keep a few warm connections so the first burst after an idle period
    # (phone batch + WS snapshot + share poll landing together) doesn't pay TLS/auth
    # connection setup on the hot path. max_size unchanged.
    pool = await asyncpg.create_pool(settings.database_url, min_size=3, max_size=10)
    async with pool.acquire() as conn:
        await conn.execute(_SCHEMA)
    return pool


def get_pool(request: Request) -> asyncpg.Pool:
    return request.app.state.pool
