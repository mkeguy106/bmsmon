import base64
import hashlib
import json
import time
import uuid

import jwt
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec

from app.db import queries as q

USER = {"X-authentik-username": "joel",
        "X-authentik-groups": "Covert.life - Full App Access - User Group"}


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


def _cfg(updated_at_ms=1000, **extra):
    return {"profile_id": "redodo-beken-bk-ble-1.0", "cold_caution_c": 5, "hot_caution_c": 45,
            "cold_crit_c": -12, "hot_crit_c": 53, "unit": "F", "updated_at_ms": updated_at_ms,
            **extra}


# --- upsert_alert_config latest-wins (query unit) --------------------------------------


async def test_upsert_alert_config_latest_wins(app):
    async with app.state.pool.acquire() as conn:
        await q.upsert_alert_config(conn, "dev-a", 20, True, 2000)
        # older push must NOT overwrite the newer row
        await q.upsert_alert_config(conn, "dev-a", 5, False, 1000)
        row = await conn.fetchrow(
            "SELECT * FROM device_alert_config WHERE device_id=$1", "dev-a")
    assert row["seize_soc"] == 20
    assert row["alerts_on"] is True
    assert row["updated_at_ms"] == 2000


async def test_upsert_alert_config_newer_overwrites(app):
    async with app.state.pool.acquire() as conn:
        await q.upsert_alert_config(conn, "dev-a", 20, True, 1000)
        await q.upsert_alert_config(conn, "dev-a", 10, False, 3000)
        n = await conn.fetchval(
            "SELECT count(*) FROM device_alert_config WHERE device_id=$1", "dev-a")
        row = await conn.fetchrow(
            "SELECT * FROM device_alert_config WHERE device_id=$1", "dev-a")
    assert n == 1                # upsert, not insert
    assert row["seize_soc"] == 10
    assert row["alerts_on"] is False
    assert row["updated_at_ms"] == 3000


# --- GET /web/alert-config --------------------------------------------------------------


async def test_alert_config_requires_identity(client):
    r = await client.get("/web/alert-config")
    assert r.status_code == 401


async def test_alert_config_empty_default(client):
    r = await client.get("/web/alert-config", headers=USER)
    assert r.status_code == 200
    assert r.json() == {"seize_soc": None, "alerts_on": True, "updated_at_ms": 0}


async def test_alert_config_returns_latest_row(app, client):
    async with app.state.pool.acquire() as conn:
        await q.upsert_alert_config(conn, "dev-a", 25, True, 1000)
        await q.upsert_alert_config(conn, "dev-b", 15, False, 2000)  # newer, different device
    r = await client.get("/web/alert-config", headers=USER)
    assert r.status_code == 200
    assert r.json() == {"seize_soc": 15, "alerts_on": False, "updated_at_ms": 2000}


# --- POST /api/v1/config carrying the alert fields --------------------------------------


async def test_config_upserts_alert_config(app, client):
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    body = json.dumps(_cfg(seize_soc=18, alerts_on=True)).encode()
    r = await client.post("/api/v1/config", content=body,
                          headers={"Authorization": f"Bearer {_token(priv, device_id, body)}"})
    assert r.status_code == 200
    async with app.state.pool.acquire() as conn:
        row = await conn.fetchrow(
            "SELECT * FROM device_alert_config WHERE device_id=$1", device_id)
    assert row["seize_soc"] == 18
    assert row["alerts_on"] is True
    # and it is mirrored read-only on the web
    r = await client.get("/web/alert-config", headers=USER)
    assert r.json() == {"seize_soc": 18, "alerts_on": True,
                        "updated_at_ms": _cfg()["updated_at_ms"]}


async def test_config_alerts_on_defaults_true_when_omitted(app, client):
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    body = json.dumps(_cfg(seize_soc=12)).encode()  # seize_soc but no alerts_on
    r = await client.post("/api/v1/config", content=body,
                          headers={"Authorization": f"Bearer {_token(priv, device_id, body)}"})
    assert r.status_code == 200
    async with app.state.pool.acquire() as conn:
        row = await conn.fetchrow(
            "SELECT * FROM device_alert_config WHERE device_id=$1", device_id)
    assert row["seize_soc"] == 12
    assert row["alerts_on"] is True


async def test_config_temp_only_body_leaves_alert_config_untouched(app, client):
    # Regression: a temp-only body (no seize_soc) must still upsert temp config and NOT
    # create an alert-config row.
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    body = json.dumps(_cfg()).encode()
    r = await client.post("/api/v1/config", content=body,
                          headers={"Authorization": f"Bearer {_token(priv, device_id, body)}"})
    assert r.status_code == 200
    async with app.state.pool.acquire() as conn:
        temp_n = await conn.fetchval(
            "SELECT count(*) FROM device_temp_config WHERE device_id=$1", device_id)
        alert_n = await conn.fetchval(
            "SELECT count(*) FROM device_alert_config WHERE device_id=$1", device_id)
    assert temp_n == 1     # temp config still works
    assert alert_n == 0    # no alert config written
    r = await client.get("/web/alert-config", headers=USER)
    assert r.json() == {"seize_soc": None, "alerts_on": True, "updated_at_ms": 0}
