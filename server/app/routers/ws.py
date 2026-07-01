import asyncio
import uuid as _uuid

from fastapi import APIRouter, WebSocket, WebSocketDisconnect

from app.db import queries as q

router = APIRouter()

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
