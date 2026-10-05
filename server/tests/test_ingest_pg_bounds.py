"""Values that pass pydantic but cannot be stored by Postgres must never 500 a batch.

insert_samples is ONE set-based statement per batch, so a single int4 overflow, float4
overflow (asyncpg's binary codec raises OverflowError) or NUL byte in a text value used
to fail the whole batch with a 500 — and the phone retries a 500 forever, so that batch
head-of-line-blocks its upload queue. Optional fields degrade to NULL (NaN/±inf too, and
NUL bytes are stripped); required config fields fail validation instead (the range row
is dropped, or the config envelope 422s), because NULL would violate NOT NULL."""
from tests import counts
import json
import time

import pytest

from app.models import RangeConfigRow, SampleIn, TempConfigBody
from tests.test_ingest_jwt import A, _enroll_device, _keypair, _token
from tests.test_range_config import _cfg, _range_row

INT32_MAX = 2**31 - 1
FLOAT32_MAX = 3.4028234663852886e38


async def _post(client, priv, device_id, payload, path="/api/v1/ingest"):
    # json.dumps writes NaN/Infinity literals, which the server's JSON parser accepts.
    body = json.dumps(payload).encode()
    return await client.post(path, content=body,
                             headers={"Authorization": f"Bearer {_token(priv, device_id, body)}"})


async def _row(app, ts_ms, cols):
    async with app.state.pool.acquire() as conn:
        return await conn.fetchrow(f"SELECT {cols} FROM samples WHERE ts_ms=$1", ts_ms)


async def test_ints_outside_int4_store_null_and_the_batch_lands(app, client):
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    now = int(time.time() * 1000)
    payload = {"batch_seq": 1, "samples": [
        {"ts_ms": now, "address": A, "soc": 80.0,
         "cycles": 3_000_000_000, "soh": -3_000_000_000, "mosfet_temp_c": INT32_MAX + 1},
        {"ts_ms": now - 1000, "address": A, "soc": 81.0, "cycles": INT32_MAX}]}
    r = await _post(client, priv, device_id, payload)
    assert r.status_code == 200
    assert counts(r.json()) == {"accepted": 2, "dropped": 0, "last_seq": 1}
    row = await _row(app, now, "soc, cycles, soh, mosfet_temp_c")
    assert row["soc"] == 80.0
    assert (row["cycles"], row["soh"], row["mosfet_temp_c"]) == (None, None, None)
    assert (await _row(app, now - 1000, "cycles"))["cycles"] == INT32_MAX  # boundary kept


async def test_nul_bytes_are_stripped_from_every_text_field(app, client):
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    now = int(time.time() * 1000)
    payload = {"batch_seq": 2, "samples": [
        {"ts_ms": now - 1000, "address": A, "link_event": "Connected\x00"},
        # last in the batch: the batteries upsert uses each address's last sample
        {"ts_ms": now, "address": A, "state": "Idle\x00", "motion_activity": "ST\x00ILL",
         "alias": "2012\x00 · A", "advertised_name": "\x00R-12100", "group_id": "20\x0012"}]}
    r = await _post(client, priv, device_id, payload)
    assert r.status_code == 200
    assert counts(r.json()) == {"accepted": 2, "dropped": 0, "last_seq": 2}
    row = await _row(app, now, "state, motion_activity")
    assert (row["state"], row["motion_activity"]) == ("Idle", "STILL")
    assert (await _row(app, now - 1000, "link_event"))["link_event"] == "Connected"
    async with app.state.pool.acquire() as conn:
        bat = await conn.fetchrow(
            "SELECT alias, advertised_name, group_id FROM batteries WHERE address=$1", A)
    assert dict(bat) == {"alias": "2012 · A", "advertised_name": "R-12100", "group_id": "2012"}


async def test_floats_outside_float4_or_non_finite_store_null(app, client):
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    now = int(time.time() * 1000)
    payload = {"batch_seq": 3, "samples": [
        {"ts_ms": now, "address": A, "soc": 1e39, "current_a": -1e39,
         "voltage_v": float("nan"), "temp_c": float("inf"), "power_w": FLOAT32_MAX,
         "lat": float("nan"), "lon": float("-inf"), "gps_accuracy_m": 5.0},
        {"ts_ms": now - 1000, "address": A, "soc": 50.0}]}
    r = await _post(client, priv, device_id, payload)
    assert r.status_code == 200
    assert counts(r.json()) == {"accepted": 2, "dropped": 0, "last_seq": 3}
    row = await _row(app, now, "soc, current_a, voltage_v, temp_c, power_w, lat, lon, "
                               "gps_accuracy_m")
    for col in ("soc", "current_a", "voltage_v", "temp_c", "lat", "lon"):
        assert row[col] is None, col
    assert row["power_w"] == pytest.approx(FLOAT32_MAX, rel=1e-6)  # largest float4 is kept
    assert row["gps_accuracy_m"] == 5.0


