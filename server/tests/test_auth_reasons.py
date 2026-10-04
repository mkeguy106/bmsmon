"""DATA-20 server side + SRV-17: a device-route 401 names its cause in X-Bmsmon-Auth-Reason
(the body keeps its old detail text), a known device's failure is counted and logged with
the clock skew and the app build, and every /api/ response carries the server's clock."""
import json
import logging
import time
import uuid

import asyncpg
import jwt
import pytest
from httpx import ASGITransport, AsyncClient

from app.routers.api_device import AUTH_REASON_HEADER as REASON
from tests.test_ingest_jwt import _bh, _enroll_device, _keypair, _payload, _token

LOG = "app.routers.api_device"
TIME = "X-Bmsmon-Server-Time-Ms"


def _token_at(priv, device_id: str, body: bytes, iat: int, exp: int) -> str:
    claims = {"sub": device_id, "iat": iat, "exp": exp, "jti": uuid.uuid4().hex, "bh": _bh(body)}
    return jwt.encode(claims, priv, algorithm="ES256")


def _auth(tok: str, ua: str = "bmsmon-android/test") -> dict:
    return {"Authorization": f"Bearer {tok}", "User-Agent": ua}


async def test_missing_bearer(client):
    r = await client.post("/api/v1/ingest", content=b"{}")
    assert r.status_code == 401 and r.headers[REASON] == "missing_bearer"
    assert r.json() == {"detail": "missing bearer"}


async def test_unparseable_token(client):
    priv, _ = _keypair()
    r = await client.post("/api/v1/config", content=b"{}",
                          headers=_auth(_token(priv, "not-a-uuid", b"{}")))
    assert r.status_code == 401 and r.headers[REASON] == "bad_token"


async def test_unknown_device_is_named_but_not_counted(app, client):
    priv, _ = _keypair()
    r = await client.post("/api/v1/ingest", content=b"{}",
                          headers=_auth(_token(priv, str(uuid.uuid4()), b"{}")))
    assert r.status_code == 401 and r.headers[REASON] == "unknown_or_revoked_device"
    assert app.state.auth_stats.failures_in_window() == 0


async def test_unknown_devices_are_logged_once_a_minute_in_all(app, client, caplog):
    caplog.set_level(logging.WARNING, logger=LOG)
    priv, _ = _keypair()
    for _ in range(2):  # a different random id each time: still one line
        r = await client.post("/api/v1/ingest", content=b"{}",
                              headers=_auth(_token(priv, str(uuid.uuid4()), b"{}")))
        assert r.status_code == 401
    lines = [rec for rec in caplog.records
             if rec.name == LOG and "unknown device" in rec.getMessage()]
    assert len(lines) == 1


async def test_no_token_reaches_the_log(app, client, caplog):
    """Every logged refusal of a known device: wrong key, clock skew, replay, body
    mismatch. Neither a token nor its signature segment may appear in any log line."""
    caplog.set_level(logging.DEBUG)
    priv, spki = _keypair()
    other, _ = _keypair()
    device_id = await _enroll_device(app, spki)
    body = json.dumps(_payload()).encode()
    now = int(time.time())
    wrong_key = _token(other, device_id, body)
    skewed = _token_at(priv, device_id, body, iat=now - 3600, exp=now - 3540)
    replayed = _token(priv, device_id, body)
    mismatched = _token(priv, device_id, body)
    for tok, content in ((wrong_key, body), (skewed, body), (replayed, body), (replayed, body),
                         (mismatched, body + b" ")):
        await client.post("/api/v1/ingest", content=content, headers=_auth(tok))
    reasons = {rec.getMessage().split(": ", 1)[1].split(" ", 1)[0]
               for rec in caplog.records if rec.name == LOG and "auth failed for" in rec.getMessage()}
    assert reasons == {"bad_signature", "clock_skew", "replay", "body_mismatch"}
    for tok in (wrong_key, skewed, replayed, mismatched):
        assert tok not in caplog.text and tok.rsplit(".", 1)[1] not in caplog.text


