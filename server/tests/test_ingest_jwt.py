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

A = "C8:47:80:15:67:44"


def _keypair():
    priv = ec.generate_private_key(ec.SECP256R1())
    spki = priv.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    return priv, spki


def _bh(body: bytes) -> str:
    return base64.urlsafe_b64encode(hashlib.sha256(body).digest()).rstrip(b"=").decode()


def _token(priv, device_id, body: bytes, exp_in=60, jti=None, aud=None):
    now = int(time.time())
    claims = {"sub": device_id, "iat": now, "exp": now + exp_in,
              "jti": jti or uuid.uuid4().hex, "bh": _bh(body)}
    if aud is not None:
        claims["aud"] = aud
    return jwt.encode(claims, priv, algorithm="ES256")


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


async def test_ingest_accepts_gzipped_body(app, client):
    # The signature's bh claim is over the PLAINTEXT body; the wire carries gzip. The server must
    # decompress before verifying + parsing, so the hash still matches.
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    body = json.dumps(_payload()).encode()
    gz = gzip.compress(body)
    assert len(gz) < len(body)  # sanity: it actually compressed
    r = await client.post("/api/v1/ingest", content=gz,
                          headers={"Authorization": f"Bearer {_token(priv, device_id, body)}",
                                   "Content-Type": "application/json",
                                   "Content-Encoding": "gzip"})
    assert r.status_code == 200
    assert r.json() == {"accepted": 1, "last_seq": 7}
    async with app.state.pool.acquire() as conn:
        assert await conn.fetchval("SELECT count(*) FROM samples") == 1


async def test_ingest_rejects_corrupt_gzip(app, client):
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    body = json.dumps(_payload()).encode()
    r = await client.post("/api/v1/ingest", content=b"not-gzip-at-all",
                          headers={"Authorization": f"Bearer {_token(priv, device_id, body)}",
                                   "Content-Encoding": "gzip"})
    assert r.status_code == 400


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


async def test_ingest_rejects_non_uuid_sub(client):
    priv, _ = _keypair()
    body = json.dumps(_payload()).encode()
    tok = _token(priv, "not-a-uuid", body)
    r = await client.post("/api/v1/ingest", content=body, headers={"Authorization": f"Bearer {tok}"})
    assert r.status_code == 401


async def _partition_count(app) -> int:
    async with app.state.pool.acquire() as conn:
        return await conn.fetchval(
            """SELECT count(*) FROM pg_inherits i
               JOIN pg_class p ON p.oid = i.inhparent WHERE p.relname = 'samples'""")


async def test_ingest_drops_out_of_window_ts_and_keeps_valid(app, client):
    # T2.3/SRV-5: broken-clock timestamps (epoch 0, year 3000, absurd values that would
    # crash datetime.fromtimestamp) are silently DROPPED — not a 4xx, which the phone
    # would treat as a poison batch — while the valid samples in the batch are stored.
    # And no partition explosion: at most one new monthly partition (the valid month).
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    parts_before = await _partition_count(app)
    now_ms = int(time.time() * 1000)
    payload = {"batch_seq": 11, "samples": [
        {"ts_ms": 0, "address": A, "soc": 1.0},                    # epoch 0 → ~678 partitions
        {"ts_ms": 32503680000000, "address": A, "soc": 2.0},       # year 3000
        {"ts_ms": 999999999999999999, "address": A, "soc": 3.0},   # datetime overflow
        {"ts_ms": -1, "address": A, "soc": 4.0},                   # negative
        {"ts_ms": now_ms, "address": A, "soc": 87.0},              # valid
    ]}
    body = json.dumps(payload).encode()
    r = await client.post("/api/v1/ingest", content=body,
                          headers={"Authorization": f"Bearer {_token(priv, device_id, body)}"})
    assert r.status_code == 200
    assert r.json() == {"accepted": 1, "last_seq": 11}
    async with app.state.pool.acquire() as conn:
        stored = await conn.fetch("SELECT ts_ms, soc FROM samples")
    assert [(x["ts_ms"], x["soc"]) for x in stored] == [(now_ms, 87.0)]
    assert await _partition_count(app) <= parts_before + 1


