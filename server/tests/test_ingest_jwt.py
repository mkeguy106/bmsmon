import base64
import hashlib
import json
import time
import uuid

import jwt
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec

from app.db import queries as q

A = "C8:47:80:15:67:44"


def _keypair():
    priv = ec.generate_private_key(ec.SECP256R1())
    spki = priv.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    return priv, spki


def _bh(body: bytes) -> str:
    return base64.urlsafe_b64encode(hashlib.sha256(body).digest()).rstrip(b"=").decode()


def _token(priv, device_id, body: bytes, exp_in=60, jti=None):
    now = int(time.time())
    return jwt.encode({"sub": device_id, "iat": now, "exp": now + exp_in,
                       "jti": jti or uuid.uuid4().hex, "bh": _bh(body)},
                      priv, algorithm="ES256")


async def _enroll_device(app, spki):
    async with app.state.pool.acquire() as conn:
        return str(await q.create_device(conn, "inst-x", spki, "dev"))


def _payload():
    return {"batch_seq": 7, "samples": [
        {"ts_ms": 1719686400000, "address": A, "alias": "2012 · A", "advertised_name": "R-12100",
         "group_id": "2012", "soc": 87.0, "current_a": -2.5, "power_w": 127.5, "voltage_v": 51.0,
         "temp_c": 25.0, "regen": False}]}


async def test_ingest_accepts_valid_signed_batch(app, client):
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    body = json.dumps(_payload()).encode()
    r = await client.post("/api/v1/ingest", content=body,
                          headers={"Authorization": f"Bearer {_token(priv, device_id, body)}",
                                   "Content-Type": "application/json"})
    assert r.status_code == 200
    assert r.json() == {"accepted": 1, "last_seq": 7}
    async with app.state.pool.acquire() as conn:
        assert await conn.fetchval("SELECT count(*) FROM samples") == 1


async def test_ingest_rejects_wrong_key(app, client):
    priv, spki = _keypair()
    other, _ = _keypair()
    device_id = await _enroll_device(app, spki)
    body = json.dumps(_payload()).encode()
    r = await client.post("/api/v1/ingest", content=body,
                          headers={"Authorization": f"Bearer {_token(other, device_id, body)}"})
    assert r.status_code == 401


async def test_ingest_rejects_tampered_body(app, client):
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    body = json.dumps(_payload()).encode()
    token = _token(priv, device_id, body)
    tampered = json.dumps({**_payload(), "batch_seq": 9}).encode()
    r = await client.post("/api/v1/ingest", content=tampered,
                          headers={"Authorization": f"Bearer {token}"})
    assert r.status_code == 401


async def test_ingest_rejects_replayed_jti(app, client):
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    body = json.dumps(_payload()).encode()
    token = _token(priv, device_id, body, jti="fixed-jti")
    h = {"Authorization": f"Bearer {token}"}
    assert (await client.post("/api/v1/ingest", content=body, headers=h)).status_code == 200
    assert (await client.post("/api/v1/ingest", content=body, headers=h)).status_code == 401


async def test_ingest_rejects_expired_token(app, client):
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    body = json.dumps(_payload()).encode()
    r = await client.post("/api/v1/ingest", content=body,
                          headers={"Authorization": f"Bearer {_token(priv, device_id, body, exp_in=-3600)}"})
    assert r.status_code == 401


async def test_ingest_rejects_unknown_device(client):
    priv, _ = _keypair()
    body = json.dumps(_payload()).encode()
    tok = _token(priv, str(uuid.uuid4()), body)
    r = await client.post("/api/v1/ingest", content=body, headers={"Authorization": f"Bearer {tok}"})
    assert r.status_code == 401


async def test_ingest_rejects_revoked_device(app, client):
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    async with app.state.pool.acquire() as conn:
        await conn.execute("UPDATE devices SET revoked=true WHERE id=$1", device_id)
    body = json.dumps(_payload()).encode()
    r = await client.post("/api/v1/ingest", content=body,
                          headers={"Authorization": f"Bearer {_token(priv, device_id, body)}"})
    assert r.status_code == 401
