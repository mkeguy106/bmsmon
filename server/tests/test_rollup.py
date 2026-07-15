"""Equivalence tests for the 30-minute samples_rollup (SRV-14).

The routed history/trend queries (rollup rows below the high-water mark, raw rows
above it) must be numerically identical to the pure-raw computation on the same
data. `_pure_raw()` re-runs a query with the high-water mark zeroed inside a
rolled-back transaction, forcing the legacy raw path — that is the baseline for
every comparison.
"""
import time
from datetime import datetime, timezone

import pytest

from app.db import queries as q
from app.db import rollup as ru

A = "C8:47:80:15:67:44"
A2 = "C8:47:80:15:62:1B"
DEV = "00000000-0000-0000-0000-000000000001"
B = ru.ROLLUP_BUCKET_MS
USER = {"X-authentik-username": "joel",
        "X-authentik-groups": "Covert.life - Full App Access - User Group"}


async def _seed_registry(conn):
    await conn.execute(
        "INSERT INTO devices (id, install_uuid, public_key_spki) VALUES ($1,$2,$3)",
        DEV, "uuid-rollup-1", b"\x00")
    now = int(time.time() * 1000)
    await q.upsert_battery(conn, A, "R-12100", "2012 · A", "2012", now)
    await q.upsert_battery(conn, A2, "R-12100", "2012 · B", "2012", now)


def _mk_rows(addr, start_ms, count, step_ms):
    """Varied telemetry: per-metric NULLs, all-NULL rows, link events, unaligned ts."""
    rows = []
    for i in range(count):
        s = {"ts_ms": start_ms + i * step_ms + (i * 137) % 900}
        if i % 11 == 3:
            s["link_event"] = "Disconnected"   # excluded everywhere
        else:
            if i % 5 != 1:
                s["soc"] = 40.0 + (i * 13) % 55 + 0.25
            if i % 4 != 2:
                s["temp_c"] = 18.0 + (i % 9) * 1.5
            if i % 3 != 0:
                s["soh"] = 95 + (i % 5)
            if i % 6 != 4:
                s["cell_min_v"] = 3.301 + (i % 3) * 0.004
                s["cell_max_v"] = 3.334 + (i % 4) * 0.006
        rows.append(q.sample_row(DEV, addr, s))
    return rows


async def _pure_raw(conn, fn):
    """Run fn() with the rollup high-water mark forced to 0 (pure raw path), rolled back."""
    tx = conn.transaction()
    await tx.start()
    try:
        await conn.execute("DELETE FROM samples_rollup_state")
        return await fn()
    finally:
        await tx.rollback()


def _eq(a, b):
    assert (a is None) == (b is None), (a, b)
    if a is not None:
        assert a == pytest.approx(b, rel=1e-5, abs=1e-3), (a, b)


def _cmp_hist(raw, routed):
    assert [(r["address"], r["bucket_ms"]) for r in routed] == \
           [(r["address"], r["bucket_ms"]) for r in raw]
    for x, y in zip(raw, routed):
        _eq(x["soc"], y["soc"])


def _cmp_trend(raw, routed):
    assert [r["bucket_ms"] for r in routed] == [r["bucket_ms"] for r in raw]
    for x, y in zip(raw, routed):
        for k in ("soh", "cell_spread_mv", "temp_avg", "temp_min", "temp_max"):
            _eq(x[k], y[k])


