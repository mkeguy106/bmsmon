"""SEC-18: device requests are authenticated BEFORE their body is read or gunzipped;
the body is then bound to the token (bh) and only THEN is the jti burned, so a request
whose body fails can be retried with the same token. Plus the per-device budget."""
import gzip
import json
import logging
import time
import uuid

import jwt
import pytest

from app.auth.device_jwt import JtiCache, JwtError, verify_body, verify_token
from app.db import queries as q
from app.middleware import API_MARKER_HEADER, API_MARKER_VALUE
from app.ratelimit import INGEST_MAX_PER_MIN, INGEST_WINDOW_S, RateLimiter
from app.routers import api_device
from tests.test_ingest_jwt import _enroll_device, _keypair, _payload, _token

ENDPOINTS = ("/api/v1/ingest", "/api/v1/config")


@pytest.fixture
def body_reads(monkeypatch):
    """Spy on api_device._read_body: records each call, then delegates to the real reader."""
    calls: list[str] = []
    real = api_device._read_body

    async def spy(request):
        calls.append(request.url.path)
        return await real(request)

    monkeypatch.setattr(api_device, "_read_body", spy)
    return calls


def _auth(tok: str) -> dict:
    return {"Authorization": f"Bearer {tok}"}


async def test_strangers_never_get_their_body_read(app, client, body_reads):
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    wrong_key, _ = _keypair()
    body = json.dumps(_payload()).encode()
    now = int(time.time())
    int_sub = jwt.encode({"sub": 123, "iat": now, "exp": now + 60, "jti": "j", "bh": "x"},
                         priv, algorithm="ES256")
    bad = [
        {},                                                  # no bearer
        _auth("garbage"),                                    # not a JWT
        _auth(int_sub),                                      # non-string sub (was a 500)
        _auth(_token(priv, str(uuid.uuid4()), body)),        # unknown device
        _auth(_token(wrong_key, device_id, body)),           # forged signature
        _auth(_token(priv, device_id, body, exp_in=-3600)),  # expired
        # uuid.UUID() accepts these spellings but asyncpg's uuid codec does not: they
        # used to reach get_device and escape as a pre-auth 500.
        _auth(_token(wrong_key, "{" + device_id + "}", body)),
        _auth(_token(wrong_key, "urn:uuid:" + device_id, body)),
    ]
    for path in ENDPOINTS:
        for headers in bad:
            r = await client.post(path, content=body, headers=headers)
            assert r.status_code == 401, (path, headers)
    assert body_reads == []


async def test_non_string_aud_is_401_not_500(app, client):
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    body = json.dumps(_payload()).encode()
    r = await client.post("/api/v1/ingest", content=body,
                          headers=_auth(_token(priv, device_id, body, aud=5)))
    assert r.status_code == 401


async def test_gzip_bomb_from_a_stranger_is_never_inflated(client, body_reads, monkeypatch):
    inflated: list[int] = []
    real = api_device._gunzip_capped
    monkeypatch.setattr(api_device, "_gunzip_capped",
                        lambda data, limit: inflated.append(len(data)) or real(data, limit))
    bomb = gzip.compress(b"\x00" * (8 * 1024 * 1024))
    for path in ENDPOINTS:
        r = await client.post(path, content=bomb,
                              headers={"Authorization": "Bearer whatever",
                                       "Content-Encoding": "gzip"})
        assert r.status_code == 401, path
    assert body_reads == [] and inflated == []


async def test_replayed_token_is_refused_before_its_body_is_read(app, client, body_reads):
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    body = json.dumps(_payload()).encode()
    h = _auth(_token(priv, device_id, body, jti="once"))
    assert (await client.post("/api/v1/ingest", content=body, headers=h)).status_code == 200
    assert (await client.post("/api/v1/ingest", content=body, headers=h)).status_code == 401
    assert body_reads == ["/api/v1/ingest"]  # only the first request's body was read


