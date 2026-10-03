"""C1: every response the app generates carries X-Bmsmon-Api: 1, so the phone can tell an
app-level rejection from Traefik's own 404/502 while bmsmon-api is down (DATA-14)."""
import pytest
from httpx import ASGITransport, AsyncClient
from starlette.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from app.main import create_app
from app.middleware import API_MARKER_HEADER, API_MARKER_VALUE
from app.ratelimit import RateLimiter

# /ws checks Origin from Task 5 on; sending the prod origin keeps this test valid either way.
ORIGIN = {"Origin": "https://bmsmon.covert.life"}


def _marked(r) -> bool:
    return r.headers.get(API_MARKER_HEADER) == API_MARKER_VALUE


def test_marker_contract_name():
    # Cross-plan contract C1: the Android uploader matches this exact header.
    assert (API_MARKER_HEADER, API_MARKER_VALUE) == ("X-Bmsmon-Api", "1")


async def test_2xx_is_marked(client):
    r = await client.get("/api/v1/health")
    assert r.status_code == 200 and _marked(r)


async def test_401_is_marked(client):
    r = await client.get("/web/fleet")
    assert r.status_code == 401 and _marked(r)
    r = await client.post("/api/v1/ingest", content=b"{}")
    assert r.status_code == 401 and _marked(r)


async def test_404_from_an_unknown_route_is_marked(client):
    r = await client.post("/api/v1/no-such-endpoint", content=b"{}")
    assert r.status_code == 404 and _marked(r)


async def test_share_404_is_marked(client):
    r = await client.get("/share/tok-nope/feed")
    assert r.status_code == 404 and _marked(r)


async def test_422_validation_error_is_marked(client):
    r = await client.post("/api/v1/enroll", json={})
    assert r.status_code == 422 and _marked(r)


async def test_413_is_marked(client, set_setting):
    set_setting("max_body_bytes", 64)
    r = await client.post("/api/v1/ingest", content=b"x" * 65,
                          headers={"Authorization": "Bearer x"})
    assert r.status_code == 413 and _marked(r)


async def test_429_is_marked(app, client):
    app.state.enroll_limiter = RateLimiter(max_attempts=1, window_s=60)
    body = {"code": "NOPE", "install_uuid": "inst-marker", "public_key_spki_b64": "AAAA"}
    assert (await client.post("/api/v1/enroll", json=body)).status_code == 400
    r = await client.post("/api/v1/enroll", json=body)
    assert r.status_code == 429 and _marked(r)


async def test_redirect_is_marked(client):
    r = await client.get("/v2/")
    assert r.status_code == 307 and _marked(r)


async def test_unhandled_500_is_marked(app):
    async def boom():
        raise RuntimeError("boom")

    app.add_api_route("/api/v1/__test_boom", boom)
    transport = ASGITransport(app=app, raise_app_exceptions=False)
    async with AsyncClient(transport=transport, base_url="http://t") as c:
        r = await c.get("/api/v1/__test_boom")
    assert r.status_code == 500 and _marked(r)


def test_static_files_are_marked(tmp_path, monkeypatch):
    (tmp_path / "index.html").write_text("<html>v2</html>")
    monkeypatch.setenv("BMSMON_WEB_DIST", str(tmp_path))
    with TestClient(create_app()) as tc:
        r = tc.get("/")
        assert r.status_code == 200 and _marked(r)
        r = tc.get("/assets/missing.js")
        assert r.status_code == 404 and _marked(r)


def test_websocket_accept_is_marked():
    # Accept-then-close (no identity -> 4401): the 101 handshake response carries the marker.
    with TestClient(create_app()) as tc:
        with tc.websocket_connect("/ws", headers=ORIGIN) as ws:
            assert (b"x-bmsmon-api", b"1") in (ws.extra_headers or [])
            with pytest.raises(WebSocketDisconnect) as exc:
                ws.receive_json()
            assert exc.value.code == 4401
