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


async def test_enroll_rejects_bad_pubkey(app, client):
    await _seed_code(app, "KEYBAD")
    r = await client.post("/api/v1/enroll", json={
        "code": "KEYBAD", "install_uuid": "inst-bad", "public_key_spki_b64": "not-valid-b64!!!"})
    assert r.status_code == 400


async def test_enroll_rejects_expired_code(app, client):
    await _seed_code(app, "OLD", minutes=-1)
    r = await client.post("/api/v1/enroll", json={
        "code": "OLD", "install_uuid": "inst-old", "public_key_spki_b64": _pub_b64()})
    assert r.status_code == 400


async def _device_row(app, install_uuid):
    async with app.state.pool.acquire() as conn:
        return await conn.fetchrow(
            "SELECT id, public_key_spki, revoked FROM devices WHERE install_uuid=$1",
            install_uuid)


async def test_reenroll_of_revoked_device_is_refused_and_code_not_burned(app, client):
    # T2.4/SRV-7: revocation is durable. A revoked install_uuid + a fresh valid code must
    # get 403, must NOT rotate the stored key or flip revoked, and must NOT consume the
    # code — it stays usable for a different device.
    await _seed_code(app, "FIRST")
    r = await client.post("/api/v1/enroll", json={
        "code": "FIRST", "install_uuid": "inst-rev", "public_key_spki_b64": _pub_b64()})
    assert r.status_code == 200
    device_id = r.json()["device_id"]
    async with app.state.pool.acquire() as conn:
        await q.revoke_device(conn, device_id)
    before = await _device_row(app, "inst-rev")

    await _seed_code(app, "SECOND")
    r = await client.post("/api/v1/enroll", json={
        "code": "SECOND", "install_uuid": "inst-rev", "public_key_spki_b64": _pub_b64()})
    assert r.status_code == 403
    assert "revoked" in r.json()["detail"]

    after = await _device_row(app, "inst-rev")
    assert after["revoked"] is True                              # still revoked
    assert bytes(after["public_key_spki"]) == bytes(before["public_key_spki"])  # key unchanged
    async with app.state.pool.acquire() as conn:
        used_at = await conn.fetchval(
            "SELECT used_at FROM enrollment_codes WHERE code_hash=$1", hash_code("SECOND"))
    assert used_at is None                                       # code not burned...
    r = await client.post("/api/v1/enroll", json={               # ...still usable elsewhere
        "code": "SECOND", "install_uuid": "inst-other", "public_key_spki_b64": _pub_b64()})
    assert r.status_code == 200
    assert r.json()["device_id"] != device_id


async def test_reenroll_of_non_revoked_device_rekeys_without_touching_revoked(app, client):
    # Legit re-key of the same phone stays allowed: same device row, new key, revoked
    # stays false.
    await _seed_code(app, "KEY1")
    r = await client.post("/api/v1/enroll", json={
        "code": "KEY1", "install_uuid": "inst-rekey", "public_key_spki_b64": _pub_b64()})
    assert r.status_code == 200
    device_id = r.json()["device_id"]
    key1 = bytes((await _device_row(app, "inst-rekey"))["public_key_spki"])

    await _seed_code(app, "KEY2")
    r = await client.post("/api/v1/enroll", json={
        "code": "KEY2", "install_uuid": "inst-rekey", "public_key_spki_b64": _pub_b64()})
    assert r.status_code == 200
    assert r.json()["device_id"] == device_id                    # same device row

    row = await _device_row(app, "inst-rekey")
    assert bytes(row["public_key_spki"]) != key1                 # key rotated
    assert row["revoked"] is False
