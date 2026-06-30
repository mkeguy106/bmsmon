import asyncio
import uuid as _uuid

from fastapi import APIRouter, WebSocket, WebSocketDisconnect

from app.db import queries as q

router = APIRouter()


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
            event = await queue.get()
            await sock.send_json(event)
    except (WebSocketDisconnect, asyncio.CancelledError):
        pass
    finally:
        bus.unsubscribe(queue)
