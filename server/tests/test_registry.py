"""Fleet-wide reads (share trail, history, rollup, GPS scrub) are driven per pack from the
batteries registry (SRV-16), so every address with a sample must have a registry row:
insert_samples guarantees it, and the maintenance pass backfills rows written any other way."""
import time

from app.db import queries as q
from app.maintenance import run_maintenance

DEV = "00000000-0000-0000-0000-000000000001"


async def test_insert_samples_registers_a_new_address_and_keeps_metadata(app):
    now = int(time.time() * 1000)
    async with app.state.pool.acquire() as conn:
        await q.insert_samples(conn, [q.sample_row(DEV, "AA:BB:CC", {"ts_ms": now, "soc": 50.0})])
        assert await conn.fetchval("SELECT count(*) FROM batteries WHERE address = 'AA:BB:CC'") == 1
        await q.upsert_battery(conn, "AA:BB:CC", "R-12100", "2012 · A", "2012", now)
        await q.insert_samples(conn, [q.sample_row(DEV, "AA:BB:CC", {"ts_ms": now + 1000, "soc": 51.0})])
        row = await conn.fetchrow("SELECT alias, group_id FROM batteries WHERE address = 'AA:BB:CC'")
    assert (row["alias"], row["group_id"]) == ("2012 · A", "2012")


async def _orphan(conn, now: int) -> None:
    await conn.execute(
        "INSERT INTO samples (device_id, address, ts_ms, ts) "
        "VALUES ($1, 'ORPHAN:01', $2::bigint, to_timestamp($2::double precision / 1000.0))", DEV, now)


async def test_orphan_addresses_are_backfilled_once(app):
    async with app.state.pool.acquire() as conn:
        await _orphan(conn, int(time.time() * 1000))
        assert await q.register_orphan_addresses(conn) == 1
        assert await q.register_orphan_addresses(conn) == 0
        assert await conn.fetchval("SELECT count(*) FROM batteries WHERE address = 'ORPHAN:01'") == 1


async def test_maintenance_runs_the_backfill(app):
    async with app.state.pool.acquire() as conn:
        await _orphan(conn, int(time.time() * 1000))
    assert (await run_maintenance(app.state.pool))["registry"] == 1


async def test_a_failing_registry_insert_never_fails_the_ingest(app, client, monkeypatch):
    import json
    from tests.test_ingest_jwt import _enroll_device, _keypair, _payload, _token
    monkeypatch.setattr(q, "_REGISTER_ADDRESSES", "INSERT INTO no_such_table VALUES ($1::text[])")
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    body = json.dumps(_payload()).encode()
    r = await client.post("/api/v1/ingest", content=body,
                          headers={"Authorization": f"Bearer {_token(priv, device_id, body)}",
                                   "Content-Type": "application/json"})
    assert r.status_code == 200
    assert r.json()["accepted"] == 1
    async with app.state.pool.acquire() as conn:
        assert await conn.fetchval("SELECT count(*) FROM samples") == 1
