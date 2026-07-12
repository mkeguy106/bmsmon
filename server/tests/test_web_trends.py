from datetime import datetime, timezone

from app.db import queries as q

USER = {"X-authentik-username": "joel",
        "X-authentik-groups": "Covert.life - Full App Access - User Group"}

A = "C8:47:80:15:67:44"
DEV = "00000000-0000-0000-0000-000000000001"


async def _seed(conn):
    await conn.execute(
        "INSERT INTO devices (id, install_uuid, public_key_spki) VALUES ($1,$2,$3)",
        DEV, "uuid-web-trends-1", b"\x00",
    )
    base = int(datetime(2026, 7, 1, tzinfo=timezone.utc).timestamp() * 1000)
    await q.upsert_battery(conn, A, "R-12100", "2012 · A", "2012", base)


async def test_trends_requires_identity(client):
    r = await client.get("/web/trends", params={"address": A, "from_ms": 0, "to_ms": 1})
    assert r.status_code == 401


async def test_trends_returns_seeded_series(app, client):
    pool = app.state.pool
    async with pool.acquire() as conn:
        await _seed(conn)
        bucket = 1_800_000  # span <= 2 days -> 30-min buckets
        base = 100 * bucket
        rows = [
            q.sample_row(DEV, A, {"ts_ms": base + 1000, "soh": 99, "temp_c": 20.0,
                                  "cell_min_v": 3.30, "cell_max_v": 3.34}),
            q.sample_row(DEV, A, {"ts_ms": base + 2000, "soh": 97, "temp_c": 30.0,
                                  "cell_min_v": 3.31, "cell_max_v": 3.33}),
        ]
        assert await q.insert_samples(conn, rows) == 2

    r = await client.get("/web/trends", headers=USER,
                          params={"address": A, "from_ms": base, "to_ms": base + bucket})
    assert r.status_code == 200
    body = r.json()
    assert set(body.keys()) == {"address", "bucket_ms", "first_ms", "points"}
    assert body["address"] == A
    assert body["bucket_ms"] == bucket
    assert body["first_ms"] == base + 1000
    assert len(body["points"]) == 1
    p = body["points"][0]
    assert set(p.keys()) == {"t", "soh", "cell_spread_mv", "temp_avg", "temp_min", "temp_max"}
    assert p["t"] == base
    assert round(p["soh"]) == 98
    assert round(p["cell_spread_mv"]) == 30
    assert p["temp_min"] == 20.0 and p["temp_max"] == 30.0
