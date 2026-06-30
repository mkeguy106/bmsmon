from pathlib import Path

import asyncpg
from fastapi import Request

from app.config import settings

_SCHEMA = (Path(__file__).parent / "schema.sql").read_text()


async def create_pool() -> asyncpg.Pool:
    pool = await asyncpg.create_pool(settings.database_url, min_size=1, max_size=10)
    async with pool.acquire() as conn:
        await conn.execute(_SCHEMA)
    return pool


def get_pool(request: Request) -> asyncpg.Pool:
    return request.app.state.pool
