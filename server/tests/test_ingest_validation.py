"""C3/SRV-18: samples are validated one by one. A bad sample is dropped and logged and
never 422s the batch (the phone deletes 4xx'd batches); only a malformed envelope 422s."""
import json
import logging
import time

import pytest

from tests.test_ingest_jwt import A, _enroll_device, _keypair, _token
from tests.test_range_config import _cfg, _range_row

LOGGER = "app.routers.api_device"


async def _post(client, priv, device_id, payload, path="/api/v1/ingest"):
    body = payload if isinstance(payload, bytes) else json.dumps(payload).encode()
    return await client.post(path, content=body,
                             headers={"Authorization": f"Bearer {_token(priv, device_id, body)}"})


def _ok(ts_ms, **kw):
    return {"ts_ms": ts_ms, "address": A, "soc": 80.0, **kw}


async def test_one_wrong_typed_sample_is_dropped_not_the_batch(app, client):
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    now = int(time.time() * 1000)
    payload = {"batch_seq": 21, "samples": [
        _ok(now - 2000),
        _ok(now - 1000, soh=99.5),  # fractional value in an int field (SRV-18's example)
        _ok(now)]}
    r = await _post(client, priv, device_id, payload)
    assert r.status_code == 200
    assert r.json() == {"accepted": 2, "dropped": 1, "last_seq": 21}
    async with app.state.pool.acquire() as conn:
        stored = sorted(x["ts_ms"] for x in await conn.fetch("SELECT ts_ms FROM samples"))
    assert stored == [now - 2000, now]


async def test_every_kind_of_bad_sample_is_dropped(app, client):
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    now = int(time.time() * 1000)
    bad = [
        {"ts_ms": now, "address": A, "regen": None},  # null for a non-null bool
        {"ts_ms": now, "address": A, "soc": "high"},  # string where a number goes
        {"ts_ms": now, "address": A, "cycles": 1.5},  # fractional int
        {"address": A, "soc": 1.0},                   # missing ts_ms
        {"ts_ms": now, "soc": 1.0},                   # missing address
        42, "sample", None, [1, 2],                   # not an object at all
    ]
    r = await _post(client, priv, device_id, {"batch_seq": 22, "samples": [*bad, _ok(now)]})
    assert r.status_code == 200
    assert r.json() == {"accepted": 1, "dropped": len(bad), "last_seq": 22}


async def test_drop_reasons_add_up(app, client):
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    now = int(time.time() * 1000)
    payload = {"batch_seq": 23, "samples": [
        _ok(now, mosfet_temp_c="hot"),                # schema
        _ok(0),                                       # ts window
        {**_ok(now - 1), "address": "junk addr"},     # address rule
        _ok(now - 2)]}
    r = await _post(client, priv, device_id, payload)
    assert r.json() == {"accepted": 1, "dropped": 3, "last_seq": 23}


async def test_unknown_fields_are_ignored_not_dropped(app, client):
    # Server-first deploy ordering relies on this: a newer app's extra keys still ingest.
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    now = int(time.time() * 1000)
    payload = {"batch_seq": 24, "samples": [_ok(now, some_future_field={"x": 1})]}
    r = await _post(client, priv, device_id, payload)
    assert r.json() == {"accepted": 1, "dropped": 0, "last_seq": 24}


async def test_all_invalid_batch_is_200_and_touches_nothing(app, client):
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    now = int(time.time() * 1000)
    r = await _post(client, priv, device_id,
                    {"batch_seq": 25, "samples": [_ok(now, soh="x"), 7]})
    assert r.json() == {"accepted": 0, "dropped": 2, "last_seq": 25}
    async with app.state.pool.acquire() as conn:
        assert await conn.fetchval("SELECT count(*) FROM batteries") == 0


@pytest.mark.parametrize("raw", [
    b"not json",
    b"[]",
    b'"a string"',
    b'{"batch_seq": 1}',                    # no samples list
    b'{"batch_seq": 1, "samples": {}}',     # samples not a list
    b'{"batch_seq": 1, "samples": null}',   # samples null
    b'{"samples": []}',                     # no batch_seq
    b'{"batch_seq": "x", "samples": []}',   # batch_seq not an int
])
async def test_only_a_malformed_envelope_is_422(app, client, raw):
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    r = await _post(client, priv, device_id, raw)
    assert r.status_code == 422


