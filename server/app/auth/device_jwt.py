import base64
import hashlib
import time

import jwt
from cryptography.hazmat.primitives.serialization import load_der_public_key

LEEWAY_SECONDS = 60

# DATA-11: expected audience for device JWTs. Newer app builds set aud="bmsmon-api";
# tokens WITHOUT an aud claim stay valid (older app versions). Enforced manually in
# verify_token() because PyJWT's default behavior is all-or-nothing: passing audience=
# would reject aud-less tokens, and not passing it rejects tokens that carry one.
AUDIENCE = "bmsmon-api"


class JwtError(Exception):
    """A device token was refused. `reason` is the machine-readable cause the API returns
    in X-Bmsmon-Auth-Reason (routers/api_device.py). `skew_s` (server_now - iat, whole
    seconds) is set only for clock_skew, i.e. only once the signature has verified."""

    def __init__(self, message: str, reason: str = "bad_signature",
                 skew_s: int | None = None) -> None:
        super().__init__(message)
        self.reason = reason
        self.skew_s = skew_s


def _skew_from_unverified(token: str) -> int | None:
    """server_now - iat read from a token whose SIGNATURE PyJWT has already verified (it
    checks the signature before any time claim): only the clocks disagree."""
    try:
        return skew_of(jwt.decode(token, options={"verify_signature": False}))
    except Exception:
        return None


def skew_of(claims: dict) -> int:
    """server_now - iat of a verified token, in whole seconds (positive: server ahead).
    Integer arithmetic on purpose: PyJWT has already checked int(iat), and float() of a
    huge integer iat would overflow, so this can never fail on an accepted token."""
    return round(time.time()) - int(claims["iat"])


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

    def contains(self, jti: str) -> bool:
        """Read-only replay probe: True while [jti] is burned and unexpired. Records
        nothing. It lets a request be refused as a replay BEFORE its body is read (SEC-18)."""
        exp = self._seen.get(jti)
        return exp is not None and exp > int(time.time())

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


def verify_token(token: str, public_key_spki: bytes) -> dict:
    """SEC-18 stage 1: everything that does NOT need the body. ES256 signature (alg
    pinned), required claims, exp/iat with LEEWAY_SECONDS, and aud verify-if-present.
    Never touches the replay cache, so it is safe to run before the body is read."""
    try:
        pub = load_der_public_key(public_key_spki)
        # verify_aud=False: PyJWT would otherwise raise InvalidAudienceError for any
        # token that carries aud when no audience= kwarg is given. The aud claim is
        # checked manually below, verify-if-present (see AUDIENCE).
        claims = jwt.decode(token, pub, algorithms=["ES256"],
                            options={"require": ["exp", "sub", "jti", "bh", "iat"],
                                     "verify_aud": False},
                            leeway=LEEWAY_SECONDS)
    except (jwt.ExpiredSignatureError, jwt.ImmatureSignatureError) as e:
        raise JwtError(str(e), "clock_skew", _skew_from_unverified(token)) from e
    except Exception as e:
        raise JwtError(str(e)) from e
    if not isinstance(claims["jti"], str) or not isinstance(claims["bh"], str):
        raise JwtError("bad claim types")
    aud = claims.get("aud")
    if aud is not None:  # verify-if-present; absent = older app, still valid
        # RFC 7519 allows aud to be a string or an array of strings; anything else fails.
        ok = aud == AUDIENCE if isinstance(aud, str) else (
            isinstance(aud, list) and AUDIENCE in aud)
        if not ok:
            raise JwtError("bad audience")
    return claims


def verify_body(claims: dict, body: bytes, jti_cache: JtiCache) -> None:
    """SEC-18 stage 2: bind the (decompressed) body to the verified token, THEN burn the
    jti. The order is load-bearing. A request whose body fails (hash mismatch here, or
    bad gzip / 413 before this is called) must not consume the token, or a retry of the
    same request would be refused as a replay."""
    if claims["bh"] != body_hash(body):
        raise JwtError("body hash mismatch", "body_mismatch")
    if jti_cache.seen(claims["jti"], int(claims["exp"]) + LEEWAY_SECONDS):
        raise JwtError("replay", "replay")


def verify(token: str, public_key_spki: bytes, body: bytes, jti_cache: JtiCache) -> dict:
    """One-shot stage 1 + 2, for callers that already hold the body."""
    claims = verify_token(token, public_key_spki)
    verify_body(claims, body, jti_cache)
    return claims
