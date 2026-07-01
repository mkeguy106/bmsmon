import json
from datetime import datetime, timezone

import asyncpg

from app.db.partitions import ensure_partitions_for_range

_COLS = ["state", "soc", "current_a", "power_w", "voltage_v", "temp_c", "mosfet_temp_c",
         "soh", "full_charge_ah", "remaining_ah", "cycles", "cell_min_v", "cell_max_v",
         "link_event", "lat", "lon", "gps_accuracy_m"]


def sample_row(device_id: str, address: str, s: dict) -> dict:
    ts_ms = int(s["ts_ms"])
    row = {"device_id": device_id, "address": address, "ts_ms": ts_ms,
           "ts": datetime.fromtimestamp(ts_ms / 1000, tz=timezone.utc)}
    for c in _COLS:
        row[c] = s.get(c)
    cells = s.get("cells")
    row["cells"] = json.dumps(cells) if cells is not None else None
    row["regen"] = bool(s.get("regen", False))
    return row


_INSERT = """
INSERT INTO samples
  (device_id,address,ts_ms,ts,state,soc,current_a,power_w,voltage_v,temp_c,
   mosfet_temp_c,soh,full_charge_ah,remaining_ah,cycles,cell_min_v,cell_max_v,cells,regen,link_event,
   lat,lon,gps_accuracy_m)
VALUES
  ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18,$19,$20,$21,$22,$23)
ON CONFLICT DO NOTHING
"""


async def insert_samples(conn: asyncpg.Connection, rows: list[dict]) -> int:
    if not rows:
        return 0
    ts_all = [r["ts_ms"] for r in rows]
    await ensure_partitions_for_range(conn, min(ts_all), max(ts_all))
    await conn.executemany(_INSERT, [
        (r["device_id"], r["address"], r["ts_ms"], r["ts"], r["state"], r["soc"],
         r["current_a"], r["power_w"], r["voltage_v"], r["temp_c"], r["mosfet_temp_c"],
         r["soh"], r["full_charge_ah"], r["remaining_ah"], r["cycles"], r["cell_min_v"],
         r["cell_max_v"], r["cells"], r["regen"], r["link_event"],
         r["lat"], r["lon"], r["gps_accuracy_m"])
        for r in rows
    ])
    return len(rows)


async def upsert_battery(conn, address, advertised_name, alias, group_id, ts_ms: int) -> None:
    ts = datetime.fromtimestamp(ts_ms / 1000, tz=timezone.utc)
    await conn.execute(
        """INSERT INTO batteries (address, advertised_name, alias, group_id, first_seen, last_seen)
           VALUES ($1,$2,$3,$4,$5,$5)
           ON CONFLICT (address) DO UPDATE SET
             advertised_name = COALESCE(EXCLUDED.advertised_name, batteries.advertised_name),
             alias = COALESCE(EXCLUDED.alias, batteries.alias),
             group_id = COALESCE(EXCLUDED.group_id, batteries.group_id),
             last_seen = GREATEST(batteries.last_seen, EXCLUDED.last_seen)""",
        address, advertised_name, alias, group_id, ts,
    )


async def upsert_temp_config(conn, device_id, cfg: dict) -> None:
    """Store the latest temperature-alert config for a device+profile (one-way phone push)."""
    await conn.execute(
        """INSERT INTO device_temp_config
             (device_id, profile_id, cold_caution_c, hot_caution_c, cold_crit_c, hot_crit_c,
              unit, updated_at_ms, received_at)
           VALUES ($1,$2,$3,$4,$5,$6,$7,$8, now())
           ON CONFLICT (device_id, profile_id) DO UPDATE SET
             cold_caution_c = EXCLUDED.cold_caution_c,
             hot_caution_c = EXCLUDED.hot_caution_c,
             cold_crit_c = EXCLUDED.cold_crit_c,
             hot_crit_c = EXCLUDED.hot_crit_c,
             unit = EXCLUDED.unit,
             updated_at_ms = EXCLUDED.updated_at_ms,
             received_at = now()
           WHERE EXCLUDED.updated_at_ms >= device_temp_config.updated_at_ms""",
        device_id, cfg["profile_id"], cfg["cold_caution_c"], cfg["hot_caution_c"],
        cfg["cold_crit_c"], cfg["hot_crit_c"], cfg["unit"], cfg["updated_at_ms"],
    )


async def fleet_snapshot(conn) -> list[dict]:
    rows = await conn.fetch(
        """SELECT DISTINCT ON (s.address)
              s.device_id, s.address, s.ts_ms, s.ts, s.state, s.soc, s.current_a, s.power_w,
              s.voltage_v, s.temp_c, s.mosfet_temp_c, s.soh, s.full_charge_ah, s.remaining_ah,
              s.cycles, s.cell_min_v, s.cell_max_v, s.cells, s.regen, s.link_event,
              s.lat, s.lon, s.gps_accuracy_m, s.received_at,
              b.alias, b.group_id, b.advertised_name
           FROM samples s LEFT JOIN batteries b ON b.address = s.address
           ORDER BY s.address, s.ts DESC"""
    )
    return [dict(r) for r in rows]


async def samples_range(conn, address: str, from_ms: int, to_ms: int) -> list[dict]:
    a = datetime.fromtimestamp(from_ms / 1000, tz=timezone.utc)
    b = datetime.fromtimestamp(to_ms / 1000, tz=timezone.utc)
    rows = await conn.fetch(
        "SELECT * FROM samples WHERE address=$1 AND ts>=$2 AND ts<=$3 ORDER BY ts", address, a, b
    )
    return [dict(r) for r in rows]


async def create_enrollment_code(conn, code_hash, created_by, expires_at) -> None:
    await conn.execute(
        "INSERT INTO enrollment_codes (code_hash, created_by, expires_at) VALUES ($1,$2,$3)",
        code_hash, created_by, expires_at)


async def claim_code(conn, code_hash, device_id, now):
    return await conn.fetchrow(
        """UPDATE enrollment_codes SET used_at=$2, device_id=$3
           WHERE code_hash=$1 AND used_at IS NULL AND expires_at > $2
           RETURNING code_hash""",
        code_hash, now, device_id)


async def create_device(conn, install_uuid, public_key_spki: bytes, label) -> str:
    return await conn.fetchval(
        """INSERT INTO devices (install_uuid, public_key_spki, label) VALUES ($1,$2,$3)
           ON CONFLICT (install_uuid) DO UPDATE SET public_key_spki=EXCLUDED.public_key_spki,
             label=EXCLUDED.label, revoked=false
           RETURNING id""",
        install_uuid, public_key_spki, label)


async def get_device(conn, device_id):
    return await conn.fetchrow("SELECT * FROM devices WHERE id=$1", device_id)


async def list_devices(conn) -> list[dict]:
    rows = await conn.fetch(
        "SELECT id, install_uuid, label, created_at, last_seen_at, revoked FROM devices ORDER BY created_at")
    return [dict(r) for r in rows]


async def revoke_device(conn, device_id) -> None:
    await conn.execute("UPDATE devices SET revoked=true WHERE id=$1", device_id)
