"""SEC-19: /ws checks Origin BEFORE accept(). WebSockets bypass CORS, and the
household's Authentik cookie rides along from any same-site *.covert.life page."""
import pytest
from starlette.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from app.main import create_app
from app.routers.ws import WS_FORBIDDEN, origin_allowed
from tests.identities import VIEWER_H

PROD = "https://bmsmon.covert.life"


def _refused_code(tc, headers) -> int:
    """Close code of a handshake the server refused BEFORE accepting it."""
    with pytest.raises(WebSocketDisconnect) as exc:
        with tc.websocket_connect("/ws", headers=headers):
            pass
    return exc.value.code


def test_prod_origin_gets_the_snapshot():
    with TestClient(create_app()) as tc:
        with tc.websocket_connect("/ws", headers={**VIEWER_H, "Origin": PROD}) as ws:
            assert ws.receive_json()["type"] == "snapshot"


def test_cross_site_origins_are_refused_before_accept():
    with TestClient(create_app()) as tc:
        for origin in ("https://evil.covert.life", "https://bmsmon.covert.life.evil.example",
                       "http://bmsmon.covert.life", "null", ""):
            assert _refused_code(tc, {**VIEWER_H, "Origin": origin}) == WS_FORBIDDEN, origin


def test_missing_origin_is_refused_outside_dev():
    with TestClient(create_app()) as tc:
        assert _refused_code(tc, VIEWER_H) == WS_FORBIDDEN


def test_origin_match_ignores_case_and_a_trailing_slash_but_not_the_port():
    assert origin_allowed("HTTPS://BMSMON.COVERT.LIFE/")
    assert not origin_allowed("https://bmsmon.covert.life:8443")


def test_allowed_origins_come_from_settings(set_setting):
    set_setting("ws_allowed_origins", ["https://a.example", "https://b.example"])
    assert origin_allowed("https://b.example")
    assert not origin_allowed(PROD)


def test_an_empty_allow_list_refuses_everything(set_setting):
    set_setting("ws_allowed_origins", [])
    assert not origin_allowed(PROD)


def test_dev_trust_allows_the_vite_dev_server_and_no_origin(set_setting):
    # Review Focus 5: web/vite.config.ts proxies /ws without rewriting Origin, and the
    # smoke test drives that proxy with dev-trust and no identity headers.
    set_setting("dev_trust_headers", True)  # the test DB is localhost, so dev-trust is active
    with TestClient(create_app()) as tc:
        for headers in ({"Origin": "http://localhost:5173"},
                        {"Origin": "http://127.0.0.1:5173"}, {}):
            with tc.websocket_connect("/ws", headers=headers) as ws:
                assert ws.receive_json()["type"] == "snapshot", headers
        assert _refused_code(tc, {"Origin": "https://evil.covert.life"}) == WS_FORBIDDEN


def test_dev_origins_refused_when_dev_trust_is_refused(set_setting):
    set_setting("dev_trust_headers", True)
    set_setting("database_url", "postgresql://bmsmon:pw@db-prod.internal.example:5432/bmsmon")
    assert not origin_allowed("http://localhost:5173")
    assert not origin_allowed(None)


def test_dev_trust_allows_a_same_origin_page(set_setting):
    # `vite build` output served by the local API itself (e.g. http://localhost:8000):
    # the page's Origin is the API's own Host. The test client's Host is "testserver".
    set_setting("dev_trust_headers", True)
    with TestClient(create_app()) as tc:
        with tc.websocket_connect("/ws", headers={"Origin": "http://testserver"}) as ws:
            assert ws.receive_json()["type"] == "snapshot"
        assert _refused_code(tc, {"Origin": "http://testserver.evil.example"}) == WS_FORBIDDEN
    assert origin_allowed("http://LOCALHOST:8000", host="localhost:8000")
    assert not origin_allowed("http://localhost:8001", host="localhost:8000")
    assert not origin_allowed("null", host="")
    assert not origin_allowed("http://[::1", host="[::1]")  # unparseable: refused, no 500


def test_same_origin_is_not_enough_outside_dev():
    with TestClient(create_app()) as tc:
        assert _refused_code(tc, {**VIEWER_H, "Origin": "http://testserver"}) == WS_FORBIDDEN
    assert not origin_allowed("http://localhost:8000", host="localhost:8000")
