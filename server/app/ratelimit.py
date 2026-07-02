"""Small in-process rate limiter for the unauthenticated /api/v1/enroll endpoint (SEC-4).

Sliding-window over a deque of timestamps per client key. PROCESS-LOCAL by design:
the server runs single-worker (see the JtiCache/SRV-8 note in auth/device_jwt.py and
the Dockerfile CMD) so one process sees all traffic; running --workers >1 would
multiply the effective limit by the worker count. An instance lives on
app.state.enroll_limiter (created per app in create_app), so each test app gets a
fresh window. Memory is bounded: empty deques are pruned on every allow() call.
"""

import time
from collections import deque

from starlette.datastructures import Headers

from app.auth.authentik import proxy_secret_ok
from app.config import settings

# Enrollment is a rare, human-driven action (mint code in the webui, type it into the
# phone) — 10 attempts / 5 min per IP is generous for legit use and useless for
# code brute-forcing (codes are single-use and expire in 10 min anyway).
ENROLL_MAX_ATTEMPTS = 10
ENROLL_WINDOW_S = 300


class RateLimiter:
    """allow(key) -> False once `max_attempts` calls landed within `window_s`."""

    def __init__(self, max_attempts: int = ENROLL_MAX_ATTEMPTS,
                 window_s: float = ENROLL_WINDOW_S, clock=time.monotonic) -> None:
        self.max_attempts = max_attempts
        self.window_s = window_s
        self._clock = clock
        self._hits: dict[str, deque[float]] = {}

    def allow(self, key: str) -> bool:
        now = self._clock()
        cutoff = now - self.window_s
        # Prune expired hits everywhere so idle keys don't accumulate forever.
        for k in list(self._hits):
            dq = self._hits[k]
            while dq and dq[0] <= cutoff:
                dq.popleft()
            if not dq:
                del self._hits[k]
        dq = self._hits.setdefault(key, deque())
        if len(dq) >= self.max_attempts:
            return False
        dq.append(now)
        return True


def client_key(client_host: str | None, headers: Headers) -> str:
    """Rate-limit key for a request: the direct peer IP, EXCEPT when the request
    carries the valid proxy shared secret (X-Bmsmon-Proxy-Secret matching
    BMSMON_PROXY_SECRET) — then the first hop of X-Forwarded-For is trusted, since
    Traefik injects/overwrites both headers. Without the secret configured (or on a
    direct-to-container request), XFF is attacker-controlled and ignored.
    """
    if settings.proxy_secret and proxy_secret_ok(headers):
        xff = headers.get("x-forwarded-for", "")
        first = xff.split(",")[0].strip()
        if first:
            return first
    return client_host or "unknown"
