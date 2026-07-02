from datetime import datetime, timedelta, timezone

from app.db import queries as q
from app.main import run_gps_scrub

A = "C8:47:80:15:25:01"
DEV = "00000000-0000-0000-0000-000000000001"

RETENTION_DAYS = 1095


def _sample(ts_ms, soc=87.0, gps=True):
    s = {"ts_ms": ts_ms, "state": "Discharging", "soc": soc, "current_a": -2.5,
         "power_w": 127.5, "voltage_v": 51.0, "temp_c": 25.0, "mosfet_temp_c": 28,
         "soh": 98, "full_charge_ah": 100.0, "remaining_ah": 87.5, "cycles": 342,
         "cell_min_v": 3.17, "cell_max_v": 3.19, "regen": False,
         "link_event": None}
    if gps:
        s.update({"lat": 40.7128, "lon": -74.006, "gps_accuracy_m": 4.5})
    return s


async def _device(conn):
    await conn.execute(
        "INSERT INTO devices (id, install_uuid, public_key_spki) VALUES ($1,$2,$3)",
        DEV, "uuid-1", b"\x00",
    )


def _ms(dt: datetime) -> int:
    return int(dt.timestamp() * 1000)


async def _insert_old_and_recent(conn):
    """One GPS sample well past the retention window, one recent GPS sample.
    Inserted directly via queries (the ingest API rejects old ts_ms). Two separate
    insert calls so the partition ensure doesn't span the whole 3+ year range."""
    now = datetime.now(timezone.utc)
    old_ms = _ms(now - timedelta(days=RETENTION_DAYS + 100))
    recent_ms = _ms(now - timedelta(hours=1))
    await _device(conn)
    await q.insert_samples(conn, [q.sample_row(DEV, A, _sample(old_ms, soc=42.0))])
    await q.insert_samples(conn, [q.sample_row(DEV, A, _sample(recent_ms, soc=87.0))])
    return old_ms, recent_ms


async def test_scrub_clears_gps_only_and_keeps_all_telemetry(app):
    pool = app.state.pool
    async with pool.acquire() as conn:
        old_ms, recent_ms = await _insert_old_and_recent(conn)

        assert await q.scrub_expired_gps(conn, RETENTION_DAYS) == 1

        old = await conn.fetchrow("SELECT * FROM samples WHERE ts_ms=$1", old_ms)
        # the row is NEVER deleted — every telemetry value survives the scrub
        assert old is not None
        assert old["soc"] == 42.0
        assert old["voltage_v"] == 51.0
        assert old["current_a"] == -2.5
        assert old["power_w"] == 127.5
        assert old["temp_c"] == 25.0
        assert old["remaining_ah"] == 87.5
        assert old["cycles"] == 342
        assert old["state"] == "Discharging"
        # ... but its location is gone
        assert old["lat"] is None
        assert old["lon"] is None
        assert old["gps_accuracy_m"] is None

        # the recent row is untouched
        recent = await conn.fetchrow("SELECT * FROM samples WHERE ts_ms=$1", recent_ms)
        assert recent["soc"] == 87.0
        assert abs(recent["lat"] - 40.7128) < 1e-6
        assert abs(recent["lon"] - -74.006) < 1e-6
        assert abs(recent["gps_accuracy_m"] - 4.5) < 1e-4

        total = await conn.fetchval("SELECT count(*) FROM samples")
        assert total == 2


async def test_scrub_is_idempotent(app):
    pool = app.state.pool
    async with pool.acquire() as conn:
        await _insert_old_and_recent(conn)
        assert await q.scrub_expired_gps(conn, RETENTION_DAYS) == 1
        # second run finds nothing left to scrub
        assert await q.scrub_expired_gps(conn, RETENTION_DAYS) == 0


async def test_scrub_disabled_when_retention_not_positive(app):
    pool = app.state.pool
    async with pool.acquire() as conn:
        old_ms, _ = await _insert_old_and_recent(conn)

        assert await q.scrub_expired_gps(conn, 0) == 0
        assert await q.scrub_expired_gps(conn, -1) == 0

        old = await conn.fetchrow("SELECT * FROM samples WHERE ts_ms=$1", old_ms)
        assert old["lat"] is not None  # keep-forever mode: nothing scrubbed
        assert old["gps_accuracy_m"] is not None


async def test_run_gps_scrub_uses_settings(app, set_setting):
    # the app-level pass (what the daily background task runs) honors the setting
    pool = app.state.pool
    async with pool.acquire() as conn:
        await _insert_old_and_recent(conn)

    set_setting("gps_retention_days", 0)
    assert await run_gps_scrub(pool) == 0  # disabled: nothing scrubbed

    set_setting("gps_retention_days", RETENTION_DAYS)
    assert await run_gps_scrub(pool) == 1
    assert await run_gps_scrub(pool) == 0
