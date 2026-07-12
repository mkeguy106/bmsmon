from datetime import datetime, timezone

import pytest

from app.db import queries as q
from app.models import SampleIn

A = "C8:47:80:15:67:44"
DEV = "00000000-0000-0000-0000-000000000001"


def _sample(ts_ms, **overrides):
    base = {"ts_ms": ts_ms, "state": "Discharging", "soc": 88.0, "current_a": -4.0,
            "power_w": -51.0, "voltage_v": 13.2, "temp_c": 24.0, "soh": 99,
            "full_charge_ah": 100.0, "remaining_ah": 88.0, "cycles": 12,
            "cell_min_v": 3.31, "cell_max_v": 3.34}
    base.update(overrides)
    return base


async def _device(conn):
    await conn.execute(
        "INSERT INTO devices (id, install_uuid, public_key_spki) VALUES ($1,$2,$3)",
        DEV, "uuid-cells-1", b"\x00",
    )


async def test_cells_persist_and_surface_in_snapshot(app):
    pool = app.state.pool
    async with pool.acquire() as conn:
        await _device(conn)
        base = int(datetime(2026, 7, 1, tzinfo=timezone.utc).timestamp() * 1000)
        await q.upsert_battery(conn, A, "R-12100", "2012 · A", "2012", base)
        row = q.sample_row(DEV, A, _sample(base, cells=[3.32, 3.31, 3.34, 3.33]))
        assert await q.insert_samples(conn, [row]) == 1

        snap = {r["address"]: r for r in await q.fleet_snapshot(conn)}
        # `real` (postgres single-precision) round-trips with float32 rounding error,
        # same as the pre-existing cell_min_v/cell_max_v columns -- approx, not ==.
        assert snap[A]["cells"] == pytest.approx([3.32, 3.31, 3.34, 3.33], abs=1e-5)


async def test_absent_cells_gives_null(app):
    pool = app.state.pool
    async with pool.acquire() as conn:
        await _device(conn)
        base = int(datetime(2026, 7, 1, 1, tzinfo=timezone.utc).timestamp() * 1000)
        await q.upsert_battery(conn, A, "R-12100", "2012 · A", "2012", base)
        row = q.sample_row(DEV, A, _sample(base))
        assert await q.insert_samples(conn, [row]) == 1

        snap = {r["address"]: r for r in await q.fleet_snapshot(conn)}
        assert snap[A]["cells"] is None


def test_sample_in_clips_cells_to_4():
    # A malformed/oversized upload (e.g. a 6s pack) must not leak past 4 elements in
    # model_dump() -- that's what both the WS broadcast (api_device.py publish) and
    # q.sample_row() consume, so this keeps the WS frame in agreement with the
    # REST fleet_snapshot's fixed cell1_v..cell4_v shape.
    s = SampleIn(ts_ms=1, address=A, cells=[3.30, 3.31, 3.32, 3.33, 3.34, 3.35])
    assert s.cells == [3.30, 3.31, 3.32, 3.33]
    assert s.model_dump()["cells"] == [3.30, 3.31, 3.32, 3.33]


def test_sample_in_cells_none_stays_none():
    s = SampleIn(ts_ms=1, address=A)
    assert s.cells is None
    assert s.model_dump()["cells"] is None
