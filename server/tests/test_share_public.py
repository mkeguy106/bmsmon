import time
from datetime import datetime, timezone

from httpx import ASGITransport, AsyncClient

from app.auth.enroll import hash_code
from app.db import queries as q
from app.routers.share import (
    ACTIVE_HOLD_MS, STATUS_STALE_MS, day_window_ms, pick_guest_status, share_status,
)

DEV = "00000000-0000-0000-0000-000000000002"
A = "C8:47:80:15:67:44"
SPARE = "C8:47:80:15:25:01"


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


def _snap_row(addr, group, alias, ts_ms, soc, cur=0.0, pw=0.0, regen=None):
    return {"address": addr, "group_id": group, "alias": alias, "ts_ms": ts_ms,
            "soc": soc, "current_a": cur, "power_w": pw, "regen": regen}


def test_pick_guest_status_aggregates_active_group():
    now = 10_000_000
    rows = [
        _snap_row("AA", "2012", "2012 · A", now - 5_000, 98.0, -4.0, -50.0),
        _snap_row("BB", "2012", "2012 · B", now - 8_000, 97.0, -3.5, -45.0),
        _snap_row("CC", "2016", "2016 · A", now - 60_000, 55.0),  # other base: ignored
    ]
    s, active = pick_guest_status(rows, now)
    assert active == "2012"
    assert set(s.keys()) == {"ts", "soc", "packs", "current_a", "power_w", "regen"}
    assert s["ts"] == now - 5_000
    assert s["soc"] == 97
    assert s["packs"] == [{"label": "A", "soc": 98}, {"label": "B", "soc": 97}]
    assert s["current_a"] == -7.5
    assert s["power_w"] == -95.0
    assert s["regen"] is False


def test_pick_guest_status_regen_and_stale_pack_excluded():
    now = 10_000_000
    rows = [
        _snap_row("AA", "2012", "2012 · A", now - 1_000, 80.0, 5.0, 60.0, regen=True),
        _snap_row("BB", "2012", "2012 · B", now - STATUS_STALE_MS - 1, 10.0, -9.0, -100.0),
    ]
    s, _ = pick_guest_status(rows, now)
    assert s["soc"] == 80          # the stale pack's 10% must not drag the min down
    assert s["packs"] == [{"label": "A", "soc": 80}]
    assert s["regen"] is True


def test_pick_guest_status_ungrouped_packs_never_merge():
    now = 10_000_000
    rows = [
        _snap_row("AA", None, "", now - 1_000, 80.0, -4.0, -50.0),
        _snap_row("BB", None, "", now - 2_000, 60.0, -3.0, -40.0),  # separate base
    ]
    s, _ = pick_guest_status(rows, now)
    assert s["packs"] == [{"label": "AA"[-2:], "soc": 80}]
    assert s["current_a"] == -4.0
    assert s["power_w"] == -50.0


def test_pick_guest_status_stale_or_empty_is_none():
    now = 10_000_000
    assert pick_guest_status([], now)[0] is None
    rows = [_snap_row("AA", "2012", "2012 · A", now - STATUS_STALE_MS - 1, 98.0)]
    assert pick_guest_status(rows, now)[0] is None


# --- active-base resolution (the chair, not "whoever polled last") ------------------
# The phone polls the staged base every ~1.5 s and rotates through the background packs,
# so at home ANY of the 8 packs can hold the newest sample. Picking the freshest row's
# group made the guest dock flip to an idle spare at 99% with a dead FLOW bar roughly a
# third of the time (measured in prod). The dock now follows the same ladder the app and
# WebUI stage use: discharging now -> recently discharged -> whatever it was showing.

def test_pick_guest_status_prefers_the_discharging_base_over_the_freshest_sample():
    """A background spare that just polled must not steal the dock from the chair."""
    now = 10_000_000
    rows = [
        _snap_row("SS", "2024", "2024 · A", now - 500, 99.0, 0.0, 0.0),   # freshest, idle
        _snap_row("AA", "2012", "2012 · A", now - 3_000, 72.0, -4.0, -50.0),
        _snap_row("BB", "2012", "2012 · B", now - 3_500, 71.0, -3.5, -45.0),
    ]
    s, active = pick_guest_status(rows, now)
    assert active == "2012"
    assert s["soc"] == 71
    assert s["power_w"] == -95.0


def test_pick_guest_status_holds_the_recently_discharged_base_while_idle():
    """Stopped at a crossing: nothing is drawing, but the chair still owns the dock."""
    now = 10_000_000
    rows = [
        _snap_row("SS", "2024", "2024 · A", now - 500, 99.0),
        _snap_row("AA", "2012", "2012 · A", now - 3_000, 72.0),
        _snap_row("BB", "2012", "2012 · B", now - 3_500, 71.0),
    ]
    last_discharge = {"AA": now - 60_000}
    s, active = pick_guest_status(rows, now, last_discharge)
    assert active == "2012"
    assert s["soc"] == 71


