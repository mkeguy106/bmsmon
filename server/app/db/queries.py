from datetime import datetime, timezone

import asyncpg

from app.db.partitions import ensure_partitions_for_range

_COLS = ["state", "soc", "current_a", "power_w", "voltage_v", "temp_c", "mosfet_temp_c",
         "soh", "full_charge_ah", "remaining_ah", "cycles", "cell_min_v", "cell_max_v",
         "link_event", "lat", "lon", "gps_accuracy_m", "eta_full_min"]

# Fixes with a larger claimed accuracy radius than this are coarse network/cell fallbacks
# (fused provider without GNSS lock, e.g. right after a phone reboot — observed 363-636 m
# jumps ~433 m off) and are excluded from rendered tracks. Real fixes run <=200 m even in a
# vehicle without GNSS; raw samples keep every fix regardless.
GPS_ACCURACY_MAX_M = 250


def sample_row(device_id: str, address: str, s: dict) -> dict:
    ts_ms = int(s["ts_ms"])
    row = {"device_id": device_id, "address": address, "ts_ms": ts_ms,
           "ts": datetime.fromtimestamp(ts_ms / 1000, tz=timezone.utc)}
    for c in _COLS:
        row[c] = s.get(c)
    row["regen"] = bool(s.get("regen", False))
    cells = s.get("cells")
    for i in range(4):
        row[f"cell{i + 1}_v"] = cells[i] if cells and i < len(cells) else None
    return row


# SRV-9: single set-based insert over unnest'd typed arrays so RETURNING can report
# the number of rows ACTUALLY inserted — ON CONFLICT DO NOTHING dedups (samples PK)
# no longer count as accepted. Array types mirror the samples column types.
_INSERT = """
WITH ins AS (
  INSERT INTO samples
    (device_id,address,ts_ms,ts,state,soc,current_a,power_w,voltage_v,temp_c,
     mosfet_temp_c,soh,full_charge_ah,remaining_ah,cycles,cell_min_v,cell_max_v,regen,link_event,
     lat,lon,gps_accuracy_m,eta_full_min,cell1_v,cell2_v,cell3_v,cell4_v)
  SELECT * FROM unnest(
    $1::uuid[], $2::text[], $3::bigint[], $4::timestamptz[], $5::text[],
    $6::real[], $7::real[], $8::real[], $9::real[], $10::real[],
    $11::int[], $12::int[], $13::real[], $14::real[], $15::int[],
    $16::real[], $17::real[], $18::boolean[], $19::text[],
    $20::float8[], $21::float8[], $22::real[], $23::real[],
    $24::real[], $25::real[], $26::real[], $27::real[])
  ON CONFLICT DO NOTHING
  RETURNING 1
)
SELECT count(*) FROM ins
"""

_INSERT_FIELDS = ["device_id", "address", "ts_ms", "ts", "state", "soc", "current_a",
                  "power_w", "voltage_v", "temp_c", "mosfet_temp_c", "soh",
                  "full_charge_ah", "remaining_ah", "cycles", "cell_min_v", "cell_max_v",
                  "regen", "link_event", "lat", "lon", "gps_accuracy_m",
                  "eta_full_min", "cell1_v", "cell2_v", "cell3_v", "cell4_v"]


