"""Body-size and gzip-decompression caps on /api/v1/ingest and /api/v1/config.

The caps fire BEFORE any signature verification, so an unauthenticated attacker
cannot make the server buffer or inflate arbitrary amounts of memory.
"""
import gzip
import json

from app.config import settings
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


async def test_ingest_rejects_gzip_bomb(client):
    # small on the wire (passes the body cap), huge decompressed
    plaintext_size = settings.max_gunzip_bytes + 1024
    bomb = gzip.compress(b"\x00" * plaintext_size)
    assert len(bomb) < settings.max_body_bytes  # sanity: it slips past the wire cap
    r = await client.post("/api/v1/ingest", content=bomb,
                          headers={"Authorization": "Bearer whatever",
                                   "Content-Encoding": "gzip"})
    assert r.status_code == 413


async def test_config_rejects_gzip_bomb(client):
    bomb = gzip.compress(b"\x00" * (settings.max_gunzip_bytes + 1024))
    r = await client.post("/api/v1/config", content=bomb,
                          headers={"Authorization": "Bearer whatever",
                                   "Content-Encoding": "gzip"})
    assert r.status_code == 413


async def test_ingest_rejects_truncated_gzip(client):
    # valid gzip prefix cut short: incremental decompressor must flag it as bad (400)
    gz = gzip.compress(json.dumps(_payload()).encode())
    r = await client.post("/api/v1/ingest", content=gz[: len(gz) // 2],
                          headers={"Authorization": "Bearer whatever",
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
