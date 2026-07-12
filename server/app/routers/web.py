import time
from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends, Query

from app.auth.authentik import AuthUser, current_user, require_admin
from app.auth.enroll import generate_code, hash_code
from app.db import queries as q
from app.db.pool import get_pool
from app.models import MintCodeResponse
from app.util import jsonable

router = APIRouter(prefix="/web")


@router.get("/fleet")
async def fleet(user: AuthUser = Depends(current_user), pool=Depends(get_pool)):
    async with pool.acquire() as conn:
        return {"fleet": jsonable(await q.fleet_snapshot(conn))}


@router.get("/temp-config")
async def temp_config(user: AuthUser = Depends(current_user), pool=Depends(get_pool)):
    """Read-only mirror of the temperature-alert thresholds the phone pushed (one-way)."""
    async with pool.acquire() as conn:
        return {"configs": jsonable(await q.get_temp_config_all(conn))}


@router.get("/alert-config")
async def alert_config(user: AuthUser = Depends(current_user), pool=Depends(get_pool)):
    """Read-only mirror of the capacity-alert config the phone pushed (one-way).

    Returns the SOC threshold at which a low pack seizes the main stage. Defaults to
    {seize_soc: null, alerts_on: true, updated_at_ms: 0} when the phone hasn't pushed one."""
    async with pool.acquire() as conn:
        cfg = await q.get_alert_config(conn)
    if cfg is None:
        return {"seize_soc": None, "alerts_on": True, "updated_at_ms": 0}
    return {"seize_soc": cfg["seize_soc"], "alerts_on": cfg["alerts_on"],
            "updated_at_ms": cfg["updated_at_ms"]}


@router.get("/range-config")
async def range_config(user: AuthUser = Depends(current_user), pool=Depends(get_pool)):
    """Read-only mirror of the learned discharge-range bands the phone pushed (one-way)."""
    async with pool.acquire() as conn:
        return {"configs": jsonable(await q.get_range_config_all(conn))}


@router.get("/history")
async def history(hours: int = Query(24, ge=1, le=168),
                  user: AuthUser = Depends(current_user), pool=Depends(get_pool)):
    """Read-only per-pack downsampled SOC history for the Fleet Health sparkline."""
    since_ms = int(time.time() * 1000) - hours * 3_600_000
    async with pool.acquire() as conn:
        rows = await q.history_series(conn, since_ms)
    series: dict[str, list[dict]] = {}
    for r in rows:
        series.setdefault(r["address"], []).append({"t": int(r["bucket_ms"]), "soc": float(r["soc"])})
    return {"series": [{"address": a, "points": p} for a, p in series.items()]}


@router.get("/samples")
async def samples(address: str, from_ms: int = Query(...), to_ms: int = Query(...),
                  user: AuthUser = Depends(require_admin), pool=Depends(get_pool)):
    """Admin-only: full sample history includes GPS coordinates.

    Bounded (SRV-11): the range is clamped to the last 7 days before to_ms and the
    result is hard-capped at SAMPLES_MAX_ROWS rows (see queries.samples_range)."""
    async with pool.acquire() as conn:
        return {"samples": jsonable(await q.samples_range(conn, address, from_ms, to_ms))}


@router.get("/devices")
async def devices(user: AuthUser = Depends(require_admin), pool=Depends(get_pool)):
    async with pool.acquire() as conn:
        return {"devices": jsonable(await q.list_devices(conn))}


@router.post("/enroll-codes", response_model=MintCodeResponse)
async def mint_code(user: AuthUser = Depends(require_admin), pool=Depends(get_pool)):
    code = generate_code()
    expires = datetime.now(timezone.utc) + timedelta(minutes=10)
    async with pool.acquire() as conn:
        await q.create_enrollment_code(conn, hash_code(code), user.username, expires)
    return MintCodeResponse(code=code, expires_at=expires.isoformat())


@router.delete("/devices/{device_id}")
async def revoke(device_id: str, user: AuthUser = Depends(require_admin), pool=Depends(get_pool)):
    async with pool.acquire() as conn:
        await q.revoke_device(conn, device_id)
    return {"revoked": device_id}