async def insert_samples(conn: asyncpg.Connection, rows: list[dict]) -> int:
    """Insert sample rows; returns the count of rows actually inserted (duplicates
    already present under the samples PK are skipped and NOT counted)."""
    if not rows:
        return 0
    ts_all = [r["ts_ms"] for r in rows]
    await ensure_partitions_for_range(conn, min(ts_all), max(ts_all))
    cols = [[r[f] for r in rows] for f in _INSERT_FIELDS]
    return await conn.fetchval(_INSERT, *cols)


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
    """Store the latest temperature-alert config for a device+profile (one-way phone push).

    The envelope fields (cutoff_*/charge_*, WEB-6c) are optional in the push body and
    stored as NULL when absent — an old-app push overwrites them to NULL too, which is
    correct latest-wins semantics (the row always mirrors the most recent push whole)."""
    await conn.execute(
        """INSERT INTO device_temp_config
             (device_id, profile_id, cold_caution_c, hot_caution_c, cold_crit_c, hot_crit_c,
              unit, updated_at_ms,
              cutoff_cold_c, cutoff_hot_c, charge_lock_cold_c, charge_lock_hot_c,
              charge_resume_cold_c, received_at)
           VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13, now())
           ON CONFLICT (device_id, profile_id) DO UPDATE SET
             cold_caution_c = EXCLUDED.cold_caution_c,
             hot_caution_c = EXCLUDED.hot_caution_c,
             cold_crit_c = EXCLUDED.cold_crit_c,
             hot_crit_c = EXCLUDED.hot_crit_c,
             unit = EXCLUDED.unit,
             updated_at_ms = EXCLUDED.updated_at_ms,
             cutoff_cold_c = EXCLUDED.cutoff_cold_c,
             cutoff_hot_c = EXCLUDED.cutoff_hot_c,
             charge_lock_cold_c = EXCLUDED.charge_lock_cold_c,
             charge_lock_hot_c = EXCLUDED.charge_lock_hot_c,
             charge_resume_cold_c = EXCLUDED.charge_resume_cold_c,
             received_at = now()
           WHERE EXCLUDED.updated_at_ms >= device_temp_config.updated_at_ms""",
        device_id, cfg["profile_id"], cfg["cold_caution_c"], cfg["hot_caution_c"],
        cfg["cold_crit_c"], cfg["hot_crit_c"], cfg["unit"], cfg["updated_at_ms"],
        cfg.get("cutoff_cold_c"), cfg.get("cutoff_hot_c"), cfg.get("charge_lock_cold_c"),
        cfg.get("charge_lock_hot_c"), cfg.get("charge_resume_cold_c"),
    )


async def get_temp_config_all(conn) -> list[dict]:
    """Latest temperature-alert config per device+profile, newest first (for the read-only webui)."""
    rows = await conn.fetch(
        """SELECT device_id, profile_id, cold_caution_c, hot_caution_c, cold_crit_c, hot_crit_c,
                  unit, updated_at_ms, received_at,
                  cutoff_cold_c, cutoff_hot_c, charge_lock_cold_c, charge_lock_hot_c,
                  charge_resume_cold_c
           FROM device_temp_config ORDER BY updated_at_ms DESC"""
    )
    return [dict(r) for r in rows]


async def upsert_alert_config(conn, device_id: str, seize_soc: int, alerts_on: bool,
                              updated_at_ms: int) -> None:
    """Store the latest capacity-alert config for a device (one-way phone push, device-level).

    Latest-wins guarded on updated_at_ms (mirrors upsert_temp_config): an incoming push only
    overwrites the stored row when its updated_at_ms >= the stored one, so a stale/reordered
    push can't clobber a newer config."""
    await conn.execute(
        """INSERT INTO device_alert_config (device_id, seize_soc, alerts_on, updated_at_ms)
           VALUES ($1,$2,$3,$4)
           ON CONFLICT (device_id) DO UPDATE SET
             seize_soc = EXCLUDED.seize_soc,
             alerts_on = EXCLUDED.alerts_on,
             updated_at_ms = EXCLUDED.updated_at_ms
           WHERE EXCLUDED.updated_at_ms >= device_alert_config.updated_at_ms""",
        device_id, seize_soc, alerts_on, updated_at_ms,
    )


async def get_alert_config(conn) -> dict | None:
    """Single most-recent capacity-alert config across all devices (for the read-only webui)."""
    row = await conn.fetchrow(
        """SELECT device_id, seize_soc, alerts_on, updated_at_ms
           FROM device_alert_config ORDER BY updated_at_ms DESC LIMIT 1"""
    )
    return dict(row) if row else None


