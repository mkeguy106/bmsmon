import asyncio

from fastapi import APIRouter, WebSocket, WebSocketDisconnect

from app.auth.authentik import resolve_user
from app.db import queries as q
from app.util import jsonable

router = APIRouter()

# WebSocket close codes (4000-4999 = application-defined).
# 4401 mirrors HTTP 401 by convention.
WS_UNAUTHORIZED = 4401
# Sent to a slow consumer whose event queue overflowed: the client is behind and
# would otherwise be permanently stale (SRV-10). The browser's reconnect logic
# re-opens the socket and gets a fresh snapshot.
WS_OVERFLOW = 4408

# Push a keepalive frame when no telemetry has flowed for this long, so the
# client can tell a healthy-but-idle fleet from a dead socket (and so idle
# WebSockets aren't torn down by proxy idle timeouts, e.g. Traefik's 180s).
KEEPALIVE_S = 25


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
