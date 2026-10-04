"""SEC-23/SRV-17: /api/v1/health/detail is the deadman an uptime monitor polls. 200 only
while telemetry arrives and the background jobs keep up; 503 naming what failed. A missing
signal is a failure, never a pass. /api/v1/health stays a bare DB ping (autoheal uses it)."""
import secrets

from app.auth.api_key import hash_key
from app.db import queries as q
from app.observability import evaluate_health
from app.routers import health as health_router

DEV = "00000000-0000-0000-0000-0000000000dd"
URL = "/api/v1/health/detail"


async def _key(conn) -> dict:
    key = secrets.token_urlsafe(32)
    await q.create_api_key(conn, "deadman test", hash_key(key))
    return {"X-API-Key": key}


async def _rollup_ran(conn, lag_s: int = 1800) -> None:
    await conn.execute(
        "INSERT INTO samples_rollup_state (id, high_water_ms) "
        "VALUES (1, (extract(epoch from now()) * 1000)::bigint - $1::bigint * 1000)", lag_s)


async def _device_seen(conn, ago_s: int) -> None:
    await conn.execute(
        "INSERT INTO devices (id, install_uuid, public_key_spki, last_seen_at) "
        "VALUES ($1, 'uuid-dd', $2, now() - make_interval(secs => $3))", DEV, b"\x00", ago_s)


async def test_needs_an_api_key(client):
    assert (await client.get(URL)).status_code == 401


async def test_healthy_is_200(app, client):
    async with app.state.pool.acquire() as conn:
        h = await _key(conn)
        await _device_seen(conn, 5)
        await _rollup_ran(conn)
    r = await client.get(URL, headers=h)
    body = r.json()
    assert r.status_code == 200, body
    assert body["ok"] is True and body["failing"] == []
    assert 0 <= body["last_ingest_age_s"] < 60
    assert 1700 <= body["rollup_lag_s"] <= 1900
    assert body["next_month_partition"] is True
    assert "samples_charging_idx" in body["online_indexes"]
    assert r.headers["cache-control"] == "no-store"


async def test_stale_ingest_is_503_and_named(app, client):
    async with app.state.pool.acquire() as conn:
        h = await _key(conn)
        await _device_seen(conn, 2 * 3600)
        await _rollup_ran(conn)
    r = await client.get(URL, headers=h)
    assert r.status_code == 503 and r.json()["failing"] == ["ingest"]


async def test_no_upload_ever_is_a_failure_not_a_pass(app, client):
    async with app.state.pool.acquire() as conn:
        h = await _key(conn)
        await _rollup_ran(conn)
    r = await client.get(URL, headers=h)
    assert r.status_code == 503 and r.json()["failing"] == ["ingest"]
    assert r.json()["last_ingest_age_s"] is None


async def test_the_override_can_only_tighten(app, client):
    async with app.state.pool.acquire() as conn:
        h = await _key(conn)
        await _device_seen(conn, 5)
        await _rollup_ran(conn)
    assert (await client.get(URL + "?max_ingest_age_s=0", headers=h)).status_code == 503
    async with app.state.pool.acquire() as conn:
        await conn.execute("UPDATE devices SET last_seen_at = now() - interval '2 hours'")
    assert (await client.get(URL + "?max_ingest_age_s=999999", headers=h)).status_code == 503


async def test_rollup_and_partition_checks(app, client, monkeypatch):
    async with app.state.pool.acquire() as conn:
        h = await _key(conn)
        await _device_seen(conn, 5)
        await _rollup_ran(conn, lag_s=4 * 3600)
    monkeypatch.setattr(health_router, "next_month_partition_name", lambda now_ms: "samples_2099_01")
    r = await client.get(URL, headers=h)
    assert r.status_code == 503 and r.json()["failing"] == ["rollup", "partition"]


async def test_clock_skew_fails_the_clock_check(app, client):
    async with app.state.pool.acquire() as conn:
        h = await _key(conn)
        await _device_seen(conn, 5)
        await _rollup_ran(conn)
    app.state.auth_stats.record_ok(-585)  # the 2026-09-16 step: accepted now, but page on it
    r = await client.get(URL, headers=h)
    assert r.status_code == 503 and r.json()["failing"] == ["clock"]
    assert r.json()["last_ok_skew_s"] == -585


async def test_auth_failures_are_reported(app, client):
    async with app.state.pool.acquire() as conn:
        h = await _key(conn)
        await _device_seen(conn, 5)
        await _rollup_ran(conn)
    app.state.auth_stats.record_failure("clock_skew", -700)
    body = (await client.get(URL, headers=h)).json()
    assert body["auth_fail_5m"] == 1 and body["last_auth_fail"]["reason"] == "clock_skew"


def test_evaluate_health_rules():
    base = dict(now_ms=10_000_000, last_ingest_ms=9_990_000, rollup_high_water_ms=9_000_000,
                next_month_partition=True, auth_fail_5m=0, last_auth_fail=None,
                last_ok_skew_s=0, online_indexes={}, ingest_limit_s=1800)
    assert evaluate_health(**base)["ok"] is True
    assert evaluate_health(**{**base, "rollup_high_water_ms": 0})["failing"] == ["rollup"]
    assert evaluate_health(**{**base, "last_ok_skew_s": 121})["failing"] == ["clock"]
    assert evaluate_health(**{**base, "last_ok_skew_s": None})["ok"] is True
    assert evaluate_health(**{**base, "last_ingest_ms": 10_000_000 - 1_801_000})["failing"] == ["ingest"]
    assert evaluate_health(**{**base, "next_month_partition": False})["failing"] == ["partition"]


def test_absurd_skew_is_clamped_where_it_is_stored():
    from app.observability import AuthStats, SKEW_CLAMP_S
    s = AuthStats()
    s.record_ok(10 ** 4300)
    s.record_failure("clock_skew", -(10 ** 4300))
    assert s.last_ok_skew_s == SKEW_CLAMP_S
    assert s.last_failure["skew_s"] == -SKEW_CLAMP_S


async def test_a_forged_huge_skew_never_500s_the_endpoint(app, client):
    async with app.state.pool.acquire() as conn:
        h = await _key(conn)
        await _device_seen(conn, 5)
        await _rollup_ran(conn)
    app.state.auth_stats.record_ok(10 ** 4300)
    r = await client.get(URL, headers=h)
    assert r.status_code == 503 and r.json()["failing"] == ["clock"]
    assert isinstance(r.json()["last_ok_skew_s"], int)
