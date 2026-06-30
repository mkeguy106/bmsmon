import base64
import hashlib
import time

import jwt
from cryptography.hazmat.primitives.serialization import load_der_public_key

LEEWAY_SECONDS = 60


class JwtError(Exception):
    pass


def body_hash(body: bytes) -> str:
    return base64.urlsafe_b64encode(hashlib.sha256(body).digest()).rstrip(b"=").decode()


class JtiCache:
    def __init__(self) -> None:
        self._seen: dict[str, int] = {}

    def seen(self, jti: str, exp: int) -> bool:
        now = int(time.time())
        self._seen = {k: v for k, v in self._seen.items() if v > now}  # prune
        if jti in self._seen:
            return True
        self._seen[jti] = exp
        return False


def unverified_sub(token: str) -> str:
    try:
        return jwt.decode(token, options={"verify_signature": False})["sub"]
    except Exception as e:
        raise JwtError("no sub") from e


def verify(token: str, public_key_spki: bytes, body: bytes, jti_cache: JtiCache) -> dict:
    try:
        pub = load_der_public_key(public_key_spki)
        claims = jwt.decode(token, pub, algorithms=["ES256"],
                            options={"require": ["exp", "sub", "jti", "bh", "iat"]},
                            leeway=LEEWAY_SECONDS)
    except Exception as e:
        raise JwtError(str(e)) from e
    if claims.get("bh") != body_hash(body):
        raise JwtError("body hash mismatch")
    if jti_cache.seen(claims["jti"], int(claims["exp"]) + LEEWAY_SECONDS):
        raise JwtError("replay")
    return claims