def test_pick_guest_status_hold_survives_a_pack_dropping_out_of_range():
    """Rule 2 keys off the base, not the pack: A can go stale while B still reports."""
    now = 10_000_000
    rows = [
        _snap_row("SS", "2024", "2024 · A", now - 500, 99.0),
        _snap_row("AA", "2012", "2012 · A", now - STATUS_STALE_MS - 1, 72.0),  # dropped BLE
        _snap_row("BB", "2012", "2012 · B", now - 3_500, 71.0),
    ]
    _, active = pick_guest_status(rows, now, {"AA": now - 60_000})
    assert active == "2012"


def test_pick_guest_status_sticks_to_the_last_active_base_once_the_hold_expires():
    """Parked for an hour: the dock stays on the chair rather than flipping to a spare."""
    now = 10_000_000
    rows = [
        _snap_row("SS", "2024", "2024 · A", now - 500, 99.0),
        _snap_row("AA", "2012", "2012 · A", now - 3_000, 72.0),
    ]
    stale_discharge = {"AA": now - ACTIVE_HOLD_MS - 1}
    assert pick_guest_status(rows, now, stale_discharge)[1] == "2024"  # no memory: freshest
    s, active = pick_guest_status(rows, now, stale_discharge, sticky="2012")
    assert active == "2012"
    assert s["soc"] == 72


def test_pick_guest_status_drops_a_sticky_base_that_went_stale():
    """The chair left the spares behind: don't pin the dock to data we no longer have."""
    now = 10_000_000
    rows = [
        _snap_row("SS", "2024", "2024 · A", now - 500, 99.0),
        _snap_row("AA", "2012", "2012 · A", now - STATUS_STALE_MS - 1, 72.0),
    ]
    s, active = pick_guest_status(rows, now, None, sticky="2012")
    assert active == "2024"
    assert s["soc"] == 99


def test_pick_guest_status_keeps_the_sticky_base_through_a_fleet_blackout():
    """No fresh rows at all -> no status, but the remembered base must not be forgotten."""
    now = 10_000_000
    rows = [_snap_row("AA", "2012", "2012 · A", now - STATUS_STALE_MS - 1, 72.0)]
    assert pick_guest_status(rows, now, None, sticky="2012") == (None, "2012")


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
        await q.upsert_battery(conn, A, "R-12100", "2012 · A", "2012", now_ms)
        await _mk_share(conn, "tok-live", now_ms, now_ms + 3_600_000)
        await _seed_fix(conn, now_ms - 60_000, 43.0, -87.9)          # today
        await _seed_fix(conn, now_ms - 2 * 86_400_000, 44.0, -88.9)  # 2 days ago: clamped out
    r = await client.get("/share/tok-live/feed")
    assert r.status_code == 200
    assert r.headers["cache-control"] == "no-store"
    assert r.headers["referrer-policy"] == "no-referrer"
    body = r.json()
    assert set(body.keys()) == {"points", "last", "expires_at", "now", "day_start",
                                "owner", "status"}
    assert body["day_start"] == day_window_ms(datetime.now(timezone.utc))[0]
    assert len(body["points"]) == 1
    # trail-detail relaxation (2026-07-14): per-bucket discharge context rides along —
    # exactly these keys, still never per-point SOC/voltage/temp/cells
    assert set(body["points"][0].keys()) == {"t", "lat", "lon", "power_w", "current_a"}
    assert body["points"][0]["power_w"] == -60.0
    assert body["points"][0]["current_a"] == -4.0
    assert body["last"]["lat"] == 43.0
    # guest dock status: deliberate, minimal battery surface — exact key sets pinned
    status = body["status"]
    assert set(status.keys()) == {"ts", "soc", "packs", "current_a", "power_w", "regen"}
    assert status["soc"] == 88
    assert status["packs"] == [{"label": "A", "soc": 88}]
    assert status["current_a"] == -4.0
    assert status["power_w"] == -60.0
    # access tracking
    listing = None
    async with app.state.pool.acquire() as conn:
        listing = await q.list_location_shares(conn, now_ms, 86_400_000)
    assert listing[0]["access_count"] == 1
    assert listing[0]["last_access_ms"] is not None


