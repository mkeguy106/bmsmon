"""CARTO basemap key, injected at runtime (BMSMON_CARTO_KEY).

The repo and the GHCR image are public, so the key never lives in either: the server reads
it from the NAS env and hands it to authenticated viewers (/web/map-config) and to holders
of an active share link (/share/{token}/map-config). These tests use an obviously fake key.
"""
import logging
import time

from httpx import ASGITransport, AsyncClient

from app.auth.enroll import hash_code
from app.config import CARTO_KEY_ENV, Settings, parse_carto_key
from app.db import queries as q
from app.main import create_app
from tests.identities import OUTSIDER_H, VIEWER_H

FAKE_KEY = "test_key_123456"


# --- parsing: strict shape, never echoed ------------------------------------------

def test_unset_is_none_without_a_problem():
    # No key is a normal state (dev, CI): nothing to warn about.
    assert parse_carto_key(None) == (None, None)


def test_valid_key_is_accepted():
    assert parse_carto_key(FAKE_KEY) == (FAKE_KEY, None)
    assert parse_carto_key("abc-DEF_09") == ("abc-DEF_09", None)


def test_surrounding_whitespace_and_quotes_are_stripped():
    for raw in (f"  {FAKE_KEY}\n", f'"{FAKE_KEY}"', f"'{FAKE_KEY}'", f' " {FAKE_KEY} " '):
        assert parse_carto_key(raw) == (FAKE_KEY, None), repr(raw)


def test_length_bounds_are_inclusive():
    assert parse_carto_key("a" * 8)[0] == "a" * 8
    assert parse_carto_key("a" * 128)[0] == "a" * 128


def test_invalid_values_are_dropped_with_a_reason():
    for raw in ("", "   ", '""', "short7c", "a" * 129, "test key 123456",
                "test_key_12345!", "tést_key_123456", "test_key_123456;rm"):
        key, problem = parse_carto_key(raw)
        assert key is None, repr(raw)
        assert problem, repr(raw)


def test_reason_depends_only_on_the_kind_of_problem():
    # A reason that reads the same for two different values can't carry any part of either.
    for a, b in (("", "  ''  "),                          # empty
                 ("test_key_12345!", "other value 99"),   # charset
                 ("short7c", "x"), ("a" * 129, "b" * 300)):  # length
        assert parse_carto_key(a)[1] == parse_carto_key(b)[1], (a, b)
        assert a.strip() not in parse_carto_key(a)[1] or not a.strip()


def test_settings_reads_the_env_at_construction(monkeypatch):
    monkeypatch.delenv(CARTO_KEY_ENV, raising=False)
    assert Settings().carto_key is None
    monkeypatch.setenv(CARTO_KEY_ENV, f'"{FAKE_KEY}"')
    assert Settings().carto_key == FAKE_KEY
    monkeypatch.setenv(CARTO_KEY_ENV, "test_key_12345!")
    assert Settings().carto_key is None


def test_settings_repr_never_shows_the_key(monkeypatch):
    monkeypatch.setenv(CARTO_KEY_ENV, FAKE_KEY)
    assert FAKE_KEY not in repr(Settings())


# --- startup warning: one, naming the variable, never the value --------------------

def _carto_warnings(caplog):
    return [r for r in caplog.records
            if r.levelno == logging.WARNING and CARTO_KEY_ENV in r.getMessage()]


def test_invalid_key_logs_one_startup_warning_without_the_value(monkeypatch, caplog):
    bad = "test_key_12345!"
    monkeypatch.setenv(CARTO_KEY_ENV, bad)
    caplog.set_level(logging.WARNING)
    create_app()
    hits = _carto_warnings(caplog)
    assert len(hits) == 1
    assert bad not in caplog.text
    assert "test_key" not in caplog.text


def test_valid_or_unset_key_logs_nothing(monkeypatch, caplog):
    caplog.set_level(logging.WARNING)
    monkeypatch.setenv(CARTO_KEY_ENV, FAKE_KEY)
    create_app()
    monkeypatch.delenv(CARTO_KEY_ENV)
    create_app()
    assert _carto_warnings(caplog) == []
    assert FAKE_KEY not in caplog.text


# --- GET /web/map-config: viewer-gated, no-store -----------------------------------

async def test_web_map_config_key_unset_is_null(client, set_setting):
    set_setting("carto_key", None)
    r = await client.get("/web/map-config", headers=VIEWER_H)
    assert r.status_code == 200
    assert r.json() == {"carto_key": None}
    assert r.headers["cache-control"] == "no-store"


async def test_web_map_config_returns_the_key(client, set_setting):
    set_setting("carto_key", FAKE_KEY)
    r = await client.get("/web/map-config", headers=VIEWER_H)
    assert r.status_code == 200
    assert r.json() == {"carto_key": FAKE_KEY}
    assert r.headers["cache-control"] == "no-store"


