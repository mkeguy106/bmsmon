"""A DB outage is a marked 503 answered INSIDE the app: the exception never reaches uvicorn,
which used to print a full traceback for every single 503 (dozens a minute during a DB
restart, against a 5 MB docker log cap). One WARNING line per exception class per 10 s
instead. Share-zone errors keep the zone's no-store/no-referrer headers."""
import logging
import socket

import asyncpg
import pytest
from httpx import ASGITransport, AsyncClient
from starlette.testclient import TestClient
from starlette.websockets import WebSocket, WebSocketDisconnect

from app.main import create_app
from app.middleware import API_MARKER_HEADER, API_MARKER_VALUE, WS_INTERNAL_ERROR
from app.ratelimit import RateLimiter


def _route(app, path: str, exc: BaseException) -> None:
    async def boom():
        raise exc

    app.add_api_route(path, boom)


async def test_db_outage_503_is_answered_without_reraising(app):
    # raise_app_exceptions=True (the default): had the exception escaped the app, the
    # transport would raise it right here, which is exactly what made uvicorn log it.
    _route(app, "/api/v1/__t_down", asyncpg.exceptions.CannotConnectNowError("starting up"))
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://t") as c:
        r = await c.get("/api/v1/__t_down")
    assert r.status_code == 503
    assert r.json() == {"detail": "database unavailable"}
    assert r.headers["Retry-After"] == "30"
    assert r.headers[API_MARKER_HEADER] == API_MARKER_VALUE


async def test_outage_warning_is_one_line_per_class_per_interval(app, caplog):
    caplog.set_level(logging.WARNING, logger="app.middleware")
    _route(app, "/api/v1/__t_down2", asyncpg.exceptions.CannotConnectNowError("x"))
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://t") as c:
        for _ in range(5):
            assert (await c.get("/api/v1/__t_down2")).status_code == 503
    lines = [r for r in caplog.records if r.name == "app.middleware"]
    assert len(lines) == 1
    assert "CannotConnectNowError" in lines[0].getMessage()
    assert lines[0].exc_info is None  # one line, no traceback


async def test_non_outage_interface_error_is_a_marked_500_logged_once(app, caplog):
    caplog.set_level(logging.ERROR, logger="app.middleware")
    _route(app, "/api/v1/__t_bug", asyncpg.exceptions.InterfaceError(
        "cannot use Connection.transaction() in a manually started transaction"))
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://t") as c:
        r = await c.get("/api/v1/__t_bug")
    assert r.status_code == 500
    assert r.headers[API_MARKER_HEADER] == API_MARKER_VALUE
    errors = [r for r in caplog.records
              if r.name == "app.middleware" and r.levelno == logging.ERROR]
    assert len(errors) == 1 and errors[0].exc_info is not None


@pytest.mark.parametrize("exc,status", [
    (asyncpg.exceptions.CannotConnectNowError("x"), 503),
    (RuntimeError("boom"), 500),
])
async def test_share_zone_errors_are_no_store_no_referrer(app, exc, status):
    _route(app, "/share/__t/boom", exc)
    transport = ASGITransport(app=app, raise_app_exceptions=False)
    async with AsyncClient(transport=transport, base_url="http://t") as c:
        r = await c.get("/share/__t/boom")
    assert r.status_code == status
    assert r.headers["cache-control"] == "no-store"
    assert r.headers["referrer-policy"] == "no-referrer"
    assert r.headers[API_MARKER_HEADER] == API_MARKER_VALUE


async def test_share_429_keeps_the_zone_headers(app, client):
    app.state.share_limiter = RateLimiter(max_attempts=1, window_s=60)
    await client.get("/share/tok-x/feed")
    r = await client.get("/share/tok-x/feed")
    assert r.status_code == 429
    assert r.headers["cache-control"] == "no-store"
    assert r.headers["referrer-policy"] == "no-referrer"


def test_websocket_db_outage_closes_1011_without_reraising():
    app = create_app()

    async def ws_down(sock: WebSocket):
        await sock.accept()
        raise asyncpg.exceptions.CannotConnectNowError("x")

    app.add_api_websocket_route("/__t_ws", ws_down)
    with (TestClient(app) as tc, tc.websocket_connect("/__t_ws") as ws,
          pytest.raises(WebSocketDisconnect) as closed):
        ws.receive_text()
    assert closed.value.code == WS_INTERNAL_ERROR == 1011


@pytest.mark.parametrize("exc", [
    ConnectionRefusedError(111, "refused"),
    socket.gaierror(-2, "unknown host"),
    TimeoutError(),
])
async def test_network_errors_are_a_503_with_retry_after(app, exc):
    _route(app, "/api/v1/__t_net", exc)
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://t") as c:
        r = await c.get("/api/v1/__t_net")
    assert r.status_code == 503
    assert r.headers["Retry-After"] == "30"
    assert r.headers[API_MARKER_HEADER] == API_MARKER_VALUE


async def test_non_network_oserror_is_a_logged_marked_500(app, caplog):
    caplog.set_level(logging.ERROR, logger="app.middleware")
    _route(app, "/api/v1/__t_fnf", FileNotFoundError(2, "no such file"))
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://t") as c:
        r = await c.get("/api/v1/__t_fnf")
    assert r.status_code == 500
    assert "Retry-After" not in r.headers
    assert r.headers[API_MARKER_HEADER] == API_MARKER_VALUE
    errors = [x for x in caplog.records if x.name == "app.middleware" and x.levelno == logging.ERROR]
    assert len(errors) == 1 and errors[0].exc_info is not None


async def test_plain_crash_still_propagates_to_uvicorn_for_its_traceback(app):
    # The catch-all answers a marked 500 and re-raises: uvicorn logs the traceback.
    _route(app, "/api/v1/__t_rt", RuntimeError("boom"))
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://t") as c:
        with pytest.raises(RuntimeError, match="boom"):
            await c.get("/api/v1/__t_rt")
