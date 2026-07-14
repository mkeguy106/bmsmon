"""Seed the dev Postgres with a realistic 4-pack fleet for a WebUI smoke test.

TRUNCATES devices/batteries/samples — dev DB only (the hardcoded localhost DSN
guards against ever running this at prod). See CLAUDE.md "WebUI smoke test".
"""
import asyncio, math, sys, time, uuid
from pathlib import Path

import asyncpg

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))  # server/ for app.*
from app.db import queries as q

DB = "postgresql://bmsmon:bmsmon@localhost:5432/bmsmon"
DEV = str(uuid.uuid4())
PACKS = [
    ("C8:47:80:15:67:44", "R-12100BNNA70-A02214", "2012 · A", "2012"),
    ("C8:47:80:15:62:1B", "R-12100BNNA70-A02345", "2012 · B", "2012"),
    ("C8:47:80:15:DB:13", "R-12100BNNA70-A03902", "2016 · A", "2016"),
    ("C8:47:80:15:25:9A", "R-12100BNNA70-A03727", "2016 · B", "2016"),
]


async def main():
    conn = await asyncpg.connect(DB)
    # Disposable dev DB: clear residue from test runs so the fleet is clean.
    for t in ("samples", "batteries", "devices"):
        await conn.execute(f"TRUNCATE {t} CASCADE")
    await conn.execute(
        "INSERT INTO devices (id, install_uuid, public_key_spki) VALUES ($1,$2,$3)",
        DEV, "smoke-test", b"\x00")
    now = int(time.time() * 1000)
    for addr, name, alias, grp in PACKS:
        await q.upsert_battery(conn, addr, name, alias, grp, now)
    rows = []
    for pi, (addr, *_rest) in enumerate(PACKS):
        driving = pi < 2  # 2012 base is active + has GPS
        for i in range(240):  # 2 h at 30 s
            ts = now - (239 - i) * 30_000
            frac = i / 239
            soc = (72 if driving else 95) - (8 * frac if driving else 0)
            cur = (-4.5 - 1.5 * math.sin(i / 9)) if driving else 0.0
            volt = 13.25 - 0.2 * (1 - soc / 100)
            payload = {
                "ts_ms": ts, "state": "Discharging" if driving else "Idle",
                "soc": round(soc, 1), "current_a": round(cur, 3),
                "power_w": round(volt * cur, 2), "voltage_v": round(volt, 3),
                "temp_c": 27.0 + pi, "mosfet_temp_c": 29 + pi, "soh": 100,
                "full_charge_ah": 102.5, "remaining_ah": round(102.5 * soc / 100, 2),
                "cycles": 210 + pi, "cell_min_v": 3.309, "cell_max_v": 3.314,
                "regen": False, "cells": [3.311, 3.309, 3.314, 3.312],
            }
            if driving:
                ang = frac * 2 * math.pi
                payload |= {"lat": round(33.0220 + 0.004 * math.sin(ang), 6),
                            "lon": round(-117.1625 + 0.005 * frac, 6),
                            "gps_accuracy_m": 9.5}
            rows.append(q.sample_row(DEV, addr, payload))
    await q.insert_samples(conn, rows)
    n = await conn.fetchval("SELECT count(*) FROM samples")
    print(f"seeded {n} samples across {len(PACKS)} packs, freshest = now")
    await conn.close()


asyncio.run(main())
