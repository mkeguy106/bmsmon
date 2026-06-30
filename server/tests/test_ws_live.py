import base64
import hashlib
import json
import time
import uuid

import jwt
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec
from starlette.testclient import TestClient

from app.main import create_app

ADMIN = "Covert.life - Full App Access - User Group"
A = "C8:47:80:15:67:44"


def _kp():
    priv = ec.generate_private_key(ec.SECP256R1())
    spki = priv.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    return priv, base64.b64encode(spki).decode()


def _bh(body: bytes) -> str:
    return base64.urlsafe_b64encode(hashlib.sha256(body).digest()).rstrip(b"=").decode()


def test_ws_snapshot_then_live_sample():
    with TestClient(create_app()) as tc:
        code = tc.post("/web/enroll-codes", headers={
            "X-authentik-username": "t", "X-authentik-groups": ADMIN}).json()["code"]
        priv, pub_b64 = _kp()
        device_id = tc.post("/api/v1/enroll", json={
            "code": code, "install_uuid": f"ws-{uuid.uuid4().hex}",
            "public_key_spki_b64": pub_b64}).json()["device_id"]
        with tc.websocket_connect("/ws") as ws:
            assert ws.receive_json()["type"] == "snapshot"
            body = json.dumps({"batch_seq": 1, "samples": [
                {"ts_ms": int(time.time() * 1000), "address": A, "soc": 55.0, "alias": "2012 · A"}]}).encode()
            tok = jwt.encode({"sub": device_id, "iat": int(time.time()), "exp": int(time.time()) + 60,
                              "jti": uuid.uuid4().hex, "bh": _bh(body)}, priv, algorithm="ES256")
            r = tc.post("/api/v1/ingest", content=body, headers={"Authorization": f"Bearer {tok}"})
            assert r.status_code == 200
            evt = ws.receive_json()
            assert evt["type"] == "sample"
            assert evt["soc"] == 55.0
