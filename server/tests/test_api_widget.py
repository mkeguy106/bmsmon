"""API-key auth and the /api/v1/groups widget feed."""

import secrets
import time

from app.auth.api_key import hash_key
from app.db import queries as q

DEV = "00000000-0000-0000-0000-0000000000aa"
A = "C8:47:80:15:67:44"   # 2012 · A
B = "C8:47:80:15:62:1B"   # 2012 · B
C = "C8:47:80:15:DB:13"   # 2016 · A

HDR = "X-API-Key"


async def _device(conn):
    await conn.execute(
        "INSERT INTO devices (id, install_uuid, public_key_spki) VALUES ($1,$2,$3)",
        DEV, "uuid-widget-1", b"\x00")


async def _mint(conn, name="test widget") -> str:
    key = secrets.token_urlsafe(32)
    await q.create_api_key(conn, name, hash_key(key))
    return key


async def _seed(conn, now_ms: int):
    """2012 A+B fresh and discharging; 2016 A stale (last heard 10 min ago)."""
    await _device(conn)
    await q.upsert_battery(conn, A, "R-12100", "2012 · A", "2012", now_ms)
    await q.upsert_battery(conn, B, "R-12100", "2012 · B", "2012", now_ms)
    await q.upsert_battery(conn, C, "R-12100", "2016 · A", "2016", now_ms)
    rows = [
        q.sample_row(DEV, A, {"ts_ms": now_ms - 2_000, "soc": 72.0, "current_a": -4.1,
                              "power_w": -54.0, "voltage_v": 13.2, "lat": 43.0, "lon": -88.0}),
        q.sample_row(DEV, B, {"ts_ms": now_ms - 3_000, "soc": 74.0, "current_a": -3.9,
                              "power_w": -51.0, "voltage_v": 13.2}),
        q.sample_row(DEV, C, {"ts_ms": now_ms - 600_000, "soc": 99.0, "current_a": 0.0,
                              "power_w": 0.0, "voltage_v": 13.4}),
    ]
    await q.insert_samples(conn, rows)


async def test_missing_key_is_401(client):
    r = await client.get("/api/v1/groups")
    assert r.status_code == 401


async def test_unknown_key_is_401(client):
    r = await client.get("/api/v1/groups", headers={HDR: "not-a-real-key"})
    assert r.status_code == 401


async def test_revoked_key_is_401_and_indistinguishable(app, client):
    async with app.state.pool.acquire() as conn:
        key = await _mint(conn)
        rows = await q.list_api_keys(conn)
        assert await q.revoke_api_key(conn, rows[0]["id"]) is True
    revoked = await client.get("/api/v1/groups", headers={HDR: key})
    unknown = await client.get("/api/v1/groups", headers={HDR: "nope"})
    assert revoked.status_code == unknown.status_code == 401
    # A prober must not be able to tell "was valid, now revoked" from "never existed".
    assert revoked.json() == unknown.json()


async def test_groups_shape_and_grouping(app, client):
    now_ms = int(time.time() * 1000)
    async with app.state.pool.acquire() as conn:
        key = await _mint(conn)
        await _seed(conn, now_ms)

    r = await client.get("/api/v1/groups", headers={HDR: key})
    assert r.status_code == 200
    assert r.headers["cache-control"] == "no-store"
    body = r.json()

    ids = [g["id"] for g in body["groups"]]
    assert ids == ["2012", "2016"]          # daily driver leads

    g2012 = body["groups"][0]
    assert [p["letter"] for p in g2012["packs"]] == ["A", "B"]
    assert [round(p["soc"]) for p in g2012["packs"]] == [72, 74]
    assert g2012["status"] == "in-use"      # a live pack is discharging
    assert g2012["connected"] is True


async def test_stale_pack_keeps_telemetry_but_reads_offline(app, client):
    now_ms = int(time.time() * 1000)
    async with app.state.pool.acquire() as conn:
        key = await _mint(conn)
        await _seed(conn, now_ms)

    body = (await client.get("/api/v1/groups", headers={HDR: key})).json()
    g2016 = next(g for g in body["groups"] if g["id"] == "2016")
    pack = g2016["packs"][0]

    # The whole point: last-known values survive so the widget can dim rather than blank.
    assert pack["connected"] is False
    assert round(pack["soc"]) == 99
    assert pack["age_ms"] >= 600_000
    assert g2016["status"] == "offline"
    assert g2016["connected"] is False


async def test_gps_is_never_exposed(app, client):
    """A battery widget has no business knowing where the chair is."""
    now_ms = int(time.time() * 1000)
    async with app.state.pool.acquire() as conn:
        key = await _mint(conn)
        await _seed(conn, now_ms)   # pack A carries lat/lon

    r = await client.get("/api/v1/groups", headers={HDR: key})
    assert r.status_code == 200
    raw = r.text
    for banned in ("lat", "lon", "gps_accuracy_m", "device_id"):
        assert f'"{banned}"' not in raw, f"{banned} leaked into the widget feed"


async def test_key_is_read_only(app, client):
    """The key must not open any write route — /ingest still demands a device JWT."""
    async with app.state.pool.acquire() as conn:
        key = await _mint(conn)
    r = await client.post("/api/v1/ingest", headers={HDR: key}, content=b"{}")
    assert r.status_code == 401


async def test_last_used_at_is_recorded(app, client):
    async with app.state.pool.acquire() as conn:
        key = await _mint(conn)
        assert (await q.list_api_keys(conn))[0]["last_used_at"] is None
    await client.get("/api/v1/groups", headers={HDR: key})
    async with app.state.pool.acquire() as conn:
        assert (await q.list_api_keys(conn))[0]["last_used_at"] is not None