async def upsert_range_config(conn, device_id, row: dict) -> None:
    """Store one pack's learned discharge-range bands (one-way phone push, latest-wins
    guarded on updated_at_ms — mirrors upsert_temp_config)."""
    await conn.execute(
        """INSERT INTO device_range_config
             (device_id, address, wh_per_day_lo, wh_per_day_hi, active_w_lo, active_w_hi,
              wh_per_mile_lo, wh_per_mile_hi, learned_days, updated_at_ms, received_at)
           VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10, now())
           ON CONFLICT (device_id, address) DO UPDATE SET
             wh_per_day_lo = EXCLUDED.wh_per_day_lo,
             wh_per_day_hi = EXCLUDED.wh_per_day_hi,
             active_w_lo = EXCLUDED.active_w_lo,
             active_w_hi = EXCLUDED.active_w_hi,
             wh_per_mile_lo = EXCLUDED.wh_per_mile_lo,
             wh_per_mile_hi = EXCLUDED.wh_per_mile_hi,
             learned_days = EXCLUDED.learned_days,
             updated_at_ms = EXCLUDED.updated_at_ms,
             received_at = now()
           WHERE EXCLUDED.updated_at_ms >= device_range_config.updated_at_ms""",
        device_id, row["address"], row["wh_per_day_lo"], row["wh_per_day_hi"],
        row["active_w_lo"], row["active_w_hi"], row["wh_per_mile_lo"], row["wh_per_mile_hi"],
        row["learned_days"], row["updated_at_ms"],
    )


async def get_range_config_all(conn) -> list[dict]:
    """Latest learned range bands per device+pack (for the read-only webui mirror)."""
    rows = await conn.fetch(
        """SELECT device_id, address, wh_per_day_lo, wh_per_day_hi, active_w_lo, active_w_hi,
                  wh_per_mile_lo, wh_per_mile_hi, learned_days, updated_at_ms, received_at
           FROM device_range_config ORDER BY updated_at_ms DESC"""
    )
    return [dict(r) for r in rows]


async def fleet_snapshot(conn) -> list[dict]:
    """Latest REAL telemetry row per pack. Link-event rows (BLE Connected/Disconnected
    transitions, all telemetry fields null) are skipped so a disconnect doesn't wipe the
    pack's last-known telemetry off the dashboard/WS snapshot.

    Driven by the small `batteries` registry with a LATERAL latest-row probe per pack
    (T2.5/SRV-6) so each call is a handful of index descents on samples_addr_ts instead
    of a DISTINCT ON scan across every monthly partition ever created. The INNER lateral
    join keeps the old semantics: a battery row with no real telemetry yet (or a pack
    with only link-event rows) does not appear."""
    rows = await conn.fetch(
        """SELECT
              s.device_id, s.address, s.ts_ms, s.ts, s.state, s.soc, s.current_a, s.power_w,
              s.voltage_v, s.temp_c, s.mosfet_temp_c, s.soh, s.full_charge_ah, s.remaining_ah,
              s.cycles, s.cell_min_v, s.cell_max_v, s.regen, s.link_event,
              s.lat, s.lon, s.gps_accuracy_m, s.eta_full_min, s.received_at,
              CASE WHEN s.cell1_v IS NULL THEN NULL
                   ELSE ARRAY[s.cell1_v, s.cell2_v, s.cell3_v, s.cell4_v] END AS cells,
              b.alias, b.group_id, b.advertised_name
           FROM batteries b
           JOIN LATERAL (
              SELECT * FROM samples s
              WHERE s.address = b.address AND s.link_event IS NULL
              ORDER BY s.ts DESC
              LIMIT 1
           ) s ON true
           ORDER BY b.address"""
    )
    return [dict(r) for r in rows]


