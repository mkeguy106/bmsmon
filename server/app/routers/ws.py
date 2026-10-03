import asyncio
from urllib.parse import urlsplit

from fastapi import APIRouter, HTTPException, WebSocket, WebSocketDisconnect

from app.auth.authentik import authorize, dev_trust_active
from app.config import settings
from app.db import queries as q
from app.util import jsonable

router = APIRouter()

# WebSocket close codes (4000-4999 = application-defined).
# 4401 mirrors HTTP 401 by convention.
WS_UNAUTHORIZED = 4401
# Authenticated but not in the viewer group (SEC-13); mirrors HTTP 403.
WS_FORBIDDEN = 4403
# Sent to a slow consumer whose event queue overflowed: the client is behind and
# would otherwise be permanently stale (SRV-10). The browser's reconnect logic
# re-opens the socket and gets a fresh snapshot.
WS_OVERFLOW = 4408

# Push a keepalive frame when no telemetry has flowed for this long, so the
# client can tell a healthy-but-idle fleet from a dead socket (and so idle
# WebSockets aren't torn down by proxy idle timeouts, e.g. Traefik's 180s).
KEEPALIVE_S = 25

# The Vite dev server's origins (web/vite.config.ts proxies /ws without rewriting
# Origin). Allowed ONLY while dev-trust is genuinely active (local DB), never in prod.
DEV_ORIGINS = ("http://localhost:5173", "http://127.0.0.1:5173")


def _norm_origin(origin: str) -> str:
    return origin.strip().rstrip("/").lower()


def _origin_netloc(origin: str) -> str:
    """host[:port] of an Origin, lowercased; "" when it has none or does not parse."""
    try:
        return urlsplit(_norm_origin(origin)).netloc
    except ValueError:  # e.g. "http://[::1" (unterminated IPv6 literal)
        return ""


def origin_allowed(origin: str | None, host: str | None = None) -> bool:
    """SEC-19 cross-site WebSocket hijacking guard: is this handshake's Origin allowed?

    Prod: only settings.ws_allowed_origins (scheme + host + port, case-insensitive,
    trailing slash ignored); a missing Origin is refused, since every browser sends one.
    Dev-trust: also DEV_ORIGINS, a missing Origin, and a same-origin page, i.e. one whose
    Origin host:port is the request's own `host` header (the built bundle served by the
    local API, e.g. http://localhost:8000).
    """
    dev = dev_trust_active()
    if origin is None:
        return dev
    allowed = {_norm_origin(o) for o in settings.ws_allowed_origins}
    if dev:
        allowed.update(DEV_ORIGINS)
        if host and _origin_netloc(origin) == host.strip().lower():
            return True
    return _norm_origin(origin) in allowed


@router.websocket("/ws")
async def ws(sock: WebSocket):
    # SEC-19: Origin first, BEFORE accept(): a cross-site page gets no upgrade at all
    # (uvicorn answers a pre-accept close with an HTTP 403; the test client sees 4403).
    if not origin_allowed(sock.headers.get("origin"), sock.headers.get("host")):
        await sock.close(code=WS_FORBIDDEN)
        return
    # Same gate as /web/* (authorize: identity + viewer group; Authentik headers, proxy
    # secret, dev-trust), applied to the handshake headers BEFORE any data flows: the
    # snapshot + live samples include GPS coordinates. Accept-then-close(4401/4403) so
    # clients get a deterministic application close code rather than an opaque handshake
    # failure.
    try:
        authorize(sock.headers)
    except HTTPException as e:
        await sock.accept()
        await sock.close(code=WS_FORBIDDEN if e.status_code == 403 else WS_UNAUTHORIZED)
        return
    await sock.accept()
    pool = sock.app.state.pool
    bus = sock.app.state.bus
    # Subscribe BEFORE taking the snapshot (SRV-10): a sample ingested while the
    # snapshot query runs lands in the queue instead of being lost. Any overlap
    # (sample also visible in the snapshot) is harmless — the client's per-pack
    # ts guard ignores stale/duplicate frames.
    queue = bus.subscribe()
    try:
        async with pool.acquire() as conn:
            fleet = await q.fleet_snapshot(conn)
        await sock.send_json({"type": "snapshot", "fleet": jsonable(fleet)})
        while True:
            if bus.overflowed(queue):
                # Slow consumer: its queue overflowed and events were dropped.
                # Close instead of leaving it silently stale; the client
                # reconnects and re-snapshots.
                await sock.close(code=WS_OVERFLOW)
                break
            try:
                # The bus queue carries pre-serialized wire text (one json.dumps per
                # event at publish, byte-identical to send_json — see LiveBus.publish),
                # so fan-out to N subscribers no longer re-serializes N times.
                text = await asyncio.wait_for(queue.get(), timeout=KEEPALIVE_S)
            except asyncio.TimeoutError:
                await sock.send_json({"type": "ping"})
                continue
            await sock.send_text(text)
    except (WebSocketDisconnect, asyncio.CancelledError):
        pass
    finally:
        bus.unsubscribe(queue)
