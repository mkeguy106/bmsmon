import time
from datetime import datetime, timezone

from app.db import queries as q

USER = {"X-authentik-username": "joel",
        "X-authentik-groups": "Covert.life - Full App Access - User Group"}

A = "C8:47:80:15:67:44"
DEV = "00000000-0000-0000-0000-000000000001"


async def _seed(conn):
    await conn.execute(
        "INSERT INTO devices (id, install_uuid, public_key_spki) VALUES ($1,$2,$3)",
        DEV, "uuid-web-charge-sessions-1", b"\x00",
    )
    base = int(datetime(2026, 7, 1, tzinfo=timezone.utc).timestamp() * 1000)
    await q.upsert_battery(conn, A, "R-12100", "2012 · A", "2012", base)


async def test_charge_sessions_requires_identity(client):
    r = await client.get("/web/charge-sessions", params={"address": A})
    assert r.status_code == 401


async def test_charge_sessions_empty(client):
    r = await client.get("/web/charge-sessions", headers=USER, params={"address": A})
    assert r.status_code == 200
    assert r.json() == {"sessions": []}


async def test_charge_sessions_detects_full_ramp(app, client):
    pool = app.state.pool
    async with pool.acquire() as conn:
        await _seed(conn)
        now_ms = int(time.time() * 1000)
        # clean 1-min bucket boundary in the recent past, comfortably inside the default
        # 30-day window the route computes off the real clock
        base = (now_ms // 60_000) * 60_000 - 10 * 60_000
        socs = [60, 70, 80, 90, 98, 99, 100]
        rows = [
            q.sample_row(DEV, A, {"ts_ms": base + i * 60_000, "soc": float(s),
                                   "current_a": 5.0, "temp_c": 28.0})
            for i, s in enumerate(socs)
        ]
        assert await q.insert_samples(conn, rows) == len(rows)

    r = await client.get("/web/charge-sessions", headers=USER, params={"address": A})
    assert r.status_code == 200
    sessions = r.json()["sessions"]
    assert len(sessions) == 1
    s = sessions[0]
    assert s["from_soc"] == 60
    assert s["duration_min"] == 6
    assert s["cv_tail_min"] == 3  # 98, 99, 100 (buckets with soc >= cv_soc=98)
    assert s["peak_temp_c"] == 28.0