async def scrub_expired_gps(conn, retention_days: int) -> int:
    """GPS retention scrub (SEC-12): NULL out the location columns on samples older than
    the retention window. Telemetry rows are NEVER deleted — the battery history is kept
    forever; only lat/lon/gps_accuracy_m are cleared. Returns the number of rows scrubbed.

    retention_days <= 0 means retention is disabled (keep GPS forever) — no-op.

    The ts predicate prunes the monthly RANGE(ts) partitions, and the IS NOT NULL guard
    makes re-runs idempotent and cheap (already-scrubbed rows are never rewritten)."""
    if retention_days <= 0:
        return 0
    status = await conn.execute(
        """UPDATE samples
           SET lat = NULL, lon = NULL, gps_accuracy_m = NULL
           WHERE ts < now() - ($1 * interval '1 day')
             AND (lat IS NOT NULL OR lon IS NOT NULL OR gps_accuracy_m IS NOT NULL)""",
        float(retention_days),
    )
    return int(status.rsplit(" ", 1)[-1])  # asyncpg status tag, e.g. "UPDATE 3"


# SRV-11 bounds for /web/samples: cap the caller-chosen range at 7 days (a wider
# request gets its start clamped up to end-7d) and hard-LIMIT the row count so an
# admin typo can't materialize millions of partition rows in one response.
SAMPLES_MAX_RANGE_MS = 7 * 24 * 3600 * 1000
SAMPLES_MAX_ROWS = 100_000


