import time
from datetime import datetime, timezone

from app.db import queries as q
from app.routers.web import TRACK_MAX_SPAN_MS
from tests.identities import OUTSIDER_H
from tests.identities import VIEWER_H as USER

A = "C8:47:80:15:67:44"
DEV = "00000000-0000-0000-0000-000000000001"
BUCKET = 15_000


async def _seed(conn):
    await conn.execute(
        "INSERT INTO devices (id, install_uuid, public_key_spki) VALUES ($1,$2,$3)",
        DEV, "uuid-web-track-1", b"\x00",
    )
    base = int(datetime(2026, 7, 1, tzinfo=timezone.utc).timestamp() * 1000)
    await q.upsert_battery(conn, A, "R-12100", "2012 · A", "2012", base)


async def test_track_requires_identity(client):
    r = await client.get("/web/track", params={"address": A, "from_ms": 0, "to_ms": 1})
    assert r.status_code == 401


async def test_track_returns_seeded_points(app, client):
    pool = app.state.pool
    async with pool.acquire() as conn:
        await _seed(conn)
        base = int(datetime(2026, 7, 1, tzinfo=timezone.utc).timestamp() * 1000)
        base = (base // BUCKET) * BUCKET
        rows = [
            q.sample_row(DEV, A, {"ts_ms": base + 1000, "lat": 43.0, "lon": -87.9,
                                  "power_w": -60.0, "current_a": -4.0, "soc": 88}),
            q.sample_row(DEV, A, {"ts_ms": base + 2000, "lat": 43.0002, "lon": -87.9,
                                  "power_w": -80.0, "current_a": -5.0, "soc": 88}),
            q.sample_row(DEV, A, {"ts_ms": base + 3000, "soc": 88}),  # indoor: excluded
        ]
        assert await q.insert_samples(conn, rows) == 3

    r = await client.get("/web/track", headers=USER,
                         params={"address": A, "from_ms": base, "to_ms": base + BUCKET})
    assert r.status_code == 200
    body = r.json()
    assert body["address"] == A
    assert len(body["points"]) == 1
    p = body["points"][0]
    assert p["t"] == base
    assert round(p["lat"], 4) == 43.0001
    assert round(p["lon"], 4) == -87.9
    assert round(p["power_w"]) == -70
    assert p["current_a"] == -4.5
    assert p["soc"] == 88.0


async def test_track_excludes_coarse_fixes(app, client):
    """Fixes with a huge accuracy radius (network/cell fallback, e.g. post-reboot) must not
    render as journey jumps; borderline (=250 m) and NULL-accuracy fixes stay."""
    pool = app.state.pool
    async with pool.acquire() as conn:
        await conn.execute(
            "INSERT INTO devices (id, install_uuid, public_key_spki) VALUES ($1,$2,$3)",
            DEV, "uuid-web-track-2", b"\x00",
        )
        base = int(datetime(2026, 7, 2, tzinfo=timezone.utc).timestamp() * 1000)
        base = (base // BUCKET) * BUCKET
        rows = [
            q.sample_row(DEV, A, {"ts_ms": base + 1000, "lat": 43.0, "lon": -87.9,
                                  "soc": 88, "gps_accuracy_m": 16.0}),
            q.sample_row(DEV, A, {"ts_ms": base + BUCKET, "lat": 43.03, "lon": -87.905,
                                  "soc": 88, "gps_accuracy_m": 400.0}),  # coarse: dropped
            q.sample_row(DEV, A, {"ts_ms": base + 2 * BUCKET, "lat": 43.0001, "lon": -87.9,
                                  "soc": 88, "gps_accuracy_m": 250.0}),  # borderline: kept
            q.sample_row(DEV, A, {"ts_ms": base + 3 * BUCKET, "lat": 43.0002, "lon": -87.9,
                                  "soc": 88}),  # no accuracy reported: kept
        ]
        assert await q.insert_samples(conn, rows) == 4

    r = await client.get("/web/track", headers=USER,
                         params={"address": A, "from_ms": base, "to_ms": base + 4 * BUCKET})
    assert r.status_code == 200
    pts = r.json()["points"]
    assert [p["t"] for p in pts] == [base, base + 2 * BUCKET, base + 3 * BUCKET]
    assert all(p["lat"] < 43.001 for p in pts)


async def test_track_returns_accuracy_radius(app, client):
    """The Journey map weights each fix by its accuracy radius, so /web/track must
    expose it. Buckets average it like the coordinates; a NULL-accuracy fix yields None."""
    pool = app.state.pool
    async with pool.acquire() as conn:
        await conn.execute(
            "INSERT INTO devices (id, install_uuid, public_key_spki) VALUES ($1,$2,$3)",
            DEV, "uuid-web-track-acc", b"\x00",
        )
        base = int(datetime(2026, 7, 3, tzinfo=timezone.utc).timestamp() * 1000)
        base = (base // BUCKET) * BUCKET
        rows = [
            q.sample_row(DEV, A, {"ts_ms": base + 1000, "lat": 43.0, "lon": -87.9,
                                  "soc": 88, "gps_accuracy_m": 10.0}),
            q.sample_row(DEV, A, {"ts_ms": base + 2000, "lat": 43.0, "lon": -87.9,
                                  "soc": 88, "gps_accuracy_m": 30.0}),
            q.sample_row(DEV, A, {"ts_ms": base + BUCKET + 1000, "lat": 43.001, "lon": -87.9,
                                  "soc": 88}),  # no accuracy reported
        ]
        assert await q.insert_samples(conn, rows) == 3

    r = await client.get("/web/track", headers=USER,
                         params={"address": A, "from_ms": base, "to_ms": base + 2 * BUCKET})
    assert r.status_code == 200
    points = r.json()["points"]
    assert len(points) == 2
    assert points[0]["acc"] == 20.0   # mean of 10 and 30
    assert points[1]["acc"] is None


def test_track_span_limit_is_a_dst_month():
    assert TRACK_MAX_SPAN_MS == 31 * 86_400_000 + 3_600_000


async def test_track_accepts_the_widest_legitimate_range(client):
    # Journey RANGE over 31 local days that include the DST fall-back night.
    r = await client.get("/web/track", headers=USER,
                         params={"address": A, "from_ms": 1_000,
                                 "to_ms": 1_000 + TRACK_MAX_SPAN_MS})
    assert r.status_code == 200
    assert r.json()["points"] == []


async def test_track_refuses_a_wider_range_with_400(client):
    for to_ms in (TRACK_MAX_SPAN_MS + 1, 4_000_000_000_000):
        r = await client.get("/web/track", headers=USER,
                             params={"address": A, "from_ms": 0, "to_ms": to_ms})
        assert r.status_code == 400, to_ms


async def test_track_live_day_and_incremental_tail_still_work(client):
    # The two shapes useTrack sends on every refresh: a whole day, and [seam, day end).
    now = int(time.time() * 1000)
    day0 = now - now % 86_400_000
    for f, t in ((day0, day0 + 86_400_000), (now - 15_000, day0 + 86_400_000)):
        r = await client.get("/web/track", headers=USER,
                             params={"address": A, "from_ms": f, "to_ms": t})
        assert r.status_code == 200, (f, t)


async def test_track_reversed_range_is_an_empty_200(client):
    r = await client.get("/web/track", headers=USER,
                         params={"address": A, "from_ms": 2_000, "to_ms": 1_000})
    assert r.status_code == 200 and r.json()["points"] == []


async def test_track_garbage_timestamps_are_422_not_500(client):
    for params in ({"from_ms": -1, "to_ms": 1}, {"from_ms": 10**18, "to_ms": 10**18 + 1}):
        r = await client.get("/web/track", headers=USER, params={"address": A, **params})
        assert r.status_code == 422, params


async def test_track_is_403_for_non_members(client):
    r = await client.get("/web/track", headers=OUTSIDER_H,
                         params={"address": A, "from_ms": 0, "to_ms": 1})
    assert r.status_code == 403


async def test_track_auth_is_checked_before_the_span(client):
    r = await client.get("/web/track",
                         params={"address": A, "from_ms": 0, "to_ms": 4_000_000_000_000})
    assert r.status_code == 401


async def test_track_membership_is_checked_before_the_span(client):
    # A non-member learns nothing from the span check either: 403, never the 400.
    r = await client.get("/web/track", headers=OUTSIDER_H,
                         params={"address": A, "from_ms": 0, "to_ms": 4_000_000_000_000})
    assert r.status_code == 403
