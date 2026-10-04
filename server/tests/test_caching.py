"""Unit tests for the process-local TtlCache / TouchThrottle helpers (pure logic,
fake clock — no app or DB)."""

import asyncio

import pytest

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


async def test_get_or_compute_runs_one_computation_for_concurrent_misses():
    cache = TtlCache(ttl_s=10.0, clock=Clock())
    calls = 0
    gate = asyncio.Event()

    async def compute():
        nonlocal calls
        calls += 1
        await gate.wait()
        return [1]

    t1 = asyncio.create_task(cache.get_or_compute("k", compute))
    t2 = asyncio.create_task(cache.get_or_compute("k", compute))
    await asyncio.sleep(0)
    gate.set()
    assert await t1 == [1] and await t2 == [1]
    assert calls == 1
    assert await cache.get_or_compute("k", compute) == [1] and calls == 1  # now cached


async def test_get_or_compute_failure_reaches_every_waiter_and_caches_nothing():
    cache = TtlCache(ttl_s=10.0, clock=Clock())
    gate = asyncio.Event()

    async def fail():
        await gate.wait()
        raise RuntimeError("db down")

    t1 = asyncio.create_task(cache.get_or_compute("k", fail))
    t2 = asyncio.create_task(cache.get_or_compute("k", fail))
    await asyncio.sleep(0)
    gate.set()
    for t in (t1, t2):
        with pytest.raises(RuntimeError):
            await t

    async def ok():
        return "v"

    assert await cache.get_or_compute("k", ok) == "v"


async def test_a_cancelled_caller_does_not_cancel_the_shared_computation():
    cache = TtlCache(ttl_s=10.0, clock=Clock())
    gate = asyncio.Event()

    async def compute():
        await gate.wait()
        return "v"

    t1 = asyncio.create_task(cache.get_or_compute("k", compute))
    t2 = asyncio.create_task(cache.get_or_compute("k", compute))
    await asyncio.sleep(0)
    t1.cancel()  # that guest's connection went away
    gate.set()
    assert await t2 == "v"
    with pytest.raises(asyncio.CancelledError):
        await t1


async def test_get_or_compute_keys_are_independent():
    cache = TtlCache(ttl_s=10.0, clock=Clock())

    async def a():
        return "a"

    async def b():
        return "b"

    assert await asyncio.gather(cache.get_or_compute("x", a), cache.get_or_compute("y", b)) == ["a", "b"]
