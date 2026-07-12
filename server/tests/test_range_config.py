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
        return str(await q.create_device(conn, "inst-range", spki, "dev"))


def _range_row(addr="C8:47:80:15:25:01", ts=1000):
    return {"address": addr,
            "wh_per_day_lo": 100.0, "wh_per_day_hi": 180.0,
            "active_w_lo": 70.0, "active_w_hi": 100.0,
            "wh_per_mile_lo": 18.0, "wh_per_mile_hi": 24.0,
            "learned_days": 10, "updated_at_ms": ts}


def _cfg(updated_at_ms=1000, ranges=None):
    body = {"profile_id": "redodo-beken-bk-ble-1.0", "cold_caution_c": 5, "hot_caution_c": 45,
            "cold_crit_c": -12, "hot_crit_c": 53, "unit": "F", "updated_at_ms": updated_at_ms}
    if ranges is not None:
        body["ranges"] = ranges
    return body


async def _post_cfg(client, priv, device_id, cfg):
    body = json.dumps(cfg).encode()
    return await client.post("/api/v1/config", content=body,
                             headers={"Authorization": f"Bearer {_token(priv, device_id, body)}"})


USER = {"X-authentik-username": "joel",
        "X-authentik-groups": "Covert.life - Full App Access - User Group"}


async def test_config_with_ranges_upserts_rows(app, client):
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    r = await _post_cfg(client, priv, device_id,
                        _cfg(ranges=[_range_row(), _range_row(addr="C8:47:80:15:67:44")]))
    assert r.status_code == 200
    async with app.state.pool.acquire() as conn:
        n = await conn.fetchval("SELECT count(*) FROM device_range_config WHERE device_id=$1",
                                uuid.UUID(device_id))
    assert n == 2


async def test_range_config_latest_wins(app, client):
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    await _post_cfg(client, priv, device_id,
                    _cfg(updated_at_ms=2000, ranges=[{**_range_row(ts=2000), "wh_per_day_lo": 111.0}]))
    # A stale (older updated_at_ms) push must not clobber the newer row.
    await _post_cfg(client, priv, device_id,
                    _cfg(updated_at_ms=1000, ranges=[{**_range_row(ts=1000), "wh_per_day_lo": 999.0}]))
    async with app.state.pool.acquire() as conn:
        lo = await conn.fetchval(
            "SELECT wh_per_day_lo FROM device_range_config WHERE device_id=$1",
            uuid.UUID(device_id))
    assert lo == 111.0


async def test_temp_only_body_still_validates(app, client):
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    r = await _post_cfg(client, priv, device_id, _cfg())   # no ranges key at all
    assert r.status_code == 200


async def test_web_range_config_mirror(app, client):
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    await _post_cfg(client, priv, device_id, _cfg(ranges=[_range_row()]))
    r = await client.get("/web/range-config", headers=USER)
    assert r.status_code == 200
    rows = r.json()["configs"]
    assert len(rows) == 1
    assert rows[0]["address"] == "C8:47:80:15:25:01"
    assert rows[0]["wh_per_mile_hi"] == 24.0
    assert rows[0]["learned_days"] == 10
