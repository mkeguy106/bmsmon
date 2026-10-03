"""Body-size and gzip-decompression caps on /api/v1/ingest and /api/v1/config.

The WIRE cap fires before anything else (BodySizeLimitMiddleware, every route). The
gunzip cap runs only AFTER the device token verifies (SEC-18): an unauthenticated caller
can never make the server inflate anything (see test_ingest_auth_order.py).
"""
import gzip
import json

from app.config import settings
from app.middleware import API_MARKER_HEADER, API_MARKER_VALUE
from tests.test_ingest_jwt import _enroll_device, _keypair, _payload, _token


async def test_ingest_rejects_oversize_body(client):
    body = b'{"pad":"' + b"x" * (settings.max_body_bytes + 100) + b'"}'
    r = await client.post("/api/v1/ingest", content=body,
                          headers={"Authorization": "Bearer whatever"})
    assert r.status_code == 413


async def test_config_rejects_oversize_body(client):
    body = b'{"pad":"' + b"x" * (settings.max_body_bytes + 100) + b'"}'
    r = await client.post("/api/v1/config", content=body,
                          headers={"Authorization": "Bearer whatever"})
    assert r.status_code == 413


async def test_gzip_bomb_from_an_enrolled_device_is_413(app, client):
    # Small on the wire (passes the body cap), huge decompressed. The token is valid,
    # so this reaches the gunzip ceiling.
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    plaintext = b"\x00" * (settings.max_gunzip_bytes + 1024)
    bomb = gzip.compress(plaintext)
    assert len(bomb) < settings.max_body_bytes  # sanity: it slips past the wire cap
    for path in ("/api/v1/ingest", "/api/v1/config"):
        r = await client.post(path, content=bomb,
                              headers={"Authorization": f"Bearer {_token(priv, device_id, plaintext)}",
                                       "Content-Encoding": "gzip"})
        assert r.status_code == 413, path


async def test_truncated_gzip_from_an_enrolled_device_is_400(app, client):
    # A valid gzip prefix cut short: the incremental decompressor must flag it (400).
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    body = json.dumps(_payload()).encode()
    gz = gzip.compress(body)
    r = await client.post("/api/v1/ingest", content=gz[: len(gz) // 2],
                          headers={"Authorization": f"Bearer {_token(priv, device_id, body)}",
                                   "Content-Encoding": "gzip"})
    assert r.status_code == 400


async def test_normal_gzipped_ingest_still_works(app, client):
    # end-to-end sanity that the capped reader didn't break the happy path
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    body = json.dumps(_payload()).encode()
    r = await client.post("/api/v1/ingest", content=gzip.compress(body),
                          headers={"Authorization": f"Bearer {_token(priv, device_id, body)}",
                                   "Content-Encoding": "gzip"})
    assert r.status_code == 200
    assert r.json()["accepted"] == 1


ENROLL = {"code": "NOPE", "install_uuid": "inst-big", "public_key_spki_b64": "AAAA"}
JSON_CT = {"Content-Type": "application/json"}


async def test_enroll_oversize_declared_body_is_413_before_the_handler(app, client):
    # SEC-17: FastAPI used to buffer enroll's whole pydantic body, pre-auth, unbounded.
    body = b'{"code":"' + b"x" * (settings.max_body_bytes + 1) + b'"}'
    r = await client.post("/api/v1/enroll", content=body, headers=JSON_CT)
    assert r.status_code == 413
    # Rejected before the handler, and therefore before its rate limiter, ever ran.
    assert app.state.enroll_limiter._hits == {}


async def test_enroll_oversize_streamed_body_is_413(app, client, set_setting):
    # No Content-Length (chunked upload): the middleware counts bytes as they stream.
    set_setting("max_body_bytes", 1024)

    async def chunks():
        for _ in range(8):
            yield b"x" * 512

    r = await client.post("/api/v1/enroll", content=chunks(), headers=JSON_CT)
    assert r.status_code == 413
    assert app.state.enroll_limiter._hits == {}


async def test_every_route_is_capped_even_unauthenticated(client, set_setting):
    set_setting("max_body_bytes", 1024)
    big = b"x" * 2048
    for path in ("/web/notes", "/web/shares", "/web/api-keys", "/api/v1/enroll",
                 "/api/v1/config", "/api/v1/ingest"):
        r = await client.post(path, content=big, headers=JSON_CT)
        assert r.status_code == 413, path


async def test_body_exactly_at_the_cap_is_allowed(client, set_setting):
    body = json.dumps(ENROLL).encode()
    cap = len(body) + 16
    set_setting("max_body_bytes", cap)
    padded = body + b" " * (cap - len(body))  # JSON whitespace: still a valid body
    r = await client.post("/api/v1/enroll", content=padded, headers=JSON_CT)
    assert r.status_code == 400  # reached the handler (invalid code); the cap is "more than"


async def test_middleware_413s_carry_the_api_marker(client, set_setting):
    # C1: the phone deletes a batch only on a MARKED 413; an unmarked one (Traefik's own)
    # is retried. The cap middleware sits inside ApiMarkerMiddleware, so both of its 413
    # paths (declared Content-Length, streamed count) must come out marked, and as JSON.
    set_setting("max_body_bytes", 1024)

    async def chunks():
        for _ in range(8):
            yield b"x" * 512

    declared = await client.post("/api/v1/enroll", content=b"x" * 2048, headers=JSON_CT)
    streamed = await client.post("/api/v1/enroll", content=chunks(), headers=JSON_CT)
    for r in (declared, streamed):
        assert r.status_code == 413
        assert r.headers.get(API_MARKER_HEADER) == API_MARKER_VALUE
        assert r.json() == {"detail": "body too large"}
