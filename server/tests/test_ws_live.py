import base64
import hashlib
import json
import time
import uuid

import jwt
import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec
from starlette.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from app.main import create_app

ADMIN = "Covert.life - Full App Access - User Group"
A = "C8:47:80:15:67:44"
AUTH = {"X-authentik-username": "t", "X-authentik-groups": ADMIN}


def _kp():
    priv = ec.generate_private_key(ec.SECP256R1())
    spki = priv.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    return priv, base64.b64encode(spki).decode()


def _bh(body: bytes) -> str:
    return base64.urlsafe_b64encode(hashlib.sha256(body).digest()).rstrip(b"=").decode()


def test_ws_snapshot_then_live_sample():
    with TestClient(create_app()) as tc:
        code = tc.post("/web/enroll-codes", headers=AUTH).json()["code"]
        priv, pub_b64 = _kp()
        device_id = tc.post("/api/v1/enroll", json={
            "code": code, "install_uuid": f"ws-{uuid.uuid4().hex}",
            "public_key_spki_b64": pub_b64}).json()["device_id"]
        with tc.websocket_connect("/ws", headers=AUTH) as ws:
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


def test_ws_without_identity_is_closed_4401():
    with TestClient(create_app()) as tc:
        with tc.websocket_connect("/ws") as ws:
            with pytest.raises(WebSocketDisconnect) as exc:
                ws.receive_json()
            assert exc.value.code == 4401


def test_ws_with_dev_trust_headers_works(set_setting):
    set_setting("dev_trust_headers", True)
    with TestClient(create_app()) as tc:
        with tc.websocket_connect("/ws") as ws:  # no headers, dev-trust identity
            assert ws.receive_json()["type"] == "snapshot"


def test_ws_requires_proxy_secret_when_set(set_setting):
    set_setting("proxy_secret", "hunter2")
    with TestClient(create_app()) as tc:
        # identity headers alone are not trusted without the proxy secret
        with tc.websocket_connect("/ws", headers=AUTH) as ws:
            with pytest.raises(WebSocketDisconnect) as exc:
                ws.receive_json()
            assert exc.value.code == 4401
        # with the secret, the handshake succeeds
        with tc.websocket_connect(
                "/ws", headers={**AUTH, "X-Bmsmon-Proxy-Secret": "hunter2"}) as ws:
            assert ws.receive_json()["type"] == "snapshot"