async def test_ingest_all_invalid_ts_batch_is_200_accepted_0(app, client):
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    parts_before = await _partition_count(app)
    payload = {"batch_seq": 12, "samples": [
        {"ts_ms": 0, "address": A, "soc": 1.0},
        {"ts_ms": 999999999999999999, "address": A, "soc": 2.0},
    ]}
    body = json.dumps(payload).encode()
    r = await client.post("/api/v1/ingest", content=body,
                          headers={"Authorization": f"Bearer {_token(priv, device_id, body)}"})
    assert r.status_code == 200
    assert r.json() == {"accepted": 0, "last_seq": 12}
    async with app.state.pool.acquire() as conn:
        assert await conn.fetchval("SELECT count(*) FROM samples") == 0
        assert await conn.fetchval("SELECT count(*) FROM batteries") == 0
    assert await _partition_count(app) == parts_before


def _gps_payload():
    return {"batch_seq": 8, "samples": [
        {"ts_ms": 1719686400000, "address": A, "alias": "2012 · A", "group_id": "2012",
         "soc": 87.0, "lat": 41.8781, "lon": -87.6298, "gps_accuracy_m": 7.5}]}


async def test_ingest_ignores_legacy_cells_field(app, client):
    # WEB-5: the dead `cells` column was dropped server-side. A stale client that
    # still sends the field must not break (pydantic ignores unknown fields).
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    payload = _payload()
    payload["samples"][0]["cells"] = [3.31, 3.32, 3.33, 3.34]
    body = json.dumps(payload).encode()
    r = await client.post("/api/v1/ingest", content=body,
                          headers={"Authorization": f"Bearer {_token(priv, device_id, body)}"})
    assert r.status_code == 200
    assert r.json() == {"accepted": 1, "last_seq": 7}


async def test_ingest_upserts_battery_last_values_per_address(app, client):
    # SRV-13: one registry upsert per unique address per batch, keeping the values
    # of the LAST-seen sample for that address.
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    now_ms = int(time.time() * 1000)
    payload = {"batch_seq": 13, "samples": [
        {"ts_ms": now_ms - 1000, "address": A, "alias": "stale alias", "group_id": "2011",
         "soc": 50.0},
        {"ts_ms": now_ms, "address": A, "alias": "2012 · A", "group_id": "2012", "soc": 51.0},
    ]}
    body = json.dumps(payload).encode()
    r = await client.post("/api/v1/ingest", content=body,
                          headers={"Authorization": f"Bearer {_token(priv, device_id, body)}"})
    assert r.status_code == 200
    assert r.json() == {"accepted": 2, "last_seq": 13}
    async with app.state.pool.acquire() as conn:
        row = await conn.fetchrow("SELECT alias, group_id FROM batteries WHERE address=$1", A)
    assert row["alias"] == "2012 · A"
    assert row["group_id"] == "2012"


async def test_ingest_accepts_correct_aud_claim(app, client):
    # DATA-11: newer app builds send aud="bmsmon-api"; the server must accept it.
    # (The existing tests above all send NO aud, proving older tokens stay valid.)
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    body = json.dumps(_payload()).encode()
    r = await client.post("/api/v1/ingest", content=body,
                          headers={"Authorization":
                                   f"Bearer {_token(priv, device_id, body, aud='bmsmon-api')}"})
    assert r.status_code == 200
    assert r.json() == {"accepted": 1, "last_seq": 7}


async def test_ingest_rejects_wrong_aud_claim(app, client):
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    body = json.dumps(_payload()).encode()
    r = await client.post("/api/v1/ingest", content=body,
                          headers={"Authorization":
                                   f"Bearer {_token(priv, device_id, body, aud='evil-api')}"})
    assert r.status_code == 401


async def test_ingest_accepts_aud_list_containing_expected(app, client):
    # RFC 7519: aud may be an array of strings.
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    body = json.dumps(_payload()).encode()
    tok = _token(priv, device_id, body, aud=["other", "bmsmon-api"])
    r = await client.post("/api/v1/ingest", content=body,
                          headers={"Authorization": f"Bearer {tok}"})
    assert r.status_code == 200