async def test_history_and_trend_equivalence_across_high_water(app):
    pool = app.state.pool
    async with pool.acquire() as conn:
        await _seed_registry(conn)
        now = int(time.time() * 1000)
        # Pack A: ~36 h of data ending ~100 s ago (spans the mark: closed buckets roll
        # up, the tail stays raw). Pack A2 stops 24 h ago — a sparse pack must neither
        # stall the global mark nor lose its history.
        await q.insert_samples(conn, _mk_rows(A, now - 36 * 3_600_000, 260, 500_000))
        await q.insert_samples(conn, _mk_rows(A2, now - 40 * 3_600_000, 100, 576_000))

        since = now - 30 * 3_600_000 + 12_345  # deliberately bucket-unaligned
        raw_hist = await q.history_series(conn, since)   # high-water 0 -> pure raw
        assert await ru.get_high_water_ms(conn) == 0

        upserted = await ru.run_rollup_pass(conn, now_ms=now)
        assert upserted > 0
        hw = await ru.get_high_water_ms(conn)
        assert hw == ((now - ru.ROLLUP_SAFETY_LAG_MS) // B) * B

        routed_hist = await q.history_series(conn, since)
        # the comparison must actually exercise both sides of the mark
        assert any(r["bucket_ms"] < hw for r in routed_hist)
        assert any(r["bucket_ms"] >= hw for r in routed_hist)
        assert {r["address"] for r in routed_hist} == {A, A2}
        _cmp_hist(raw_hist, routed_hist)

        # trends at 30-min and 6-h buckets over an unaligned window spanning the mark
        for bucket in (1_800_000, 21_600_000):
            f, t = now - 34 * 3_600_000 + 777, now - 60_000
            raw_tr = await _pure_raw(conn, lambda: q.trend_series(conn, A, f, t, bucket))
            routed_tr = await q.trend_series(conn, A, f, t, bucket)
            assert routed_tr, f"no trend rows at bucket {bucket}"
            _cmp_trend(raw_tr, routed_tr)


async def test_trend_non_multiple_bucket_stays_raw_and_correct(app):
    """A bucket that isn't a multiple of 30 min can't be served from the rollup —
    trend_bucket_ms never returns one, but the routing guard must fall back to raw."""
    pool = app.state.pool
    async with pool.acquire() as conn:
        await _seed_registry(conn)
        now = int(time.time() * 1000)
        await q.insert_samples(conn, _mk_rows(A, now - 6 * 3_600_000, 50, 300_000))
        await ru.run_rollup_pass(conn, now_ms=now)
        f, t = now - 6 * 3_600_000, now
        raw = await _pure_raw(conn, lambda: q.trend_series(conn, A, f, t, 900_000))
        routed = await q.trend_series(conn, A, f, t, 900_000)
    assert routed == raw  # same path, exact equality


async def test_rollup_pass_idempotent(app):
    pool = app.state.pool
    async with pool.acquire() as conn:
        await _seed_registry(conn)
        now = int(time.time() * 1000)
        await q.insert_samples(conn, _mk_rows(A, now - 10 * 3_600_000, 60, 400_000))
        n1 = await ru.run_rollup_pass(conn, now_ms=now)
        assert n1 > 0
        rows1 = [tuple(r) for r in await conn.fetch(
            "SELECT * FROM samples_rollup ORDER BY address, bucket_ms")]
        hw1 = await ru.get_high_water_ms(conn)
        await ru.run_rollup_pass(conn, now_ms=now)  # re-rolls the trailing window
        rows2 = [tuple(r) for r in await conn.fetch(
            "SELECT * FROM samples_rollup ORDER BY address, bucket_ms")]
        hw2 = await ru.get_high_water_ms(conn)
    assert rows2 == rows1
    assert hw2 == hw1  # unchanged for the same now


async def test_late_arrival_is_rerolled(app):
    """A sample landing in an already-rolled bucket (offline outbox) is stale in the
    rollup until the next pass, then the trailing re-roll folds it in exactly."""
    pool = app.state.pool
    async with pool.acquire() as conn:
        await _seed_registry(conn)
        now = int(time.time() * 1000)
        bucket = ((now - 3 * 3_600_000) // B) * B
        await q.insert_samples(conn, [q.sample_row(DEV, A, {"ts_ms": bucket + 1000, "soc": 80.0})])
        await ru.run_rollup_pass(conn, now_ms=now)
        hist = await q.history_series(conn, since_ms=bucket)
        assert [round(r["soc"]) for r in hist] == [80]

        # late arrival, same closed bucket
        await q.insert_samples(conn, [q.sample_row(DEV, A, {"ts_ms": bucket + 61_000, "soc": 90.0})])
        stale = await q.history_series(conn, since_ms=bucket)
        assert [round(r["soc"]) for r in stale] == [80]  # documented staleness window

        await ru.run_rollup_pass(conn, now_ms=now + 60_000)
        fresh = await q.history_series(conn, since_ms=bucket)
        assert [round(r["soc"]) for r in fresh] == [85]
        raw = await _pure_raw(conn, lambda: q.history_series(conn, since_ms=bucket))
        _cmp_hist(raw, fresh)


async def test_backfill_spans_months_in_chunks(app):
    """First run on an existing DB backfills everything from raw, chunked by month."""
    pool = app.state.pool
    async with pool.acquire() as conn:
        await _seed_registry(conn)
        may = int(datetime(2026, 5, 15, tzinfo=timezone.utc).timestamp() * 1000)
        jun = int(datetime(2026, 6, 10, tzinfo=timezone.utc).timestamp() * 1000)
        await q.insert_samples(conn, _mk_rows(A, may, 40, 700_000))
        await q.insert_samples(conn, _mk_rows(A, jun, 40, 700_000))
        now = int(time.time() * 1000)
        n = await ru.run_rollup_pass(conn, now_ms=now)
        assert n > 0
        assert await ru.get_high_water_ms(conn) == ((now - ru.ROLLUP_SAFETY_LAG_MS) // B) * B
        months = await conn.fetch(
            """SELECT DISTINCT date_trunc('month', to_timestamp(bucket_ms / 1000.0) AT TIME ZONE 'UTC') AS m
               FROM samples_rollup ORDER BY m""")
        assert len(months) == 2
        # a fully-historical window is served entirely from the rollup — still identical
        f, t = may - 3_600_000, jun + 40 * 700_000 + 3_600_000
        bucket = 86_400_000
        raw = await _pure_raw(conn, lambda: q.trend_series(conn, A, f, t, bucket))
        routed = await q.trend_series(conn, A, f, t, bucket)
        _cmp_trend(raw, routed)


def test_month_chunks_split_at_utc_month_starts():
    lo = int(datetime(2026, 5, 20, 13, 30, tzinfo=timezone.utc).timestamp() * 1000)
    hi = int(datetime(2026, 7, 2, 1, 0, tzinfo=timezone.utc).timestamp() * 1000)
    chunks = ru._month_chunks(lo, hi)
    assert chunks[0][0] == lo and chunks[-1][1] == hi
    for (_, b1), (a2, _) in zip(chunks, chunks[1:]):
        assert b1 == a2
    jun = int(datetime(2026, 6, 1, tzinfo=timezone.utc).timestamp() * 1000)
    jul = int(datetime(2026, 7, 1, tzinfo=timezone.utc).timestamp() * 1000)
    assert [c[1] for c in chunks] == [jun, jul, hi]
    # boundaries stay 30-min-bucket aligned
    assert all(a % ru.ROLLUP_BUCKET_MS == 0 for _, a in chunks[:-1])


async def test_empty_db_pass_is_noop_and_ingest_never_writes_rollup(app):
    pool = app.state.pool
    async with pool.acquire() as conn:
        assert await ru.run_rollup_pass(conn) == 0
        assert await ru.get_high_water_ms(conn) == 0  # backfill starts at true first sample
        await _seed_registry(conn)
        now = int(time.time() * 1000)
        await q.insert_samples(conn, _mk_rows(A, now - 3_600_000, 5, 60_000))
        # ingest path must NOT write the rollup synchronously
        assert await conn.fetchval("SELECT count(*) FROM samples_rollup") == 0


async def test_link_event_only_pack_yields_no_rows_either_path(app):
    pool = app.state.pool
    async with pool.acquire() as conn:
        await _seed_registry(conn)
        now = int(time.time() * 1000)
        rows = [q.sample_row(DEV, A2, {"ts_ms": now - i * 3_600_000, "link_event": "Connected"})
                for i in range(1, 4)]
        await q.insert_samples(conn, rows)
        await ru.run_rollup_pass(conn, now_ms=now)
        hist = await q.history_series(conn, since_ms=now - 5 * 3_600_000)
        tr = await q.trend_series(conn, A2, now - 5 * 3_600_000, now, 1_800_000)
    assert hist == [] and tr == []


async def test_main_run_rollup_wrapper(app):
    from app.main import run_rollup
    assert await run_rollup(app.state.pool) == 0


async def test_web_routes_shapes_span_high_water(app, client):
    """Route sanity: history/trends/charge-sessions shapes unchanged with data
    spanning the high-water mark (rollup + raw tail)."""
    pool = app.state.pool
    now = int(time.time() * 1000)
    async with pool.acquire() as conn:
        await _seed_registry(conn)
        await q.insert_samples(conn, _mk_rows(A, now - 12 * 3_600_000, 80, 500_000))
        # a full charge session: 2 h ramp to 100 % at 1-min steps, charging current
        ramp = []
        start = now - 2 * 3_600_000
        for i in range(115):
            soc = min(100.0, 60.0 + i * 0.4)
            ramp.append(q.sample_row(DEV, A, {
                "ts_ms": start + i * 60_000, "soc": soc, "current_a": 5.0,
                "temp_c": 25.0 + (i % 5)}))
        await q.insert_samples(conn, ramp)
        await ru.run_rollup_pass(conn, now_ms=now)
        assert await ru.get_high_water_ms(conn) > 0

    r = await client.get("/web/history", headers=USER, params={"hours": 24})
    assert r.status_code == 200
    series = r.json()["series"]
    assert series and set(series[0].keys()) == {"address", "points"}
    assert set(series[0]["points"][0].keys()) == {"t", "soc"}

    r = await client.get("/web/trends", headers=USER,
                         params={"address": A, "from_ms": now - 12 * 3_600_000, "to_ms": now})
    assert r.status_code == 200
    body = r.json()
    assert set(body.keys()) == {"address", "bucket_ms", "first_ms", "points"}
    assert body["points"] and set(body["points"][0].keys()) == \
        {"t", "soh", "cell_spread_mv", "temp_avg", "temp_min", "temp_max"}

    r = await client.get("/web/charge-sessions", headers=USER,
                         params={"address": A, "days": 7})
    assert r.status_code == 200
    sessions = r.json()["sessions"]
    assert len(sessions) == 1
    assert sessions[0]["from_soc"] == 60
