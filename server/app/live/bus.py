import asyncio

# Per-subscriber buffer. A browser only needs to absorb short stalls (GC, tab
# switch); a client that falls this many events behind is closed and re-snapshots.
QUEUE_MAX = 1000


class LiveBus:
    """In-process fan-out of ingested samples to /ws subscribers.

    SINGLE-WORKER CONSTRAINT (SRV-8): this bus is process-local. Running uvicorn
    with --workers >1 silently breaks it — publishers and subscribers land in
    different processes and WS clients miss every sample ingested elsewhere.
    Keep the server single-process (see the Dockerfile CMD note) or move this
    to a shared broker first.
    """

    def __init__(self) -> None:
        # queue -> overflowed flag. On a full queue we stop delivering and mark
        # the subscriber; the WS loop notices and CLOSES that socket so the
        # client's reconnect logic fetches a fresh snapshot, instead of the old
        # silent-drop behavior that left a slow client permanently stale (SRV-10).
        self._subs: dict[asyncio.Queue, bool] = {}

    def subscribe(self) -> asyncio.Queue:
        q: asyncio.Queue = asyncio.Queue(maxsize=QUEUE_MAX)
        self._subs[q] = False
        return q

    def unsubscribe(self, q: asyncio.Queue) -> None:
        self._subs.pop(q, None)

    def overflowed(self, q: asyncio.Queue) -> bool:
        return self._subs.get(q, False)

    async def publish(self, event: dict) -> None:
        for q in list(self._subs):
            try:
                q.put_nowait(event)
            except asyncio.QueueFull:
                self._subs[q] = True  # slow consumer: the WS loop closes the socket
