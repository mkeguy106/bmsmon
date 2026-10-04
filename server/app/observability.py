"""Process-local operational signals (single worker, SRV-8): device-auth counters and the
small helpers behind the device-auth log lines and GET /api/v1/health/detail. A restart
starts the counters from zero; nothing here is persisted."""
import time
from collections import deque
from typing import Callable

# auth_fail_5m in /api/v1/health/detail.
AUTH_FAIL_WINDOW_S = 300
USER_AGENT_MAX_LEN = 200
# A forged token's iat can make the skew an absurd integer; store a bounded one so it can
# always be serialised (10 years, far past any real clock disagreement).
SKEW_CLAMP_S = 10 * 365 * 86400


def _clamp_skew(skew_s: int) -> int:
    return max(-SKEW_CLAMP_S, min(SKEW_CLAMP_S, int(skew_s)))


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
        self.last_failure = {"reason": reason, "at_ms": int(now * 1000), "skew_s": None if skew_s is None else _clamp_skew(skew_s)}

    def record_ok(self, skew_s: int) -> None:
        self.last_ok_skew_s = _clamp_skew(skew_s)

    def failures_in_window(self) -> int:
        self._prune(self._clock())
        return len(self._failures)

    def _prune(self, now: float) -> None:
        cutoff = now - AUTH_FAIL_WINDOW_S
        while self._failures and self._failures[0] <= cutoff:
            self._failures.popleft()
# The rollup runs every 15 min and trails now by at most ~50 min; 3 h means it stopped.
ROLLUP_LAG_MAX_S = 3 * 3600
# The server accepts a 600 s disagreement (auth/device_jwt.py); page well before that.
CLOCK_SKEW_MAX_S = 120


def evaluate_health(*, now_ms: int, last_ingest_ms: int | None, rollup_high_water_ms: int,
                    next_month_partition: bool, auth_fail_5m: int,
                    last_auth_fail: dict | None, last_ok_skew_s: int | None,
                    online_indexes: dict[str, bool], ingest_limit_s: int) -> dict:
    """The /api/v1/health/detail body. ok is False when any check fails. A missing signal
    (no upload ever, no rollup yet) is a failure, never a pass."""
    ingest_age = None if last_ingest_ms is None else max(0, (now_ms - last_ingest_ms) // 1000)
    rollup_lag = (None if rollup_high_water_ms <= 0
                  else max(0, (now_ms - rollup_high_water_ms) // 1000))
    failing: list[str] = []
    if ingest_age is None or ingest_age > ingest_limit_s:
        failing.append("ingest")
    if rollup_lag is None or rollup_lag > ROLLUP_LAG_MAX_S:
        failing.append("rollup")
    if not next_month_partition:
        failing.append("partition")
    if last_ok_skew_s is not None and abs(last_ok_skew_s) > CLOCK_SKEW_MAX_S:
        failing.append("clock")
    return {
        "ok": not failing,
        "failing": failing,
        "server_time_ms": now_ms,
        "last_ingest_ms": last_ingest_ms,
        "last_ingest_age_s": ingest_age,
        "auth_fail_5m": auth_fail_5m,
        "last_auth_fail": last_auth_fail,
        "last_ok_skew_s": last_ok_skew_s,
        "rollup_lag_s": rollup_lag,
        "next_month_partition": next_month_partition,
        "online_indexes": online_indexes,
        "limits": {"ingest_age_s": ingest_limit_s, "rollup_lag_s": ROLLUP_LAG_MAX_S,
                   "clock_skew_s": CLOCK_SKEW_MAX_S},
    }