async def test_web_map_config_rejects_like_the_other_config_endpoints(client, set_setting):
    set_setting("carto_key", FAKE_KEY)
    for headers, code in ((None, 401), (OUTSIDER_H, 403)):
        mine = await client.get("/web/map-config", headers=headers)
        theirs = await client.get("/web/temp-config", headers=headers)
        assert mine.status_code == theirs.status_code == code
        assert mine.content == theirs.content
        assert FAKE_KEY not in mine.text


# --- GET /share/{token}/map-config: the feed's token gate, but not a view ----------

async def _mk_share(conn, token: str, created_ms: int, expires_ms: int,
                    revoked_ms: int | None = None) -> int:
    sid = await q.create_location_share(conn, hash_code(token), "T", "joel",
                                        created_ms, expires_ms)
    if revoked_ms is not None:
        await q.revoke_location_share(conn, sid, revoked_ms)
    return sid


def _sec_headers(r):
    assert r.headers["cache-control"] == "no-store"
    assert r.headers["referrer-policy"] == "no-referrer"


async def test_share_map_config_active_returns_the_key(app, client, set_setting):
    set_setting("carto_key", FAKE_KEY)
    now_ms = int(time.time() * 1000)
    async with app.state.pool.acquire() as conn:
        await _mk_share(conn, "tok-map-live", now_ms, now_ms + 3_600_000)
    r = await client.get("/share/tok-map-live/map-config")
    assert r.status_code == 200
    assert r.json() == {"carto_key": FAKE_KEY}
    _sec_headers(r)


async def test_share_map_config_active_with_no_key_is_null(app, client, set_setting):
    set_setting("carto_key", None)
    now_ms = int(time.time() * 1000)
    async with app.state.pool.acquire() as conn:
        await _mk_share(conn, "tok-map-nokey", now_ms, now_ms + 3_600_000)
    r = await client.get("/share/tok-map-nokey/map-config")
    assert r.status_code == 200
    assert r.json() == {"carto_key": None}
    _sec_headers(r)


async def test_share_map_config_expired_is_410(app, client, set_setting):
    set_setting("carto_key", FAKE_KEY)
    now_ms = int(time.time() * 1000)
    async with app.state.pool.acquire() as conn:
        await _mk_share(conn, "tok-map-exp", now_ms - 7_200_000, now_ms - 3_600_000)
    r = await client.get("/share/tok-map-exp/map-config")
    assert r.status_code == 410
    assert FAKE_KEY not in r.text
    _sec_headers(r)


async def test_share_map_config_unknown_and_revoked_are_the_feeds_bare_404(
        app, client, set_setting):
    set_setting("carto_key", FAKE_KEY)
    now_ms = int(time.time() * 1000)
    async with app.state.pool.acquire() as conn:
        await _mk_share(conn, "tok-map-rev", now_ms, now_ms + 3_600_000, revoked_ms=now_ms)
    unknown = await client.get("/share/tok-map-nope/map-config")
    revoked = await client.get("/share/tok-map-rev/map-config")
    feed_404 = await client.get("/share/tok-map-nope/feed")
    assert unknown.status_code == revoked.status_code == feed_404.status_code == 404
    assert unknown.content == revoked.content == feed_404.content
    for r in (unknown, revoked):
        assert FAKE_KEY not in r.text
        _sec_headers(r)


async def test_share_map_config_does_not_count_as_a_view(app, client, set_setting):
    set_setting("carto_key", FAKE_KEY)
    now_ms = int(time.time() * 1000)
    async with app.state.pool.acquire() as conn:
        await _mk_share(conn, "tok-map-touch", now_ms, now_ms + 3_600_000)
    for _ in range(3):
        assert (await client.get("/share/tok-map-touch/map-config")).status_code == 200
    async with app.state.pool.acquire() as conn:
        row = (await q.list_location_shares(conn, now_ms, 86_400_000))[0]
    assert row["access_count"] == 0
    assert row["last_access_ms"] is None
    # ...and it didn't spend the touch throttle either: the first real view still counts.
    assert (await client.get("/share/tok-map-touch/feed")).status_code == 200
    async with app.state.pool.acquire() as conn:
        row = (await q.list_location_shares(conn, now_ms, 86_400_000))[0]
    assert row["access_count"] == 1


async def test_share_map_config_is_rate_limited_per_ip(app):
    limit = app.state.share_limiter.max_attempts
    transport = ASGITransport(app=app, client=("9.9.9.8", 12345))
    async with AsyncClient(transport=transport, base_url="http://t") as c:
        responses = [await c.get("/share/tok-map-nope/map-config") for _ in range(limit + 1)]
    assert responses[0].status_code == 404
    assert responses[-1].status_code == 429
    _sec_headers(responses[-1])