async def samples_range(conn, address: str, from_ms: int, to_ms: int,
                        limit: int = SAMPLES_MAX_ROWS) -> list[dict]:
    from_ms = max(from_ms, to_ms - SAMPLES_MAX_RANGE_MS)
    a = datetime.fromtimestamp(from_ms / 1000, tz=timezone.utc)
    b = datetime.fromtimestamp(to_ms / 1000, tz=timezone.utc)
    rows = await conn.fetch(
        "SELECT * FROM samples WHERE address=$1 AND ts>=$2 AND ts<=$3 ORDER BY ts LIMIT $4",
        address, a, b, limit,
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


async def create_device(conn, install_uuid, public_key_spki: bytes, label) -> str | None:
    """Insert a new device, or re-key an existing NON-revoked one (legit re-enroll of the
    same phone). Revocation is durable (T2.4/SRV-7): the DO UPDATE's WHERE guard makes the
    upsert a no-op for a revoked install_uuid — nothing is written, the key is untouched,
    and None is returned so the caller can refuse the enrollment."""
    return await conn.fetchval(
        """INSERT INTO devices (install_uuid, public_key_spki, label) VALUES ($1,$2,$3)
           ON CONFLICT (install_uuid) DO UPDATE SET public_key_spki=EXCLUDED.public_key_spki,
             label=EXCLUDED.label
           WHERE devices.revoked = false
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


HISTORY_BUCKET_MS = 1_800_000  # 30-minute buckets


async def history_series(conn, since_ms: int) -> list[dict]:
    """Per-pack 30-minute-bucketed average SOC since since_ms (real telemetry only).

    Returns flat rows {address, bucket_ms, soc} ordered by address, bucket_ms — the
    route groups them into one series per pack. Bounded by the window, not row-capped.

    The ts predicate mirrors the ts_ms one (ts is derived from ts_ms at insert time) so
    the planner can prune the monthly RANGE(ts) partitions — ts_ms alone can't."""
    rows = await conn.fetch(
        """SELECT address,
                  (ts_ms / $2) * $2 AS bucket_ms,
                  avg(soc)::real AS soc
             FROM samples
            WHERE ts_ms >= $1 AND ts >= to_timestamp($1::double precision / 1000.0)
              AND link_event IS NULL AND soc IS NOT NULL
            GROUP BY address, bucket_ms
            ORDER BY address, bucket_ms""",
        since_ms, HISTORY_BUCKET_MS,
    )
    return [dict(r) for r in rows]


async def charge_session_buckets(conn, address: str, since_ms: int) -> list[dict]:
    """1-minute buckets of charging-only rows (current_a > 0.1) since since_ms.
    The redundant ts predicate exists purely for partition pruning (see history_series)."""
    rows = await conn.fetch(
        """SELECT (ts_ms / 60000) * 60000 AS bucket_ms,
                  avg(soc)::real AS soc, max(temp_c)::real AS temp_max
             FROM samples
            WHERE address = $1 AND ts_ms >= $2 AND ts >= to_timestamp($2::double precision / 1000.0)
              AND link_event IS NULL AND current_a > 0.1
            GROUP BY bucket_ms ORDER BY bucket_ms""",
        address, since_ms,
    )
    return [dict(r) for r in rows]


def trend_bucket_ms(span_ms: int) -> int:
    """Adaptive bucket size for /web/trends, coarsened as the requested span grows so a
    multi-month range doesn't return an unbounded number of points."""
    D = 86_400_000
    if span_ms <= 2 * D:
        return 1_800_000       # 30 min
    if span_ms <= 14 * D:
        return 21_600_000      # 6 h
    if span_ms <= 92 * D:
        return 86_400_000      # 1 day
    return 604_800_000         # 7 days


async def trend_series(conn, address: str, from_ms: int, to_ms: int, bucket_ms: int) -> list[dict]:
    """Per-pack bucketed SOH / cell-spread / temperature trend for /web/trends.
    The redundant ts predicates exist purely for partition pruning (see history_series)."""
    rows = await conn.fetch(
        """SELECT (ts_ms / $4) * $4 AS bucket_ms,
                  avg(soh)::real AS soh,
                  avg((cell_max_v - cell_min_v) * 1000)::real AS cell_spread_mv,
                  avg(temp_c)::real AS temp_avg, min(temp_c)::real AS temp_min, max(temp_c)::real AS temp_max
             FROM samples
            WHERE address = $1 AND ts_ms >= $2 AND ts_ms < $3
              AND ts >= to_timestamp($2::double precision / 1000.0)
              AND ts < to_timestamp($3::double precision / 1000.0)
              AND link_event IS NULL
            GROUP BY bucket_ms ORDER BY bucket_ms""",
        address, from_ms, to_ms, bucket_ms,
    )
    return [dict(r) for r in rows]


async def track_series(conn, address: str, from_ms: int, to_ms: int) -> list[dict]:
    """15-second buckets of GPS-carrying real telemetry (lat/lon present) with discharge context.
    Coarse fixes (accuracy radius > GPS_ACCURACY_MAX_M) are gated out; NULL accuracy passes.
    The redundant ts predicates exist purely for partition pruning (see history_series)."""
    rows = await conn.fetch(
        """SELECT (ts_ms / 15000) * 15000 AS bucket_ms,
                  avg(lat)::double precision AS lat, avg(lon)::double precision AS lon,
                  avg(power_w)::real AS power_w, avg(current_a)::real AS current_a, avg(soc)::real AS soc
             FROM samples
            WHERE address = $1 AND ts_ms >= $2 AND ts_ms < $3
              AND ts >= to_timestamp($2::double precision / 1000.0)
              AND ts < to_timestamp($3::double precision / 1000.0)
              AND link_event IS NULL AND lat IS NOT NULL AND lon IS NOT NULL
              AND (gps_accuracy_m IS NULL OR gps_accuracy_m <= $4)
            GROUP BY bucket_ms ORDER BY bucket_ms""",
        address, from_ms, to_ms, GPS_ACCURACY_MAX_M,
    )
    return [dict(r) for r in rows]


async def first_sample_ms(conn, address: str) -> int | None:
    """Earliest real-telemetry timestamp for a pack, used by /web/trends to bound the
    selectable history range on the client. Written as an ORDER BY address, ts LIMIT 1
    lookup (a backward descent of samples_addr_ts) instead of min(ts_ms), which would
    seq-scan every partition."""
    return await conn.fetchval(
        """SELECT ts_ms FROM samples WHERE address = $1 AND link_event IS NULL
           ORDER BY address ASC, ts ASC LIMIT 1""", address)


async def get_notes(conn) -> list[dict]:
    rows = await conn.fetch("SELECT base_id, body, updated_at_ms FROM web_notes ORDER BY base_id")
    return [dict(r) for r in rows]


async def upsert_note(conn, base_id: str, body: str, updated_at_ms: int) -> None:
    await conn.execute(
        """INSERT INTO web_notes (base_id, body, updated_at_ms) VALUES ($1, $2, $3)
           ON CONFLICT (base_id) DO UPDATE SET body = EXCLUDED.body, updated_at_ms = EXCLUDED.updated_at_ms""",
        base_id, body, updated_at_ms)


async def create_location_share(conn, token_hash: str, name: str, created_by: str,
                                created_at_ms: int, expires_at_ms: int) -> int:
    row = await conn.fetchrow(
        """INSERT INTO location_shares (token_hash, name, created_by, created_at, expires_at)
           VALUES ($1, $2, $3, $4, $5) RETURNING id""",
        token_hash, name, created_by, created_at_ms, expires_at_ms)
    return int(row["id"])


async def get_location_share(conn, token_hash: str) -> dict | None:
    row = await conn.fetchrow(
        """SELECT id, name, created_at, expires_at, revoked_at
             FROM location_shares WHERE token_hash = $1""", token_hash)
    return dict(row) if row else None


async def touch_location_share(conn, share_id: int, now_ms: int) -> None:
    await conn.execute(
        """UPDATE location_shares
              SET last_access_ms = $2, access_count = access_count + 1
            WHERE id = $1""", share_id, now_ms)


async def list_location_shares(conn, now_ms: int, keep_ended_ms: int) -> list[dict]:
    """Active shares plus anything that ended (revoked or expired) within keep_ended_ms.
    end-of-life = revoked_at when revoked, else expires_at — COALESCE covers both."""
    rows = await conn.fetch(
        """SELECT id, name, created_at, expires_at, revoked_at, last_access_ms, access_count
             FROM location_shares
            WHERE COALESCE(revoked_at, expires_at) > $1::bigint - $2::bigint
            ORDER BY created_at DESC""", now_ms, keep_ended_ms)
    return [dict(r) for r in rows]


async def revoke_location_share(conn, share_id: int, now_ms: int) -> None:
    await conn.execute(
        "UPDATE location_shares SET revoked_at = $2 WHERE id = $1 AND revoked_at IS NULL",
        share_id, now_ms)


async def gps_track_all(conn, from_ms: int, to_ms: int) -> list[dict]:
    """15-second buckets of GPS fixes across the whole fleet. Feeds the public
    location-share guest page: coordinates plus per-bucket discharge context
    (power/current — the 2026-07-14 trail-detail relaxation) and deliberately
    nothing else (no SOC, voltage, temperature, or cells).
    The redundant ts predicates exist purely for partition pruning (see history_series)."""
    rows = await conn.fetch(
        """SELECT (ts_ms / 15000) * 15000 AS bucket_ms,
                  avg(lat)::double precision AS lat, avg(lon)::double precision AS lon,
                  avg(power_w)::real AS power_w, avg(current_a)::real AS current_a
             FROM samples
            WHERE ts_ms >= $1 AND ts_ms < $2
              AND ts >= to_timestamp($1::double precision / 1000.0)
              AND ts < to_timestamp($2::double precision / 1000.0)
              AND link_event IS NULL AND lat IS NOT NULL AND lon IS NOT NULL
              AND (gps_accuracy_m IS NULL OR gps_accuracy_m <= $3)
            GROUP BY bucket_ms ORDER BY bucket_ms""",
        from_ms, to_ms, GPS_ACCURACY_MAX_M,
    )
    return [dict(r) for r in rows]
