from datetime import datetime, timezone

from app.db import queries as q

A = "C8:47:80:15:67:44"
DEV = "00000000-0000-0000-0000-000000000001"


def _sample(ts_ms, soc=87.0):
    return {"ts_ms": ts_ms, "state": "Discharging", "soc": soc, "current_a": -2.5,
            "power_w": 127.5, "voltage_v": 51.0, "temp_c": 25.0, "mosfet_temp_c": 28,
            "soh": 98, "full_charge_ah": 100.0, "remaining_ah": 87.5, "cycles": 342,
            "cell_min_v": 3.17, "cell_max_v": 3.19, "cells": None, "regen": False,
            "link_event": None}


async def _device(conn):
    await conn.execute(
        "INSERT INTO devices (id, install_uuid, public_key_spki) VALUES ($1,$2,$3)",
        DEV, "uuid-1", b"\x00",
    )


async def test_insert_is_idempotent_and_snapshot_returns_latest(app):
    pool = app.state.pool
    async with pool.acquire() as conn:
        await _device(conn)
        base = int(datetime(2026, 6, 29, tzinfo=timezone.utc).timestamp() * 1000)
        rows = [q.sample_row(DEV, A, _sample(base, 80.0)),
                q.sample_row(DEV, A, _sample(base + 1500, 79.0))]
        assert await q.insert_samples(conn, rows) == 2
        # re-send the same batch -> no duplicates
        await q.insert_samples(conn, rows)
        await q.upsert_battery(conn, A, "R-12100", "2012 · A", "2012", base + 1500)

        snap = await q.fleet_snapshot(conn)
        assert len(snap) == 1
        assert snap[0]["address"] == A
        assert snap[0]["soc"] == 79.0          # the latest sample
        assert snap[0]["alias"] == "2012 · A"

        total = await conn.fetchval("SELECT count(*) FROM samples")
        assert total == 2                        # idempotent
