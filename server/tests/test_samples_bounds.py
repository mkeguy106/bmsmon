"""SRV-11: /web/samples is bounded — range clamped to 7 days, hard row LIMIT."""
import time

from app.db import queries as q

A = "C8:47:80:15:67:44"
DEV = "00000000-0000-0000-0000-000000000002"
ADMIN_H = {"X-authentik-username": "joel",
           "X-authentik-groups": "Covert.life - Full App Access - User Group"}

DAY_MS = 86_400_000


async def _seed(app, ts_list):
    async with app.state.pool.acquire() as conn:
        await conn.execute(
            "INSERT INTO devices (id, install_uuid, public_key_spki) VALUES ($1,$2,$3)",
            DEV, "uuid-samples-bounds", b"\x00")
        rows = [q.sample_row(DEV, A, {"ts_ms": t, "soc": 50.0}) for t in ts_list]
        await q.insert_samples(conn, rows)


async def test_year_long_range_is_200_and_clamped_to_last_7_days(app, client):
    now_ms = int(time.time() * 1000)
    old = now_ms - 8 * DAY_MS      # inside the requested year, outside the 7-day cap
    recent = now_ms - 3_600_000
    await _seed(app, [old, recent])
    r = await client.get(
        f"/web/samples?address={A}&from_ms={now_ms - 365 * DAY_MS}&to_ms={now_ms}",
        headers=ADMIN_H)
    assert r.status_code == 200
    assert [s["ts_ms"] for s in r.json()["samples"]] == [recent]


async def test_samples_range_enforces_row_limit(app):
    now_ms = int(time.time() * 1000)
    await _seed(app, [now_ms - i * 1000 for i in range(5)])
    async with app.state.pool.acquire() as conn:
        rows = await q.samples_range(conn, A, now_ms - DAY_MS, now_ms, limit=2)
    assert len(rows) == 2
