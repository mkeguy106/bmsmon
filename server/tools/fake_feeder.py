"""Enroll a synthetic device and stream fake telemetry so the WebUI shows live data.

Usage:
  1) Mint a code (admin):  curl -XPOST -H 'X-authentik-username: dev' \
       -H 'X-authentik-groups: Covert.life - Full App Access - User Group' \
       http://localhost:8000/web/enroll-codes
     ...or run with BMSMON_DEV_TRUST_HEADERS=1 and use --mint.
  2) python tools/fake_feeder.py --base http://localhost:8000 --code <CODE>
"""
import argparse
import base64
import hashlib
import json
import math
import time
import urllib.request
import uuid

import jwt
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec

PACKS = [("C8:47:80:15:67:44", "2012 · A", "2012"), ("C8:47:80:15:62:1B", "2012 · B", "2012"),
         ("C8:47:80:15:DB:13", "2016 · A", "2016"), ("C8:47:80:15:25:9A", "2016 · B", "2016")]


def _post(url, data, headers=None):
    req = urllib.request.Request(url, data=data, headers=headers or {}, method="POST")
    with urllib.request.urlopen(req) as r:
        return r.status, r.read()


def _bh(body: bytes) -> str:
    return base64.urlsafe_b64encode(hashlib.sha256(body).digest()).rstrip(b"=").decode()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8000")
    ap.add_argument("--code", required=True)
    ap.add_argument("--mint", action="store_true", help="mint the code first (needs dev-trust)")
    args = ap.parse_args()

    code = args.code
    if args.mint:
        _, b = _post(args.base + "/web/enroll-codes", b"", {
            "X-authentik-username": "dev",
            "X-authentik-groups": "Covert.life - Full App Access - User Group"})
        code = json.loads(b)["code"]
        print("minted code", code)

    priv = ec.generate_private_key(ec.SECP256R1())
    spki = priv.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    _, b = _post(args.base + "/api/v1/enroll", json.dumps({
        "code": code, "install_uuid": str(uuid.uuid4()),
        "public_key_spki_b64": base64.b64encode(spki).decode()}).encode(),
        {"Content-Type": "application/json"})
    device_id = json.loads(b)["device_id"]
    print("enrolled device", device_id)

    seq = 0
    while True:
        seq += 1
        t = time.time()
        samples = []
        for i, (addr, alias, gid) in enumerate(PACKS):
            soc = 60 + 20 * math.sin(t / 30 + i)
            cur = -3 * (1 + math.sin(t / 7 + i))
            samples.append({"ts_ms": int(t * 1000), "address": addr, "alias": alias,
                            "advertised_name": "R-12100", "group_id": gid, "soc": round(soc, 1),
                            "current_a": round(cur, 2), "power_w": round(abs(cur) * 51, 1),
                            "voltage_v": 51.0, "temp_c": 25.0,
                            "state": "Discharging" if cur < -0.1 else "Idle", "regen": False})
        body = json.dumps({"batch_seq": seq, "samples": samples}).encode()
        token = jwt.encode({"sub": device_id, "iat": int(t), "exp": int(t) + 60,
                            "jti": uuid.uuid4().hex, "bh": _bh(body)}, priv, algorithm="ES256")
        st, _ = _post(args.base + "/api/v1/ingest", body, {"Authorization": f"Bearer {token}"})
        print("batch", seq, "->", st)
        time.sleep(1.5)


if __name__ == "__main__":
    main()