async def test_corrupt_gzip_does_not_burn_the_jti(app, client):
    # Review Focus 3.
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    body = json.dumps(_payload()).encode()
    h = {**_auth(_token(priv, device_id, body, jti="retry-gz")), "Content-Encoding": "gzip"}
    gz = gzip.compress(body)
    r = await client.post("/api/v1/ingest", content=gz[: len(gz) // 2], headers=h)
    assert r.status_code == 400
    assert (await client.post("/api/v1/ingest", content=gz, headers=h)).status_code == 200


async def test_body_hash_mismatch_does_not_burn_the_jti(app, client):
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    body = json.dumps(_payload()).encode()
    h = _auth(_token(priv, device_id, body, jti="retry-bh"))
    other = json.dumps({**_payload(), "batch_seq": 99}).encode()
    assert (await client.post("/api/v1/ingest", content=other, headers=h)).status_code == 401
    assert (await client.post("/api/v1/ingest", content=body, headers=h)).status_code == 200


async def test_streamed_oversize_body_does_not_burn_the_jti(app, client, set_setting):
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    body = json.dumps(_payload()).encode()
    set_setting("max_body_bytes", len(body) + 64)
    h = _auth(_token(priv, device_id, body, jti="retry-413"))

    async def too_much():  # no Content-Length: only the streamed count can stop it
        yield body
        yield b" " * 128

    assert (await client.post("/api/v1/ingest", content=too_much(), headers=h)).status_code == 413
    assert (await client.post("/api/v1/ingest", content=body, headers=h)).status_code == 200


async def test_device_budget_counts_only_authenticated_requests(app, client, body_reads):
    app.state.ingest_limiter = RateLimiter(max_attempts=2, window_s=60)
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    forger, _ = _keypair()
    body = json.dumps(_payload()).encode()
    for _ in range(5):  # someone who knows the device id cannot spend its budget
        r = await client.post("/api/v1/ingest", content=body,
                              headers=_auth(_token(forger, device_id, body)))
        assert r.status_code == 401
    for _ in range(2):
        r = await client.post("/api/v1/ingest", content=body,
                              headers=_auth(_token(priv, device_id, body)))
        assert r.status_code == 200
    r = await client.post("/api/v1/config", content=body,
                          headers=_auth(_token(priv, device_id, body)))
    assert r.status_code == 429      # ingest + config share one per-device budget
    assert r.headers.get(API_MARKER_HEADER) == API_MARKER_VALUE  # C1: the phone retries it
    # Other spellings of the same UUID are the same device, so they share its bucket.
    for alias in (device_id.upper(), device_id.replace("-", ""), "{" + device_id + "}",
                  "urn:uuid:" + device_id):
        r = await client.post("/api/v1/ingest", content=body,
                              headers=_auth(_token(priv, alias, body)))
        assert r.status_code == 429, alias
    assert len(body_reads) == 2      # every 429 was decided before reading the body
    priv2, spki2 = _keypair()
    async with app.state.pool.acquire() as conn:
        device2 = str(await q.create_device(conn, "inst-second", spki2, "dev2"))
    r = await client.post("/api/v1/ingest", content=body,
                          headers=_auth(_token(priv2, device2, body)))
    assert r.status_code == 200      # per DEVICE, not per IP (same client address)


async def test_budget_warning_is_throttled_per_device(app, client, caplog):
    # A retry storm against an exhausted budget must not flood the 5 MB prod log: one
    # WARNING per device per REJECT_LOG_INTERVAL_S, through the same throttle as drops.
    caplog.set_level(logging.WARNING, logger="app.routers.api_device")
    app.state.ingest_limiter = RateLimiter(max_attempts=1, window_s=60)
    body = json.dumps(_payload()).encode()

    async def post(priv, device_id):
        return await client.post("/api/v1/ingest", content=body,
                                 headers=_auth(_token(priv, device_id, body)))

    def lines(device_id):
        return [r.getMessage() for r in caplog.records
                if "budget exceeded" in r.getMessage() and device_id in r.getMessage()]

    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    assert (await post(priv, device_id)).status_code == 200
    for _ in range(4):
        assert (await post(priv, device_id)).status_code == 429
    assert len(lines(device_id)) == 1
    priv2, spki2 = _keypair()
    async with app.state.pool.acquire() as conn:
        device2 = str(await q.create_device(conn, "inst-budget-2", spki2, "dev2"))
    assert (await post(priv2, device2)).status_code == 200
    assert (await post(priv2, device2)).status_code == 429
    assert len(lines(device2)) == 1  # another device has its own window


async def test_default_device_budget_never_throttles_an_outbox_drain(app):
    # Review Focus 2: a serial drain tops out ~10-16 POST/s (<= ~1000/min).
    rl = app.state.ingest_limiter
    assert (rl.max_attempts, rl.window_s) == (INGEST_MAX_PER_MIN, INGEST_WINDOW_S)
    assert INGEST_WINDOW_S == 60 and INGEST_MAX_PER_MIN >= 3 * 1000
    assert all(rl.allow("phone") for _ in range(1200))


def test_verify_token_never_touches_the_replay_cache():
    priv, spki = _keypair()
    body = b"{}"
    claims = verify_token(_token(priv, "00000000-0000-0000-0000-000000000001", body,
                                 jti="j1"), spki)
    cache = JtiCache()
    assert claims["jti"] == "j1" and not cache.contains("j1")
    verify_body(claims, body, cache)
    assert cache.contains("j1")
    with pytest.raises(JwtError, match="replay"):
        verify_body(claims, body, cache)


def test_verify_body_checks_the_hash_before_burning():
    priv, spki = _keypair()
    claims = verify_token(_token(priv, "00000000-0000-0000-0000-000000000001", b"{}",
                                 jti="j2"), spki)
    cache = JtiCache()
    with pytest.raises(JwtError, match="body hash"):
        verify_body(claims, b"tampered", cache)
    assert not cache.contains("j2")