async def test_revoked_device_is_counted_and_logged(app, client, caplog):
    caplog.set_level(logging.WARNING, logger=LOG)
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    async with app.state.pool.acquire() as conn:
        await conn.execute("UPDATE devices SET revoked = true WHERE id = $1", device_id)
    body = json.dumps(_payload()).encode()
    r = await client.post("/api/v1/ingest", content=body, headers=_auth(_token(priv, device_id, body)))
    assert r.status_code == 401 and r.headers[REASON] == "unknown_or_revoked_device"
    assert r.json() == {"detail": "unknown or revoked device"}
    assert app.state.auth_stats.failures_in_window() == 1
    assert device_id in caplog.text and "bmsmon-android/test" in caplog.text


async def test_unknown_and_revoked_look_identical_to_the_caller(app, client):
    """A prober must not learn whether a device id was ever enrolled."""
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    async with app.state.pool.acquire() as conn:
        await conn.execute("UPDATE devices SET revoked = true WHERE id = $1", device_id)
    body = json.dumps(_payload()).encode()
    revoked = await client.post("/api/v1/ingest", content=body,
                                headers=_auth(_token(priv, device_id, body)))
    unknown = await client.post("/api/v1/ingest", content=body,
                                headers=_auth(_token(priv, str(uuid.uuid4()), body)))
    assert revoked.status_code == unknown.status_code == 401
    assert revoked.content == unknown.content
    assert revoked.headers[REASON] == unknown.headers[REASON]


async def test_wrong_key_is_bad_signature_and_logged_once_per_interval(app, client, caplog):
    caplog.set_level(logging.WARNING, logger=LOG)
    _, spki = _keypair()
    other, _ = _keypair()
    device_id = await _enroll_device(app, spki)
    body = json.dumps(_payload()).encode()
    for _ in range(3):
        r = await client.post("/api/v1/ingest", content=body,
                              headers=_auth(_token(other, device_id, body)))
        assert r.status_code == 401 and r.headers[REASON] == "bad_signature"
        assert r.json() == {"detail": "bad signature"}
    lines = [rec for rec in caplog.records if rec.name == LOG and "bad_signature" in rec.getMessage()]
    assert len(lines) == 1
    assert app.state.auth_stats.failures_in_window() == 3


@pytest.mark.parametrize("iat_offset", [-3600, 3600])
async def test_clock_skew_is_named_and_measured(app, client, caplog, iat_offset):
    """iat 1 h in the past = the server's clock is 1 h AHEAD of the phone (skew +3600);
    iat 1 h in the future = the server is 1 h BEHIND it (skew -3600, the 2026-09-16 shape)."""
    caplog.set_level(logging.WARNING, logger=LOG)
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    body = json.dumps(_payload()).encode()
    now = int(time.time())
    tok = _token_at(priv, device_id, body, iat=now + iat_offset, exp=now + iat_offset + 60)
    r = await client.post("/api/v1/ingest", content=body, headers=_auth(tok))
    assert r.status_code == 401 and r.headers[REASON] == "clock_skew"
    fail = app.state.auth_stats.last_failure
    assert fail["reason"] == "clock_skew"
    assert abs(fail["skew_s"] + iat_offset) <= 2
    assert "clock_skew" in caplog.text and "skew " in caplog.text


async def test_replay_and_tampered_body_are_named(app, client):
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    body = json.dumps(_payload()).encode()
    tok = _token(priv, device_id, body)
    assert (await client.post("/api/v1/ingest", content=body, headers=_auth(tok))).status_code == 200
    r = await client.post("/api/v1/ingest", content=body, headers=_auth(tok))
    assert r.status_code == 401 and r.headers[REASON] == "replay"
    tok2 = _token(priv, device_id, body)
    r = await client.post("/api/v1/ingest", content=body + b" ", headers=_auth(tok2))
    assert r.status_code == 401 and r.headers[REASON] == "body_mismatch"


async def test_success_records_the_skew_and_carries_no_reason(app, client):
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    body = json.dumps(_payload()).encode()
    r = await client.post("/api/v1/ingest", content=body, headers=_auth(_token(priv, device_id, body)))
    assert r.status_code == 200 and REASON not in r.headers
    assert abs(app.state.auth_stats.last_ok_skew_s) <= 2


