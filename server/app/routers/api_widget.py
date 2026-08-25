"""Read-only group telemetry for headless clients (the desktop widgets).

Sits under /api/, which Traefik routes past Authentik, and is gated by an API key
(app/auth/api_key.py) rather than a browser session. Deliberately narrow:

  * GET only. An API key can never write; there is no route here that mutates.
  * No GPS. `lat`/`lon`/`gps_accuracy_m` are in the fleet row but are not exposed
    — a battery widget has no need to know where the chair is, and the guest
    share zone already treats location as the more sensitive field.
  * No device identity. `device_id` stays server-side.

The pack/base vocabulary matches web/src/v2/fleet.ts so the widget, the WebUI and
the Android stage all describe the fleet the same way. Staleness and the status
ladder are evaluated HERE rather than in the client, so there is one implementation
of "is this pack live" instead of three.
"""

import time

from fastapi import APIRouter, Depends
from fastapi.responses import JSONResponse

from app.auth.api_key import require_api_key
from app.db import queries as q
from app.db.pool import get_pool
from app.util import jsonable

router = APIRouter(prefix="/api/v1")

# A pack is treated as offline when nothing has arrived for this long. Mirrors
# STALE_MS in web/src/v2/useFleetData.ts — keep the two in step.
STALE_MS = 90_000

# Inner-ring full scale, mirroring POWER_RING_FULL_W in the app and web Ring.
POWER_FULL_W = 300

# 2012 is the daily driver, so it leads the list the way it does in the WebUI.
DAILY_DRIVER_BASE = "2012"

LETTERS = ["A", "B", "C", "D"]

# Current thresholds; the BMS deadband is ~1.04 A so these are well clear of noise.
CHARGE_EPS = 0.1

# Telemetry the widget actually draws. Anything not listed is withheld by default,
# which is why adding a field is a deliberate act rather than an accident.
PACK_FIELDS = (
    "soc", "voltage_v", "current_a", "power_w", "temp_c", "mosfet_temp_c",
    "soh", "cycles", "state", "remaining_ah", "full_charge_ah",
    "cell_min_v", "cell_max_v",
)


def _status(packs: list[dict]) -> str:
    """Base status ladder, mirroring baseStatus() in web/src/v2/fleet.ts."""
    live = [p for p in packs if p["connected"]]
    if not live:
        return "offline"
    if any((p.get("current_a") or 0) < -CHARGE_EPS for p in live):
        return "in-use"
    if any((p.get("current_a") or 0) > CHARGE_EPS for p in live):
        return "charging"
    if any((p.get("soc") or 0) >= 99 for p in live):
        return "backup"
    return "spares"


@router.get("/groups")
async def groups(_key=Depends(require_api_key), pool=Depends(get_pool)):
    """Latest telemetry for every battery pair, grouped by base.

    Disconnected packs keep their last-known values and are flagged
    `connected: false` rather than being omitted — the widget dims them instead of
    going blank, which is the whole point of showing a spare that has gone to sleep.
    """
    async with pool.acquire() as conn:
        fleet = await q.fleet_snapshot(conn)

    now_ms = int(time.time() * 1000)
    by_base: dict[str, list[dict]] = {}
    for row in fleet:
        gid = row.get("group_id") or row.get("address")
        by_base.setdefault(gid, []).append(row)

    out = []
    for gid, rows in by_base.items():
        rows.sort(key=lambda r: (r.get("alias") or r.get("address") or ""))
        packs = []
        for n, r in enumerate(rows):
            ts_ms = r.get("ts_ms") or 0
            pack = {
                "letter": LETTERS[n] if n < len(LETTERS) else str(n + 1),
                "alias": r.get("alias"),
                "address": r.get("address"),
                "ts_ms": ts_ms,
                "age_ms": max(0, now_ms - ts_ms),
                "connected": (now_ms - ts_ms) <= STALE_MS,
            }
            for f in PACK_FIELDS:
                pack[f] = r.get(f)
            packs.append(pack)

        last_seen = max((p["ts_ms"] for p in packs), default=0)
        # jsonable() takes a list of row dicts, not an envelope — apply it to the
        # packs, which is where any asyncpg scalar would need coercing.
        out.append({
            "id": gid,
            "label": f"Base {gid}",
            "status": _status(packs),
            "connected": any(p["connected"] for p in packs),
            "last_seen_ms": last_seen or None,
            "packs": jsonable(packs),
        })

    out.sort(key=lambda g: (g["id"] != DAILY_DRIVER_BASE, g["id"]))
    return JSONResponse(
        {"now_ms": now_ms, "stale_ms": STALE_MS,
         "power_full_w": POWER_FULL_W, "groups": out},
        # Telemetry is live and key-gated: never let a proxy or browser retain it.
        headers={"Cache-Control": "no-store"},
    )
