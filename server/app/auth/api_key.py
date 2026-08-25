"""API-key auth for read-only telemetry clients (the desktop widgets).

A third identity path, deliberately separate from the two that already exist:
device JWTs authorise the *write* path (`/ingest`), and Authentik authorises
browsers (`/web/*`). Neither can serve a widget — it has no browser session to
carry SSO, and it must not be able to write anything.

Security model, mirroring the share-token one in routers/share.py:
  * 256-bit keys (`secrets.token_urlsafe(32)`), only the sha256 is ever stored,
    so a dump of `api_keys` yields nothing usable.
  * Lookup is *by hash*, so no secret is ever compared in Python — there is no
    string comparison to leak timing.
  * Unknown and revoked keys are the same bare 401, indistinguishable to a prober.
  * A per-IP rate limiter throttles guessing (see app/ratelimit.py).
  * Read-only by construction: nothing here grants a write route.

Lives under `/api/`, which Traefik already routes past Authentik — so this needs
no reverse-proxy change.
"""

import hashlib
import logging

from fastapi import Depends, HTTPException, Request

from app.db import queries as q
from app.db.pool import get_pool
from app.ratelimit import client_key

logger = logging.getLogger(__name__)

HEADER = "x-api-key"


def hash_key(key: str) -> str:
    return hashlib.sha256(key.strip().encode()).hexdigest()


async def require_api_key(request: Request, pool=Depends(get_pool)) -> dict:
    """FastAPI dependency: resolve the X-API-Key header to an api_keys row, or 401.

    Rate-limited per IP *before* touching the database, so a brute-forcer cannot
    use this endpoint as a free query generator.
    """
    ip = client_key(request.client.host if request.client else None, request.headers)
    if not request.app.state.apikey_limiter.allow(ip):
        logger.warning("api-key: rate limit exceeded for %s", ip)
        raise HTTPException(429, "too many attempts; try again later")

    supplied = request.headers.get(HEADER, "").strip()
    if not supplied:
        raise HTTPException(401, "missing api key")

    async with pool.acquire() as conn:
        row = await q.get_api_key(conn, hash_key(supplied))
        if row is None or row["revoked_at"] is not None:
            # Unknown and revoked are the same answer on purpose.
            raise HTTPException(401, "invalid api key")
        await q.touch_api_key(conn, row["id"])
    return dict(row)
