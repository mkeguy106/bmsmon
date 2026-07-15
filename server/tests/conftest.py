import pytest
import pytest_asyncio
from httpx import ASGITransport, AsyncClient

from app.config import settings
from app.main import create_app


@pytest.fixture
def set_setting():
    """Temporarily override a field on the frozen Settings singleton (restored after the test)."""
    saved: dict[str, object] = {}

    def _set(name: str, value):
        if name not in saved:
            saved[name] = getattr(settings, name)
        object.__setattr__(settings, name, value)

    yield _set
    for name, value in saved.items():
        object.__setattr__(settings, name, value)


@pytest_asyncio.fixture
async def app():
    application = create_app()
    async with application.router.lifespan_context(application):
        # clean slate each test
        pool = application.state.pool
        async with pool.acquire() as conn:
            await conn.execute(
                "TRUNCATE samples, samples_rollup, samples_rollup_state, batteries, "
                "enrollment_codes, devices, device_temp_config, "
                "device_alert_config, device_range_config, web_notes, location_shares "
                "RESTART IDENTITY CASCADE"
            )
        yield application


@pytest_asyncio.fixture
async def client(app):
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://t") as c:
        yield c