async def test_ingest_drops_junk_addresses_and_keeps_valid(app, client):
    # SRV-13: junk addresses would create PERMANENT batteries rows — dropped (never
    # 4xx, same poison-batch policy as the ts_ms filter) while valid samples land.
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    now_ms = int(time.time() * 1000)
    payload = {"batch_seq": 14, "samples": [
        {"ts_ms": now_ms, "address": "", "soc": 1.0},                       # empty
        {"ts_ms": now_ms, "address": "A" * 33, "soc": 2.0},                 # too long
        {"ts_ms": now_ms, "address": "C8:47:80:15:67:44\n", "soc": 3.0},    # control char
        {"ts_ms": now_ms, "address": "junk addr", "soc": 4.0},              # space
        {"ts_ms": now_ms, "address": "C8:47:80:ÿ5:67:44", "soc": 5.0}, # non-ASCII
        {"ts_ms": now_ms, "address": A, "soc": 87.0},                       # valid MAC
        {"ts_ms": now_ms - 1, "address": "c8:47:80:15:67:44", "soc": 86.0}, # lowercase ok
    ]}
    body = json.dumps(payload).encode()
    r = await client.post("/api/v1/ingest", content=body,
                          headers={"Authorization": f"Bearer {_token(priv, device_id, body)}"})
    assert r.status_code == 200
    assert r.json() == {"accepted": 2, "last_seq": 14}
    async with app.state.pool.acquire() as conn:
        addrs = [x["address"] for x in await conn.fetch("SELECT address FROM batteries")]
        n = await conn.fetchval("SELECT count(*) FROM samples")
    assert sorted(addrs) == [A, "c8:47:80:15:67:44"]
    assert n == 2


async def test_ingest_all_junk_addresses_is_200_accepted_0(app, client):
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    now_ms = int(time.time() * 1000)
    payload = {"batch_seq": 15, "samples": [{"ts_ms": now_ms, "address": "\x00\x01", "soc": 1.0}]}
    body = json.dumps(payload).encode()
    r = await client.post("/api/v1/ingest", content=body,
                          headers={"Authorization": f"Bearer {_token(priv, device_id, body)}"})
    assert r.status_code == 200
    assert r.json() == {"accepted": 0, "last_seq": 15}
    async with app.state.pool.acquire() as conn:
        assert await conn.fetchval("SELECT count(*) FROM batteries") == 0


async def test_ingest_accepted_excludes_db_duplicates(app, client):
    # SRV-9: `accepted` is the count of rows ACTUALLY inserted. Re-uploading the same
    # samples (same device/address/ts PK) dedups via ON CONFLICT DO NOTHING → 0.
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    body = json.dumps(_payload()).encode()
    h1 = {"Authorization": f"Bearer {_token(priv, device_id, body)}"}
    r = await client.post("/api/v1/ingest", content=body, headers=h1)
    assert r.status_code == 200 and r.json()["accepted"] == 1
    h2 = {"Authorization": f"Bearer {_token(priv, device_id, body)}"}  # fresh jti
    r = await client.post("/api/v1/ingest", content=body, headers=h2)
    assert r.status_code == 200
    assert r.json() == {"accepted": 0, "last_seq": 7}
    async with app.state.pool.acquire() as conn:
        assert await conn.fetchval("SELECT count(*) FROM samples") == 1


async def test_ingest_stores_gps(app, client):
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    body = json.dumps(_gps_payload()).encode()
    r = await client.post("/api/v1/ingest", content=body,
                          headers={"Authorization": f"Bearer {_token(priv, device_id, body)}"})
    assert r.status_code == 200
    assert r.json() == {"accepted": 1, "last_seq": 8}
    async with app.state.pool.acquire() as conn:
        row = await conn.fetchrow("SELECT lat, lon, gps_accuracy_m FROM samples")
    assert row["lat"] == 41.8781
    assert row["lon"] == -87.6298
    assert abs(row["gps_accuracy_m"] - 7.5) < 1e-4
