"""Tiny process-local caching/throttling helpers for hot paths.

PROCESS-LOCAL by design — same single-worker constraint as JtiCache/RateLimiter
(SRV-8): one uvicorn process sees all traffic, so a module-free instance held on
app.state is both correct and test-isolated (each create_app() gets fresh state).

Used for:
- the public share feed's fleet GPS-track query (all guests polling within the TTL
  collapse onto one DB query), and
- write-throttling the "touch" UPDATEs (location_shares.last_access / devices.
  last_seen_at) that otherwise churn a dead tuple per request/batch.
"""

import asyncio
import time
from typing import Any, Awaitable, Callable, Hashable


class TtlCache:
    """Minimal TTL cache: get() returns None once the entry is older than ttl_s.

    put() evicts every expired entry, so a cache whose keys rotate (e.g. the share
    feed's per-day window key) stays at ~1 live entry and never grows across days.
    """

    def __init__(self, ttl_s: float, clock: Callable[[], float] = time.monotonic) -> None:
        self.ttl_s = ttl_s
        self._clock = clock
        self._entries: dict[Hashable, tuple[float, Any]] = {}  # key -> (expires, value)
        self._inflight: dict[Hashable, asyncio.Future] = {}

    def get(self, key: Hashable) -> Any | None:
        entry = self._entries.get(key)
        if entry is None:
            return None
        if self._clock() >= entry[0]:
            del self._entries[key]
            return None
        return entry[1]

    def put(self, key: Hashable, value: Any) -> None:
        now = self._clock()
        for k in [k for k, (exp, _) in self._entries.items() if now >= exp]:
            del self._entries[k]
        self._entries[key] = (now + self.ttl_s, value)

    async def get_or_compute(self, key: Hashable, compute: Callable[[], Awaitable[Any]]) -> Any:
        """The cached value for key, else ONE shared computation of it (SRV-26
        single-flight): misses that arrive while it runs await the same task instead of
        each running the query. A cancelled caller (its client went away) does not cancel
        the shared computation. A failure reaches every waiter and caches nothing, so the
        next call recomputes. The value must not be None (None means "miss" here)."""
        hit = self.get(key)
        if hit is not None:
            return hit
        task = self._inflight.get(key)
        if task is None:
            task = asyncio.ensure_future(compute())
            self._inflight[key] = task

            def _settle(t: asyncio.Future, key: Hashable = key) -> None:
                # Registered before any waiter's shield, so it runs first: the value is
                # cached by the time a waiter resumes.
                self._inflight.pop(key, None)
                if not t.cancelled() and t.exception() is None:
                    self.put(key, t.result())

            task.add_done_callback(_settle)
        return await asyncio.shield(task)

    def clear(self) -> None:
        self._entries.clear()


class TouchThrottle:
    """should_touch(key) -> True at most once per interval_s per key.

    Gates bookkeeping UPDATEs (share last_access, device last_seen_at) that are pure
    dead-tuple churn at request rate. Callers accept that the throttled counter or
    timestamp becomes approximate within one interval. Keys whose window has lapsed
    are pruned on every allowed call, so the map stays bounded by the number of keys
    active within the last interval.
    """

    def __init__(self, interval_s: float, clock: Callable[[], float] = time.monotonic) -> None:
        self.interval_s = interval_s
        self._clock = clock
        self._last: dict[Hashable, float] = {}

    def should_touch(self, key: Hashable) -> bool:
        now = self._clock()
        last = self._last.get(key)
        if last is not None and now - last < self.interval_s:
            return False
        for k in [k for k, t in self._last.items() if now - t >= self.interval_s]:
            del self._last[k]
        self._last[key] = now
        return True

    def clear(self) -> None:
        self._last.clear()
