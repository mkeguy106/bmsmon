"""Unit tests for the process-local TtlCache / TouchThrottle helpers (pure logic,
fake clock — no app or DB)."""

from app.caching import TouchThrottle, TtlCache


class Clock:
    def __init__(self, t: float = 0.0) -> None:
        self.t = t

    def __call__(self) -> float:
        return self.t


def test_ttl_cache_hit_within_ttl():
    clk = Clock()
    c = TtlCache(ttl_s=10.0, clock=clk)
    c.put("day1", [1, 2, 3])
    clk.t = 9.9
    assert c.get("day1") == [1, 2, 3]


def test_ttl_cache_miss_after_expiry():
    clk = Clock()
    c = TtlCache(ttl_s=10.0, clock=clk)
    c.put("day1", "v")
    clk.t = 10.0  # boundary: exactly ttl_s old = expired
    assert c.get("day1") is None


def test_ttl_cache_key_change_is_miss():
    clk = Clock()
    c = TtlCache(ttl_s=10.0, clock=clk)
    c.put("day1", "v")
    assert c.get("day2") is None  # new day window = new key = fresh query


def test_ttl_cache_put_evicts_expired_keys():
    clk = Clock()
    c = TtlCache(ttl_s=10.0, clock=clk)
    c.put("day1", "old")
    clk.t = 100.0
    c.put("day2", "new")
    assert c._entries.keys() == {"day2"}  # yesterday's window never accumulates


def test_ttl_cache_clear():
    c = TtlCache(ttl_s=10.0, clock=Clock())
    c.put("k", "v")
    c.clear()
    assert c.get("k") is None


def test_touch_throttled_within_window():
    clk = Clock()
    t = TouchThrottle(interval_s=60.0, clock=clk)
    assert t.should_touch(1) is True
    clk.t = 59.9
    assert t.should_touch(1) is False


def test_touch_allowed_after_window():
    clk = Clock()
    t = TouchThrottle(interval_s=60.0, clock=clk)
    assert t.should_touch(1) is True
    clk.t = 60.0
    assert t.should_touch(1) is True


def test_touch_keys_are_independent():
    clk = Clock()
    t = TouchThrottle(interval_s=60.0, clock=clk)
    assert t.should_touch("share-1") is True
    assert t.should_touch("share-2") is True
    assert t.should_touch("share-1") is False


def test_touch_prunes_lapsed_keys():
    clk = Clock()
    t = TouchThrottle(interval_s=60.0, clock=clk)
    t.should_touch("old")
    clk.t = 120.0
    t.should_touch("new")
    assert t._last.keys() == {"new"}  # revoked shares / dead devices don't accumulate
