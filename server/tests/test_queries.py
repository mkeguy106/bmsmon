from datetime import datetime, timezone

from app.db import queries as q

A = "C8:47:80:15:67:44"
DEV = "00000000-0000-0000-0000-000000000001"


def _sample(ts_ms, soc=87.0):
    return {"ts_ms": ts_ms, "state": "Discharging", "soc": soc, "current_a": -2.5,
            "power_w": 127.5, "voltage_v": 51.0, "temp_c": 25.0, "mosfet_temp_c": 28,
            "soh": 98, "full_charge_ah": 100.0, "remaining_ah": 87.5, "cycles": 342,
            "cell_min_v": 3.17, "cell_max_v": 3.19, "regen": False,
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


async def test_snapshot_skips_link_event_rows(app):
    # A BLE link transition uploads as a sample with ALL telemetry null and only
    # link_event set. The snapshot must return the latest REAL telemetry row, not
    # the null link row — "disconnected packs keep their last-known telemetry".
    pool = app.state.pool
    async with pool.acquire() as conn:
        await _device(conn)
        base = int(datetime(2026, 6, 30, tzinfo=timezone.utc).timestamp() * 1000)
        # ingest always upserts the battery registry row before inserting samples;
        # the snapshot query is driven by it (T2.5)
        await q.upsert_battery(conn, A, "R-12100", "2012 · A", "2012", base)
        telemetry = q.sample_row(DEV, A, _sample(base, 81.0))
        link_row = q.sample_row(DEV, A, {"ts_ms": base + 5000, "link_event": "Disconnected"})
        assert await q.insert_samples(conn, [telemetry, link_row]) == 2

        snap = await q.fleet_snapshot(conn)
        assert len(snap) == 1
        assert snap[0]["soc"] == 81.0            # the real telemetry, not the null link row
        assert snap[0]["voltage_v"] == 51.0
        assert snap[0]["ts_ms"] == base          # the telemetry row's timestamp


async def test_snapshot_lateral_matches_latest_real_telemetry_per_pack(app):
    # Multi-pack equivalence for the lateral rewrite (T2.5): each pack's snapshot row must
    # be its latest REAL telemetry sample, even with trailing link-event rows on top; a
    # battery registry row with no real samples (link-only pack C) must NOT appear.
    pool = app.state.pool
    packs = {
        "C8:47:80:15:67:44": ("2012 · A", "2012"),
        "C8:47:80:15:62:1B": ("2012 · B", "2012"),
        "C8:47:80:46:0A:D6": ("2023 · A", "2023"),
    }
    link_only = "C8:47:80:15:DB:13"
    base = int(datetime(2026, 6, 28, tzinfo=timezone.utc).timestamp() * 1000)
    async with pool.acquire() as conn:
        await _device(conn)
        rows = []
        for i, (addr, (alias, grp)) in enumerate(packs.items()):
            await q.upsert_battery(conn, addr, "R-12100", alias, grp, base)
            # three telemetry samples, then a trailing link-event row (the newest ts)
            for j in range(3):
                rows.append(q.sample_row(DEV, addr, _sample(base + j * 1500, 90.0 - i - j)))
            rows.append(q.sample_row(
                DEV, addr, {"ts_ms": base + 60_000, "link_event": "Disconnected"}))
        await q.upsert_battery(conn, link_only, "R-12100", "2016 · A", "2016", base)
        rows.append(q.sample_row(
            DEV, link_only, {"ts_ms": base + 1000, "link_event": "Connected"}))
        await q.insert_samples(conn, rows)

        snap = await q.fleet_snapshot(conn)
        assert sorted(r["address"] for r in snap) == sorted(packs)  # no ghost link-only pack
        by_addr = {r["address"]: r for r in snap}
        for i, (addr, (alias, grp)) in enumerate(packs.items()):
            r = by_addr[addr]
            assert r["ts_ms"] == base + 2 * 1500          # latest REAL telemetry row
            assert r["soc"] == 90.0 - i - 2
            assert r["voltage_v"] == 51.0
            assert r["link_event"] is None
            assert r["alias"] == alias and r["group_id"] == grp


async def test_ensure_partition_tolerates_concurrent_create(app):
    # T2.3: two connections racing CREATE TABLE IF NOT EXISTS for the same new partition
    # must both succeed (the catalog-race unique_violation is caught and ignored).
    import asyncio

    from app.db.partitions import ensure_partition

    pool = app.state.pool
    async with pool.acquire() as c1, pool.acquire() as c2:
        await asyncio.gather(ensure_partition(c1, 2031, 3), ensure_partition(c2, 2031, 3))
        exists = await c1.fetchval(
            "SELECT count(*) FROM pg_class WHERE relname = 'samples_2031_03'")
        assert exists == 1
        # inside an outer transaction the guard uses a savepoint — the transaction survives
        async with c1.transaction():
            await ensure_partition(c1, 2031, 3)
            assert await c1.fetchval("SELECT 1") == 1
