import asyncio
import uuid as _uuid

from fastapi import APIRouter, WebSocket, WebSocketDisconnect

from app.auth.authentik import resolve_user
from app.db import queries as q

router = APIRouter()

# WebSocket close code for "not authenticated" (4000-4999 = application-defined;
# 4401 mirrors HTTP 401 by convention).
WS_UNAUTHORIZED = 4401

# Push a keepalive frame when no telemetry has flowed for this long, so the
# client can tell a healthy-but-idle fleet from a dead socket (and so idle
# WebSockets aren't torn down by proxy idle timeouts, e.g. Traefik's 180s).
KEEPALIVE_S = 25


def _jsonable(rows: list[dict]) -> list[dict]:
    out = []
    for r in rows:
        d = dict(r)
        for k, v in list(d.items()):
            if hasattr(v, "isoformat"):
                d[k] = v.isoformat()
            elif isinstance(v, _uuid.UUID):
                d[k] = str(v)
        out.append(d)
    return out


@router.websocket("/ws")
async def ws(sock: WebSocket):
    # Same identity resolution as /web/* (Authentik headers, proxy secret, dev-trust),
    # applied to the handshake headers BEFORE any data flows: the snapshot + live
    # samples include GPS coordinates. Accept-then-close(4401) so clients get a
    # deterministic application close code rather than an opaque handshake failure.
    if resolve_user(sock.headers) is None:
        await sock.accept()
        await sock.close(code=WS_UNAUTHORIZED)
        return
    await sock.accept()
    pool = sock.app.state.pool
    bus = sock.app.state.bus
    async with pool.acquire() as conn:
        fleet = await q.fleet_snapshot(conn)
    await sock.send_json({"type": "snapshot", "fleet": _jsonable(fleet)})
    queue = bus.subscribe()
    try:
        while True:
            try:
                event = await asyncio.wait_for(queue.get(), timeout=KEEPALIVE_S)
            except asyncio.TimeoutError:
                await sock.send_json({"type": "ping"})
                continue
            await sock.send_json(_jsonable([event])[0])
    except (WebSocketDisconnect, asyncio.CancelledError):
        pass
    finally:
        bus.unsubscribe(queue)