async def test_feed_track_cached_between_rapid_polls(app, client):
    """Two rapid polls return identical trail points even though a new fix landed in
    between (the fleet GPS query is TTL-cached per poll period), and the throttled
    touch bookkeeping counts the burst as one access. Clearing the cache (= TTL lapse)
    makes the next poll pick up the new fix."""
    now_ms = int(time.time() * 1000)
    async with app.state.pool.acquire() as conn:
        await _seed_device(conn)
        await _mk_share(conn, "tok-cache", now_ms, now_ms + 3_600_000)
        await _seed_fix(conn, now_ms - 90_000, 43.0, -87.9)
    r1 = await client.get("/share/tok-cache/feed")
    async with app.state.pool.acquire() as conn:
        await _seed_fix(conn, now_ms - 30_000, 43.5, -87.5)
    r2 = await client.get("/share/tok-cache/feed")
    assert r1.status_code == r2.status_code == 200
    assert r1.json()["points"] == r2.json()["points"]  # cache hit: new fix not yet visible
    assert len(r2.json()["points"]) == 1
    # touch throttle: the rapid second poll didn't bump access_count (approximate by design)
    async with app.state.pool.acquire() as conn:
        listing = await q.list_location_shares(conn, now_ms, 86_400_000)
    assert listing[0]["access_count"] == 1
    app.state.share_track_cache.clear()  # simulate TTL expiry
    r3 = await client.get("/share/tok-cache/feed")
    assert len(r3.json()["points"]) == 2  # fresh query sees the new fix


async def test_feed_cache_never_leaks_into_expired_or_gone_shares(app, client):
    """Per-share state (expiry, revocation) stays per-request: a warm track cache from
    an active share must not resurrect an expired (410) or revoked (404) link."""
    now_ms = int(time.time() * 1000)
    async with app.state.pool.acquire() as conn:
        await _seed_device(conn)
        await _mk_share(conn, "tok-warm", now_ms, now_ms + 3_600_000)
        await _mk_share(conn, "tok-dead", now_ms - 7_200_000, now_ms - 3_600_000)
        await _seed_fix(conn, now_ms - 60_000, 43.0, -87.9)
    assert (await client.get("/share/tok-warm/feed")).status_code == 200  # warms the cache
    assert (await client.get("/share/tok-dead/feed")).status_code == 410
    assert (await client.get("/share/tok-nope/feed")).status_code == 404


async def test_feed_status_stays_live_despite_track_cache(app, client):
    """The guest dock status is computed from a fresh fleet snapshot per request —
    only the GPS trail is cached."""
    now_ms = int(time.time() * 1000)
    async with app.state.pool.acquire() as conn:
        await _seed_device(conn)
        await q.upsert_battery(conn, A, "R-12100", "2012 · A", "2012", now_ms)
        await _mk_share(conn, "tok-live2", now_ms, now_ms + 3_600_000)
        await _seed_fix(conn, now_ms - 60_000, 43.0, -87.9)
    r1 = await client.get("/share/tok-live2/feed")
    assert r1.json()["status"]["soc"] == 88
    async with app.state.pool.acquire() as conn:
        await _seed_fix(conn, now_ms, 43.0, -87.9)  # helper seeds soc=88; tweak below
        await conn.execute("UPDATE samples SET soc=42 WHERE ts_ms=$1", now_ms)
    r2 = await client.get("/share/tok-live2/feed")
    assert r2.json()["status"]["soc"] == 42          # live snapshot moved...
    assert r2.json()["points"] == r1.json()["points"]  # ...while the trail stayed cached


async def _seed_pack_sample(conn, addr, ts_ms, soc, current_a):
    rows = [q.sample_row(DEV, addr, {"ts_ms": ts_ms, "soc": soc, "current_a": current_a,
                                     "power_w": current_a * 12.8})]
    assert await q.insert_samples(conn, rows) == 1


async def _two_base_fleet(conn, now_ms, token):
    await _seed_device(conn)
    await q.upsert_battery(conn, A, "R-12100", "2012 · A", "2012", now_ms)
    await q.upsert_battery(conn, SPARE, "R-12100", "2024 · A", "2024", now_ms)
    await _mk_share(conn, token, now_ms, now_ms + 3_600_000)


async def test_feed_status_follows_the_chair_not_the_freshest_spare(app, client):
    """The rotating background sampler owns the newest row a third of the time at home;
    the dock must still describe the base that's actually moving."""
    now_ms = int(time.time() * 1000)
    async with app.state.pool.acquire() as conn:
        await _two_base_fleet(conn, now_ms, "tok-chair")
        await _seed_pack_sample(conn, A, now_ms - 3_000, 72, -4.0)
        await _seed_pack_sample(conn, SPARE, now_ms - 500, 99, 0.0)   # freshest, idle
    status = (await client.get("/share/tok-chair/feed")).json()["status"]
    assert status["soc"] == 72
    assert status["packs"] == [{"label": "A", "soc": 72}]
    assert app.state.share_active_base == "2012"


