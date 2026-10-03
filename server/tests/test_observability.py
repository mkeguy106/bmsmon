"""Pure helpers behind the device-auth log lines and /api/v1/health/detail."""
from app.observability import AUTH_FAIL_WINDOW_S, AuthStats, clean_user_agent


class Clock:
    def __init__(self, t: float = 1000.0) -> None:
        self.t = t

    def __call__(self) -> float:
        return self.t


def test_failures_count_within_the_window_only():
    clk = Clock()
    s = AuthStats(clock=clk)
    s.record_failure("clock_skew", -585)
    clk.t += 10
    s.record_failure("bad_signature", None)
    assert s.failures_in_window() == 2
    clk.t += AUTH_FAIL_WINDOW_S - 5     # the first is now 305 s old, the second 295 s
    assert s.failures_in_window() == 1
    assert s.last_failure == {"reason": "bad_signature", "at_ms": 1_010_000, "skew_s": None}


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
