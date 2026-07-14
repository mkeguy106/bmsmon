"""Public location-share endpoints. The /share/ Traefik zone bypasses Authentik, so
these are reachable by anyone holding a share URL (capability link). Security model:
192-bit tokens (secrets.token_urlsafe(24)), only sha256 stored, unknown/revoked are an
indistinguishable bare 404, expired gets a human page, every response is
no-store/no-referrer, and a per-IP rate limiter throttles scanning. Guests only ever
see today's GPS trail — never battery fields (see queries.gps_track_all)."""

import os
import time
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse

from app.auth.enroll import hash_code
from app.config import settings
from app.db import queries as q
from app.db.pool import get_pool
from app.ratelimit import client_key

router = APIRouter(prefix="/share")

_SEC_HEADERS = {"Referrer-Policy": "no-referrer", "Cache-Control": "no-store"}

_EXPIRED_HTML = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><title>Share expired</title>
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex, nofollow">
<style>body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;
background:#09090b;color:#f4f4f5;font-family:Inter,system-ui,sans-serif;text-align:center;
padding:24px}p{color:#a1a1aa}</style></head>
<body><div><h2>This location share has expired</h2>
<p>Ask for a new link to keep following along.</p></div></body></html>"""


def share_status(share: dict | None, now_ms: int) -> str:
    """'active' | 'expired' | 'gone'. Unknown and revoked are both 'gone' — deliberately
    indistinguishable to probers; only a genuinely expired (once-valid) link admits it."""
    if share is None or share.get("revoked_at") is not None:
        return "gone"
    return "active" if now_ms < share["expires_at"] else "expired"


def day_window_ms(now: datetime) -> tuple[int, int]:
    """[local midnight, now] in epoch ms — the guest window is always just today,
    regardless of share duration (server-side clamp; guests send no time params).
    Local = the container's TZ env, i.e. the household timezone."""
    local = now.astimezone()
    start = local.replace(hour=0, minute=0, second=0, microsecond=0)
    return int(start.timestamp() * 1000), int(local.timestamp() * 1000)


async def _resolve(request: Request, token: str, pool) -> tuple[str, dict | None]:
    key = client_key(request.client.host if request.client else None, request.headers)
    if not request.app.state.share_limiter.allow(key):
        raise HTTPException(429, "too many requests", headers=_SEC_HEADERS)
    async with pool.acquire() as conn:
        share = await q.get_location_share(conn, hash_code(token))
    return share_status(share, int(time.time() * 1000)), share


@router.get("/{token}")
async def share_page(token: str, request: Request, pool=Depends(get_pool)):
    status, _share = await _resolve(request, token, pool)
    if status == "gone":
        raise HTTPException(404, "Not Found", headers=_SEC_HEADERS)
    if status == "expired":
        return HTMLResponse(_EXPIRED_HTML, headers=_SEC_HEADERS)
    index = os.path.join(os.environ.get("BMSMON_WEB_DIST", "/app/web/dist"),
                         "share", "index.html")
    return FileResponse(index, headers=_SEC_HEADERS)


@router.get("/{token}/feed")
async def share_feed(token: str, request: Request, pool=Depends(get_pool)):
    status, share = await _resolve(request, token, pool)
    if status == "gone":
        raise HTTPException(404, "Not Found", headers=_SEC_HEADERS)
    if status == "expired":
        raise HTTPException(410, "share expired", headers=_SEC_HEADERS)
    from_ms, now_ms = day_window_ms(datetime.now(timezone.utc))
    async with pool.acquire() as conn:
        rows = await q.gps_track_all(conn, from_ms, now_ms + 1)
        await q.touch_location_share(conn, share["id"], now_ms)
    points = [{"t": int(r["bucket_ms"]), "lat": r["lat"], "lon": r["lon"]} for r in rows]
    return JSONResponse(
        {"points": points, "last": points[-1] if points else None,
         "expires_at": share["expires_at"], "now": now_ms, "owner": settings.share_owner},
        headers=_SEC_HEADERS)
