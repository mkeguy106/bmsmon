"""Task S1: the phone's charger snapshot (the optional `phone` block of a live ingest
batch) is stored on devices. It must NEVER cost a batch: the phone deletes 4xx'd batches."""
from tests import counts
import json
import logging
import time

import pytest

from tests.test_ingest_jwt import _enroll_device, _keypair, _payload, _token

COLS = ("phone_level, phone_plugged, phone_charge_mah, phone_fault, phone_fault_since, "
        "phone_status_at")


def _block(**kw):
    now_ms = int(time.time() * 1000)
    b = {"level": 80, "plugged": 4, "charge_mah": 3000, "fault": False,
         "fault_since_ms": None, "at_ms": now_ms}
    b.update(kw)
    return b


async def _post(client, priv, device_id, phone, seq=7, **extra):
    p = _payload()
    p["batch_seq"] = seq
    if phone is not ...:
        p["phone"] = phone
    p.update(extra)
    body = json.dumps(p).encode()
    return await client.post("/api/v1/ingest", content=body,
                             headers={"Authorization": f"Bearer {_token(priv, device_id, body)}",
                                      "Content-Type": "application/json"})


async def _row(app, device_id):
    async with app.state.pool.acquire() as conn:
        return await conn.fetchrow(f"SELECT {COLS} FROM devices WHERE id = $1", device_id)


@pytest.fixture
async def dev(app):
    priv, spki = _keypair()
    return priv, await _enroll_device(app, spki)


async def test_valid_block_is_stored(app, client, dev):
    priv, did = dev
    r = await _post(client, priv, did, _block())
    assert r.status_code == 200
    assert counts(r.json()) == {"accepted": 1, "dropped": 0, "last_seq": 7}
    row = await _row(app, did)
    assert (row["phone_level"], row["phone_plugged"], row["phone_charge_mah"],
            row["phone_fault"]) == (80, 4, 3000, False)
    assert row["phone_fault_since"] is None
    assert row["phone_status_at"] is not None


async def test_import_batch_stores_nothing(app, client, dev):
    priv, did = dev
    r = await _post(client, priv, did, _block(), seq=-1)
    assert r.status_code == 200
    row = await _row(app, did)
    assert row["phone_status_at"] is None and row["phone_level"] is None


async def test_no_block_changes_nothing(app, client, dev):
    priv, did = dev
    assert (await _post(client, priv, did, ...)).status_code == 200
    assert (await _row(app, did))["phone_status_at"] is None


@pytest.mark.parametrize("bad", [
    "a string", [1, 2], 5, True, {}, {"level": 80},
    _block(level="80"), _block(level=101), _block(level=-1), _block(plugged=16),
    _block(plugged=None), _block(fault="yes"), _block(fault=None), _block(at_ms="x"),
    _block(level=10**30), _block(plugged=10**30), _block(at_ms=10**30), _block(level=1.5),
    _block(charge_mah="lots"), _block(fault_since_ms="x"),
])
async def test_malformed_blocks_never_cost_the_batch(app, client, dev, bad):
    priv, did = dev
    r = await _post(client, priv, did, bad)
    assert r.status_code == 200
    assert counts(r.json()) == {"accepted": 1, "dropped": 0, "last_seq": 7}
    row = await _row(app, did)
    assert row["phone_status_at"] is None and row["phone_level"] is None
    async with app.state.pool.acquire() as conn:
        assert await conn.fetchval("SELECT count(*) FROM samples") == 1


async def test_unstorable_optionals_degrade_to_null(app, client, dev):
    priv, did = dev
    r = await _post(client, priv, did, _block(charge_mah=10**30, fault=True,
                                              fault_since_ms=10**30))
    assert r.status_code == 200
    row = await _row(app, did)
    assert row["phone_charge_mah"] is None and row["phone_fault_since"] is None
    assert row["phone_fault"] is True


async def test_write_is_throttled_but_a_change_writes_at_once(app, client, dev):
    priv, did = dev
    await _post(client, priv, did, _block(level=80))
    first = await _row(app, did)
    await _post(client, priv, did, _block(level=70))  # same fault/plugged, within 60 s
    again = await _row(app, did)
    assert again["phone_level"] == 80 and again["phone_status_at"] == first["phone_status_at"]
    await _post(client, priv, did, _block(level=70, fault=True))  # fault changed
    assert (await _row(app, did))["phone_fault"] is True
    assert (await _row(app, did))["phone_level"] == 70
    await _post(client, priv, did, _block(level=60, fault=True, plugged=2))  # plugged changed
    assert (await _row(app, did))["phone_plugged"] == 2
    # 60 s later the same state writes again
    app.state.phone_power_cache[did] = (True, 2, time.monotonic() - 61)
    await _post(client, priv, did, _block(level=50, fault=True, plugged=2))
    assert (await _row(app, did))["phone_level"] == 50


async def test_future_fault_since_is_clamped(app, client, dev):
    priv, did = dev
    future = int(time.time() * 1000) + 3_600_000
    await _post(client, priv, did, _block(fault=True, fault_since_ms=future))
    async with app.state.pool.acquire() as conn:
        ok = await conn.fetchval(
            "SELECT phone_fault_since <= now() AND phone_fault_since > now() - interval '1 minute' "
            "FROM devices WHERE id = $1", did)
    assert ok is True


@pytest.mark.parametrize("shift_ms", [3_600_000, -3_600_000, 0])
async def test_fault_age_is_independent_of_phone_clock_skew(app, client, dev, shift_ms):
    priv, did = dev
    at = int(time.time() * 1000) + shift_ms
    await _post(client, priv, did, _block(at_ms=at, fault=True, fault_since_ms=at - 400_000))
    async with app.state.pool.acquire() as conn:
        age = await conn.fetchval(
            "SELECT extract(epoch FROM now() - phone_fault_since) FROM devices WHERE id = $1", did)
    assert abs(age - 400) <= 2


