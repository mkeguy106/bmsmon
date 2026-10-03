"""Process-local operational signals (single worker, SRV-8): device-auth counters and the
small helpers behind the device-auth log lines and GET /api/v1/health/detail. A restart
starts the counters from zero; nothing here is persisted."""
import time
from collections import deque
from typing import Callable

# auth_fail_5m in /api/v1/health/detail.
AUTH_FAIL_WINDOW_S = 300
USER_AGENT_MAX_LEN = 200


def clean_user_agent(raw: str | None) -> str | None:
    """A User-Agent fit to log and store: printable ASCII only (0x20-0x7E), at most
    USER_AGENT_MAX_LEN characters, None when nothing is left."""
    if not raw:
        return None
    s = "".join(ch for ch in raw if " " <= ch <= "~").strip()[:USER_AGENT_MAX_LEN]
    return s or None


class AuthStats:
    """Device-auth outcomes for /api/v1/health/detail. Only KNOWN devices are recorded:
    anyone can present a well-formed token for a random device id, so counting those would
    let a stranger move the numbers (routers/api_device.py decides what is recorded)."""

    def __init__(self, clock: Callable[[], float] = time.time) -> None:
        self._clock = clock
        self._failures: deque[float] = deque()
        self.last_failure: dict | None = None
        self.last_ok_skew_s: int | None = None

    def record_failure(self, reason: str, skew_s: int | None) -> None:
        now = self._clock()
        self._failures.append(now)
        self._prune(now)
        self.last_failure = {"reason": reason, "at_ms": int(now * 1000), "skew_s": skew_s}

    def record_ok(self, skew_s: int) -> None:
        self.last_ok_skew_s = int(skew_s)

    def failures_in_window(self) -> int:
        self._prune(self._clock())
        return len(self._failures)

    def _prune(self, now: float) -> None:
        cutoff = now - AUTH_FAIL_WINDOW_S
        while self._failures and self._failures[0] <= cutoff:
            self._failures.popleft()