async def test_a_nan_soc_stores_null(app, client):
    # A NaN literal used to ingest as a stored NaN (and a NaN in the live WS frame).
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    now = int(time.time() * 1000)
    r = await _post(client, priv, device_id,
                    {"batch_seq": 4, "samples": [{"ts_ms": now, "address": A, "soc": float("nan")}]})
    assert counts(r.json()) == {"accepted": 1, "dropped": 0, "last_seq": 4}
    assert (await _row(app, now, "soc"))["soc"] is None


async def test_bad_cells_become_null_in_place(app, client):
    # cells are positional (cell1_v..cell4_v), so a bad item is nulled, never removed.
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    now = int(time.time() * 1000)
    payload = {"batch_seq": 5, "samples": [
        {"ts_ms": now, "address": A, "cells": [3.31, 1e39, float("nan"), 3.34]}]}
    r = await _post(client, priv, device_id, payload)
    assert counts(r.json()) == {"accepted": 1, "dropped": 0, "last_seq": 5}
    row = await _row(app, now, "cell1_v, cell2_v, cell3_v, cell4_v")
    assert row["cell1_v"] == pytest.approx(3.31, abs=1e-5)
    assert (row["cell2_v"], row["cell3_v"]) == (None, None)
    assert row["cell4_v"] == pytest.approx(3.34, abs=1e-5)


def test_sample_model_bounds_are_exact():
    s = SampleIn(ts_ms=1, address=A, cycles=INT32_MAX, soh=-(2**31), mosfet_temp_c=2**31,
                 soc=-FLOAT32_MAX, voltage_v=FLOAT32_MAX * 1.0000001, lat=1e300)
    assert (s.cycles, s.soh, s.mosfet_temp_c) == (INT32_MAX, -(2**31), None)
    assert s.soc == -FLOAT32_MAX and s.voltage_v is None
    assert s.lat == 1e300  # lat/lon are float8: only non-finite values are nulled


async def test_config_range_row_with_unstorable_values_is_dropped_not_500(app, client):
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    bad = [
        {**_range_row("C8:47:80:15:07:DE"), "wh_per_day_lo": 1e39},        # float4 overflow
        {**_range_row("C8:47:80:15:25:9A"), "active_w_hi": float("nan")},  # non-finite
        {**_range_row("C8:47:80:15:DB:13"), "learned_days": 3_000_000_000},  # int4 overflow
        {**_range_row("C8:47:80:46:0A:D6"), "updated_at_ms": 2**63},       # int8 overflow
    ]
    good = _range_row("C8:47:80:15:25:01\x00")
    r = await _post(client, priv, device_id, _cfg(ranges=[*bad, good]), path="/api/v1/config")
    assert r.status_code == 200
    assert counts(r.json()) == {"ok": True, "dropped": len(bad)}
    async with app.state.pool.acquire() as conn:
        addrs = [x["address"] for x in await conn.fetch("SELECT address FROM device_range_config")]
    assert addrs == ["C8:47:80:15:25:01"]  # stored with the NUL stripped


async def test_config_optional_fields_degrade_and_text_is_stripped(app, client):
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    cfg = {**_cfg(), "profile_id": "redodo\x00", "unit": "F\x00", "cutoff_cold_c": 1e39,
           "cutoff_hot_c": float("nan"), "charge_lock_cold_c": 0.0, "seize_soc": 3_000_000_000}
    r = await _post(client, priv, device_id, cfg, path="/api/v1/config")
    assert r.status_code == 200
    async with app.state.pool.acquire() as conn:
        row = await conn.fetchrow(
            "SELECT profile_id, unit, cutoff_cold_c, cutoff_hot_c, charge_lock_cold_c "
            "FROM device_temp_config")
        alert_rows = await conn.fetchval("SELECT count(*) FROM device_alert_config")
    assert dict(row) == {"profile_id": "redodo", "unit": "F", "cutoff_cold_c": None,
                         "cutoff_hot_c": None, "charge_lock_cold_c": 0.0}
    assert alert_rows == 0  # an unstorable seize_soc is absent, so the alert config is untouched


@pytest.mark.parametrize("field,value", [
    ("cold_caution_c", 3_000_000_000),
    ("hot_crit_c", -(2**31) - 1),
    ("updated_at_ms", 2**63),
])
async def test_config_unstorable_required_value_is_422_not_500(app, client, field, value):
    # The core thresholds ARE the config envelope (422 when malformed, see C3); a value
    # Postgres cannot store is malformed, and NULL would violate NOT NULL.
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    r = await _post(client, priv, device_id, {**_cfg(), field: value}, path="/api/v1/config")
    assert r.status_code == 422


def test_config_models_reject_non_finite_required_floats():
    with pytest.raises(ValueError):
        RangeConfigRow.model_validate({**_range_row(), "wh_per_mile_hi": float("inf")})
    assert TempConfigBody.model_validate({**_cfg(), "charge_resume_cold_c": float("-inf")}
                                         ).charge_resume_cold_c is None
