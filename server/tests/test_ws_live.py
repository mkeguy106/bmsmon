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


def _enroll(tc):
    code = tc.post("/web/enroll-codes", headers=AUTH).json()["code"]
    priv, pub_b64 = _kp()
    device_id = tc.post("/api/v1/enroll", json={
        "code": code, "install_uuid": f"ws-{uuid.uuid4().hex}",
        "public_key_spki_b64": pub_b64}).json()["device_id"]
    return priv, device_id


def _ingest(tc, priv, device_id, samples, batch_seq=1):
    body = json.dumps({"batch_seq": batch_seq, "samples": samples}).encode()
    tok = jwt.encode({"sub": device_id, "iat": int(time.time()), "exp": int(time.time()) + 60,
                      "jti": uuid.uuid4().hex, "bh": _bh(body)}, priv, algorithm="ES256")
    return tc.post("/api/v1/ingest", content=body, headers={"Authorization": f"Bearer {tok}"})


def test_ws_snapshot_then_live_sample():
    with TestClient(create_app()) as tc:
        priv, device_id = _enroll(tc)
        with tc.websocket_connect("/ws", headers=AUTH) as ws:
            assert ws.receive_json()["type"] == "snapshot"
            r = _ingest(tc, priv, device_id, [
                {"ts_ms": int(time.time() * 1000), "address": A, "soc": 55.0, "alias": "2012 · A"}])
            assert r.status_code == 200
            evt = ws.receive_json()
            assert evt["type"] == "sample"
            assert evt["soc"] == 55.0


def test_ws_skips_historical_import_batches():
    # batch_seq = -1 marks a historical import (WEB-5): stored, but NOT fanned out to
    # the live WS — the next frame the client sees is the next live-batch sample.
    with TestClient(create_app()) as tc:
        priv, device_id = _enroll(tc)
        now_ms = int(time.time() * 1000)
        with tc.websocket_connect("/ws", headers=AUTH) as ws:
            assert ws.receive_json()["type"] == "snapshot"
            r = _ingest(tc, priv, device_id,
                        [{"ts_ms": now_ms - 5000, "address": A, "soc": 11.0}], batch_seq=-1)
            assert r.status_code == 200
            assert r.json() == {"accepted": 1, "last_seq": -1}  # stored, just not broadcast
            r = _ingest(tc, priv, device_id,
                        [{"ts_ms": now_ms, "address": A, "soc": 22.0}], batch_seq=2)
            assert r.status_code == 200
            evt = ws.receive_json()
            assert evt["type"] == "sample"
            assert evt["soc"] == 22.0  # the import sample (soc=11) never hit the WS


def test_ws_slow_consumer_is_closed(monkeypatch):
    # SRV-10: a subscriber whose queue overflows must be CLOSED (its reconnect logic
    # re-opens + re-snapshots), not left permanently stale by silent drops.
    from app.live import bus as bus_mod
    from app.routers.ws import WS_OVERFLOW

    monkeypatch.setattr(bus_mod, "QUEUE_MAX", 1)
    with TestClient(create_app()) as tc:
        priv, device_id = _enroll(tc)
        now_ms = int(time.time() * 1000)
        with tc.websocket_connect("/ws", headers=AUTH) as ws:
            assert ws.receive_json()["type"] == "snapshot"
            # 3 samples against a 1-slot queue: the 2nd/3rd publish overflow it
            r = _ingest(tc, priv, device_id, [
                {"ts_ms": now_ms + i, "address": A, "soc": 50.0 + i} for i in range(3)])
            assert r.status_code == 200
            assert ws.receive_json()["type"] == "sample"  # the one event that fit
            with pytest.raises(WebSocketDisconnect) as exc:
                ws.receive_json()
            assert exc.value.code == WS_OVERFLOW


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
