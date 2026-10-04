"""SEC-23/SRV-17: /api/v1/health/detail is the deadman an uptime monitor polls. 200 only
while telemetry arrives and the background jobs keep up; 503 naming what failed. A missing
signal is a failure, never a pass. /api/v1/health stays a bare DB ping (autoheal uses it)."""
import json
import secrets
import time

from app.auth.api_key import hash_key
from app.db import queries as q
from app.observability import evaluate_health
from app.routers import health as health_router
from tests.test_ingest_jwt import _enroll_device, _keypair, _payload, _token

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
    app.state.auth_stats.record_failure(DEV, "clock_skew", -700)
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
    s.record_failure(DEV, "clock_skew", -(10 ** 4300))
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


# I1: the ingest check means "live samples are being stored". Only a live batch that gets at
# least one valid sample to the insert refreshes devices.last_seen_at.

def _sample(**over) -> dict:
    return {**_payload()["samples"][0], "ts_ms": int(time.time() * 1000), **over}


# One sample per drop rule: schema (no address), ts window, address rule.
def _all_invalid() -> list[dict]:
    return [{"ts_ms": int(time.time() * 1000)}, _sample(ts_ms=1), _sample(address="not a mac")]


async def _post(client, priv, device_id, path, payload, ua=None):
    body = json.dumps(payload).encode()
    headers = {"Authorization": f"Bearer {_token(priv, device_id, body)}"}
    if ua is not None:
        headers["User-Agent"] = ua
    return await client.post(path, content=body, headers=headers)


async def _ingest(client, priv, device_id, samples, batch_seq=7):
    return await _post(client, priv, device_id, "/api/v1/ingest",
                       {"batch_seq": batch_seq, "samples": samples})


async def _device(app):
    priv, spki = _keypair()
    return priv, await _enroll_device(app, spki)


async def _last_seen(app, device_id):
    async with app.state.pool.acquire() as conn:
        return await conn.fetchval("SELECT last_seen_at FROM devices WHERE id = $1", device_id)


async def _age_last_upload(app, device_id, secs: int):
    """Backdate the last upload and reset the write throttle, so the next batch WOULD
    refresh last_seen_at if it qualified."""
    async with app.state.pool.acquire() as conn:
        await conn.execute("UPDATE devices SET last_seen_at = now() - make_interval(secs => $2) "
                           "WHERE id = $1", device_id, secs)
    app.state.device_touch.clear()
    return await _last_seen(app, device_id)


async def test_a_batch_whose_every_sample_is_dropped_leaves_the_deadman_failing(app, client):
    priv, device_id = await _device(app)
    async with app.state.pool.acquire() as conn:
        h = await _key(conn)
        await _rollup_ran(conn)
    assert (await _ingest(client, priv, device_id, [_sample()])).status_code == 200
    before = await _age_last_upload(app, device_id, 5)
    r = await _ingest(client, priv, device_id, _all_invalid())
    assert r.status_code == 200 and r.json() == {"accepted": 0, "dropped": 3, "last_seq": 7}
    assert await _last_seen(app, device_id) == before
    r = await client.get(URL + "?max_ingest_age_s=1", headers=h)
    assert r.status_code == 503 and r.json()["failing"] == ["ingest"]


async def test_a_live_batch_with_one_valid_sample_refreshes_the_signal(app, client):
    priv, device_id = await _device(app)
    async with app.state.pool.acquire() as conn:
        h = await _key(conn)
        await _rollup_ran(conn)
    assert (await _ingest(client, priv, device_id, [_sample()])).status_code == 200
    before = await _age_last_upload(app, device_id, 5)
    r = await _ingest(client, priv, device_id, [_sample(ts_ms=int(time.time() * 1000) + 1)]
                      + _all_invalid())
    assert r.json() == {"accepted": 1, "dropped": 3, "last_seq": 7}
    assert await _last_seen(app, device_id) > before
    r = await client.get(URL + "?max_ingest_age_s=60", headers=h)
    assert "ingest" not in r.json()["failing"]


async def test_a_resent_batch_of_stored_samples_still_counts(app, client):
    """A re-send of rows already stored (accepted == 0 by the samples key) is a healthy
    pipeline: the samples reached the insert."""
    priv, device_id = await _device(app)
    samples = [_sample()]
    assert (await _ingest(client, priv, device_id, samples)).json()["accepted"] == 1
    before = await _age_last_upload(app, device_id, 5)
    assert (await _ingest(client, priv, device_id, samples)).json()["accepted"] == 0
    assert await _last_seen(app, device_id) > before


async def test_an_import_batch_does_not_refresh_the_signal(app, client):
    priv, device_id = await _device(app)
    r = await _ingest(client, priv, device_id, [_sample()], batch_seq=-1)
    assert r.json()["accepted"] == 1
    assert await _last_seen(app, device_id) is None


async def test_a_config_push_records_the_build_but_not_the_signal(app, client):
    priv, device_id = await _device(app)
    cfg = {"profile_id": "redodo", "cold_caution_c": 5, "hot_caution_c": 45, "cold_crit_c": -12,
           "hot_crit_c": 53, "unit": "F", "updated_at_ms": 1}
    r = await _post(client, priv, device_id, "/api/v1/config", cfg, ua="bmsmon-android/2.0")
    assert r.status_code == 200
    async with app.state.pool.acquire() as conn:
        row = await conn.fetchrow("SELECT last_seen_at, user_agent FROM devices WHERE id = $1",
                                  device_id)
    assert row["last_seen_at"] is None and row["user_agent"] == "bmsmon-android/2.0"


async def test_a_dropped_batch_does_not_use_up_the_write_throttle(app, client):
    priv, device_id = await _device(app)
    assert (await _ingest(client, priv, device_id, _all_invalid())).json()["accepted"] == 0
    assert await _last_seen(app, device_id) is None
    assert (await _ingest(client, priv, device_id, [_sample()])).json()["accepted"] == 1
    assert await _last_seen(app, device_id) is not None
