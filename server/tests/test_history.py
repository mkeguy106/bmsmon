from datetime import datetime, timezone

from app.db import queries as q

A = "C8:47:80:15:67:44"
DEV = "00000000-0000-0000-0000-000000000001"
BUCKET = 1_800_000


async def _device(conn):
    await conn.execute(
        "INSERT INTO devices (id, install_uuid, public_key_spki) VALUES ($1,$2,$3)",
        DEV, "uuid-history-1", b"\x00",
    )


async def _seed(conn):
    await _device(conn)
    base = int(datetime(2026, 7, 1, tzinfo=timezone.utc).timestamp() * 1000)
    await q.upsert_battery(conn, A, "R-12100", "2012 · A", "2012", base)
    return DEV, A


async def test_history_buckets_and_averages(app):
    pool = app.state.pool
    async with pool.acquire() as conn:
        device_id, address = await _seed(conn)
        base = 10 * BUCKET  # a clean bucket boundary
        rows = [
            q.sample_row(device_id, address, {"ts_ms": base + 60_000, "soc": 80.0}),
            q.sample_row(device_id, address, {"ts_ms": base + 120_000, "soc": 90.0}),  # same bucket -> avg 85
            q.sample_row(device_id, address, {"ts_ms": base + BUCKET + 60_000, "soc": 70.0}),  # next bucket
        ]
        assert await q.insert_samples(conn, rows) == 3
        series = await q.history_series(conn, since_ms=base)
    pts = [(r["bucket_ms"], round(r["soc"])) for r in series if r["address"] == address]
    assert pts == [(base, 85), (base + BUCKET, 70)]


async def test_history_excludes_link_events(app):
    pool = app.state.pool
    async with pool.acquire() as conn:
        device_id, address = await _seed(conn)
        base = 20 * BUCKET
        rows = [
            q.sample_row(device_id, address, {"ts_ms": base + 1000, "soc": 50.0}),
            q.sample_row(device_id, address, {"ts_ms": base + 2000, "soc": None, "link_event": "Connected"}),
        ]
        await q.insert_samples(conn, rows)
        series = await q.history_series(conn, since_ms=base)
    assert [round(r["soc"]) for r in series if r["address"] == address] == [50]
