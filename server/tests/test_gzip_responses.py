import time
from datetime import datetime, timezone

from app.db import queries as q

USER = {"X-authentik-username": "joel",
        "X-authentik-groups": "Covert.life - Full App Access - User Group"}

A = "C8:47:80:15:67:44"
DEV = "00000000-0000-0000-0000-000000000001"
BUCKET = 1_800_000


async def _seed_history(conn, buckets: int) -> None:
    await conn.execute(
        "INSERT INTO devices (id, install_uuid, public_key_spki) VALUES ($1,$2,$3)",
        DEV, "uuid-gzip-1", b"\x00",
    )
    base_ms = int(datetime(2026, 7, 1, tzinfo=timezone.utc).timestamp() * 1000)
    await q.upsert_battery(conn, A, "R-12100", "2012 · A", "2012", base_ms)
    now_ms = int(time.time() * 1000)
    start = (now_ms // BUCKET) * BUCKET - buckets * BUCKET
    rows = [q.sample_row(DEV, A, {"ts_ms": start + i * BUCKET, "soc": 50.0 + (i % 40)})
            for i in range(buckets)]
    await q.insert_samples(conn, rows)


async def test_large_web_response_is_gzipped(app, client):
    pool = app.state.pool
    async with pool.acquire() as conn:
        await _seed_history(conn, 46)  # ~46 points -> body comfortably over minimum_size

    r = await client.get("/web/history", headers={**USER, "Accept-Encoding": "gzip"},
                         params={"hours": 24})
    assert r.status_code == 200
    assert r.headers.get("content-encoding") == "gzip"
    # httpx transparently decompresses; the JSON must be intact
    series = [s for s in r.json()["series"] if s["address"] == A]
    assert len(series) == 1 and len(series[0]["points"]) == 46


async def test_small_web_response_is_not_gzipped(client):
    r = await client.get("/web/history", headers={**USER, "Accept-Encoding": "gzip"})
    assert r.status_code == 200
    assert r.headers.get("content-encoding") != "gzip"
    assert r.json() == {"series": []}
