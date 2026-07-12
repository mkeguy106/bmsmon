import time
from datetime import datetime, timezone

from app.db import queries as q

USER = {"X-authentik-username": "joel",
        "X-authentik-groups": "Covert.life - Full App Access - User Group"}

A = "C8:47:80:15:67:44"
DEV = "00000000-0000-0000-0000-000000000001"
BUCKET = 1_800_000


async def _seed(conn):
    await conn.execute(
        "INSERT INTO devices (id, install_uuid, public_key_spki) VALUES ($1,$2,$3)",
        DEV, "uuid-web-history-1", b"\x00",
    )
    base = int(datetime(2026, 7, 1, tzinfo=timezone.utc).timestamp() * 1000)
    await q.upsert_battery(conn, A, "R-12100", "2012 · A", "2012", base)


async def test_history_requires_identity(client):
    r = await client.get("/web/history")
    assert r.status_code == 401


async def test_history_empty(client):
    r = await client.get("/web/history", headers=USER)
    assert r.status_code == 200
    assert r.json() == {"series": []}


async def test_history_returns_grouped_series(app, client):
    pool = app.state.pool
    async with pool.acquire() as conn:
        await _seed(conn)
        # a clean bucket boundary in the recent past, so it falls inside the route's
        # now-minus-hours window (the route computes since_ms off the real clock)
        now_ms = int(time.time() * 1000)
        base = (now_ms // BUCKET) * BUCKET - 4 * BUCKET
        rows = [
            q.sample_row(DEV, A, {"ts_ms": base + 60_000, "soc": 80.0}),
            q.sample_row(DEV, A, {"ts_ms": base + 120_000, "soc": 90.0}),  # same bucket -> avg 85
            q.sample_row(DEV, A, {"ts_ms": base + BUCKET + 60_000, "soc": 70.0}),  # next bucket
        ]
        assert await q.insert_samples(conn, rows) == 3

    # request a window wide enough to include the seeded (fixed 2026-07-01) timestamps
    r = await client.get("/web/history", headers=USER, params={"hours": 168})
    assert r.status_code == 200
    body = r.json()
    series = [s for s in body["series"] if s["address"] == A]
    assert len(series) == 1
    points = series[0]["points"]
    assert points == [
        {"t": base, "soc": 85.0},
        {"t": base + BUCKET, "soc": 70.0},
    ]
    # ascending t order
    assert [p["t"] for p in points] == sorted(p["t"] for p in points)


async def test_history_hours_bounds_rejected(client):
    r = await client.get("/web/history", headers=USER, params={"hours": 0})
    assert r.status_code == 422
    r = await client.get("/web/history", headers=USER, params={"hours": 169})
    assert r.status_code == 422