async def test_fault_since_after_at_is_clamped_to_now(app, client, dev):
    priv, did = dev
    at = int(time.time() * 1000)
    await _post(client, priv, did, _block(at_ms=at, fault=True, fault_since_ms=at + 600_000))
    async with app.state.pool.acquire() as conn:
        age = await conn.fetchval(
            "SELECT extract(epoch FROM now() - phone_fault_since) FROM devices WHERE id = $1", did)
    assert 0 <= age <= 2


async def test_the_phones_exact_block_is_stored(app, client, dev):
    # The literal bytes android CloudJsonTest pins for the phone block.
    priv, did = dev
    body = ('{"batch_seq":7,"samples":[' + json.dumps(_payload()["samples"][0]) + '],"phone":{"level":42,"plugged":4,'
            '"charge_mah":1234,"fault":true,"fault_since_ms":1700000000000,"at_ms":1700000060000}}'
            ).encode()
    r = await client.post("/api/v1/ingest", content=body,
                          headers={"Authorization": f"Bearer {_token(priv, did, body)}",
                                   "Content-Type": "application/json"})
    assert r.status_code == 200 and r.json()["accepted"] == 1
    row = await _row(app, did)
    assert (row["phone_level"], row["phone_plugged"], row["phone_charge_mah"],
            row["phone_fault"]) == (42, 4, 1234, True)
    async with app.state.pool.acquire() as conn:
        age = await conn.fetchval(
            "SELECT extract(epoch FROM now() - phone_fault_since) FROM devices WHERE id = $1", did)
    assert abs(age - 60) <= 2


async def _detail(app, client):
    from tests.test_health_detail import _key, URL
    async with app.state.pool.acquire() as conn:
        h = await _key(conn)
    return await client.get(URL + "?checks=phone_power", headers=h)


async def test_ingested_fault_six_minutes_old_pages_and_two_minutes_does_not(app, client, dev):
    priv, did = dev
    now = int(time.time() * 1000)
    await _post(client, priv, did, _block(at_ms=now, fault=True, fault_since_ms=now - 360_000))
    r = await _detail(app, client)
    assert r.status_code == 503 and r.json()["failing"] == ["phone_power"]
    app.state.phone_power_cache.clear()
    await _post(client, priv, did, _block(at_ms=now, fault=True, fault_since_ms=now - 120_000))
    assert (await _detail(app, client)).status_code == 200


async def test_fault_since_is_stored_and_cleared_with_the_fault(app, client, dev):
    priv, did = dev
    since = int(time.time() * 1000) - 600_000
    await _post(client, priv, did, _block(fault=True, fault_since_ms=since))
    assert (await _row(app, did))["phone_fault_since"] is not None
    await _post(client, priv, did, _block(fault=False, fault_since_ms=since))
    assert (await _row(app, did))["phone_fault_since"] is None


async def test_transition_log_lines(app, client, dev, caplog):
    priv, did = dev
    caplog.set_level(logging.INFO, logger="app.routers.api_device")
    await _post(client, priv, did, _block(fault=False))
    await _post(client, priv, did, _block(fault=True, level=64, plugged=4))
    await _post(client, priv, did, _block(fault=True, level=63, plugged=4))
    await _post(client, priv, did, _block(fault=False))
    msgs = [r.getMessage() for r in caplog.records if "phone charger" in r.getMessage()]
    assert msgs == [f"phone charger fault started device={did} level=64 plugged=4",
                    f"phone charger fault cleared device={did}"]


async def test_invalid_block_logs_a_throttled_warning_without_values(app, client, dev, caplog):
    priv, did = dev
    caplog.set_level(logging.WARNING, logger="app.routers.api_device")
    for _ in range(3):
        await _post(client, priv, did, _block(level="SECRET99"))
    warns = [r.getMessage() for r in caplog.records if "phone" in r.getMessage()]
    assert len(warns) == 1 and "level" in warns[0] and "SECRET99" not in warns[0]


async def test_a_failing_write_never_fails_the_batch(app, client, dev, monkeypatch):
    from app.db import queries as q

    async def boom(*a, **k):
        raise RuntimeError("db hiccup")
    monkeypatch.setattr(q, "store_phone_power", boom)
    priv, did = dev
    r = await _post(client, priv, did, _block())
    assert r.status_code == 200 and r.json()["accepted"] == 1
    assert did not in app.state.phone_power_cache


async def test_schema_applies_twice(app):
    from app.db.pool import apply_schema
    async with app.state.pool.acquire() as conn:
        await apply_schema(conn)
        await apply_schema(conn)
        cols = await conn.fetch(
            "SELECT column_name FROM information_schema.columns WHERE table_name='devices' "
            "AND column_name LIKE 'phone_%'")
    assert {c["column_name"] for c in cols} == {
        "phone_level", "phone_plugged", "phone_charge_mah", "phone_fault",
        "phone_fault_since", "phone_status_at"}


async def test_a_persistent_write_failure_logs_once(app, client, dev, monkeypatch, caplog):
    from app.db import queries as q

    async def boom(*a, **k):
        raise RuntimeError("db hiccup")
    monkeypatch.setattr(q, "store_phone_power", boom)
    caplog.set_level(logging.WARNING, logger="app.routers.api_device")
    priv, did = dev
    for _ in range(3):
        await _post(client, priv, did, _block())
    assert len([r for r in caplog.records if "phone power snapshot failed" in r.getMessage()]) == 1
