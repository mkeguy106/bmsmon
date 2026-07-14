import time
from datetime import datetime, timezone

from httpx import ASGITransport, AsyncClient

from app.auth.enroll import hash_code
from app.db import queries as q
from app.routers.share import day_window_ms, share_status

DEV = "00000000-0000-0000-0000-000000000002"
A = "C8:47:80:15:67:44"


def test_share_status():
    now = 1_000_000
    assert share_status(None, now) == "gone"
    assert share_status({"revoked_at": 5, "expires_at": now + 1}, now) == "gone"
    assert share_status({"revoked_at": None, "expires_at": now + 1}, now) == "active"
    assert share_status({"revoked_at": None, "expires_at": now}, now) == "expired"


def test_day_window_is_local_midnight_to_now():
    now = datetime(2026, 7, 13, 15, 30, tzinfo=timezone.utc)
    from_ms, to_ms = day_window_ms(now)
    assert to_ms == int(now.timestamp() * 1000)
    start = datetime.fromtimestamp(from_ms / 1000).astimezone()
    assert (start.hour, start.minute, start.second) == (0, 0, 0)
    assert 0 < to_ms - from_ms <= 25 * 3600 * 1000  # <= a DST-long day


async def _mk_share(conn, token: str, created_ms: int, expires_ms: int,
                    revoked_ms: int | None = None) -> int:
    sid = await q.create_location_share(conn, hash_code(token), "T", "joel",
                                        created_ms, expires_ms)
    if revoked_ms is not None:
        await q.revoke_location_share(conn, sid, revoked_ms)
    return sid


async def _seed_fix(conn, ts_ms: int, lat: float, lon: float,
                    accuracy_m: float | None = None):
    rows = [q.sample_row(DEV, A, {"ts_ms": ts_ms, "lat": lat, "lon": lon,
                                  "power_w": -60.0, "current_a": -4.0, "soc": 88,
                                  "gps_accuracy_m": accuracy_m})]
    assert await q.insert_samples(conn, rows) == 1


async def _seed_device(conn):
    await conn.execute(
        "INSERT INTO devices (id, install_uuid, public_key_spki) VALUES ($1,$2,$3)",
        DEV, "uuid-share-public-1", b"\x00")


async def test_unknown_and_revoked_are_identical_404(app, client):
    now_ms = int(time.time() * 1000)
    async with app.state.pool.acquire() as conn:
        await _mk_share(conn, "tok-revoked", now_ms, now_ms + 3_600_000, revoked_ms=now_ms)
    for path in ("/share/{t}", "/share/{t}/feed"):
        unknown = await client.get(path.format(t="tok-unknown"))
        revoked = await client.get(path.format(t="tok-revoked"))
        assert unknown.status_code == revoked.status_code == 404
        assert unknown.content == revoked.content
        for r in (unknown, revoked):
            assert r.headers["cache-control"] == "no-store"
            assert r.headers["referrer-policy"] == "no-referrer"


async def test_expired_page_and_feed(app, client):
    now_ms = int(time.time() * 1000)
    async with app.state.pool.acquire() as conn:
        await _mk_share(conn, "tok-exp", now_ms - 7_200_000, now_ms - 3_600_000)
    page = await client.get("/share/tok-exp")
    assert page.status_code == 200
    assert b"expired" in page.content.lower()
    feed_resp = await client.get("/share/tok-exp/feed")
    assert feed_resp.status_code == 410
    assert feed_resp.headers["cache-control"] == "no-store"
    assert feed_resp.headers["referrer-policy"] == "no-referrer"


async def test_feed_today_only_no_battery_fields(app, client):
    now_ms = int(time.time() * 1000)
    async with app.state.pool.acquire() as conn:
        await _seed_device(conn)
        await _mk_share(conn, "tok-live", now_ms, now_ms + 3_600_000)
        await _seed_fix(conn, now_ms - 60_000, 43.0, -87.9)          # today
        await _seed_fix(conn, now_ms - 2 * 86_400_000, 44.0, -88.9)  # 2 days ago: clamped out
    r = await client.get("/share/tok-live/feed")
    assert r.status_code == 200
    assert r.headers["cache-control"] == "no-store"
    assert r.headers["referrer-policy"] == "no-referrer"
    body = r.json()
    assert set(body.keys()) == {"points", "last", "expires_at", "now", "owner"}
    assert len(body["points"]) == 1
    assert set(body["points"][0].keys()) == {"t", "lat", "lon"}
    assert body["last"]["lat"] == 43.0
    # access tracking
    listing = None
    async with app.state.pool.acquire() as conn:
        listing = await q.list_location_shares(conn, now_ms, 86_400_000)
    assert listing[0]["access_count"] == 1
    assert listing[0]["last_access_ms"] is not None


async def test_feed_excludes_coarse_fixes(app, client):
    """The guest trail must not jump to a coarse network fix (huge accuracy radius)."""
    now_ms = int(time.time() * 1000)
    async with app.state.pool.acquire() as conn:
        await _seed_device(conn)
        await _mk_share(conn, "tok-acc", now_ms, now_ms + 3_600_000)
        await _seed_fix(conn, now_ms - 90_000, 43.0, -87.9, accuracy_m=16.0)
        await _seed_fix(conn, now_ms - 60_000, 43.03, -87.905, accuracy_m=400.0)  # dropped
    r = await client.get("/share/tok-acc/feed")
    assert r.status_code == 200
    body = r.json()
    assert len(body["points"]) == 1
    assert body["last"]["lat"] == 43.0


async def test_feed_rate_limited_per_ip(app):
    transport = ASGITransport(app=app, client=("9.9.9.9", 12345))
    async with AsyncClient(transport=transport, base_url="http://t") as c:
        codes = [(await c.get("/share/tok-nope/feed")).status_code for _ in range(61)]
    assert codes[0] == 404
    assert codes[-1] == 429


async def test_active_page_serves_guest_shell(app, client, tmp_path, monkeypatch):
    (tmp_path / "share").mkdir()
    (tmp_path / "share" / "index.html").write_text("<html>guest-shell</html>")
    monkeypatch.setenv("BMSMON_WEB_DIST", str(tmp_path))
    now_ms = int(time.time() * 1000)
    async with app.state.pool.acquire() as conn:
        await _mk_share(conn, "tok-page", now_ms, now_ms + 3_600_000)
    r = await client.get("/share/tok-page")
    assert r.status_code == 200
    assert b"guest-shell" in r.content
