from datetime import datetime, timezone

from app.db import queries as q

A = "C8:47:80:15:67:44"
DEV = "00000000-0000-0000-0000-000000000001"


def test_trend_bucket_ms_thresholds():
    D = 86_400_000
    assert q.trend_bucket_ms(1 * D) == 1_800_000
    assert q.trend_bucket_ms(10 * D) == 21_600_000
    assert q.trend_bucket_ms(60 * D) == 86_400_000
    assert q.trend_bucket_ms(400 * D) == 604_800_000


async def _device_and_battery(conn):
    await conn.execute(
        "INSERT INTO devices (id, install_uuid, public_key_spki) VALUES ($1,$2,$3)",
        DEV, "uuid-trends-1", b"\x00",
    )
    base = int(datetime(2026, 7, 1, tzinfo=timezone.utc).timestamp() * 1000)
    await q.upsert_battery(conn, A, "R-12100", "2012 · A", "2012", base)
    return DEV, A


async def test_trend_series_aggregates(app):
    pool = app.state.pool
    async with pool.acquire() as conn:
        device_id, address = await _device_and_battery(conn)
        bucket = 86_400_000
        base = 100 * bucket
        rows = [
            q.sample_row(device_id, address, {"ts_ms": base + 1000, "soh": 99, "temp_c": 20.0,
                                              "cell_min_v": 3.30, "cell_max_v": 3.34}),
            q.sample_row(device_id, address, {"ts_ms": base + 2000, "soh": 97, "temp_c": 30.0,
                                              "cell_min_v": 3.31, "cell_max_v": 3.33}),
        ]
        await q.insert_samples(conn, rows)
        pts = await q.trend_series(conn, address, base, base + bucket, bucket)
        assert len(pts) == 1
        p = pts[0]
        assert p["bucket_ms"] == base
        assert round(p["soh"]) == 98
        assert round(p["cell_spread_mv"]) == 30   # avg of 40mV and 20mV
        assert p["temp_min"] == 20.0 and p["temp_max"] == 30.0
        assert await q.first_sample_ms(conn, address) == base + 1000
