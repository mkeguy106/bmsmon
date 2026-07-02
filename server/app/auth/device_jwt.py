import base64
import hashlib
import time

import jwt
from cryptography.hazmat.primitives.serialization import load_der_public_key

LEEWAY_SECONDS = 60

# DATA-11: expected audience for device JWTs. Newer app builds set aud="bmsmon-api";
# tokens WITHOUT an aud claim stay valid (older app versions). Enforced manually in
# verify() because PyJWT's default behavior is all-or-nothing: passing audience=
# would reject aud-less tokens, and not passing it rejects tokens that carry one.
AUDIENCE = "bmsmon-api"


class JwtError(Exception):
    pass


def body_hash(body: bytes) -> str:
    return base64.urlsafe_b64encode(hashlib.sha256(body).digest()).rstrip(b"=").decode()


class JtiCache:
    """JWT-replay guard: remembers seen jti values until their exp passes.

    SINGLE-WORKER CONSTRAINT (SRV-8): this cache is process-local. Running
    uvicorn with --workers >1 silently defeats replay protection — a replayed
    token just needs to land on a worker that hasn't seen the jti. A restart
    also clears it (bounded by the short token TTL + LEEWAY). Keep the server
    single-process (see the Dockerfile CMD note) or move this to a shared
    store first.
    """

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
        # verify_aud=False: PyJWT would otherwise raise InvalidAudienceError for any
        # token that carries aud when no audience= kwarg is given. The aud claim is
        # checked manually below, verify-if-present (see AUDIENCE).
        claims = jwt.decode(token, pub, algorithms=["ES256"],
                            options={"require": ["exp", "sub", "jti", "bh", "iat"],
                                     "verify_aud": False},
                            leeway=LEEWAY_SECONDS)
    except Exception as e:
        raise JwtError(str(e)) from e
    aud = claims.get("aud")
    if aud is not None:  # verify-if-present; absent = older app, still valid
        # RFC 7519 allows aud to be a string or an array of strings.
        ok = aud == AUDIENCE if isinstance(aud, str) else AUDIENCE in aud
        if not ok:
            raise JwtError("bad audience")
    if claims.get("bh") != body_hash(body):
        raise JwtError("body hash mismatch")
    if jti_cache.seen(claims["jti"], int(claims["exp"]) + LEEWAY_SECONDS):
        raise JwtError("replay")
    return claims
