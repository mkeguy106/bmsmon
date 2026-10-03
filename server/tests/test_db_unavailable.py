"""A DB outage is a marked 503 (transient, retry); a real crash stays a marked 500."""
import asyncio

import asyncpg
import pytest
from httpx import ASGITransport, AsyncClient

from app.middleware import API_MARKER_HEADER, API_MARKER_VALUE, is_db_unavailable


@pytest.mark.parametrize("exc", [
    asyncpg.exceptions.CannotConnectNowError("starting up"),
    asyncpg.exceptions.ConnectionDoesNotExistError("gone"),
    asyncpg.exceptions.TooManyConnectionsError("full"),
    asyncpg.exceptions.PostgresConnectionError("x"),
    asyncpg.exceptions.InterfaceError("pool is closed"),
    asyncpg.exceptions.InterfaceError("connection is closed"),
    ConnectionRefusedError(111, "refused"),
    OSError("unreachable"),
    asyncio.TimeoutError(),
])
def test_classifier_true(exc):
    assert is_db_unavailable(exc)


@pytest.mark.parametrize("exc", [
    ValueError("x"),
    RuntimeError("boom"),
    asyncpg.exceptions.InterfaceError("cannot use Connection.transaction() in a manually started transaction"),
    asyncpg.exceptions.UniqueViolationError("dup"),
])
def test_classifier_false(exc):
    assert not is_db_unavailable(exc)


async def _get(app, exc):
    async def boom():
        raise exc

    app.add_api_route("/api/v1/__test_dbdown", boom)
    transport = ASGITransport(app=app, raise_app_exceptions=False)
    async with AsyncClient(transport=transport, base_url="http://t") as c:
        return await c.get("/api/v1/__test_dbdown")


async def test_db_down_is_marked_503(app):
    r = await _get(app, asyncpg.exceptions.CannotConnectNowError("starting up"))
    assert r.status_code == 503
    assert r.json() == {"detail": "database unavailable"}
    assert r.headers["Retry-After"] == "30"
    assert r.headers[API_MARKER_HEADER] == API_MARKER_VALUE


async def test_crash_is_marked_500(app):
    r = await _get(app, RuntimeError("boom"))
    assert r.status_code == 500
    assert "Retry-After" not in r.headers
    assert r.headers[API_MARKER_HEADER] == API_MARKER_VALUE
