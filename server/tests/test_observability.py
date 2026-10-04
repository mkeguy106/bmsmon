"""Pure helpers behind the device-auth log lines and /api/v1/health/detail."""
from app.observability import (
    AUTH_FAIL_MAX_PER_DEVICE,
    AUTH_FAIL_MAX_TOTAL,
    AUTH_FAIL_WINDOW_S,
    AuthStats,
    clean_user_agent,
)


class Clock:
    def __init__(self, t: float = 1000.0) -> None:
        self.t = t

    def __call__(self) -> float:
        return self.t


def test_failures_count_within_the_window_only():
    clk = Clock()
    s = AuthStats(clock=clk)
    s.record_failure("dev-a", "clock_skew", -585)
    clk.t += 10
    s.record_failure("dev-b", "bad_signature", None)
    assert s.failures_in_window() == 2
    clk.t += AUTH_FAIL_WINDOW_S - 5     # the first is now 305 s old, the second 295 s
    assert s.failures_in_window() == 1
    assert s.last_failure == {"reason": "bad_signature", "at_ms": 1_010_000, "skew_s": None}


def test_failures_are_capped_per_device_dropping_the_oldest():
    clk = Clock()
    s = AuthStats(clock=clk)
    for _ in range(AUTH_FAIL_MAX_PER_DEVICE + 50):
        s.record_failure("dev-a", "bad_signature", None)
        clk.t += 1
    s.record_failure("dev-b", "bad_signature", None)
    assert s.failures_in_window() == AUTH_FAIL_MAX_PER_DEVICE + 1
    # the 100 kept for dev-a are the newest: ageing out the dropped 50 changes nothing
    clk.t = 1000.0 + 49 + AUTH_FAIL_WINDOW_S
    assert s.failures_in_window() == AUTH_FAIL_MAX_PER_DEVICE + 1
    clk.t += 1  # now the oldest kept one (t = 1050) leaves the window
    assert s.failures_in_window() == AUTH_FAIL_MAX_PER_DEVICE


def test_failures_are_capped_in_all_dropping_the_oldest():
    clk = Clock()
    s = AuthStats(clock=clk)
    devices = AUTH_FAIL_MAX_TOTAL // AUTH_FAIL_MAX_PER_DEVICE + 1
    for i in range(devices):
        for _ in range(AUTH_FAIL_MAX_PER_DEVICE):
            s.record_failure(f"dev-{i}", "bad_signature", None)
        clk.t += 1
    assert s.failures_in_window() == AUTH_FAIL_MAX_TOTAL
    assert "dev-0" not in s._failures  # the oldest device's failures went first
    assert len(s._failures[f"dev-{devices - 1}"]) == AUTH_FAIL_MAX_PER_DEVICE


def test_last_ok_skew_is_remembered():
    s = AuthStats(clock=Clock())
    assert s.last_ok_skew_s is None
    s.record_ok(3)
    s.record_ok(-2)
    assert s.last_ok_skew_s == -2


def test_clean_user_agent():
    assert clean_user_agent(None) is None
    assert clean_user_agent("   ") is None
    assert clean_user_agent("bmsmon-android/1.4 (42; abc1234)") == "bmsmon-android/1.4 (42; abc1234)"
    assert clean_user_agent("ua\x00\x07é\nx") == "uax"
    assert len(clean_user_agent("a" * 500)) == 200
    # a cut that lands on a space leaves no trailing whitespace
    assert clean_user_agent("a" * 199 + " " + "b" * 50) == "a" * 199
