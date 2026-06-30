import base64
import gzip
import hashlib
import json
import time
import uuid

import jwt
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec

from app.db import queries as q


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


def _cfg(updated_at_ms=1000):
    return {"profile_id": "redodo-beken-bk-ble-1.0", "cold_caution_c": 5, "hot_caution_c": 45,
            "cold_crit_c": -12, "hot_crit_c": 53, "unit": "F", "updated_at_ms": updated_at_ms}


async def test_config_accepts_gzipped_signed_body(app, client):
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    body = json.dumps(_cfg()).encode()
    gz = gzip.compress(body)
    r = await client.post("/api/v1/config", content=gz,
                          headers={"Authorization": f"Bearer {_token(priv, device_id, body)}",
                                   "Content-Type": "application/json", "Content-Encoding": "gzip"})
    assert r.status_code == 200
    assert r.json() == {"ok": True}
    async with app.state.pool.acquire() as conn:
        row = await conn.fetchrow("SELECT * FROM device_temp_config WHERE device_id=$1", device_id)
    assert row["cold_crit_c"] == -12
    assert row["hot_crit_c"] == 53
    assert row["unit"] == "F"


async def test_config_upserts_latest(app, client):
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    for crit, ts in [(-12, 1000), (-15, 2000)]:
        cfg = {**_cfg(updated_at_ms=ts), "cold_crit_c": crit}
        body = json.dumps(cfg).encode()
        r = await client.post("/api/v1/config", content=body,
                              headers={"Authorization": f"Bearer {_token(priv, device_id, body)}"})
        assert r.status_code == 200
    async with app.state.pool.acquire() as conn:
        n = await conn.fetchval("SELECT count(*) FROM device_temp_config WHERE device_id=$1", device_id)
        crit = await conn.fetchval("SELECT cold_crit_c FROM device_temp_config WHERE device_id=$1", device_id)
    assert n == 1            # upsert, not insert
    assert crit == -15       # latest wins


async def test_config_rejects_wrong_key(app, client):
    priv, spki = _keypair()
    other, _ = _keypair()
    device_id = await _enroll_device(app, spki)
    body = json.dumps(_cfg()).encode()
    r = await client.post("/api/v1/config", content=body,
                          headers={"Authorization": f"Bearer {_token(other, device_id, body)}"})
    assert r.status_code == 401


async def test_config_rejects_corrupt_gzip(app, client):
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    body = json.dumps(_cfg()).encode()
    r = await client.post("/api/v1/config", content=b"not-gzip",
                          headers={"Authorization": f"Bearer {_token(priv, device_id, body)}",
                                   "Content-Encoding": "gzip"})
    assert r.status_code == 400