@pytest.mark.parametrize("iat_offset", [-100, 45])
async def test_skew_inside_the_leeway_is_still_accepted(app, client, iat_offset):
    """The new clock_skew mapping only renames refusals. A token PyJWT accepts under the
    leeway (phone 45 s ahead; or iat 100 s back, so exp lapsed 40 s ago) must stay a 200,
    and its skew is what gets recorded."""
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    body = json.dumps(_payload()).encode()
    now = int(time.time())
    tok = _token_at(priv, device_id, body, iat=now + iat_offset, exp=now + iat_offset + 60)
    r = await client.post("/api/v1/ingest", content=body, headers=_auth(tok))
    assert r.status_code == 200 and REASON not in r.headers
    assert abs(app.state.auth_stats.last_ok_skew_s + iat_offset) <= 2
    assert app.state.auth_stats.failures_in_window() == 0


async def test_config_success_carries_no_reason_and_records_the_skew(app, client):
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    body = json.dumps({"profile_id": "redodo-beken-bk-ble-1.0", "cold_caution_c": 5,
                       "hot_caution_c": 45, "cold_crit_c": -12, "hot_crit_c": 53,
                       "unit": "F", "updated_at_ms": 1000}).encode()
    r = await client.post("/api/v1/config", content=body, headers=_auth(_token(priv, device_id, body)))
    assert r.status_code == 200 and REASON not in r.headers
    assert abs(app.state.auth_stats.last_ok_skew_s) <= 2


async def test_every_api_response_carries_the_server_clock(client):
    for method, path in (("GET", "/api/v1/health"), ("POST", "/api/v1/ingest")):
        r = await client.request(method, path, content=b"{}" if method == "POST" else None)
        assert abs(int(r.headers[TIME]) - int(time.time() * 1000)) < 5_000, path
    assert TIME not in (await client.get("/web/fleet")).headers  # only /api/


@pytest.mark.parametrize("exc, status", [
    (asyncpg.exceptions.CannotConnectNowError("starting up"), 503),  # marked outage
    (RuntimeError("boom"), 500),  # crash: sent from outside ApiMarkerMiddleware
])
async def test_outage_and_crash_carry_the_server_clock_once(app, exc, status):
    async def boom():
        raise exc

    app.add_api_route("/api/v1/__test_clock", boom)
    transport = ASGITransport(app=app, raise_app_exceptions=False)
    async with AsyncClient(transport=transport, base_url="http://t") as c:
        r = await c.get("/api/v1/__test_clock")
    assert r.status_code == status
    stamps = r.headers.get_list(TIME)
    assert len(stamps) == 1 and abs(int(stamps[0]) - int(time.time() * 1000)) < 5_000


async def test_a_body_cap_413_carries_the_server_clock(client, set_setting):
    set_setting("max_body_bytes", 10)
    r = await client.post("/api/v1/ingest", content=b"x" * 100)
    assert r.status_code == 413 and len(r.headers.get_list(TIME)) == 1


async def test_an_invalid_envelope_is_logged_without_its_contents(app, client, caplog):
    caplog.set_level(logging.WARNING, logger=LOG)
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    body = b'{"batch_seq": "x", "samples": [{"lat": 43.123456}]}'
    r = await client.post("/api/v1/ingest", content=body, headers=_auth(_token(priv, device_id, body)))
    assert r.status_code == 422
    assert "invalid envelope" in caplog.text and "batch_seq" in caplog.text
    assert "43.123456" not in caplog.text


async def test_an_invalid_config_envelope_is_logged(app, client, caplog):
    caplog.set_level(logging.WARNING, logger=LOG)
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    body = b'{"profile_id": "p"}'
    r = await client.post("/api/v1/config", content=body, headers=_auth(_token(priv, device_id, body)))
    assert r.status_code == 422 and r.json() == {"detail": "invalid body"}
    assert "invalid envelope" in caplog.text and "/api/v1/config" in caplog.text
