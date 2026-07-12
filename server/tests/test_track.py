from datetime import datetime, timezone

from app.db import queries as q

A = "C8:47:80:15:67:44"
DEV = "00000000-0000-0000-0000-000000000001"


def _sample(ts_ms, **overrides):
    base = {"ts_ms": ts_ms, "state": "Discharging", "soc": 88.0, "current_a": -4.0,
            "power_w": -51.0, "voltage_v": 13.2, "temp_c": 24.0, "soh": 99,
            "full_charge_ah": 100.0, "remaining_ah": 88.0, "cycles": 12,
            "cell_min_v": 3.31, "cell_max_v": 3.34}
    base.update(overrides)
    return base


async def _device(conn):
    await conn.execute(
        "INSERT INTO devices (id, install_uuid, public_key_spki) VALUES ($1,$2,$3)",
        DEV, "uuid-track-1", b"\x00",
    )


async def test_track_series_buckets_and_filters_gps(app):
    pool = app.state.pool
    async with pool.acquire() as conn:
        await _device(conn)
        bucket = 15_000
        base = int(datetime(2026, 7, 1, tzinfo=timezone.utc).timestamp() * 1000)
        base = (base // bucket) * bucket
        await q.upsert_battery(conn, A, "R-12100", "2012 · A", "2012", base)
        rows = [
            q.sample_row(DEV, A, _sample(base + 1000, lat=43.0, lon=-87.9,
                                          power_w=-60.0, current_a=-4.0, soc=88)),
            q.sample_row(DEV, A, _sample(base + 2000, lat=43.0002, lon=-87.9,
                                          power_w=-80.0, current_a=-5.0, soc=88)),
            q.sample_row(DEV, A, _sample(base + 3000, soc=88)),  # indoor: no lat/lon -> excluded
        ]
        assert await q.insert_samples(conn, rows) == 3

        pts = await q.track_series(conn, A, base, base + bucket)
        assert len(pts) == 1
        assert round(pts[0]["lat"], 4) == 43.0001
        assert round(pts[0]["power_w"]) == -70
