import base64
from datetime import datetime, timedelta, timezone

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec

from app.auth.enroll import hash_code
from app.db import queries as q


def _pub_b64():
    priv = ec.generate_private_key(ec.SECP256R1())
    spki = priv.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    return base64.b64encode(spki).decode()


async def _seed_code(app, code="ABC123", minutes=10):
    async with app.state.pool.acquire() as conn:
        await q.create_enrollment_code(
            conn, hash_code(code), "admin@covert.life",
            datetime.now(timezone.utc) + timedelta(minutes=minutes))


async def test_enroll_with_valid_code(app, client):
    await _seed_code(app, "GOODCODE")
    r = await client.post("/api/v1/enroll", json={
        "code": "GOODCODE", "install_uuid": "inst-1", "public_key_spki_b64": _pub_b64()})
    assert r.status_code == 200
    assert r.json()["device_id"]


async def test_enroll_rejects_unknown_code(client):
    r = await client.post("/api/v1/enroll", json={
        "code": "NOPE", "install_uuid": "inst-2", "public_key_spki_b64": _pub_b64()})
    assert r.status_code == 400


async def test_code_is_single_use(app, client):
    await _seed_code(app, "ONCE")
    body = {"code": "ONCE", "install_uuid": "inst-3", "public_key_spki_b64": _pub_b64()}
    assert (await client.post("/api/v1/enroll", json=body)).status_code == 200
    assert (await client.post("/api/v1/enroll", json=body)).status_code == 400