async def test_feed_status_holds_the_chair_across_a_pause(app, client):
    """Chair idle at a crossing, spare freshest: the recent-discharge hold (a real DB
    lookup) keeps the dock on the chair instead of jumping to the 99% spare."""
    now_ms = int(time.time() * 1000)
    async with app.state.pool.acquire() as conn:
        await _two_base_fleet(conn, now_ms, "tok-hold")
        await _seed_pack_sample(conn, A, now_ms - 120_000, 72, -4.0)  # drove 2 min ago
        await _seed_pack_sample(conn, A, now_ms - 3_000, 72, 0.0)     # now stopped
        await _seed_pack_sample(conn, SPARE, now_ms - 500, 99, 0.0)
    status = (await client.get("/share/tok-hold/feed")).json()["status"]
    assert status["soc"] == 72
    assert app.state.share_active_base == "2012"


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


async def test_feed_since_returns_only_newer_buckets_and_never_requeries(app, client):
    """Incremental poll: `since` slices the CACHED trail, so a 4 s poll costs no DB work.
    Proof that it is a cache slice and not a fresh query: a fix seeded after the first
    poll is invisible until the TTL lapses."""
    now_ms = int(time.time() * 1000)
    async with app.state.pool.acquire() as conn:
        await _seed_device(conn)
        await _mk_share(conn, "tok-inc", now_ms, now_ms + 3_600_000)
        await _seed_fix(conn, now_ms - 90_000, 43.0, -87.9)
        await _seed_fix(conn, now_ms - 30_000, 43.1, -87.8)
    full = (await client.get("/share/tok-inc/feed")).json()
    assert len(full["points"]) == 2
    seam = full["points"][-1]["t"]
    async with app.state.pool.acquire() as conn:
        await _seed_fix(conn, now_ms - 5_000, 43.2, -87.7)
    inc = (await client.get(f"/share/tok-inc/feed?since={seam}")).json()
    assert [p["t"] for p in inc["points"]] == [seam]   # seam bucket re-sent, nothing older
    app.state.share_track_cache.clear()                # TTL lapse
    inc2 = (await client.get(f"/share/tok-inc/feed?since={seam}")).json()
    assert len(inc2["points"]) == 2                    # seam + the newly landed fix


async def test_feed_since_keeps_last_as_the_whole_trails_newest(app, client):
    """A poll with nothing new must not blank the live chair marker."""
    now_ms = int(time.time() * 1000)
    async with app.state.pool.acquire() as conn:
        await _seed_device(conn)
        await _mk_share(conn, "tok-last", now_ms, now_ms + 3_600_000)
        await _seed_fix(conn, now_ms - 90_000, 43.0, -87.9)
    body = (await client.get(f"/share/tok-last/feed?since={now_ms}")).json()
    assert body["points"] == []                # nothing at/after `since`...
    assert body["last"]["lat"] == 43.0         # ...but the marker still has a position


async def test_feed_garbage_or_stale_since_degrades_to_the_full_trail(app, client):
    """A share link must never 4xx on a stray query param; the whole day is always a
    correct answer, and a `since` from before today's window has nothing to trim."""
    now_ms = int(time.time() * 1000)
    async with app.state.pool.acquire() as conn:
        await _seed_device(conn)
        await _mk_share(conn, "tok-junk", now_ms, now_ms + 3_600_000)
        await _seed_fix(conn, now_ms - 90_000, 43.0, -87.9)
    for q_str in ("since=banana", "since=", "since=-5", "since=0", "since=1e9"):
        r = await client.get(f"/share/tok-junk/feed?{q_str}")
        assert r.status_code == 200, q_str
        assert len(r.json()["points"]) == 1, q_str


async def test_feed_since_does_not_resurrect_expired_or_gone_shares(app, client):
    now_ms = int(time.time() * 1000)
    async with app.state.pool.acquire() as conn:
        await _seed_device(conn)
        await _mk_share(conn, "tok-warm2", now_ms, now_ms + 3_600_000)
        await _mk_share(conn, "tok-dead2", now_ms - 7_200_000, now_ms - 3_600_000)
        await _seed_fix(conn, now_ms - 60_000, 43.0, -87.9)
    assert (await client.get("/share/tok-warm2/feed")).status_code == 200
    assert (await client.get(f"/share/tok-dead2/feed?since={now_ms}")).status_code == 410
    assert (await client.get(f"/share/tok-nope2/feed?since={now_ms}")).status_code == 404


async def test_feed_rate_limited_per_ip(app):
    limit = app.state.share_limiter.max_attempts
    assert limit >= 15 * 4  # a 4 s guest poll is 15/min; leave room for several guests
    transport = ASGITransport(app=app, client=("9.9.9.9", 12345))
    async with AsyncClient(transport=transport, base_url="http://t") as c:
        codes = [(await c.get("/share/tok-nope/feed")).status_code
                 for _ in range(limit + 1)]
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