async def test_drops_are_logged_once_per_device_per_minute_without_values(app, client, caplog):
    caplog.set_level(logging.WARNING, logger=LOGGER)
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    now = int(time.time() * 1000)
    bad = _ok(now, soh=99.5, lat=41.8781, lon=-87.6298)
    for seq in (26, 27):
        r = await _post(client, priv, device_id, {"batch_seq": seq, "samples": [bad]})
        assert r.json()["dropped"] == 1
    lines = [rec.getMessage() for rec in caplog.records
             if rec.name == LOGGER and "invalid sample" in rec.getMessage()]
    assert len(lines) == 1  # the second batch, inside the window, is suppressed
    assert device_id in lines[0]
    assert "soh" in lines[0] and "int_from_float" in lines[0]
    for value in ("41.8781", "-87.6298", "99.5"):  # never log values: samples carry GPS
        assert value not in lines[0]


async def test_every_drop_kind_logs_once_per_device_and_devices_are_independent(
        app, client, caplog):
    # N1: the ts-window and address drops go through the same per-device throttle as the
    # schema rejects, each kind on its own clock, so one noisy kind can't hide another.
    from app.db import queries as q
    caplog.set_level(logging.WARNING, logger=LOGGER)
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    priv2, spki2 = _keypair()
    async with app.state.pool.acquire() as conn:
        other_id = str(await q.create_device(conn, "inst-y", spki2, "other"))
    now = int(time.time() * 1000)

    def batch(seq):
        return {"batch_seq": seq, "samples": [
            _ok(now, soh=99.5),                          # schema
            _ok(0),                                      # ts window
            {**_ok(now - 1), "address": "junk addr"}]}   # address rule

    def lines(device, needle):
        return [r.getMessage() for r in caplog.records
                if r.name == LOGGER and needle in r.getMessage() and device in r.getMessage()]

    for seq in (30, 31):  # second batch lands inside every window
        assert (await _post(client, priv, device_id, batch(seq))).json()["dropped"] == 3
    for needle in ("invalid sample", "out-of-range ts_ms", "invalid address"):
        assert len(lines(device_id, needle)) == 1, needle
    # another device has its own windows
    assert (await _post(client, priv2, other_id, batch(32))).json()["dropped"] == 3
    for needle in ("invalid sample", "out-of-range ts_ms", "invalid address"):
        assert len(lines(other_id, needle)) == 1, needle
        assert len(lines(device_id, needle)) == 1, needle


async def test_config_drops_a_bad_range_row_and_keeps_the_rest(app, client):
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    good = _range_row("C8:47:80:15:25:01")
    bad = {**_range_row("C8:47:80:15:07:DE"), "wh_per_day_lo": "lots"}
    r = await _post(client, priv, device_id, _cfg(ranges=[bad, good]), path="/api/v1/config")
    assert r.status_code == 200
    assert r.json() == {"ok": True, "dropped": 1}
    async with app.state.pool.acquire() as conn:
        addrs = [x["address"] for x in await conn.fetch("SELECT address FROM device_range_config")]
        temp_rows = await conn.fetchval("SELECT count(*) FROM device_temp_config")
    assert addrs == ["C8:47:80:15:25:01"]
    assert temp_rows == 1


async def test_config_malformed_core_is_still_422(app, client):
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    r = await _post(client, priv, device_id, {**_cfg(), "cold_caution_c": "chilly"},
                    path="/api/v1/config")
    assert r.status_code == 422


async def test_config_range_row_drop_is_logged_without_values(app, client, caplog):
    caplog.set_level(logging.WARNING, logger=LOGGER)
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    bad = {**_range_row("C8:47:80:15:07:DE"), "wh_per_day_lo": "lots"}
    r = await _post(client, priv, device_id, _cfg(ranges=[bad]), path="/api/v1/config")
    assert r.json() == {"ok": True, "dropped": 1}
    lines = [rec.getMessage() for rec in caplog.records
             if rec.name == LOGGER and "range row" in rec.getMessage()]
    assert len(lines) == 1
    assert "/api/v1/config" in lines[0] and device_id in lines[0]
    assert "wh_per_day_lo" in lines[0] and "float_parsing" in lines[0]
    assert "lots" not in lines[0]
