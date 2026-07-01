import asyncio

import asyncpg
import pytest
import pytest_asyncio
from httpx import ASGITransport, AsyncClient

from app.config import settings
from app.main import create_app


@pytest_asyncio.fixture
async def app():
    application = create_app()
    async with application.router.lifespan_context(application):
        # clean slate each test
        pool = application.state.pool
        async with pool.acquire() as conn:
            await conn.execute(
                "TRUNCATE samples, batteries, enrollment_codes, devices, device_temp_config "
                "RESTART IDENTITY CASCADE"
            )
        yield application


@pytest_asyncio.fixture
async def client(app):
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://t") as c:
        yield c
